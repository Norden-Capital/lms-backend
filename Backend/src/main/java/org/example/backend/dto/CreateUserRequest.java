package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An administrator creating somebody else's account.
 *
 * <p>Not {@link RegisterRequest}, and the difference is the whole point: that one
 * is self-registration and has no role field, because letting the caller choose
 * their own role would let anyone sign up as an administrator. This one is
 * reached only with {@code USER:CREATE}, so choosing the role is exactly what it
 * is for.
 *
 * <p>The password is set by the administrator and handed over out of band. There
 * is no mail server, so an invitation link is not an option — and this matches
 * what the add-user screen does, which generates one and asks you to pass it on.
 *
 * <p>⚠️ Nothing forces the person to change it afterwards. There is no
 * {@code must_change_password} column, so a temporary password stays their
 * password until somebody tells them otherwise.
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

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password,

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
