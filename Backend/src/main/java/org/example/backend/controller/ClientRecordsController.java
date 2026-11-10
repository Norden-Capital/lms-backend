package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.ApplicationDtos;
import org.example.backend.dto.PageResponse;
import org.example.backend.entity.ApplicationStatus;
import org.example.backend.service.ClientRecordsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The Leads, Abandoned and Failed lists, a client's page, and Leads Overview. */
@RestController
@RequestMapping("/api")
@Tag(name = "Clients", description = "People who applied, and their applications")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize("hasAuthority('CUSTOMER:READ')")
public class ClientRecordsController {

    private final ClientRecordsService records;

    public ClientRecordsController(ClientRecordsService records) {
        this.records = records;
    }

    @GetMapping("/applications")
    @Operation(summary = "One list, newest first unless sorted",
               description = "`status` is LEAD, ABANDONED or FAILED. `search` looks at the email and the application's id. "
                       + "`sort` is `field,direction`, the field one of id, email, brand, channel, amount, result or createdAt.")
    public PageResponse<ApplicationDtos.Row> list(@RequestParam ApplicationStatus status,
                                                  @RequestParam(required = false) String brand,
                                                  @RequestParam(required = false) String channel,
                                                  @RequestParam(required = false) String search,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "25") int size,
                                                  @RequestParam(required = false) String sort) {
        return records.list(status, brand, channel, search, page, size, sort);
    }

    @GetMapping("/applications/overview")
    @Operation(summary = "The numbers for Leads Overview",
               description = "`days` is 7, 30 or 90, or give `from` and `to` (yyyy-MM-dd) for your own dates. "
                       + "Test applications are left out unless `includeTest` is true.")
    public ApplicationDtos.Overview overview(@RequestParam(defaultValue = "30") int days,
                                             @RequestParam(required = false) String brand,
                                             @RequestParam(defaultValue = "false") boolean includeTest,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        return records.overview(days, brand, includeTest, from, to);
    }

    @GetMapping("/applications/brands")
    @Operation(summary = "The brands, for the filter on the lists")
    public List<ApplicationDtos.BrandOption> brands() {
        return records.brandOptions();
    }

    @GetMapping("/applications/channels")
    @Operation(summary = "The channels, for the filter on the lists")
    public List<String> channels() {
        return records.channelOptions();
    }

    @GetMapping("/clients/{id}")
    @Operation(summary = "One client, with every time they applied")
    public ApplicationDtos.ClientDetail client(@PathVariable String id) {
        return records.client(id);
    }
}
