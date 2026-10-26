package org.example.backend.repository;

import org.example.backend.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /** Someone's sessions that are still usable, most recently used first. */
    @Query("""
            SELECT s FROM UserSession s
            WHERE s.userId = :userId AND s.endedAt IS NULL AND s.expiresAt > :now AND s.createdAt >= :since
            ORDER BY s.lastSeenAt DESC
            """)
    List<UserSession> findLive(@Param("userId") Long userId, @Param("now") Instant now, @Param("since") Instant since);

    /** Ends every open session of someone's except one. Returns how many. */
    @Modifying
    @Query("""
            UPDATE UserSession s SET s.endedAt = :now
            WHERE s.userId = :userId AND s.endedAt IS NULL AND s.id <> :keep
            """)
    int endAllExcept(@Param("userId") Long userId, @Param("keep") UUID keep, @Param("now") Instant now);

    /** Ends every open session of someone's. Returns how many. */
    @Modifying
    @Query("UPDATE UserSession s SET s.endedAt = :now WHERE s.userId = :userId AND s.endedAt IS NULL")
    int endAll(@Param("userId") Long userId, @Param("now") Instant now);

    /** Marks a session as just used. */
    @Modifying
    @Query("UPDATE UserSession s SET s.lastSeenAt = :now WHERE s.id = :id")
    void touch(@Param("id") UUID id, @Param("now") Instant now);
}
