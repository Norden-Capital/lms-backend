package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.ChannelDtos;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.ChannelService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Channels, on Configuration → Channels — the Owner only, like Brands. */
@RestController
@RequestMapping("/api/channels")
@Tag(name = "Channels", description = "The marketing channels that get a link — the Owner only")
@SecurityRequirement(name = "BearerAuth")
// Each endpoint names its own permission — owner pages since 2026-11-14 (V20).
public class ChannelController {

    private final ChannelService channels;

    public ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CHANNEL_SETUP:READ')")
    @Operation(summary = "Every channel, on and off, SEO last",
            description = "name = the utm_source tag. label = the name shown, or null. active false = off.")
    public List<ChannelDtos.Channel> list() {
        return channels.channels();
    }

    @GetMapping("/applies")
    @PreAuthorize("hasAuthority('CHANNEL_SETUP:READ')")
    @Operation(summary = "This year's applies per channel and brand",
            description = "Forms sent in full, real ones only. Channel → brand id → [Q1, Q2, Q3, Q4] (UTC).")
    public Map<String, Map<String, int[]>> applies() {
        return channels.appliesThisYear();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CHANNEL_SETUP:CREATE')")
    @Operation(summary = "Add a channel", description = "`name` is what goes after utm_source=, like email.")
    public List<ChannelDtos.Channel> add(@RequestBody Map<String, String> request,
                                         @AuthenticationPrincipal AuthPrincipal principal) {
        return channels.add(request.get("name"), principal.userId());
    }

    // No DELETE since 2026-11-18: a channel is turned off instead.
    @PatchMapping("/{name}")
    @PreAuthorize("hasAuthority('CHANNEL_SETUP:UPDATE')")
    @Operation(summary = "Edit a channel's label, or turn it on or off",
            description = "Send `label` (text, or empty/null for none) and/or `active` (true/false). The tag can't change. SEO can't be changed at all.")
    public List<ChannelDtos.Channel> update(@PathVariable String name, @RequestBody(required = false) Map<String, Object> request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return channels.update(name, request, principal.userId());
    }
}
