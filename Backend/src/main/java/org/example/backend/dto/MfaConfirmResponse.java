package org.example.backend.dto;

import java.util.List;

/**
 * 2FA is now on, and here are the recovery codes.
 *
 * <p>This is the only time these values are ever readable — only their hashes are
 * stored. Without them, losing the phone holding the authenticator app means
 * losing the account permanently.
 */
public record MfaConfirmResponse(
        String message,
        List<String> recoveryCodes
) {
}
