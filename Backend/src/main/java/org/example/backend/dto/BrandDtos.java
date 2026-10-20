package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** What the brand endpoints send and receive. */
public final class BrandDtos {

    private BrandDtos() {
    }

    public record Brand(String id, String name, String email, Instant createdAt) {
    }

    public record Save(
            @NotBlank(message = "Give the brand a name.") @Size(max = 80) String name,
            @NotBlank(message = "Give the brand's email address.")
            @Email(message = "That is not a valid email address.") @Size(max = 255) String email) {
    }
}
