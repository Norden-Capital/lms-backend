package org.example.backend.config;

import org.example.backend.entity.Role;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.repository.RoleRepository;
import org.example.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The first administrator, and nothing else.
 *
 * <p><b>This used to create two accounts with their passwords written in the
 * source</b> — {@code admin@example.com / admin123} and a second with a fixed
 * TOTP seed — on every boot, including on the server. Anyone who read the
 * repository was an administrator, and deleting the accounts did not help
 * because the next restart put them back. That is why this is now a bootstrap
 * rather than a seeder.
 *
 * <p>Three rules, and each one closes a way the old version failed:
 *
 * <ul>
 *   <li><b>Only when there are no users at all.</b> Not "when this email is
 *       missing" — that is what made the accounts immortal. Once anybody exists,
 *       this does nothing forever.</li>
 *   <li><b>The password comes from the environment.</b> There is no default. A
 *       password that lives in a file that lives in a repository is a published
 *       password, whatever it says next to it.</li>
 *   <li><b>Without one, nothing is created.</b> It fails towards an empty
 *       database and a loud log line, not towards a guessable account. An
 *       installation with no way in is recoverable; one with a known
 *       administrator is not.</li>
 * </ul>
 *
 * <p>The account is created without two-factor, which is not a gap: it is
 * mandatory here, so the first sign-in lands in enrolment and nothing else can be
 * done until it is finished.
 *
 * <p>Roles and permissions are seeded by migration instead — they are static
 * reference data. This is here because a password has to be hashed by the
 * application, which SQL cannot do.
 */
@Configuration
public class DatabaseSeeder {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

    private static final String ADMIN_ROLE = "ADMIN";

    /** No default. An unset value means "create nothing", never "use a fallback". */
    @Value("${bootstrap.admin.email:}")
    private String adminEmail;

    @Value("${bootstrap.admin.password:}")
    private String adminPassword;

    @Value("${bootstrap.admin.first-name:System}")
    private String adminFirstName;

    @Value("${bootstrap.admin.last-name:Administrator}")
    private String adminLastName;

    @Bean
    public CommandLineRunner bootstrapFirstAdmin(UserRepository userRepository,
                                                 RoleRepository roleRepository,
                                                 PasswordEncoder passwordEncoder) {
        return args -> {
            if (userRepository.count() > 0) {
                log.debug("Accounts already exist — bootstrap skipped.");
                return;
            }

            if (adminEmail.isBlank() || adminPassword.isBlank()) {
                log.warn("""
                        No accounts exist and no bootstrap administrator is configured, so none \
                        was created. Nobody can sign in.

                        Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD and restart. Both \
                        are read once, on an empty database, and ignored afterwards.""");
                return;
            }

            if (adminPassword.length() < 8) {
                log.error("BOOTSTRAP_ADMIN_PASSWORD is shorter than 8 characters — no account created.");
                return;
            }

            Role admin = roleRepository.findByName(ADMIN_ROLE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Role " + ADMIN_ROLE + " is missing — check the migrations ran"));

            User user = User.builder()
                    .firstName(adminFirstName.trim())
                    .lastName(adminLastName.trim())
                    .email(adminEmail.trim().toLowerCase())
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .role(admin)
                    // ACTIVE rather than PENDING_VERIFICATION: nothing sends email, so an
                    // account waiting on a link nobody receives could never be used.
                    .status(UserStatus.ACTIVE)
                    .build();

            userRepository.save(user);

            // The email, never the password. A log file is a place passwords are
            // found later, and this one is already known to whoever set it.
            log.info("""
                    Bootstrap administrator created: {}

                    Two-factor is mandatory, so the first sign-in goes straight to enrolment. \
                    Nothing else can be done until it is finished.""", user.getEmail());
        };
    }
}
