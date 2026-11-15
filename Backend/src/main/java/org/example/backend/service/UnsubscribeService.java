package org.example.backend.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.example.backend.dto.PageResponse;
import org.example.backend.dto.UnsubscribeDtos;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.Brand;
import org.example.backend.entity.Client;
import org.example.backend.entity.PublicIds;
import org.example.backend.entity.Unsubscribe;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.BrandRepository;
import org.example.backend.repository.ClientRepository;
import org.example.backend.repository.LoanApplicationRepository;
import org.example.backend.repository.UnsubscribeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.Optional;

/**
 * Unsubscribes — decided 2026-11-14. The brand websites' /unsubscribe page saves
 * here; the LMS lists them, exports them, and shows them on a client's page.
 */
@Service
public class UnsubscribeService {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeService.class);

    private static final int MAX_PAGE_SIZE = 100;
    /** A CSV bigger than this is refused: narrow the dates or the brand. */
    static final int MAX_EXPORT_ROWS = 50_000;
    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "updatedAt", "id");
    /** At most this many ticked rows in one export. */
    static final int MAX_EXPORT_IDS = 1_000;

    /** The columns the list can be sorted by ({@code sort=field,direction}), and what each one sorts. */
    private static final Map<String, String> SORTABLE = Map.of(
            "date", "updatedAt",
            "email", "email",
            "brand", "brand.name",
            "reason", "reason");

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /**
     * A new unsubscribe, or the same email and brand again: then the reason, text,
     * address and date are replaced and the first date is kept.
     */
    private static final String UPSERT = """
            INSERT INTO unsubscribes (public_id, email, brand_id, client_id, reason, other_text, ip)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (email, brand_id) DO UPDATE SET
                client_id  = COALESCE(EXCLUDED.client_id, unsubscribes.client_id),
                reason     = EXCLUDED.reason,
                other_text = EXCLUDED.other_text,
                ip         = EXCLUDED.ip,
                updated_at = now()""";

    private final UnsubscribeRepository unsubscribes;
    private final JdbcTemplate db;
    private final BrandRepository brands;
    private final ClientRepository clients;
    private final EncryptionService encryption;
    private final ActionLogService actionLog;
    private final ZoneId zone;
    private final LoanApplicationRepository applications;
    private final ObjectMapper objectMapper;

    public UnsubscribeService(UnsubscribeRepository unsubscribes,
                              LoanApplicationRepository applications,
                              ObjectMapper objectMapper,
                              BrandRepository brands,
                              ClientRepository clients,
                              EncryptionService encryption,
                              ActionLogService actionLog,
                              JdbcTemplate db,
                              @Value("${app.seo.timezone:America/Toronto}") String timezone) {
        this.unsubscribes = unsubscribes;
        this.applications = applications;
        this.objectMapper = objectMapper;
        this.brands = brands;
        this.clients = clients;
        this.encryption = encryption;
        this.actionLog = actionLog;
        this.db = db;
        this.zone = ZoneId.of(timezone);
    }

    // ── From the brand websites ─────────────────────────────────────────────

    /**
     * Saves an unsubscribe. A switched-off brand still takes them: an opt-out is
     * always kept. The answer never says whether the email was known.
     */
    @Transactional
    public void save(UnsubscribeDtos.Request request, String ip) {
        Brand brand = brandOf(request.host())
                .orElseThrow(() -> ApiException.badRequest("This website is not set up for unsubscribes."));
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Long clientId = clients.findByEmail(email).map(Client::getId).orElse(null);
        String otherText = request.otherText() == null || request.otherText().isBlank()
                ? null : request.otherText().strip();

        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason();

        // One statement, so two clicks at the same moment still make one row. Plain JDBC
        // with explicit types: an empty client or reason must reach PostgreSQL as a typed null.
        db.update(UPSERT, ps -> {
            ps.setString(1, PublicIds.generate("UN"));
            ps.setString(2, email);
            ps.setLong(3, brand.getId());
            ps.setObject(4, clientId, Types.BIGINT);
            ps.setObject(5, reason, Types.VARCHAR);
            ps.setObject(6, otherText, Types.VARCHAR);
            ps.setString(7, encryption.encrypt(ip));
        });
    }

    /** loanhero.ca, www.loanhero.ca → the brand on loanhero.ca, switched on or not. */
    private Optional<Brand> brandOf(String host) {
        String name = host.trim().toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        while (name.contains(".")) {
            Optional<Brand> brand = brands.findByDomain(name);
            if (brand.isPresent()) return brand;
            name = name.substring(name.indexOf('.') + 1);
        }
        return Optional.empty();
    }

    // ── For the LMS ─────────────────────────────────────────────────────────

    /**
     * One page of the list, newest first unless {@code sort} says otherwise.
     * {@code from} and {@code to} are yyyy-MM-dd, both days counted.
     *
     * @param sort {@code field,direction}, a field from {@link #SORTABLE}
     */
    @Transactional(readOnly = true)
    public PageResponse<UnsubscribeDtos.Row> list(String search, String brand, String reason,
                                                  String from, String to, int page, int size, String sort) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), sortOf(sort));
        return PageResponse.from(unsubscribes.findAll(filter(search, brand, reason, from, to), request).map(this::row));
    }

    /**
     * As CSV, in the list's order: the ticked rows when {@code ids} is given (the
     * filters are then ignored), otherwise the whole filtered list. Logged, because
     * it is a list of emails.
     *
     * <p>Not read-only: the Action Log line is written in the same transaction, and
     * PostgreSQL refuses an INSERT in a read-only one (found by DevOps 2026-11-15).
     */
    @Transactional
    public String export(String search, String brand, String reason, String from, String to, String sort,
                         List<String> ids, Long actorId) {
        Specification<Unsubscribe> filter;
        List<String> ticked = ids == null ? List.of()
                : ids.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
        if (!ticked.isEmpty()) {
            if (ticked.size() > MAX_EXPORT_IDS) {
                throw ApiException.badRequest("Tick at most " + MAX_EXPORT_IDS + " rows, or export by filters.");
            }
            filter = (root, query, cb) -> root.get("publicId").in(ticked);
        } else {
            filter = filter(search, brand, reason, from, to);
        }
        long count = unsubscribes.count(filter);
        if (count > MAX_EXPORT_ROWS) {
            throw ApiException.badRequest("Too many rows (" + count + "). Pick fewer dates or one brand.");
        }

        StringBuilder csv = new StringBuilder("Date (UTC),Email,Brand,Reason,Other text,Client ID,First time (UTC)\r\n");
        for (Unsubscribe u : unsubscribes.findAll(filter, sortOf(sort))) {
            csv.append(cell(u.getUpdatedAt().toString())).append(',')
                    .append(cell(u.getEmail())).append(',')
                    .append(cell(u.getBrand().getName())).append(',')
                    .append(cell(u.getReason() == null ? "" : u.getReason().name())).append(',')
                    .append(cell(u.getOtherText())).append(',')
                    .append(cell(u.getClient() == null ? "" : u.getClient().getPublicId())).append(',')
                    .append(cell(u.getCreatedAt().toString())).append("\r\n");
        }
        actionLog.record(ActionType.UNSUBSCRIBES_EXPORTED, actorId, null, count + " rows");
        return csv.toString();
    }

    /** The brands, for the list's filter — the Brands page is the Owner's. */
    @Transactional(readOnly = true)
    public List<org.example.backend.dto.ApplicationDtos.BrandOption> brandOptions() {
        return brands.findAllByOrderByNameAscEmailAsc().stream()
                .map(b -> new org.example.backend.dto.ApplicationDtos.BrandOption(b.getPublicId(), b.getName(), b.getDomain(), b.getLogoUrl()))
                .toList();
    }

    /** The brands this email unsubscribed from — shown on the client's page. */
    @Transactional(readOnly = true)
    public List<UnsubscribeDtos.OnClient> forEmail(String email) {
        return unsubscribes.findByEmailOrderByUpdatedAtDescIdDesc(email).stream()
                .map(u -> new UnsubscribeDtos.OnClient(u.getBrand().getName(), u.getBrand().getLogoUrl(),
                        u.getBrand().getDomain(), u.getReason() == null ? null : u.getReason().name(), u.getUpdatedAt()))
                .toList();
    }

    private Specification<Unsubscribe> filter(String search, String brand, String reason, String from, String to) {
        Unsubscribe.Reason wanted = reasonOf(reason);
        Instant start = from == null || from.isBlank() ? null : day(from).atStartOfDay(zone).toInstant();
        Instant end = to == null || to.isBlank() ? null : day(to).plusDays(1).atStartOfDay(zone).toInstant();
        if (start != null && end != null && !start.isBefore(end)) {
            throw ApiException.badRequest("The first date must be before the last.");
        }

        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (brand != null && !brand.isBlank()) {
                where.add(cb.equal(root.join("brand", JoinType.INNER).get("publicId"), brand));
            }
            if (wanted != null) where.add(cb.equal(root.get("reason"), wanted));
            if (start != null) where.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), start));
            if (end != null) where.add(cb.lessThan(root.get("updatedAt"), end));
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.like(root.get("email"), like, '\\'));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static Unsubscribe.Reason reasonOf(String reason) {
        if (reason == null || reason.isBlank()) return null;
        try {
            return Unsubscribe.Reason.valueOf(reason.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("There is no such reason.");
        }
    }

    private static LocalDate day(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Dates look like 2026-11-14.");
        }
    }

    /** {@code field,direction} into a {@link Sort}; the newest first breaks ties, so pages never shuffle. */
    private static Sort sortOf(String sort) {
        if (sort == null || sort.isBlank()) return NEWEST;
        String[] parts = sort.split(",");
        String property = SORTABLE.get(parts[0].trim());
        if (property == null) {
            throw ApiException.badRequest("Cannot sort by " + parts[0].trim() + ". Allowed: "
                    + String.join(", ", new TreeSet<>(SORTABLE.keySet())) + ".");
        }
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, property).and(NEWEST);
    }

    private UnsubscribeDtos.Row row(Unsubscribe u) {
        Brand brand = u.getBrand();
        // Linked when they unsubscribed; found by email when they applied afterwards.
        Client client = u.getClient() != null ? u.getClient() : clients.findByEmail(u.getEmail()).orElse(null);
        return new UnsubscribeDtos.Row(u.getPublicId(), u.getEmail(),
                client == null ? null : client.getPublicId(),
                client == null ? null : leadName(client),
                brand.getPublicId(), brand.getName(), brand.getLogoUrl(), brand.getDomain(),
                u.getReason() == null ? null : u.getReason().name(), u.getOtherText(),
                decryptOrNull(u.getIp()), u.getCreatedAt(), u.getUpdatedAt());
    }

    /** "Jane Tester", from the person's latest application; null when it has no name. */
    private String leadName(Client client) {
        return applications.findFirstByClientOrderByCreatedAtDescIdDesc(client).map(application -> {
            try {
                Map<String, Object> stored = objectMapper.readValue(encryption.decrypt(application.getAnswers()), JSON_OBJECT);
                Object data = stored.get("data");
                if (!(data instanceof Map<?, ?> answers)) return null;
                String name = (text(answers.get("firstName")) + " " + text(answers.get("lastName"))).trim();
                return name.isEmpty() ? null : name;
            } catch (RuntimeException e) {
                log.warn("Could not read the name of {}: {}", application.getPublicId(), e.getMessage());
                return null;
            }
        }).orElse(null);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String decryptOrNull(String value) {
        try {
            return encryption.decrypt(value);
        } catch (IllegalStateException e) {
            log.warn("Could not read an unsubscribe's address: {}", e.getMessage());
            return null;
        }
    }

    /**
     * One CSV cell: quoted, and a leading = + - @ made harmless so a spreadsheet
     * never runs what somebody typed in "other".
     */
    static String cell(String value) {
        if (value == null) return "";
        String safe = value.replace("\r", " ").replace("\n", " ");
        if (!safe.isEmpty() && "=+-@\t".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
