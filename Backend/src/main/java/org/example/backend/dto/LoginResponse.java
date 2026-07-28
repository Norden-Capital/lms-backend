package org.example.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of a login attempt, in one of two shapes.
 *
 * <p>2FA off — the login is finished:
 * <pre>{ "mfaRequired": false, "accessToken": "...", "expiresInSeconds": 86400, "user": { ... } }</pre>
 *
 * <p>2FA on — one more step to go:
 * <pre>{ "mfaRequired": true, "mfaToken": "..." }</pre>
 *
 * <p>Note what the second shape does <em>not</em> contain: no access token, and no
 * user details. Nothing is revealed until both factors have been satisfied.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        boolean mfaRequired,
        String mfaToken,
        String accessToken,
        Long expiresInSeconds,
        UserResponse user
) {
    public static LoginResponse success(String accessToken, long expiresInSeconds, UserResponse user) {
        return new LoginResponse(false, null, accessToken, expiresInSeconds, user);
    }

    public static LoginResponse mfaRequired(String mfaToken) {
        return new LoginResponse(true, mfaToken, null, null, null);
    }
}
