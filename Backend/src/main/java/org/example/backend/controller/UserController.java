package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.dto.UserResponse;
import org.example.backend.entity.User;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Reading user accounts")
@SecurityRequirement(name = "BearerAuth")
public class UserController {

    private final UserRepository userRepository;
    private final PermissionService permissionService;

    public UserController(UserRepository userRepository, PermissionService permissionService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    @Operation(summary = "The signed-in user, with their effective permissions")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthPrincipal principal) {
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> ApiException.notFound("User not found."));

        return ResponseEntity.ok(UserResponse.from(user, permissionService.effectivePermissionsFor(user)));
    }

    /**
     * Previously any authenticated account could download the whole user list.
     * Now it takes an explicit permission, which only ADMIN and MANAGER hold.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('USER:READ')")
    @Transactional(readOnly = true)
    @Operation(summary = "List all users", description = "Requires the USER:READ permission.")
    public ResponseEntity<List<UserResponse>> all() {
        List<UserResponse> users = userRepository.findAll().stream()
                .map(user -> UserResponse.from(user, permissionService.effectivePermissionsFor(user)))
                .toList();

        return ResponseEntity.ok(users);
    }
}
