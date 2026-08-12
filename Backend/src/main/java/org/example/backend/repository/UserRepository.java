package org.example.backend.repository;

import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Whether the email is taken by <em>somebody else</em>.
     *
     * <p>Needed on update, where the account being edited already holds the
     * address it is being saved with — {@code existsByEmail} would reject every
     * save that did not change it.
     */
    boolean existsByEmailAndIdNot(String email, Long id);

    /**
     * How many accounts in a role are in a given state.
     *
     * <p>Used for one thing: refusing to switch off the last working
     * administrator. Nothing else in the system can create one, so an
     * organisation that loses its last one has no way back in.
     */
    long countByRoleNameAndStatus(String roleName, UserStatus status);

    /**
     * Login path: pulls the user, their role, and the role's permissions in one
     * query. The user's own overrides are loaded separately by
     * {@link UserPermissionRepository} — fetching two collections at once would
     * produce a cartesian product.
     */
    @Query("SELECT u FROM User u JOIN FETCH u.role r LEFT JOIN FETCH r.permissions WHERE u.email = :email")
    Optional<User> findByEmailWithRolePermissions(String email);

    /**
     * The list, with each user's role already loaded.
     *
     * <p>The graph is what stops this being N+1 all over again: the summary shows
     * a role name, and without it Hibernate fetches the role row separately for
     * every person on the page.
     *
     * <p>A {@code Specification} override rather than a {@code @Query}, so the
     * filters in {@link UserSpecifications} still apply.
     */
    @Override
    @EntityGraph(attributePaths = "role")
    Page<User> findAll(org.springframework.data.jpa.domain.Specification<User> spec, Pageable pageable);

    /** One user with their role and its permissions, for the detail screen. */
    @EntityGraph(attributePaths = {"role", "role.permissions"})
    Optional<User> findWithRoleById(Long id);
}
