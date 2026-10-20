package org.example.backend.seo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.example.backend.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SEO Overview — live from each brand's Google Analytics 4. Needs SEO_OVERVIEW:READ. */
@RestController
@RequestMapping("/api/seo")
@Tag(name = "SEO", description = "Visitors per brand, live from Google Analytics 4 (kept 10 minutes).")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize("hasAuthority('SEO_OVERVIEW:READ')")
public class SeoController {

    private final SeoService service;
    private final GoogleServiceAccount account;

    public SeoController(SeoService service, GoogleServiceAccount account) {
        this.service = service;
        this.account = account;
    }

    @GetMapping("/brands")
    @Operation(summary = "Active brands with a GA4 property")
    public List<SeoService.SeoBrand> brands() {
        // 503 without a key, so the page can say "not connected" before anything else.
        if (!account.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Analytics is not connected yet.");
        }
        return service.brands();
    }

    @GetMapping("/overview")
    @Operation(summary = "Visitors for one brand",
        description = "range: today, yesterday, 7d, month (this month), year (this year) or custom with from/to (YYYY-MM-DD). Compared with the period of the same length just before.")
    public SeoService.Overview overview(@RequestParam String brandId, @RequestParam(defaultValue = "7d") String range,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return service.overview(brandId, range, from, to);
    }
}
