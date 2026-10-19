package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.backend.dto.ChangeRoleRequest;
import org.example.backend.dto.CreateUserRequest;
import org.example.backend.dto.MessageResponse;
import org.example.backend.dto.PageResponse;
import org.example.backend.dto.UpdateUserRequest;
import org.example.backend.dto.UpdatePermissionsRequest;
import org.example.backend.dto.UpdateUserStatusRequest;
import org.example.backend.dto.UserResponse;
import org.example.backend.dto.UserSummaryResponse;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.AccountLinkService;
import org.example.backend.service.AuthService;
import org.example.backend.service.PermissionService;
import org.example.backend.service.UserPermissionService;
import org.example.backend.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Reading and managing user accounts")
@SecurityRequirement(name = "BearerAuth")
public class UserController {

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final AuthService authService;
    private final UserService userService;
    private final UserPermissionService userPermissionService;
    private final AccountLinkService accountLinkService;

    public UserController(UserRepository userRepository,
                          PermissionService permissionService,
                          AuthService authService,
                          UserService userService,
                          UserPermissionService userPermissionService,
                          AccountLinkService accountLinkService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.authService = authService;
        this.userService = userService;
        this.userPermissionService = userPermissionService;
        this.accountLinkService = accountLinkService;
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
     * One page of accounts, filtered in the database rather than in the caller.
     *
     * <p>This used to return every row at once, working out each person's
     * permissions as it went — roughly 2N+1 queries to render a table that shows
     * fifteen rows and no permissions at all.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('USER:READ')")
    @Operation(summary = "List users",
               description = """
                       Paged, searched, filtered and sorted by the database. Requires USER:READ.

                       Every filter is optional; leaving one out means "any". `search` is matched
                       against first name, last name and email at once, because somebody typing a
                       name does not know which column it lives in.

                       `sort` is `field,direction` — for example `lastName,asc`. Sorting by
                       anything outside the allowed list is a 400 rather than a 500.

                       Your own account is never in the list — it is managed from your settings.""")
    public ResponseEntity<PageResponse<UserSummaryResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Boolean mfaEnabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(userService.list(search, role, status, mfaEnabled, page, size, sort,
                principal.userId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER:READ')")
    @Operation(summary = "One user in full",
               description = "Includes their effective permissions and the account's timestamps.")
    public ResponseEntity<UserResponse> get(@PathVariable("id") String publicId,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(userService.get(internalId(publicId), principal.userId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER:CREATE')")
    @Operation(summary = "Invite somebody",
               description = """
                       Creates the account as PENDING_VERIFICATION and emails an invitation. The
                       person chooses their own password from the link (valid 7 days), which makes
                       the account ACTIVE. Their first sign-in then lands them in two-factor
                       enrolment, which is mandatory.

                       This is the only way an account is created — there is no self-registration.""")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {

        UserResponse created = userService.create(request, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('USER:UPDATE')")
    @Operation(summary = "Edit a user's details",
               description = """
                       Name, email and phone only. Role, status, permissions, password and
                       two-factor each have their own endpoint, because they carry different
                       consequences — an endpoint for fixing a typo in a surname should not also
                       be a way to make somebody an administrator.

                       All fields are required. A blank phone clears it.""")
    public ResponseEntity<UserResponse> update(@PathVariable("id") String publicId,
                                               @Valid @RequestBody UpdateUserRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(userService.update(internalId(publicId), request, principal.userId()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USER:UPDATE')")
    @Operation(summary = "Switch an account off, or back on",
               description = """
                       There is no delete. The row stays so history survives and the email address
                       stays taken, which is what makes an account something you can turn back on
                       rather than re-create as a stranger.

                       Refused in two cases: on your own account, and on the last active
                       administrator. Both are ways to lock an organisation out permanently.""")
    public ResponseEntity<UserResponse> changeStatus(@PathVariable("id") String publicId,
                                                     @Valid @RequestBody UpdateUserStatusRequest request,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(userService.changeStatus(internalId(publicId), request.status(), principal.userId()));
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAuthority('USER:UPDATE')")
    @Operation(summary = "Change a user's role",
               description = """
                       ⚠️ This clears that person's permission exceptions, deliberately.

                       An exception is stored as a difference from a role — "this Member also
                       gets exports" — so once the role changes, the stored row describes
                       something that no longer exists. Worse, a DENY written against an
                       administrator survives a later promotion as a live rule, quietly taking a
                       permission away from somebody every screen shows as having it.

                       Refused on your own account, and on the last active administrator. Both
                       are ways to lock an organisation out of its own system.""")
    public ResponseEntity<UserResponse> changeRole(@PathVariable("id") String publicId,
                                                   @Valid @RequestBody ChangeRoleRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(userService.changeRole(internalId(publicId), request.role(), principal.userId()));
    }

    @PostMapping("/{id}/password-reset")
    @PreAuthorize("hasAuthority('USER:UPDATE')")
    @Operation(summary = "Email somebody a password reset link",
               description = """
                       For a person who cannot get in. The administrator never sees or chooses the
                       password: the person picks it from their own inbox. Two-factor is unchanged.

                       Refused for an invited person who has not joined yet (resend the invitation
                       instead), for a switched-off account, and twice within a minute.""")
    public ResponseEntity<MessageResponse> sendPasswordReset(@PathVariable("id") String publicId,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(accountLinkService.sendPasswordReset(internalId(publicId), principal.userId()));
    }

    @PostMapping("/{id}/invitation")
    @PreAuthorize("hasAuthority('USER:CREATE')")
    @Operation(summary = "Send the invitation email again",
               description = """
                       For an invited person who lost the email or let it expire. The old link stops
                       working. Refused once they have joined, and twice within a minute.""")
    public ResponseEntity<MessageResponse> resendInvitation(@PathVariable("id") String publicId,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(accountLinkService.resendInvitation(internalId(publicId), principal.userId()));
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('USER:UPDATE')")
    @Operation(summary = "Replace one person's permission exceptions",
               description = """
                       Send the differences from their role, not a copy of everything they hold:
                       `granted` is what they have that the role does not give, `denied` is what
                       the role gives that they must not have. Sending the whole effective set
                       instead would freeze their access, so that changing the role later moved
                       nobody.

                       Both lists replace whatever was there. A permission absent from both means
                       "no exception" — which is the only way an exception can be removed.

                       Refused if: it is your own account; the account is an administrator, who
                       holds everything by definition; a permission belongs to an admin-only
                       module such as Management; or the result would let somebody create, edit,
                       delete or export on a module they cannot read.

                       Takes effect immediately — their cached access is cleared, not left to
                       expire.""")
    public ResponseEntity<UserResponse> replacePermissions(
            @PathVariable("id") String publicId,
            @Valid @RequestBody UpdatePermissionsRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(userPermissionService.replaceFor(internalId(publicId), request, principal.userId()));
    }

    @PostMapping("/{id}/2fa/reset")
    @PreAuthorize("hasAuthority('USER:UPDATE')")
    @Operation(summary = "Clear a user's two-factor authentication",
               description = """
                       The way back for someone who has lost both their phone and their recovery
                       codes. Two-factor cannot be switched off by the person who holds it, so
                       without this their account would be unreachable for good.

                       Afterwards that user signs in with their password alone until they enrol
                       again — so this needs USER:UPDATE, and it is logged.

                       You cannot call this on yourself: that would be self-disable by another
                       name. Ask another administrator.""")
    public ResponseEntity<MessageResponse> resetMfa(@PathVariable("id") String publicId,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(authService.resetMfaFor(internalId(publicId), principal.userId()));
    }

    /**
     * Addresses carry the public ID, like NC-7K4F-92QX, never the numeric id —
     * which would give away how many accounts exist and invite trying the next
     * one. Everything behind the controller still works on the numeric id.
     *
     * <p>An unknown ID is the same 404 as an account somebody may not see.
     */
    private Long internalId(String publicId) {
        return userRepository.findIdByPublicId(publicId.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("No user with ID " + publicId + "."));
    }
}
