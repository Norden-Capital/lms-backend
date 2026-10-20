package org.example.backend.repository;

import org.example.backend.entity.BackupRun;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {

    Optional<BackupRun> findByPublicId(String publicId);

    /** Every run, newest first. A year of nightly runs is a few hundred rows. */
    @EntityGraph(attributePaths = "triggeredBy")
    List<BackupRun> findAllByOrderByStartedAtDesc();

    /**
     * Runs left "running" by a server that stopped halfway — they will never
     * finish, so they are closed as failed when the server starts again.
     */
    @Modifying
    @Query("""
            update BackupRun r
               set r.outcome = org.example.backend.entity.BackupRun.Outcome.FAILED,
                   r.finishedAt = :now,
                   r.error = :why
             where r.outcome = org.example.backend.entity.BackupRun.Outcome.RUNNING""")
    int closeAbandoned(Instant now, String why);
}
