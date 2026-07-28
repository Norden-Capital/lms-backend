package org.example.backend.repository;

import org.example.backend.entity.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    /** The codes still available to this user — used ones are kept for the audit trail. */
    List<MfaRecoveryCode> findByUserIdAndUsedAtIsNull(Long userId);

    /** Called when a fresh batch is issued, so old codes stop working. */
    void deleteByUserId(Long userId);
}
