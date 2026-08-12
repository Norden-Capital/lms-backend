package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Editing who somebody is — not what they may do.
 *
 * <p>Role, status, permissions, password and two-factor each have their own
 * endpoint. That is not tidiness: they carry different consequences and, in time,
 * different permissions, and a single "update user" that quietly accepts a role
 * field is how an endpoint meant for fixing a typo in a surname becomes a way to
 * make yourself an administrator.
 *
 * <p>Every field is required. This is a PUT-shaped body on a PATCH-shaped verb,
 * because a partial update where {@code null} means "leave it alone" cannot
 * express "clear the phone number" — and clearing it is a thing people do.
 */
public record UpdateUserRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must be at most 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must be at most 100 characters")
        String lastName,

        /**
         * Changing this changes how they sign in. It is allowed — people do
         * change name and address — but it is the reason this endpoint needs
         * {@code USER:UPDATE} rather than being something anyone can call.
         */
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        /** Blank clears it. The column is nullable and plenty of people have none. */
        @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,20}$", message = "Phone number format is not valid")
        String phone
) {
}
