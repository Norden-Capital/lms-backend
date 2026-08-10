package org.example.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of a login attempt, in one of three shapes.
 *
 * <p>2FA on — one more step to go:
 * <pre>{ "mfaRequired": true, "mfaToken": "..." }</pre>
 *
 * <p>2FA set up and satisfied — the login is finished:
 * <pre>{ "accessToken": "...", "expiresInSeconds": 86400, "user": { ... } }</pre>
 *
 * <p>2FA never set up — signed in, but able to do nothing except enrol:
 * <pre>{ "enrolmentRequired": true, "accessToken": "...", "user": { ... } }</pre>
 *
 * <p>Note what the first shape does <em>not</em> contain: no access token, and no
 * user details. Nothing is revealed until both factors have been satisfied.
 *
 * <p>The third shape does carry a token, and that is not a hole. It is an
 * enrolment-pending token holding no permissions, which {@code SecurityConfig}
 * accepts on the enrolment endpoints alone. The flag exists so the frontend knows
 * to send the person to the enrolment screen; it is not what enforces anything.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        boolean mfaRequired,
        boolean enrolmentRequired,
        String mfaToken,
        String accessToken,
        Long expiresInSeconds,
        UserResponse user
) {
    public static LoginResponse success(String accessToken, long expiresInSeconds, UserResponse user) {
        return new LoginResponse(false, false, null, accessToken, expiresInSeconds, user);
    }

    public static LoginResponse mfaRequired(String mfaToken) {
        return new LoginResponse(true, false, mfaToken, null, null, null);
    }

    public static LoginResponse enrolmentRequired(String enrolmentToken, long expiresInSeconds, UserResponse user) {
        return new LoginResponse(false, true, null, enrolmentToken, expiresInSeconds, user);
    }
}
