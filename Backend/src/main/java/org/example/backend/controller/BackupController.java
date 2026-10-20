package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.BackupOverviewResponse;
import org.example.backend.dto.BackupRunResponse;
import org.example.backend.dto.DownloadLinkResponse;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.BackupService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Copies of the database — the Owner only. See {@link BackupService}.
 */
@RestController
@RequestMapping("/api/backups")
@Tag(name = "Backups", description = "Nightly copies of the database — the Owner only")
@SecurityRequirement(name = "BearerAuth")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "The state of the backups, and one page of the log",
               description = """
                       `sort` is `field,direction` — startedAt, outcome, sizeBytes or durationSeconds;
                       newest first when left out. `page` is 1-based and clamped to the last page.
                       `size` is 10, 25 or 50.""")
    public ResponseEntity<BackupOverviewResponse> overview(
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(backupService.overview(sort, page, size));
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Back up now",
               description = "Answers straight away with the run in progress. 409 if one is already running.")
    public ResponseEntity<BackupRunResponse> start(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(backupService.startNow(principal.userId()));
    }

    @PostMapping("/{id}/download-link")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "A two-minute link to download one backup",
               description = "The link points at GET /api/backups/download and needs no sign-in — it is the proof.")
    public ResponseEntity<DownloadLinkResponse> downloadLink(@PathVariable String id,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(new DownloadLinkResponse(backupService.downloadLink(id, principal.userId())));
    }

    @GetMapping("/download")
    @Operation(summary = "Download one backup, by a link from download-link")
    public ResponseEntity<InputStreamResource> download(@RequestParam String token) {
        BackupService.Download file = backupService.open(token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(file.stream()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Delete one backup",
               description = "Removes the file on the server and in Cloudflare R2. The line stays in the history.")
    public ResponseEntity<BackupRunResponse> delete(@PathVariable String id,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(backupService.delete(id, principal.userId()));
    }
}
