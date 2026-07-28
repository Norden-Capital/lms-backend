package org.example.backend.repository;

import org.example.backend.entity.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    /**
     * Fetched as its own query rather than joined onto the user, so the role's
     * permissions and the user's overrides never form a cartesian product.
     */
    @Query("SELECT up FROM UserPermission up JOIN FETCH up.permission WHERE up.user.id = :userId")
    List<UserPermission> findByUserIdWithPermission(Long userId);
}
