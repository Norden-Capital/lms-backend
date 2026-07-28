package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Proves the QR was really scanned, by sending back a code the app produced. */
public record MfaConfirmRequest(

        @NotBlank(message = "The 6-digit code from your authenticator app is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "The code must be exactly 6 digits")
        String code
) {
}
