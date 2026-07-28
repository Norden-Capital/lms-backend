package org.example.backend.service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Authenticator support: seeds, QR codes, code checking, and the recovery
 * codes that stop a lost phone from meaning a lost account.
 */
@Service
public class TwoFactorAuthService {

    /** How many recovery codes are issued at once. */
    public static final int RECOVERY_CODE_COUNT = 10;

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I/O/0/1
    private static final int CODE_GROUP_LENGTH = 5;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final CodeVerifier codeVerifier;
    private final SecureRandom random = new SecureRandom();

    private final String issuer;

    public TwoFactorAuthService(@Value("${app.mfa.issuer:LMS}") String issuer) {
        this.issuer = issuer;

        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        // Accept the adjacent 30-second window either side, so a code entered as it
        // rolls over still works. Wider than this meaningfully weakens the factor.
        verifier.setAllowedTimePeriodDiscrepancy(1);
        this.codeVerifier = verifier;
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    /** A {@code data:image/png;base64,...} URL the frontend can drop straight into an {@code <img>}. */
    public String generateQrCodeDataUrl(String secret, String email) {
        QrData data = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)   // what Google Authenticator expects
                .digits(6)
                .period(30)
                .build();

        QrGenerator generator = new ZxingPngQrGenerator();
        try {
            byte[] image = generator.generate(data);
            return Utils.getDataUriForImage(image, generator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new IllegalStateException("Failed to generate the 2FA QR code", e);
        }
    }

    public boolean isCodeValid(String secret, String code) {
        if (secret == null || code == null || code.isBlank()) return false;
        return codeVerifier.isValidCode(secret, code.trim());
    }

    /**
     * Fresh single-use codes, in plaintext. The caller must hash these before
     * storing and show them to the user exactly once — after this they are
     * unrecoverable by design.
     */
    public List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            codes.add(randomGroup() + "-" + randomGroup());
        }
        return codes;
    }

    private String randomGroup() {
        StringBuilder sb = new StringBuilder(CODE_GROUP_LENGTH);
        for (int i = 0; i < CODE_GROUP_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
