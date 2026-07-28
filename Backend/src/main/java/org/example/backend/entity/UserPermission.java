package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A single exception to what a user's role allows — one person given something
 * their colleagues on the same role don't have, or denied something they do.
 *
 * <p>In practice this table stays nearly empty: it holds exceptions, not
 * permissions. A unique constraint on (user_id, permission_id) makes it
 * impossible to hold both a GRANT and a DENY for the same permission, so the
 * database rejects the contradiction rather than the code having to check.
 */
@Entity
@Table(
        name = "user_permissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_permissions_user_permission",
                columnNames = {"user_id", "permission_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private PermissionEffect effect;

    /** Which admin approved this. Answers "why can Sarah export payroll?" a year later. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private User grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(length = 255)
    private String reason;

    @PrePersist
    void onCreate() {
        if (grantedAt == null) grantedAt = Instant.now();
    }
}
