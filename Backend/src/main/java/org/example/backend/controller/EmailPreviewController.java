package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.backend.service.EmailTemplates;
import org.example.backend.service.EmailTemplates.Email;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The platform's emails, rendered with sample data, for the frontend's
 * {@code /preview} page.
 *
 * <p>Rendered by the same {@link EmailTemplates} that real emails use, so what is
 * approved on the preview is exactly what goes out — there is no second copy of
 * the design to drift.
 *
 * <p>Public on purpose: nothing here is real. The names are invented and the
 * links carry a token that does not exist.
 */
@RestController
@RequestMapping("/api/email-previews")
@Tag(name = "Email previews", description = "Sample renders of every email the platform sends")
public class EmailPreviewController {

    /** One email as it would arrive. */
    public record EmailPreview(String key, String name, String sentWhen, String from, String subject, String html, String text) {
    }

    private static final String SAMPLE_TOKEN = "preview-only-this-link-does-not-work";

    private final String frontendUrl;
    private final String from;

    public EmailPreviewController(@Value("${app.frontend-url}") String frontendUrl,
                                  @Value("${app.mail.from}") String from) {
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.from = from;
    }

    @GetMapping
    @SecurityRequirements
    @Operation(summary = "Every email, rendered with sample data")
    public ResponseEntity<List<EmailPreview>> all() {
        Email invitation = EmailTemplates.invitation(
                "Yara", "Sarah Amrani", frontendUrl + "/accept-invite?token=" + SAMPLE_TOKEN, 7);
        Email reset = EmailTemplates.passwordReset(
                "Yara", frontendUrl + "/reset-password?token=" + SAMPLE_TOKEN, 30);

        return ResponseEntity.ok(List.of(
                new EmailPreview("invitation", "Invitation",
                        "An administrator invites somebody from Users → Invite user, or resends an invitation.",
                        from, invitation.subject(), invitation.html(), invitation.text()),
                new EmailPreview("password-reset", "Password reset",
                        "Somebody uses \"Forgot password?\" on the sign-in page, or an administrator sends a reset link.",
                        from, reset.subject(), reset.html(), reset.text())
        ));
    }
}
