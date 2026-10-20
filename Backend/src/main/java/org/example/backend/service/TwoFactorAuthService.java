package org.example.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.OptionalLong;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.exceptions.TimeProviderException;
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
     * Which 30-second step a code belongs to, if it is valid at all — the same
     * window {@link #isCodeValid} allows, one step either side.
     *
     * <p>The step is what makes a code single-use (decided 2026-10-19): the
     * caller remembers the last step it accepted and refuses any code from that
     * step or before. Without it the same six digits worked for up to ninety
     * seconds, to anybody who saw them.
     */
    public OptionalLong matchingStep(String secret, String code) {
        if (secret == null || code == null || code.isBlank()) return OptionalLong.empty();
        String candidate = code.trim();
        try {
            long current = Math.floorDiv(timeProvider.getTime(), PERIOD_SECONDS);
            for (long step = current - 1; step <= current + 1; step++) {
                String expected = codeGenerator.generate(secret, step);
                if (MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        candidate.getBytes(StandardCharsets.US_ASCII))) {
                    return OptionalLong.of(step);
                }
            }
        } catch (CodeGenerationException | TimeProviderException e) {
            return OptionalLong.empty();
        }
        return OptionalLong.empty();
    }

    private static final long PERIOD_SECONDS = 30;

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
