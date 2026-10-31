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
    @Operation(summary = "One list, newest first",
               description = "`status` is LEAD, ABANDONED or FAILED. `search` looks at the email and the application's id.")
    public PageResponse<ApplicationDtos.Row> list(@RequestParam ApplicationStatus status,
                                                  @RequestParam(required = false) String brand,
                                                  @RequestParam(required = false) String search,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "25") int size) {
        return records.list(status, brand, search, page, size);
    }

    @GetMapping("/applications/overview")
    @Operation(summary = "The numbers for Leads Overview",
               description = "`days` is 7, 30 or 90. Test applications are left out unless `includeTest` is true.")
    public ApplicationDtos.Overview overview(@RequestParam(defaultValue = "30") int days,
                                             @RequestParam(required = false) String brand,
                                             @RequestParam(defaultValue = "false") boolean includeTest) {
        return records.overview(days, brand, includeTest);
    }

    @GetMapping("/applications/brands")
    @Operation(summary = "The brands, for the filter on the lists")
    public List<ApplicationDtos.BrandOption> brands() {
        return records.brandOptions();
    }

    @GetMapping("/clients/{id}")
    @Operation(summary = "One client, with every time they applied")
    public ApplicationDtos.ClientDetail client(@PathVariable String id) {
        return records.client(id);
    }
}
