package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.ActionLogResponse;
import org.example.backend.dto.PageResponse;
import org.example.backend.entity.ActionType;
import org.example.backend.service.ActionLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/action-log")
@Tag(name = "Action log", description = "Who did what to whom, and when — the Owner only")
@SecurityRequirement(name = "BearerAuth")
public class ActionLogController {

    private final ActionLogService actionLogService;

    public ActionLogController(ActionLogService actionLogService) {
        this.actionLogService = actionLogService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "The action log, newest first",
               description = """
                       The Owner only — administrators and members are refused. `action` narrows
                       to one kind of event; `search` matches names, emails, details and addresses.""")
    public ResponseEntity<PageResponse<ActionLogResponse>> list(
            @RequestParam(required = false) ActionType action,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return ResponseEntity.ok(actionLogService.list(action, search, page, size));
    }
}
