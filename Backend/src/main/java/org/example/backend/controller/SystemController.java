package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.SystemHealthResponse;
import org.example.backend.service.SystemMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the platform and the machine under it are doing.
 *
 * <p>Administrators only, and by role rather than by permission — deliberately.
 * Every other guard in this application is a `RESOURCE:ACTION` that can be
 * granted to one person, and this is not one of those: it exposes the shape of
 * the infrastructure, which is not something to hand out a piece at a time. It is
 * the same reasoning that marks the Management module admin-only in the database.
 *
 * <p>If it ever should be grantable, the move is to add a SYSTEM module to the
 * catalogue in a migration, not to loosen this.
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Server, application and database health")
@SecurityRequirement(name = "BearerAuth")
public class SystemController {

    private final SystemMetricsService systemMetricsService;

    public SystemController(SystemMetricsService systemMetricsService) {
        this.systemMetricsService = systemMetricsService;
    }

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Live figures for the server, the backend and the database",
               description = """
                       One call rather than four, because the page draws them side by side and
                       separate requests would let the panels disagree about which moment they
                       describe.

                       Host CPU, memory, load and disk come from /proc, which Docker does not put
                       in a namespace — so these are the machine's real figures, not the
                       container's. Network is the exception, and reads the host's counters
                       through a mount; server.networkIsHost says whether that mount is present.

                       Container figures come from a read-only Docker proxy, not the socket.
                       containers.available is false when no proxy is reachable, and the list is
                       then empty — which is not the same claim as "nothing is running".

                       Counters are cumulative where the underlying source is cumulative — network
                       bytes and request totals. Turning those into a rate means sampling this
                       endpoint over time, which is the caller's job.""")
    public ResponseEntity<SystemHealthResponse> health() {
        return ResponseEntity.ok(systemMetricsService.snapshot());
    }
}
