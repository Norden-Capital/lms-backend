package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Re-confirms the current password before a sensitive change.
 *
 * <p>Used when switching 2FA off: being logged in is not enough on its own, or a
 * borrowed unlocked laptop would be all it takes to strip someone's second
 * factor.
 */
public record PasswordConfirmRequest(

        @NotBlank(message = "Your current password is required")
        String password
) {
}
