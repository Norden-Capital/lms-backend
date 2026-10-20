package org.example.backend.dto;

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
 * <p><b>No email.</b> An account's address cannot be changed — decided
 * 2026-10-19. Changing it raised more questions than it answered: links already
 * sent to the old address, whether the new one is even real, who tells the old
 * owner. A wrong address is fixed by cancelling the invitation and inviting the
 * right one. An {@code email} sent here anyway is ignored.
 *
 * <p>Every field is required. This is a PUT-shaped body on a PATCH-shaped verb,
 * because a partial update where {@code null} means "leave it alone" cannot
 * express "clear the phone number" — and clearing it is a thing people do.
 */
public record UpdateUserRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name can be up to 100 letters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name can be up to 100 letters")
        String lastName,

        /** Blank clears it. The column is nullable and plenty of people have none. */
        @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,20}$", message = "Phone number is not valid")
        String phone
) {
}
