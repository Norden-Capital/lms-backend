package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.backend.dto.ApplicationDtos;
import org.example.backend.dto.PageResponse;
import org.example.backend.dto.UnsubscribeDtos;
import org.example.backend.exception.ApiException;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.security.ClientIp;
import org.example.backend.service.UnsubscribeService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Unsubscribes. The brand websites save them through their own server, like the
 * apply form ({@code X-Proxy-Secret} and {@code X-Client-IP}); the LMS lists and
 * exports them with the UNSUBSCRIBE permission.
 */
@RestController
@Tag(name = "Unsubscribes", description = "People who asked a brand for no more emails")
public class UnsubscribeController {

    private final UnsubscribeService unsubscribes;
    private final ClientIp clientIp;

    public UnsubscribeController(UnsubscribeService unsubscribes, ClientIp clientIp) {
        this.unsubscribes = unsubscribes;
        this.clientIp = clientIp;
    }

    @PostMapping("/api/public/unsubscribes")
    @Operation(summary = "Save an unsubscribe (brand website's server only)",
               description = "Always answers {\"ok\":true} when the form is valid, whether or not the email is known.")
    public UnsubscribeDtos.Result save(@Valid @RequestBody UnsubscribeDtos.Request body, HttpServletRequest request) {
        if (!clientIp.viaWebsite(request)) {
            throw ApiException.forbidden("You do not have permission to do that.");
        }
        if (request.getHeader("X-Client-IP") == null) {
            throw ApiException.badRequest("The visitor's address is missing.");
        }
        unsubscribes.save(body, clientIp.resolve(request));
        return UnsubscribeDtos.Result.OK;
    }

    @GetMapping("/api/unsubscribes")
    @PreAuthorize("hasAuthority('UNSUBSCRIBE:READ')")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "The list, newest first",
               description = "`search` looks at the email. `brand` is a brand id, `reason` one of the reasons. "
                       + "`from` and `to` are yyyy-MM-dd (both days counted, Toronto time). "
                       + "`sort` is `field,direction`, the field one of date, email, brand or reason.")
    public PageResponse<UnsubscribeDtos.Row> list(@RequestParam(required = false) String search,
                                                  @RequestParam(required = false) String brand,
                                                  @RequestParam(required = false) String reason,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "25") int size,
                                                  @RequestParam(required = false) String sort) {
        return unsubscribes.list(search, brand, reason, from, to, page, size, sort);
    }

    @GetMapping("/api/unsubscribes/brands")
    @PreAuthorize("hasAuthority('UNSUBSCRIBE:READ')")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "The brands, for the filter")
    public List<ApplicationDtos.BrandOption> brands() {
        return unsubscribes.brandOptions();
    }

    @GetMapping("/api/unsubscribes/export")
    @PreAuthorize("hasAuthority('UNSUBSCRIBE:EXPORT')")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "The list as a CSV file",
               description = "Only the ticked rows when `ids` is given (`ids=UN-…&ids=UN-…` or `ids=UN-…,UN-…`, at most 1,000); "
                       + "otherwise the same filters as the list. Same `sort`. Written to the action log.")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String search,
                                         @RequestParam(required = false) String brand,
                                         @RequestParam(required = false) String reason,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false) List<String> ids,
                                         @AuthenticationPrincipal AuthPrincipal principal) {
        String csv = unsubscribes.export(search, brand, reason, from, to, sort, ids, principal.userId());
        // The byte-order mark makes Excel read the file as UTF-8 (accents in names and text).
        byte[] body = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("unsubscribes-" + LocalDate.now() + ".csv").build().toString())
                .body(body);
    }
}
