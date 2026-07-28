package org.example.backend.repository;

import org.example.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Login path: pulls the user, their role, and the role's permissions in one
     * query. The user's own overrides are loaded separately by
     * {@link UserPermissionRepository} — fetching two collections at once would
     * produce a cartesian product.
     */
    @Query("SELECT u FROM User u JOIN FETCH u.role r LEFT JOIN FETCH r.permissions WHERE u.email = :email")
    Optional<User> findByEmailWithRolePermissions(String email);
}
