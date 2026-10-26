package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One finished sign-in — decided 2026-10-26. Its id travels in the access
 * token as "sid"; ending the row ends that sign-in. See SessionService.
 */
@Entity
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(length = 64, updatable = false)
    private String ip;

    @Column(name = "user_agent", length = 400, updatable = false)
    private String userAgent;

    /** Still usable: not ended, and not past its token's expiry. */
    public boolean isLive(Instant now) {
        return endedAt == null && expiresAt.isAfter(now);
    }
}
