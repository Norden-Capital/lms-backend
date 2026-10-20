package org.example.backend.repository;

import org.example.backend.entity.ActionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long>, JpaSpecificationExecutor<ActionLog> {

    /** With actor and target, which every line shows — one query, not one per person. */
    @Override
    @EntityGraph(attributePaths = {"actor", "target"})
    Page<ActionLog> findAll(Specification<ActionLog> spec, Pageable pageable);
}
