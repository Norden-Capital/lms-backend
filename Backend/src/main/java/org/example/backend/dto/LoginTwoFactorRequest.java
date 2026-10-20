package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Step two of logging in, when 2FA is enabled.
 *
 * <p>The {@code mfaToken} is the short-lived token handed out by step one; it is
 * what proves the password was already accepted. Supply either the 6-digit
 * {@code code} from the authenticator app or a {@code recoveryCode} — one of the
 * two is required.
 */
public record LoginTwoFactorRequest(

        @NotBlank(message = "Please sign in again")
        String mfaToken,

        String code,

        String recoveryCode
) {
    public boolean hasCode() {
        return code != null && !code.isBlank();
    }

    public boolean hasRecoveryCode() {
        return recoveryCode != null && !recoveryCode.isBlank();
    }
}
