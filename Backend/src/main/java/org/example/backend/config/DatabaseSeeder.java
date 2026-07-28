package org.example.backend.config;

import org.example.backend.entity.Role;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.repository.RoleRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Creates the accounts needed to exercise every login path on a fresh database:
 * one without a second factor, and one with it already enrolled.
 *
 * <p>Roles and permissions are seeded by the V2 migration instead — they are
 * static reference data. Users are seeded here because a password has to be
 * hashed and a TOTP seed encrypted by the application, neither of which SQL can do.
 */
@Configuration
public class DatabaseSeeder {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "admin123";

    private static final String MFA_EMAIL = "mfa@example.com";
    private static final String MFA_PASSWORD = "mfa12345";

    /**
     * A fixed TOTP seed, so the authenticator app entry stays valid across database
     * resets. Real enrolments generate a random one per user — this constant exists
     * only so a developer does not have to re-scan a QR code every time.
     */
    private static final String MFA_TEST_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    @Bean
    public CommandLineRunner seedUsers(UserRepository userRepository,
                                       RoleRepository roleRepository,
                                       PasswordEncoder passwordEncoder,
                                       EncryptionService encryptionService) {
        return args -> {
            seedAdmin(userRepository, roleRepository, passwordEncoder);
            seedMfaUser(userRepository, roleRepository, passwordEncoder, encryptionService);
        };
    }

    /** Full access, no second factor. The account to log in with day to day. */
    private void seedAdmin(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder) {
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            log.info("Admin user already present — skipping seed.");
            return;
        }

        User admin = User.builder()
                .firstName("Admin")
                .lastName("User")
                .email(ADMIN_EMAIL)
                .phone("+11122233333")
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .role(requireRole(roleRepository, "ADMIN"))
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .build();

        userRepository.save(admin);

        log.warn("""

                ****************************************************************
                Seeded a development admin account:
                    {} / {}
                Change this password before the application is reachable
                by anyone else.
                ****************************************************************""",
                ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    /**
     * Second factor already enrolled, so {@code POST /api/auth/login} returns
     * {@code mfaRequired: true} and the two-step path can be tested.
     *
     * <p>Given the MANAGER role on purpose: it also covers the "authenticated but
     * not allowed" case, which an admin account can never produce.
     */
    private void seedMfaUser(UserRepository userRepository,
                             RoleRepository roleRepository,
                             PasswordEncoder passwordEncoder,
                             EncryptionService encryptionService) {
        if (userRepository.existsByEmail(MFA_EMAIL)) {
            log.info("2FA test user already present — skipping seed.");
            return;
        }

        User mfaUser = User.builder()
                .firstName("Mfa")
                .lastName("Tester")
                .email(MFA_EMAIL)
                .passwordHash(passwordEncoder.encode(MFA_PASSWORD))
                .role(requireRole(roleRepository, "MANAGER"))
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                // Stored encrypted, exactly as a real enrolment would store it.
                .mfaSecret(encryptionService.encrypt(MFA_TEST_SECRET))
                .mfaEnabled(true)
                .mfaConfirmedAt(Instant.now())
                .build();

        userRepository.save(mfaUser);

        String otpauth = "otpauth://totp/" + URLEncoder.encode(MFA_EMAIL, StandardCharsets.UTF_8)
                + "?secret=" + MFA_TEST_SECRET
                + "&issuer=LMS&algorithm=SHA1&digits=6&period=30";

        log.warn("""

                ****************************************************************
                Seeded a development account with 2FA already enabled:
                    {} / {}

                Add it to an authenticator app using "enter a setup key":
                    Secret : {}
                    Or URI : {}

                Logging in with this account returns mfaRequired: true and an
                mfaToken — send that plus the 6-digit code to /api/auth/login/2fa.

                This seed has no recovery codes. Call /api/auth/2fa/recovery-codes
                once logged in to issue some.
                ****************************************************************""",
                MFA_EMAIL, MFA_PASSWORD, MFA_TEST_SECRET, otpauth);
    }

    private Role requireRole(RoleRepository roleRepository, String name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException(
                        name + " role missing — the V2 seed migration did not run"));
    }
}
