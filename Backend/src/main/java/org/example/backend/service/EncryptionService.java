package org.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Reversible encryption for the one secret that cannot be hashed.
 *
 * <p>Passwords and recovery codes are hashed, because verifying them only ever
 * needs a comparison. A TOTP seed is different: computing the code we expect
 * requires the original value back, so it has to be encrypted rather than
 * one-way hashed.
 *
 * <p>AES-GCM is used because it authenticates as well as encrypts — a tampered
 * ciphertext fails to decrypt instead of silently producing garbage. A fresh
 * random IV is generated per value and stored alongside the ciphertext, which is
 * what stops identical inputs producing identical output.
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // 96 bits, the size GCM is designed for
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${app.encryption.key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.key = new SecretKeySpec(generated, "AES");
            log.warn("""

                    ****************************************************************
                    No app.encryption.key configured — generated a random AES key.
                    Any MFA secret stored now becomes unreadable after a restart,
                    so 2FA will break for those users.
                    Set the ENCRYPTION_KEY environment variable before deploying.
                    ****************************************************************""");
        } else {
            // SHA-256 turns a passphrase of any length into the exact 256 bits AES
            // requires, so the configured value doesn't have to be a precise size.
            this.key = new SecretKeySpec(sha256(configuredKey), "AES");
        }
    }

    /** @return Base64 of {@code iv || ciphertext}, safe to store in a varchar column */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt value", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt value — wrong key, or the data was tampered with", e);
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
