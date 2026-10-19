package org.example.backend.repository;

import org.example.backend.entity.AuthToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    /** With the user and their role, which every caller goes on to read. */
    @EntityGraph(attributePaths = {"user", "user.role"})
    Optional<AuthToken> findByTokenHashAndType(String tokenHash, AuthToken.Type type);

    /** The most recent link of a kind — "was one just sent?" */
    Optional<AuthToken> findFirstByUserIdAndTypeOrderByCreatedAtDesc(Long userId, AuthToken.Type type);

    /** Kills every outstanding link of a kind, so only the newest email works. */
    long deleteByUserIdAndType(Long userId, AuthToken.Type type);
}
