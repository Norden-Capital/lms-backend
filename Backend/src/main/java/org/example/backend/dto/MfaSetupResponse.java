package org.example.backend.dto;

/**
 * What the user needs to add the account to Google Authenticator.
 *
 * <p>Getting this does <em>not</em> switch 2FA on. Nothing changes until a code
 * from the app is confirmed — otherwise closing the tab mid-setup would lock the
 * account permanently.
 *
 * @param secret     the seed, for people who type it in rather than scan
 * @param qrCodeImage a {@code data:image/png;base64,...} URL to render directly
 */
public record MfaSetupResponse(
        String secret,
        String qrCodeImage
) {
}
