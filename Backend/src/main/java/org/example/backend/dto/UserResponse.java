package org.example.backend.dto;

import org.example.backend.entity.User;

import java.time.Instant;
import java.util.Set;

/**
 * A user as the API exposes them, in full.
 *
 * <p>Kept separate from the entity so the password hash and MFA secret physically
 * cannot leak into a response by someone adding a field to the entity later.
 *
 * <p>For one account at a time — {@code /me} and {@code /users/{id}}. Lists use
 * {@link UserSummaryResponse}, which drops the permissions and with them the two
 * queries per person it takes to work them out.
 *
 * <p>The four timestamps are sent as ISO-8601 strings. They are what the detail
 * screen shows under "Account" and in its activity panel, and every one of them
 * was already in the database and simply not exposed.
 */
public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String role,
        String status,
        boolean mfaEnabled,
        Set<String> permissions,

        /** When the account was created. Never null. */
        Instant createdAt,

        /** When they last signed in successfully. Null if they never have. */
        Instant lastLoginAt,

        /**
         * When the email address was confirmed.
         *
         * <p>⚠️ Always null today: there is no mail server, so nothing sends a
         * verification link and nothing sets this. Accounts are created ACTIVE
         * instead.
         */
        Instant emailVerifiedAt,

        /** When they finished two-factor enrolment. Null until they do. */
        Instant mfaConfirmedAt
) {
    public static UserResponse from(User user, Set<String> permissions) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().getName(),
                user.getStatus().name(),
                user.isMfaEnabled(),
                permissions,
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getEmailVerifiedAt(),
                user.getMfaConfirmedAt()
        );
    }
}
