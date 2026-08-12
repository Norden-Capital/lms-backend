package org.example.backend.dto;

import org.example.backend.entity.User;

import java.time.Instant;

/**
 * A user as a row in a list — everything the table shows and nothing else.
 *
 * <p><b>Deliberately carries no permissions.</b> {@link UserResponse} does, and
 * working them out costs two queries per person: one for the role's permissions
 * and one for their own overrides. Returning the full shape for a list of fifty
 * therefore cost a hundred queries to render a table that never displays a single
 * permission. Dropping the field is the whole fix.
 *
 * <p>Anyone who needs the permissions is looking at one account, and
 * {@code GET /api/users/{id}} gives them the full record.
 */
public record UserSummaryResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String role,
        String status,
        boolean mfaEnabled,

        /**
         * When the account was created.
         *
         * <p>The list sorts by this by default — newest first — and a column
         * ordered by something invisible is a column that looks arbitrary. Cheap
         * to include: it is on the row already, unlike the permissions, which is
         * why those stay out.
         */
        Instant createdAt
) {
    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().getName(),
                user.getStatus().name(),
                user.isMfaEnabled(),
                user.getCreatedAt()
        );
    }
}
