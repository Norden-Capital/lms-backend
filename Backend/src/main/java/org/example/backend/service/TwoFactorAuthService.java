package org.example.backend.service;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import org.springframework.stereotype.Service;

@Service
public class TwoFactorAuthService {

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final CodeVerifier codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public String generateQrCodeDataUrl(String secret, String email) {
        QrData data = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer("LMS Backend")
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        QrGenerator generator = new ZxingPngQrGenerator();
        try {
            byte[] imageData = generator.generate(data);
            return Utils.getDataUriForImage(imageData, generator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new RuntimeException("Error generating QR code", e);
        }
    }

    public boolean isCodeValid(String secret, String code) {
        if (secret == null || code == null) return false;
        return codeVerifier.isValidCode(secret, code);
    }
}
