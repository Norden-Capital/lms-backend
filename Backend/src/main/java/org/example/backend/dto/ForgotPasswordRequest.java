package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "I forgot my password" — just the address to send the link to. */
public record ForgotPasswordRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email
) {
}
