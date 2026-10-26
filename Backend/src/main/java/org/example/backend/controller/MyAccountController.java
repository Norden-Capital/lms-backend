package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.backend.dto.ChangePasswordRequest;
import org.example.backend.dto.MessageResponse;
import org.example.backend.dto.SessionResponse;
import org.example.backend.dto.UpdateUserRequest;
import org.example.backend.dto.UserResponse;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.User;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.ActionLogService;
import org.example.backend.service.AuthService;
import org.example.backend.service.SessionService;
import org.example.backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Your own details, sessions and password, for Settings — added 2026-10-26.
 * Anyone signed in, and only ever about themselves: who you are comes from the
 * token, never from the request.
 */
@RestController
@RequestMapping("/api/users/me")
@Tag(name = "My account", description = "Your own sessions and password")
@SecurityRequirement(name = "BearerAuth")
public class MyAccountController {

    private final SessionService sessionService;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final ActionLogService actionLog;
    private final UserService userService;

    public MyAccountController(SessionService sessionService, AuthService authService,
                               UserRepository userRepository, ActionLogService actionLog,
                               UserService userService) {
        this.sessionService = sessionService;
        this.authService = authService;
        this.userRepository = userRepository;
        this.actionLog = actionLog;
        this.userService = userService;
    }

    /** Your own name and phone — added 2026-10-26. The email never changes. */
    @PatchMapping
    @Operation(summary = "Change your own name and phone")
    public UserResponse updateMe(@AuthenticationPrincipal AuthPrincipal principal,
                                 @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateMine(principal.userId(), request);
    }

    @GetMapping("/sessions")
    @Operation(summary = "Where you are signed in, most recently used first")
    public List<SessionResponse> sessions(@AuthenticationPrincipal AuthPrincipal principal) {
        // Not before the account's cut-off: a role change or a reset already
        // ended those, whatever their row says.
        User me = userRepository.findById(principal.userId())
                .orElseThrow(() -> ApiException.notFound("User not found."));
        return sessionService.live(me.getId(), me.getTokensValidFrom()).stream()
                .map(s -> SessionResponse.from(s, principal.sessionId()))
                .toList();
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Sign out one of your sessions")
    public MessageResponse endSession(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        sessionService.end(principal.userId(), id);
        actionLog.record(ActionType.SESSION_ENDED, principal.userId(), null, "One session");
        return new MessageResponse("Signed out of that session.");
    }

    @PostMapping("/sessions/end-others")
    @Operation(summary = "Sign out everywhere except here")
    public MessageResponse endOthers(@AuthenticationPrincipal AuthPrincipal principal) {
        int ended = sessionService.endOthers(principal.userId(), principal.sessionId());
        actionLog.record(ActionType.SESSION_ENDED, principal.userId(), null, ended + " other session(s)");
        return new MessageResponse(ended == 0 ? "No other sessions." : "Signed out everywhere else.");
    }

    /** Log out: ends this session, so the token stops working even if it was copied. */
    @PostMapping("/logout")
    @Operation(summary = "End this session")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal.sessionId() != null) {
            try {
                sessionService.end(principal.userId(), principal.sessionId());
            } catch (ApiException alreadyEnded) {
                // Ended a moment ago from elsewhere: logging out is still done.
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    @Operation(summary = "Change your password",
               description = "Needs the current one. Signs you out everywhere except this session.")
    public MessageResponse changePassword(@AuthenticationPrincipal AuthPrincipal principal,
                                          @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(principal.userId(), principal.sessionId(),
                request.currentPassword(), request.newPassword());
    }
}
