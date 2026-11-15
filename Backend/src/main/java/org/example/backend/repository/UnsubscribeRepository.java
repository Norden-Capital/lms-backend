package org.example.backend.repository;

import org.example.backend.entity.Unsubscribe;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface UnsubscribeRepository
        extends JpaRepository<Unsubscribe, Long>, JpaSpecificationExecutor<Unsubscribe> {

    /** Every brand this email unsubscribed from, newest first — for the client's page. */
    @EntityGraph(attributePaths = "brand")
    List<Unsubscribe> findByEmailOrderByUpdatedAtDescIdDesc(String email);

    /** The list's pages: brand and client in the same query. */
    @Override
    @EntityGraph(attributePaths = {"brand", "client"})
    Page<Unsubscribe> findAll(Specification<Unsubscribe> filter, Pageable pageable);

    /** The CSV: brand and client in the same query, not one query per row. */
    @Override
    @EntityGraph(attributePaths = {"brand", "client"})
    List<Unsubscribe> findAll(Specification<Unsubscribe> filter, Sort sort);

    boolean existsByBrandId(Long brandId);
}
