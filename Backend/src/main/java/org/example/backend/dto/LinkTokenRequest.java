package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Is this emailed link still good?" — asked by the page before it shows a form.
 *
 * <p>The token travels in a POST body rather than a query string so it never
 * lands in an access log.
 */
public record LinkTokenRequest(

        @NotBlank(message = "The link is incomplete")
        @Size(max = 100, message = "The link is not valid")
        String token
) {
}
