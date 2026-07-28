package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Step one of logging in: email and password only.
 *
 * <p>There is no TOTP field here on purpose. Mixing both factors into one call is
 * what made the old flow possible to bypass — the second factor now lives in its
 * own endpoint that requires proof the first one already passed.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {
}
