package org.example.backend.dto;

import org.example.backend.entity.UserSession;

import java.time.Instant;
import java.util.UUID;

/**
 * One place you are signed in, for Settings — added 2026-10-26. The browser's
 * description is sent as it came; the website turns it into "Chrome on Windows".
 *
 * @param current the session this very request belongs to
 */
public record SessionResponse(
        UUID id,
        String userAgent,
        String ip,
        Instant createdAt,
        Instant lastSeenAt,
        boolean current
) {
    public static SessionResponse from(UserSession session, UUID current) {
        return new SessionResponse(session.getId(), session.getUserAgent(), session.getIp(),
                session.getCreatedAt(), session.getLastSeenAt(), session.getId().equals(current));
    }
}
