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

/** Brands, on the Configuration page — the Owner only. */
@RestController
@RequestMapping("/api/brands")
@Tag(name = "Brands", description = "Brand addresses for the inbox — the Owner only")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize("hasRole('OWNER')")
public class BrandController {

    private final BrandService brands;

    public BrandController(BrandService brands) {
        this.brands = brands;
    }

    @GetMapping
    @Operation(summary = "Every brand, by name")
    public List<BrandDtos.Brand> list() {
        return brands.list();
    }

    @PostMapping
    @Operation(summary = "Add a brand",
               description = "Its address must forward into the inbox mailbox and be in its Gmail \"Send mail as\" list.")
    public ResponseEntity<BrandDtos.Brand> create(@Valid @RequestBody BrandDtos.Save request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(brands.create(request, principal.userId()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a brand's name or address")
    public BrandDtos.Brand update(@PathVariable String id, @Valid @RequestBody BrandDtos.Save request,
                                  @AuthenticationPrincipal AuthPrincipal principal) {
        return brands.update(id, request, principal.userId());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a brand. Its mail stays in Gmail; it is just no longer tagged.")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal AuthPrincipal principal) {
        brands.delete(id, principal.userId());
        return ResponseEntity.noContent().build();
    }
}
