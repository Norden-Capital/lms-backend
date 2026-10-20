package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Choosing a password from an emailed link — the first one from an invitation,
 * or a new one from a reset. The token is the proof of who is asking.
 */
public record SetPasswordWithTokenRequest(

        @NotBlank(message = "The link is incomplete")
        @Size(max = 100, message = "The link is not valid")
        String token,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be 8 to 100 characters")
        String password
) {
}
