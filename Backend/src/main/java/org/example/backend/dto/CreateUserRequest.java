package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An administrator inviting somebody: who they are, and their role.
 *
 * <p>There is no password field, and that is the point. The person receives an
 * invitation email and chooses their own, so no administrator ever knows anyone
 * else's password. Reached only with {@code USER:CREATE}, so choosing the role is
 * exactly what it is for — there is no self-registration.
 */
public record CreateUserRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must be at most 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must be at most 100 characters")
        String lastName,

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,20}$", message = "Phone number format is not valid")
        String phone,

        /**
         * The role's name — ADMIN or MEMBER. Checked against the database rather
         * than an enum here, so adding a role later is a migration and not a
         * recompile.
         */
        @NotBlank(message = "Role is required")
        String role
) {
}
