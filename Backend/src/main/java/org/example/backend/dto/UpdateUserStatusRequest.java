package org.example.backend.dto;

import jakarta.validation.constraints.NotNull;
import org.example.backend.entity.UserStatus;

/**
 * Turning an account off, or back on.
 *
 * <p>Bound as the enum rather than a string, so an unknown value is rejected by
 * the framework with a 400 before any code runs. There is no "delete" beside
 * this: accounts are switched off, never removed, so that history survives and
 * the email address stays taken.
 */
public record UpdateUserStatusRequest(

        @NotNull(message = "Status is required")
        UserStatus status
) {
}
