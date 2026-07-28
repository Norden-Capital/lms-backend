package org.example.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The single shape every error comes back in.
 *
 * <p>Previously errors were returned as bare strings, which meant the frontend had
 * to guess whether a response body was JSON or plain text before it could show
 * anything useful.
 *
 * @param fieldErrors field name to message, present only on validation failures
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse validation(String message, String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), 400, "Bad Request", message, path, fieldErrors);
    }
}
