package org.example.backend.repository;

import org.example.backend.entity.ActionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long>, JpaSpecificationExecutor<ActionLog> {

    /** With actor and target, which every line shows — one query, not one per person. */
    @Override
    @EntityGraph(attributePaths = {"actor", "target"})
    Page<ActionLog> findAll(Specification<ActionLog> spec, Pageable pageable);

    /**
     * One person's sign-ins, counted per day in the given time zone: [day, ok, failed].
     * A good sign-in has them as the actor; a failed one has them as the target.
     */
    @Query(value = """
            SELECT to_char(created_at AT TIME ZONE :zone, 'YYYY-MM-DD') AS day,
                   count(*) FILTER (WHERE action = 'SIGN_IN') AS ok,
                   count(*) FILTER (WHERE action = 'SIGN_IN_FAILED') AS failed
            FROM action_logs
            WHERE (action = 'SIGN_IN' AND actor_id = :userId)
               OR (action = 'SIGN_IN_FAILED' AND target_id = :userId)
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> signInDays(@Param("userId") Long userId, @Param("zone") String zone);
}
