package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.CustomerResponse;
import org.example.backend.dto.OverviewMetricResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ⚠️ <b>Placeholder modules, built to prove the permission model works.</b>
 *
 * <p>Neither of these has a table, an entity or a service behind it. They return
 * invented rows. What is real is the annotation above each one: the permission it
 * demands is looked up against the database on every request, so a member who
 * holds {@code CUSTOMER:READ} and not {@code SEO_OVERVIEW:READ} gets one list and
 * a 403 on the other — with the same token, in the same session.
 *
 * <p>That is the point. A permission tested only in the frontend is not tested:
 * hiding a button proves nothing about what happens when somebody asks anyway.
 *
 * <p>Delete this file when the real Customers and Overview modules arrive, and
 * move the annotations onto them.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Modules", description = "Placeholder endpoints that exercise the permission model")
@SecurityRequirement(name = "BearerAuth")
public class ModulesController {

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('CUSTOMER:READ')")
    @Operation(summary = "The customer list",
               description = """
                       ⚠️ Invented rows. Requires CUSTOMER:READ, which is the part that is real —
                       it is checked against the database on every request, not against whatever
                       the token said when it was issued.""")
    public ResponseEntity<List<CustomerResponse>> customers() {
        return ResponseEntity.ok(List.of(
                new CustomerResponse(1L, "Atlas Trading", "Referral", "ACTIVE"),
                new CustomerResponse(2L, "Bourgogne Frères", "Organic search", "ACTIVE"),
                new CustomerResponse(3L, "Cedar & Co", "Email campaign", "LAPSED"),
                new CustomerResponse(4L, "Dahlia Imports", "Direct", "ACTIVE"),
                new CustomerResponse(5L, "El Fenn Logistics", "Paid social", "PROSPECT")
        ));
    }

    @GetMapping("/overview/seo")
    @PreAuthorize("hasAuthority('SEO_OVERVIEW:READ')")
    @Operation(summary = "Search performance",
               description = "⚠️ Invented numbers. Requires SEO_OVERVIEW:READ.")
    public ResponseEntity<List<OverviewMetricResponse>> seo() {
        return ResponseEntity.ok(List.of(
                new OverviewMetricResponse("Impressions", "184,209", "+12.4%"),
                new OverviewMetricResponse("Clicks", "9,418", "+6.1%"),
                new OverviewMetricResponse("Average position", "14.2", "-1.8"),
                new OverviewMetricResponse("Indexed pages", "1,027", "+43")
        ));
    }

    @GetMapping("/overview/email")
    @PreAuthorize("hasAuthority('EMAIL_OVERVIEW:READ')")
    @Operation(summary = "Email performance",
               description = "⚠️ Invented numbers. Requires EMAIL_OVERVIEW:READ.")
    public ResponseEntity<List<OverviewMetricResponse>> email() {
        return ResponseEntity.ok(List.of(
                new OverviewMetricResponse("Sent", "42,880", "+3.2%"),
                new OverviewMetricResponse("Open rate", "38.7%", "+1.4pp"),
                new OverviewMetricResponse("Click rate", "6.9%", "-0.3pp"),
                new OverviewMetricResponse("Unsubscribes", "112", "-18")
        ));
    }
}
