package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One row per human.
 *
 * <p>Deliberately not annotated with Lombok's {@code @Data}: that would generate a
 * {@code toString()} printing the password hash and MFA secret into the logs, and
 * an {@code equals()} over the id that misbehaves for unsaved entities.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    /** Login identity. Always stored lowercase so Jane@x.com and jane@x.com are one account. */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(length = 20)
    private String phone;

    /** BCrypt hash. One-way — the real password is never stored. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Whether login should demand a 6-digit code from the authenticator app. */
    @Column(name = "mfa_enabled", nullable = false)
    @Builder.Default
    private boolean mfaEnabled = false;

    /**
     * TOTP seed shared with Google Authenticator. Encrypted rather than hashed —
     * the original value is needed to compute the code we expect, so it cannot be
     * one-way like a password.
     */
    @Column(name = "mfa_secret", length = 255)
    private String mfaSecret;

    /**
     * Proof the QR was actually scanned and a code worked. Without this, enabling
     * 2FA at sign-up locks people out forever if they close the tab before scanning.
     */
    @Column(name = "mfa_confirmed_at")
    private Instant mfaConfirmedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<UserPermission> permissionOverrides = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    /** True while a lockout from too many failed logins is still in force. */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }
}
