package org.example.backend.dto;

import org.example.backend.entity.User;

import java.util.Set;

/**
 * A user as the API exposes them.
 *
 * <p>Kept separate from the entity so the password hash and MFA secret physically
 * cannot leak into a response by someone adding a field to the entity later.
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
        Set<String> permissions
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
                permissions
        );
    }
}
