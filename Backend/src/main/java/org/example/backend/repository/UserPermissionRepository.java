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

    /**
     * Drops every per-person exception for one account.
     *
     * <p>Called when a role changes. An exception is stored as a difference from
     * a role — "this Member also gets exports" — so once the role underneath it
     * changes, the sentence no longer parses. Worse, a DENY that was written
     * against an administrator survives a promotion and quietly takes the
     * permission away again, which is a hole nobody would think to look for.
     */
    long deleteByUserId(Long userId);
}
