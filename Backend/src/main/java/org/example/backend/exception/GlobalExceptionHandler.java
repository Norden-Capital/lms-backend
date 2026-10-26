package org.example.backend.exception;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.example.backend.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every exception into the same JSON shape.
 *
 * <p>Without this, Spring returns its own error format for some failures and
 * whatever a controller happened to return for others, so the frontend has to
 * special-case each endpoint.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus()).body(ErrorResponse.of(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()));
    }

    /** Bean-validation failures, reported field by field so a form can highlight them. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.badRequest().body(ErrorResponse.validation(
                "Please fix the marked fields.",
                request.getRequestURI(),
                fieldErrors));
    }

    /** Malformed JSON, or a field of the wrong type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "The request was not valid. Please try again.",
                request.getRequestURI()));
    }

    /**
     * A value of the wrong kind in the address, like {@code ?page=abc} or a
     * status that does not exist. The caller's mistake, so 400 — not a crash.
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(TypeMismatchException ex,
                                                            HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST, "A value in the request is not valid.", request);
    }

    /**
     * Spring's own "you asked wrong" cases — added 2026-10-26. Before, the
     * catch-all below answered them all with 500 and logged each one as a
     * crash, so real crashes were buried among wrong addresses.
     *
     * <p>Spring Security answers 401 before any of these for someone signed out,
     * so a stranger still cannot tell which addresses exist.
     */
    @ExceptionHandler({
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(ServletException ex, HttpServletRequest request) {
        return switch (ex) {
            case NoResourceFoundException e -> clientError(HttpStatus.NOT_FOUND, "Not found.", request);
            case HttpRequestMethodNotSupportedException e ->
                    clientError(HttpStatus.METHOD_NOT_ALLOWED, "This action is not allowed here.", request);
            case HttpMediaTypeNotSupportedException e ->
                    clientError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request was not valid. Please try again.", request);
            default -> clientError(HttpStatus.BAD_REQUEST, "Something is missing in the request.", request);
        };
    }

    private ResponseEntity<ErrorResponse> clientError(HttpStatusCode status, String message,
                                                      HttpServletRequest request) {
        log.debug("{} on {} {}", status.value(), request.getMethod(), request.getRequestURI());
        HttpStatus known = HttpStatus.valueOf(status.value());
        return ResponseEntity.status(known).body(ErrorResponse.of(
                known.value(), known.getReasonPhrase(), message, request.getRequestURI()));
    }

    /**
     * A caller who is authenticated but not allowed.
     *
     * <p>This handler has to exist. {@code @PreAuthorize} throws
     * {@code AuthorizationDeniedException} from inside the controller invocation,
     * which is downstream of the filter chain that would normally translate it
     * into a 403. Without an entry here the catch-all below claims it instead, and
     * every authorisation failure in the application answers 500 — indistinguishable
     * from a crash, to the frontend and to whoever is debugging it.
     *
     * <p>{@code AuthorizationDeniedException} extends {@code AccessDeniedException},
     * so matching the parent covers both the annotation and the filter chain.
     *
     * <p>Logged at debug, not error: being refused is the system working.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                            HttpServletRequest request) {
        log.debug("Access denied on {} {}", request.getMethod(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                // Deliberately says nothing about which permission is missing —
                // that would map out the permission model for anyone probing.
                "You do not have permission to do that.",
                request.getRequestURI()));
    }

    /**
     * Anything unforeseen. The detail goes to the log, never to the caller — stack
     * traces and internal messages are reconnaissance for an attacker.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "Something went wrong. Please try again.",
                request.getRequestURI()));
    }
}
