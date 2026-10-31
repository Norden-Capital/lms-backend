package org.example.backend.service;

import org.example.backend.dto.BrandDtos;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.Brand;
import org.example.backend.entity.PublicIds;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.BrandReplyRepository;
import org.example.backend.repository.BrandRepository;
import org.example.backend.repository.LoanApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brands — decided 2026-10-20, on Configuration → Brands, the Owner's alone.
 *
 * <p>Added by website alone: the name and logo are read from the site
 * ({@link WebsiteInfo}) and the address is support@{domain}. Every brand
 * address forwards into the inbox mailbox; the inbox tags mail by it and
 * answers from it. A brand can be switched off without being removed.
 */
@Service
public class BrandService {

    /** The address every brand's mail arrives at, before its domain. */
    private static final String MAILBOX = "support";

    private final BrandRepository brands;
    private final BrandReplyRepository replies;
    private final LoanApplicationRepository applications;
    private final WebsiteInfo websites;
    private final ActionLogService actionLog;

    public BrandService(BrandRepository brands, BrandReplyRepository replies,
                        LoanApplicationRepository applications, WebsiteInfo websites,
                        ActionLogService actionLog) {
        this.applications = applications;
        this.brands = brands;
        this.replies = replies;
        this.websites = websites;
        this.actionLog = actionLog;
    }

    /** Every brand, with this year's replies per quarter. */
    @Transactional(readOnly = true)
    public List<BrandDtos.Brand> list() {
        Map<Long, int[]> perQuarter = new HashMap<>();
        LocalDate january = LocalDate.now(ZoneOffset.UTC).withDayOfYear(1);
        for (Object[] row : replies.countByQuarterSince(january.atStartOfDay().toInstant(ZoneOffset.UTC))) {
            int quarter = ((Number) row[1]).intValue();
            perQuarter.computeIfAbsent(((Number) row[0]).longValue(), id -> new int[4])[quarter - 1] =
                    ((Number) row[2]).intValue();
        }
        return brands.findAllByOrderByNameAscEmailAsc().stream()
                .map(brand -> toDto(brand, perQuarter.getOrDefault(brand.getId(), new int[4])))
                .toList();
    }

    /**
     * One more reply sent from this address, if it is a brand's — Reply and
     * Reply all only, not new emails or forwards.
     */
    @Transactional
    public void countReply(String fromEmail) {
        brands.findByEmailIgnoreCase(fromEmail).ifPresent(brand -> replies.record(brand.getId()));
    }

    @Transactional
    public BrandDtos.Brand add(String website, Long actorId) {
        String domain = WebsiteInfo.domainOf(website);
        if (brands.findByDomain(domain).isPresent()) throw ApiException.conflict(domain + " is already a brand.");
        String email = MAILBOX + "@" + domain;
        if (brands.findByEmailIgnoreCase(email).isPresent()) throw ApiException.conflict("A brand already uses " + email + ".");

        WebsiteInfo.Info info = websites.read(domain);
        Brand brand = brands.save(Brand.builder()
                .publicId(PublicIds.generate("BR"))
                .name(info.name())
                .email(email)
                .domain(domain)
                .logoUrl(info.logoUrl())
                .active(true)
                .build());
        actionLog.record(ActionType.BRAND_ADDED, actorId, null, describe(brand));
        return toDto(brand);
    }

    /** Reads the name and logo from the website again — after a rebrand, say. */
    @Transactional
    public BrandDtos.Brand refresh(String id, Long actorId) {
        Brand brand = find(id);
        WebsiteInfo.Info info = websites.read(brand.getDomain());
        String before = describe(brand);
        brand.setName(info.name());
        brand.setLogoUrl(info.logoUrl());
        actionLog.record(ActionType.BRAND_UPDATED, actorId, null, before + " → " + describe(brand));
        return toDto(brand);
    }

    /** Changes the name or the address — a site's title is not always the brand's name. */
    @Transactional
    public BrandDtos.Brand update(String id, BrandDtos.Update request, Long actorId) {
        Brand brand = find(id);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        brands.findByEmailIgnoreCase(email)
                .filter(other -> !other.getId().equals(brand.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict(other.getName() + " already uses " + email + ".");
                });
        String before = describe(brand);
        brand.setName(request.name().strip());
        brand.setEmail(email);
        String after = describe(brand);
        if (!before.equals(after)) actionLog.record(ActionType.BRAND_UPDATED, actorId, null, before + " → " + after);
        return toDto(brand);
    }

    /** Sets the brand's Google Analytics property — digits only, or empty to clear it. */
    @Transactional
    public BrandDtos.Brand setGa4(String id, String input, Long actorId) {
        Brand brand = find(id);
        String value = input == null ? "" : input.strip().replaceFirst("(?i)^properties/", "");
        if (!value.isEmpty() && !value.matches("[0-9]{6,15}")) {
            throw ApiException.badRequest("The Google Analytics ID is a number, like 123456789.");
        }
        String next = value.isEmpty() ? null : value;
        if (!java.util.Objects.equals(brand.getGa4PropertyId(), next)) {
            brand.setGa4PropertyId(next);
            actionLog.record(ActionType.BRAND_UPDATED, actorId, null,
                    describe(brand) + (next == null ? " Google Analytics removed" : " Google Analytics " + next));
        }
        return toDto(brand);
    }

    @Transactional
    public BrandDtos.Brand setActive(String id, boolean active, Long actorId) {
        Brand brand = find(id);
        if (brand.isActive() != active) {
            brand.setActive(active);
            actionLog.record(ActionType.BRAND_UPDATED, actorId, null,
                    describe(brand) + (active ? " switched on" : " switched off"));
        }
        return toDto(brand);
    }

    @Transactional
    public void delete(String id, Long actorId) {
        Brand brand = find(id);
        if (applications.existsByBrandId(brand.getId())) {
            throw ApiException.conflict("This brand has applications, so it cannot be removed. Switch it off instead.");
        }
        brands.delete(brand);
        actionLog.record(ActionType.BRAND_REMOVED, actorId, null, describe(brand));
    }

    private Brand find(String id) {
        return brands.findByPublicId(id == null ? "" : id)
                .orElseThrow(() -> ApiException.notFound("Brand not found."));
    }

    private static String describe(Brand brand) {
        return brand.getName() + " <" + brand.getEmail() + ">";
    }

    /** For a single brand just changed — its counts come with the next full list. */
    private static BrandDtos.Brand toDto(Brand brand) {
        return toDto(brand, new int[4]);
    }

    private static BrandDtos.Brand toDto(Brand brand, int[] replies) {
        return new BrandDtos.Brand(brand.getPublicId(), brand.getName(), brand.getEmail(), brand.getDomain(),
                brand.getLogoUrl(), brand.isActive(), brand.getCreatedAt(), replies, brand.getGa4PropertyId());
    }
}
