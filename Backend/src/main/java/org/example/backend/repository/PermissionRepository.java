package org.example.backend.repository;

import org.example.backend.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByResourceAndAction(String resource, String action);

    /** Ordered so an admin permission grid renders in a stable, predictable order. */
    List<Permission> findAllByOrderByResourceAscActionAsc();
}
