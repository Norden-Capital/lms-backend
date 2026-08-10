package org.example.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 2FA is now on, and here are the recovery codes.
 *
 * <p>This is the only time the codes are ever readable — only their hashes are
 * stored. Without them, losing the phone holding the authenticator app means
 * losing the account permanently.
 *
 * <p><b>The token is the other half of enrolment.</b> Someone reaching this point
 * has been carrying an enrolment-pending token, which is authorised for nothing
 * beyond enrolling. Switching 2FA on does not upgrade the token they already
 * hold — a JWT cannot be changed after it is issued — so without handing back a
 * new one here, they would finish the task and remain locked out, with the
 * enrolment endpoints now answering "already enabled". Logging out and back in
 * would be the only escape, and nobody would guess that.
 *
 * <p>Both token fields are absent when regenerating recovery codes: that caller
 * already holds a full token, and issuing a second one would needlessly extend
 * the lifetime of a credential nobody asked to renew.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MfaConfirmResponse(
        String message,
        List<String> recoveryCodes,
        String accessToken,
        Long expiresInSeconds
) {
    /** Enrolment finished: the codes, plus the token that unlocks the rest of the app. */
    public static MfaConfirmResponse enrolled(String message,
                                              List<String> recoveryCodes,
                                              String accessToken,
                                              long expiresInSeconds) {
        return new MfaConfirmResponse(message, recoveryCodes, accessToken, expiresInSeconds);
    }

    /** A fresh batch of codes for someone already enrolled. No new token. */
    public static MfaConfirmResponse codesOnly(String message, List<String> recoveryCodes) {
        return new MfaConfirmResponse(message, recoveryCodes, null, null);
    }
}
