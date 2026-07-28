package org.example.backend.repository;

import org.example.backend.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Invalidates any outstanding links when a new one is requested. */
    void deleteByUserId(Long userId);
}
