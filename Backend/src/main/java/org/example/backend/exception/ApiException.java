package org.example.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * An error that is the caller's to fix, carrying the status it should come back as.
 *
 * <p>Static factories rather than a class per error: the status and the message are
 * the only things that actually vary, so a dozen near-identical subclasses would
 * add files without adding meaning.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** The request clashes with existing state — a taken email address, typically. */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** Too many failed attempts; the account is temporarily locked. */
    public static ApiException locked(String message) {
        return new ApiException(HttpStatus.LOCKED, message);
    }
}
