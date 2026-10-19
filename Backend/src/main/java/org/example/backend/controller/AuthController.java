package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.backend.dto.*;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.AccountLinkService;
import org.example.backend.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login, emailed links, and two-factor authentication")
public class AuthController {

    private final AuthService authService;
    private final AccountLinkService accountLinkService;

    public AuthController(AuthService authService, AccountLinkService accountLinkService) {
        this.authService = authService;
        this.accountLinkService = accountLinkService;
    }

    // ── Anonymous ───────────────────────────────────────────────────────────

    // There is no /register. Accounts are created by invitation only.

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in with email and password",
               description = """
                       Returns an access token straight away when 2FA is off.

                       When 2FA is on it returns `mfaRequired: true` and a short-lived `mfaToken`
                       instead — no access token and no user details. Send that token to
                       `/api/auth/login/2fa` with the 6-digit code to finish.""")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/login/2fa")
    @SecurityRequirements
    @Operation(summary = "Finish logging in with a second factor",
               description = "Send either the 6-digit `code` from the authenticator app, or a `recoveryCode`.")
    public ResponseEntity<LoginResponse> loginTwoFactor(@Valid @RequestBody LoginTwoFactorRequest request) {
        return ResponseEntity.ok(authService.loginTwoFactor(request));
    }

    // ── Anonymous: the links in emails ──────────────────────────────────────
    //
    // Tokens travel in POST bodies, never query strings, so they stay out of
    // access logs. None of these get past two-factor: they set a password, and
    // signing in still asks for the authenticator code.

    @PostMapping("/password/forgot")
    @SecurityRequirements
    @Operation(summary = "Email a password reset link",
               description = """
                       Always answers the same, whether or not the address has an account — so it
                       cannot be used to find out who has one. Only active accounts get a link.""")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok(accountLinkService.forgotPassword(request.email()));
    }

    @PostMapping("/password/reset/check")
    @SecurityRequirements
    @Operation(summary = "Is this reset link still good?",
               description = "Lets the page show the form, or say the link has expired, before anyone types.")
    public ResponseEntity<LinkInfoResponse> checkPasswordReset(@Valid @RequestBody LinkTokenRequest request) {
        return ResponseEntity.ok(accountLinkService.checkPasswordReset(request.token()));
    }

    @PostMapping("/password/reset")
    @SecurityRequirements
    @Operation(summary = "Choose a new password from a reset link",
               description = "Clears any lockout and ends every existing session. Two-factor is unchanged.")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody SetPasswordWithTokenRequest request) {
        return ResponseEntity.ok(accountLinkService.resetPassword(request));
    }

    @PostMapping("/invitation/check")
    @SecurityRequirements
    @Operation(summary = "Is this invitation still good, and who is it for?")
    public ResponseEntity<LinkInfoResponse> checkInvitation(@Valid @RequestBody LinkTokenRequest request) {
        return ResponseEntity.ok(accountLinkService.checkInvitation(request.token()));
    }

    @PostMapping("/invitation/accept")
    @SecurityRequirements
    @Operation(summary = "Accept an invitation by choosing a password",
               description = "The account becomes active. The first sign-in then goes to two-factor enrolment.")
    public ResponseEntity<MessageResponse> acceptInvitation(@Valid @RequestBody SetPasswordWithTokenRequest request) {
        return ResponseEntity.ok(accountLinkService.acceptInvitation(request));
    }

    // ── Authenticated: managing your own second factor ──────────────────────

    @PostMapping("/2fa/setup")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "Begin two-factor enrolment",
               description = """
                       Returns a secret and a QR code for the **logged-in user only** — it takes no
                       email parameter, so it cannot be used to enrol somebody else.

                       This does not switch 2FA on. Nothing changes until `/2fa/confirm` succeeds.""")
    public ResponseEntity<MfaSetupResponse> setupMfa(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(authService.startMfaSetup(principal.userId()));
    }

    @PostMapping("/2fa/confirm")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "Confirm enrolment and switch 2FA on",
               description = "Returns recovery codes. They are shown once and never retrievable again.")
    public ResponseEntity<MfaConfirmResponse> confirmMfa(@AuthenticationPrincipal AuthPrincipal principal,
                                                         @Valid @RequestBody MfaConfirmRequest request) {
        return ResponseEntity.ok(authService.confirmMfaSetup(principal.userId(), request));
    }

    @PostMapping("/2fa/disable")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "Turn two-factor authentication off",
               description = "Requires the current password — being logged in is not enough on its own.")
    public ResponseEntity<MessageResponse> disableMfa(@AuthenticationPrincipal AuthPrincipal principal,
                                                      @Valid @RequestBody PasswordConfirmRequest request) {
        return ResponseEntity.ok(authService.disableMfa(principal.userId(), request));
    }

    @PostMapping("/2fa/recovery-codes")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "Issue a fresh set of recovery codes",
               description = "Every previously issued code stops working immediately.")
    public ResponseEntity<MfaConfirmResponse> regenerateRecoveryCodes(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PasswordConfirmRequest request) {
        return ResponseEntity.ok(authService.regenerateRecoveryCodes(principal.userId(), request));
    }
}
