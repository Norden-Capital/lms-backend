package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Moving somebody between roles.
 *
 * <p>Its own endpoint rather than a field on {@link UpdateUserRequest}, because
 * this is the single most consequential thing anybody does to another account —
 * and an endpoint for correcting a surname should not also be able to hand out
 * administrator.
 *
 * <p>⚠️ <b>Changing a role clears that person's permission exceptions.</b> See
 * the service for why; the short version is that an exception is stored as a
 * difference from a role, so once the role changes it is describing something
 * that no longer exists.
 */
public record ChangeRoleRequest(

        /** The role's name — ADMIN or MEMBER. Checked against the database. */
        @NotBlank(message = "Role is required")
        String role
) {
}
