package org.example.backend.repository;

import jakarta.persistence.LockModeType;
import org.example.backend.entity.ApplicationStatus;
import org.example.backend.entity.Client;
import org.example.backend.entity.LoanApplication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoanApplicationRepository
        extends JpaRepository<LoanApplication, Long>, JpaSpecificationExecutor<LoanApplication> {

    Optional<LoanApplication> findByStepToken(String stepToken);

    /** Held until the transaction ends, so two clicks on the button cannot both send. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoanApplication a WHERE a.stepToken = :token")
    Optional<LoanApplication> lockByStepToken(@Param("token") String token);

    /** A list's page: client and brand come along, so the rows need no extra queries. */
    @Override
    @EntityGraph(attributePaths = {"client", "brand"})
    Page<LoanApplication> findAll(Specification<LoanApplication> filter, Pageable pageable);

    @EntityGraph(attributePaths = "brand")
    List<LoanApplication> findByClientOrderByCreatedAtDescIdDesc(Client client);

    /** Counts per day, brand, status and LeadScout answer: day, brand id, status, answer, count. */
    @Query(value = """
            SELECT to_char(a.created_at AT TIME ZONE :zone, 'YYYY-MM-DD'), a.brand_id, a.status, a.leadscout_status, count(*)
            FROM loan_applications a
            WHERE a.created_at >= :from AND a.mode IN (:modes)
            GROUP BY 1, 2, 3, 4""", nativeQuery = true)
    List<Object[]> dailyCounts(@Param("from") Instant from, @Param("zone") String zone,
                               @Param("modes") Collection<String> modes);

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
