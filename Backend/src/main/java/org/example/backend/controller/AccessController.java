package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.PermissionCatalogueResponse;
import org.example.backend.dto.RoleResponse;
import org.example.backend.service.AccessCatalogueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The permission model itself — what exists, not who has it.
 *
 * <p>Its own controller rather than more methods on {@link UserController},
 * because none of this is about a user. These describe the shape of the system,
 * and they are what let the frontend stop carrying a hand-maintained copy of the
 * module list in TypeScript.
 *
 * <p><b>No permission required beyond being signed in.</b> Deliberate: this is
 * the vocabulary, not anybody's access. A member being able to read that a
 * {@code CUSTOMER:DELETE} permission exists tells them nothing they could not
 * infer from the application having a delete button, and requiring
 * {@code USER:READ} would mean a member's own settings screen could not name
 * their own permissions.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Access", description = "The permission model: modules, actions and roles")
@SecurityRequirement(name = "BearerAuth")
public class AccessController {

    private final AccessCatalogueService accessCatalogueService;

    public AccessController(AccessCatalogueService accessCatalogueService) {
        this.accessCatalogueService = accessCatalogueService;
    }

    @GetMapping("/permissions")
    @Operation(summary = "Every module and what may be done to it",
               description = """
                       Grouped by sidebar section and returned in menu order, because that is the
                       shape every screen draws. Actions come back read-first, since read is the
                       one all the others depend on.

                       `adminOnly` marks a module whose permissions can only ever come from being
                       an administrator — Management. Do not offer those to a member: the API
                       refuses to grant them individually, so showing the boxes would only produce
                       an error later.""")
    public ResponseEntity<PermissionCatalogueResponse> permissions() {
        return ResponseEntity.ok(accessCatalogueService.catalogue());
    }

    @GetMapping("/roles")
    @Operation(summary = "The roles, and what each grants on its own",
               description = """
                       Read-only. Nothing changes what a role grants — `role_permissions` is
                       written by migration and never by the application, so that the answer to
                       "what does Member mean" stays in the repository rather than becoming
                       something you have to query production to find out.""")
    public ResponseEntity<List<RoleResponse>> roles() {
        return ResponseEntity.ok(accessCatalogueService.roles());
    }
}
