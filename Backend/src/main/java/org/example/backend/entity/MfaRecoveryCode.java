package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A single-use way back in when the phone holding the authenticator app is lost,
 * wiped, or replaced. Without these, losing the phone means losing the account
 * permanently — the TOTP seed exists nowhere else.
 *
 * <p>Stored hashed, exactly like a password, so a leaked database doesn't hand
 * over working codes. The user sees each code once, when it's generated.
 */
@Entity
@Table(name = "mfa_recovery_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    /** Stamped the moment it's redeemed — that's what makes each code single-use. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }
}
