package org.example.backend.service;

import org.example.backend.dto.BrandDtos;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.Brand;
import org.example.backend.entity.PublicIds;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.BrandRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Brands — decided 2026-10-20, on the Configuration page, the Owner's alone.
 * Each is a name and the address its mail arrives at; the inbox tags mail by
 * that address and answers from it.
 */
@Service
public class BrandService {

    private final BrandRepository brands;
    private final ActionLogService actionLog;

    public BrandService(BrandRepository brands, ActionLogService actionLog) {
        this.brands = brands;
        this.actionLog = actionLog;
    }

    @Transactional(readOnly = true)
    public List<BrandDtos.Brand> list() {
        return brands.findAllByOrderByNameAscEmailAsc().stream().map(BrandService::toDto).toList();
    }

    @Transactional
    public BrandDtos.Brand create(BrandDtos.Save request, Long actorId) {
        String email = normalise(request.email());
        if (brands.findByEmailIgnoreCase(email).isPresent()) {
            throw ApiException.conflict("A brand already uses " + email + ".");
        }
        Brand brand = brands.save(Brand.builder()
                .publicId(PublicIds.generate("BR"))
                .name(request.name().strip())
                .email(email)
                .build());
        actionLog.record(ActionType.BRAND_ADDED, actorId, null, describe(brand));
        return toDto(brand);
    }

    @Transactional
    public BrandDtos.Brand update(String id, BrandDtos.Save request, Long actorId) {
        Brand brand = find(id);
        String email = normalise(request.email());
        brands.findByEmailIgnoreCase(email)
                .filter(other -> !other.getId().equals(brand.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("A brand already uses " + email + ".");
                });
        String before = describe(brand);
        brand.setName(request.name().strip());
        brand.setEmail(email);
        actionLog.record(ActionType.BRAND_UPDATED, actorId, null, before + " → " + describe(brand));
        return toDto(brand);
    }

    @Transactional
    public void delete(String id, Long actorId) {
        Brand brand = find(id);
        brands.delete(brand);
        actionLog.record(ActionType.BRAND_REMOVED, actorId, null, describe(brand));
    }

    private Brand find(String id) {
        return brands.findByPublicId(id == null ? "" : id)
                .orElseThrow(() -> ApiException.notFound("No such brand."));
    }

    private static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String describe(Brand brand) {
        return brand.getName() + " <" + brand.getEmail() + ">";
    }

    private static BrandDtos.Brand toDto(Brand brand) {
        return new BrandDtos.Brand(brand.getPublicId(), brand.getName(), brand.getEmail(), brand.getCreatedAt());
    }
}
