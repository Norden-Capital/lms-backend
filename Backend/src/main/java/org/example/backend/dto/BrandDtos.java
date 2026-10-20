package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** What the brand endpoints send and receive. */
public final class BrandDtos {

    private BrandDtos() {
    }

    /**
     * @param email   support@{domain} — where its mail arrives, and answers go out from
     * @param logoUrl read from the website; null when it declared none
     * @param active  switched off, it is out of the inbox but kept
     * @param replies this year's replies sent from it, one number per quarter (Q1–Q4)
     */
    public record Brand(String id, String name, String email, String domain, String logoUrl, boolean active,
                        Instant createdAt, int[] replies) {
    }

    /** Just the website — "ask4loan.ca" or a full address; the rest is read from it. */
    public record Add(@NotBlank(message = "Type the brand's website.") @Size(max = 500) String website) {
    }

    public record Active(boolean active) {
    }

    /** Fixing what was read from the website — the name, or a brand that is not support@. */
    public record Update(
            @NotBlank(message = "Give the brand a name.") @Size(max = 80, message = "Keep the name under 80 letters.") String name,
            @NotBlank(message = "Give the brand's email address.")
            @Email(message = "That is not a valid email address.") @Size(max = 255) String email) {
    }
}
