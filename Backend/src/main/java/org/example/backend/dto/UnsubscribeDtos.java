package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** The /unsubscribe page on the brand websites, and the LMS list. */
public final class UnsubscribeDtos {

    private UnsubscribeDtos() {
    }

    /**
     * What a brand website's server sends. {@code host} is the address the page
     * was opened on (loanhero.ca or www.loanhero.ca): it says which brand.
     * {@code reason} may be left out or empty.
     */
    public record Request(
            @NotBlank @Size(max = 255) String host,
            @NotBlank(message = "Enter a valid email.")
            @Email(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Enter a valid email.")
            @Size(max = 254, message = "Enter a valid email.") String email,
            @Pattern(regexp = "|TOO_MANY_EMAILS|NOT_INTERESTED|ALREADY_GOT_LOAN|NEVER_SIGNED_UP|OTHER",
                    message = "Choose one.") String reason,
            @Size(max = 300, message = "Keep it under 300 characters.") String otherText
    ) {
    }

    /** Always the same answer, so nobody can learn who is a customer. */
    public record Result(boolean ok) {
        public static final Result OK = new Result(true);
    }

    /**
     * One line of the LMS list. {@code clientId} and {@code leadName} are set when the
     * email belongs to someone who applied (before or after unsubscribing); the name is
     * the one from their latest application.
     */
    public record Row(String id, String email, String clientId, String leadName, String brandId, String brand, String brandLogo,
                      String brandDomain, String reason, String otherText, String ip,
                      Instant createdAt, Instant updatedAt) {
    }

    /** On a client's page: a brand they unsubscribed from. */
    public record OnClient(String brand, String brandLogo, String brandDomain, String reason, Instant at) {
    }
}
