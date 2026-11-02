package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@PreAuthorize("hasRole('OWNER')")
public class ChannelController {

    private final ChannelService channels;

    public ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    @Operation(summary = "Every channel with a link, by name")
    public List<String> list() {
        return channels.list();
    }

    @PostMapping
    @Operation(summary = "Add a channel", description = "`name` is what goes after utm_source=, like email.")
    public List<String> add(@RequestBody Map<String, String> request,
                            @AuthenticationPrincipal AuthPrincipal principal) {
        return channels.add(request.get("name"), principal.userId());
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Remove a channel", description = "Its leads keep their channel.")
    public List<String> remove(@PathVariable String name, @AuthenticationPrincipal AuthPrincipal principal) {
        return channels.remove(name, principal.userId());
    }
}
