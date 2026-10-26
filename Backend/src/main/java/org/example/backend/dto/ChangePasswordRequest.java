package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Changing your own password from Settings — added 2026-10-26. */
public record ChangePasswordRequest(

        @NotBlank(message = "Type your current password")
        String currentPassword,

        // The same rule as choosing one from a link.
        @NotBlank(message = "Type a new password")
        @Size(min = 8, max = 100, message = "Password must be 8 to 100 characters")
        String newPassword
) {
}
