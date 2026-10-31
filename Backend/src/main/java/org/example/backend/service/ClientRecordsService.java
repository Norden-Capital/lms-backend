package org.example.backend.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.example.backend.dto.ApplicationDtos;
import org.example.backend.dto.PageResponse;
import org.example.backend.entity.ApplicationEvent;
import org.example.backend.entity.ApplicationStatus;
import org.example.backend.entity.Brand;
import org.example.backend.entity.Client;
import org.example.backend.entity.LoanApplication;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.ApplicationEventRepository;
import org.example.backend.repository.BrandRepository;
import org.example.backend.repository.ClientRepository;
import org.example.backend.repository.LoanApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** What the LMS pages read about clients: the three lists, one client, and the daily numbers. */
@Service
public class ClientRecordsService {

    private static final Logger log = LoggerFactory.getLogger(ClientRecordsService.class);

    private static final Set<ApplicationStatus> LISTS =
            Set.of(ApplicationStatus.LEAD, ApplicationStatus.ABANDONED, ApplicationStatus.FAILED);
    private static final Set<Integer> PERIODS = Set.of(7, 30, 90);
    private static final int MAX_PAGE_SIZE = 100;
    private static final List<String> LEADSCOUT_ANSWERS = List.of("SOLD", "CPF", "NOT_SOLD", "EXISTS");

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final LoanApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final ClientRepository clients;
    private final BrandRepository brands;
    private final EncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public ClientRecordsService(LoanApplicationRepository applications,
                                ApplicationEventRepository events,
                                ClientRepository clients,
                                BrandRepository brands,
                                EncryptionService encryption,
                                ObjectMapper objectMapper,
                                @Value("${app.seo.timezone:America/Toronto}") String timezone) {
        this.applications = applications;
        this.events = events;
        this.clients = clients;
        this.brands = brands;
        this.encryption = encryption;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * One page of a list, newest first. The answers are encrypted, so {@code search}
     * can only look at the email and the application's id.
     */
    @Transactional(readOnly = true)
    public PageResponse<ApplicationDtos.Row> list(ApplicationStatus status, String brand, String search,
                                                  int page, int size) {
        if (!LISTS.contains(status)) throw ApiException.badRequest("There is no such list.");

        Specification<LoanApplication> filter = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("status"), status));
            if (brand != null && !brand.isBlank()) {
                where.add(cb.equal(root.join("brand", JoinType.INNER).get("publicId"), brand));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.join("client", JoinType.INNER).get("email")), like, '\\'),
                        cb.like(cb.lower(root.get("publicId")), like, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<LoanApplication> found = applications.findAll(filter, request);
        return PageResponse.from(found.map(this::row));
    }

    private ApplicationDtos.Row row(LoanApplication application) {
        Map<String, Object> data = asObject(read(application).get("data"));
        String name = (text(data.get("firstName")) + " " + text(data.get("lastName"))).trim();
        return new ApplicationDtos.Row(
                application.getPublicId(),
                application.getClient().getPublicId(),
                application.getClient().getEmail(),
                name.isEmpty() ? null : name,
                data.get("phone") == null ? null : text(data.get("phone")),
                application.getBrand().getName(),
                application.getStatus().name(),
                application.getLeadscoutStatus(),
                application.getRequestedAmount(),
                application.getLanguage(),
                isTest(application),
                application.getCreatedAt(),
                application.getUpdatedAt());
    }

    /** A client, with every time they applied. */
    @Transactional(readOnly = true)
    public ApplicationDtos.ClientDetail client(String id) {
        Client client = clients.findByPublicId(id == null ? "" : id)
                .orElseThrow(() -> ApiException.notFound("Client not found."));

        List<ApplicationDtos.ApplicationDetail> details = new ArrayList<>();
        for (LoanApplication application : applications.findByClientOrderByCreatedAtDescIdDesc(client)) {
            Map<String, Object> stored = read(application);
            List<ApplicationDtos.Send> sends = events.findByApplicationOrderByCreatedAtAscIdAsc(application).stream()
                    .map(this::send)
                    .toList();
            details.add(new ApplicationDtos.ApplicationDetail(
                    application.getPublicId(),
                    application.getBrand().getName(),
                    application.getStatus().name(),
                    application.getLeadscoutStatus(),
                    application.getRedirectUrl(),
                    application.getRequestedAmount(),
                    application.getLanguage(),
                    isTest(application),
                    application.getConsentAt(),
                    application.getCreatedAt(),
                    application.getUpdatedAt(),
                    stored.get("ip") == null ? null : text(stored.get("ip")),
                    stored.get("userAgent") == null ? null : text(stored.get("userAgent")),
                    asObject(stored.get("data")),
                    asObject(stored.get("extra")),
                    sends));
        }
        return new ApplicationDtos.ClientDetail(client.getPublicId(), client.getEmail(), client.getCreatedAt(), details);
    }

    private ApplicationDtos.Send send(ApplicationEvent event) {
        return new ApplicationDtos.Send(event.getCreatedAt(), event.getMode(), event.getHttpStatus(),
                event.getLeadscoutStatus(), event.getProblem(), event.getDurationMs(),
                decryptOrNull(event.getRequest()), decryptOrNull(event.getResponse()));
    }

    /** The brands, for the filter: anybody who can see the lists, unlike the Owner-only Brands page. */
    @Transactional(readOnly = true)
    public List<ApplicationDtos.BrandOption> brandOptions() {
        return brands.findAllByOrderByNameAscEmailAsc().stream()
                .map(brand -> new ApplicationDtos.BrandOption(brand.getPublicId(), brand.getName()))
                .toList();
    }

    /** The numbers for Leads Overview: the last {@code days} days, on the business calendar. */
    @Transactional(readOnly = true)
    public ApplicationDtos.Overview overview(int days, String brand, boolean includeTest) {
        if (!PERIODS.contains(days)) throw ApiException.badRequest("Choose 7, 30 or 90 days.");

        LocalDate today = LocalDate.now(zone);
        LocalDate first = today.minusDays(days - 1L);
        Instant from = first.atStartOfDay(zone).toInstant();
        List<String> modes = includeTest ? List.of("live", "test", "fake") : List.of("live");

        Map<String, int[]> perDay = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            perDay.put(day.toString(), new int[3]);
        }
        Map<Long, int[]> perBrand = new LinkedHashMap<>();
        Map<String, Integer> leadscout = new LinkedHashMap<>();
        LEADSCOUT_ANSWERS.forEach(answer -> leadscout.put(answer, 0));

        List<Brand> allBrands = brands.findAllByOrderByNameAscEmailAsc();
        allBrands.forEach(item -> perBrand.put(item.getId(), new int[3]));
        Long onlyBrand = brand == null || brand.isBlank() ? null : allBrands.stream()
                .filter(item -> item.getPublicId().equals(brand))
                .map(Brand::getId)
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("There is no such brand."));

        int[] totals = new int[3];
        for (Object[] line : applications.dailyCounts(from, zone.getId(), modes)) {
            String date = (String) line[0];
            Long brandId = ((Number) line[1]).longValue();
            int column = columnOf((String) line[2]);
            String answer = (String) line[3];
            int count = ((Number) line[4]).intValue();

            if (column < 0) continue;
            perBrand.get(brandId)[column] += count;
            if (onlyBrand != null && !onlyBrand.equals(brandId)) continue;
            totals[column] += count;
            int[] day = perDay.get(date);
            if (day != null) day[column] += count;
            if (column == 0 && leadscout.containsKey(answer)) leadscout.merge(answer, count, Integer::sum);
        }

        List<ApplicationDtos.Day> daily = perDay.entrySet().stream()
                .map(e -> new ApplicationDtos.Day(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
        List<ApplicationDtos.BrandCount> byBrand = allBrands.stream()
                .map(item -> {
                    int[] count = perBrand.get(item.getId());
                    return new ApplicationDtos.BrandCount(item.getPublicId(), item.getName(), count[0], count[1], count[2]);
                })
                .sorted(Comparator.comparingInt(ApplicationDtos.BrandCount::leads).reversed())
                .toList();
        return new ApplicationDtos.Overview(days, includeTest, totals[0], totals[1], totals[2], leadscout, daily, byBrand);
    }

    /** Leads, abandoned, failed. The rest are on their way or history, and are not counted. */
    private static int columnOf(String status) {
        return switch (status) {
            case "LEAD" -> 0;
            case "ABANDONED" -> 1;
            case "FAILED" -> 2;
            default -> -1;
        };
    }

    private static boolean isTest(LoanApplication application) {
        return !LeadScoutClient.LIVE.equals(application.getMode());
    }

    /** One unreadable application (a changed key, say) must not take the whole list down. */
    private Map<String, Object> read(LoanApplication application) {
        try {
            return objectMapper.readValue(encryption.decrypt(application.getAnswers()), JSON_OBJECT);
        } catch (RuntimeException e) {
            log.error("Could not read the answers of {}: {}", application.getPublicId(), e.getMessage());
            return Map.of();
        }
    }

    private String decryptOrNull(String value) {
        try {
            return encryption.decrypt(value);
        } catch (RuntimeException e) {
            log.error("Could not read a LeadScout record: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
