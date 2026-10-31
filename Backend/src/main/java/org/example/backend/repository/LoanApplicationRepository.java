package org.example.backend.repository;

import jakarta.persistence.LockModeType;
import org.example.backend.entity.ApplicationStatus;
import org.example.backend.entity.Client;
import org.example.backend.entity.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    Optional<LoanApplication> findByStepToken(String stepToken);

    /** Held until the transaction ends, so two clicks on the button cannot both send. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoanApplication a WHERE a.stepToken = :token")
    Optional<LoanApplication> lockByStepToken(@Param("token") String token);

    boolean existsByClient(Client client);

    boolean existsByBrandId(Long brandId);

    /** A person who became a lead leaves the Abandoned list; their old tries stay as history. */
    @Modifying
    @Query("""
            UPDATE LoanApplication a SET a.status = :to, a.updatedAt = :now
            WHERE a.client = :client AND a.status = :from AND a.id <> :except""")
    int moveOthers(@Param("client") Client client,
                   @Param("except") Long except,
                   @Param("from") ApplicationStatus from,
                   @Param("to") ApplicationStatus to,
                   @Param("now") Instant now);
}
