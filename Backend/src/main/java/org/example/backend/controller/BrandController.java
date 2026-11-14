package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.backend.dto.BrandDtos;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.BrandService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Brands, on Configuration → Brands — the Owner only. */
@RestController
@RequestMapping("/api/brands")
@Tag(name = "Brands", description = "Brand addresses for the inbox — the Owner only")
@SecurityRequirement(name = "BearerAuth")
// Each endpoint names its own permission — owner pages since 2026-11-14 (V20).
public class BrandController {

    private final BrandService brands;

    public BrandController(BrandService brands) {
        this.brands = brands;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BRAND:READ')")
    @Operation(summary = "Every brand, by name")
    public List<BrandDtos.Brand> list() {
        return brands.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BRAND:CREATE')")
    @Operation(summary = "Add a brand by its website",
               description = """
                       The name and logo are read from the site; the address is support@{domain}, which
                       must forward into the inbox mailbox and be in its Gmail "Send mail as" list.""")
    public ResponseEntity<BrandDtos.Brand> add(@Valid @RequestBody BrandDtos.Add request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(brands.add(request.website(), principal.userId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BRAND:UPDATE')")
    @Operation(summary = "Change a brand's name or address")
    public BrandDtos.Brand update(@PathVariable String id, @Valid @RequestBody BrandDtos.Update request,
                                  @AuthenticationPrincipal AuthPrincipal principal) {
        return brands.update(id, request, principal.userId());
    }

    @PostMapping("/{id}/refresh")
    @PreAuthorize("hasAuthority('BRAND:UPDATE')")
    @Operation(summary = "Read the name and logo from the website again")
    public BrandDtos.Brand refresh(@PathVariable String id, @AuthenticationPrincipal AuthPrincipal principal) {
        return brands.refresh(id, principal.userId());
    }

    @PutMapping("/{id}/ga4")
    @PreAuthorize("hasAuthority('BRAND:UPDATE')")
    @Operation(summary = "Set the brand's Google Analytics property — for the SEO Overview")
    public BrandDtos.Brand setGa4(@PathVariable String id, @Valid @RequestBody BrandDtos.Ga4 request,
                                  @AuthenticationPrincipal AuthPrincipal principal) {
        return brands.setGa4(id, request.propertyId(), principal.userId());
    }

    @PutMapping("/{id}/active")
    @PreAuthorize("hasAuthority('BRAND:UPDATE')")
    @Operation(summary = "Switch a brand on or off — off, it leaves the inbox but is kept")
    public BrandDtos.Brand setActive(@PathVariable String id, @RequestBody BrandDtos.Active request,
                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return brands.setActive(id, request.active(), principal.userId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('BRAND:DELETE')")
    @Operation(summary = "Remove a brand. Its mail stays in Gmail; it is just no longer tagged.")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal AuthPrincipal principal) {
        brands.delete(id, principal.userId());
        return ResponseEntity.noContent().build();
    }
}
