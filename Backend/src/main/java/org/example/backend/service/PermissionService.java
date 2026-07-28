package org.example.backend.service;

import org.example.backend.entity.PermissionEffect;
import org.example.backend.entity.User;
import org.example.backend.entity.UserPermission;
import org.example.backend.repository.UserPermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out what a user is actually allowed to do.
 *
 * <p>The whole authorisation model comes down to one line:
 *
 * <pre>effective = (role's permissions ∪ user GRANTs) − user DENYs</pre>
 *
 * <p>Deny always wins. Keeping that rule in exactly one place is what makes it
 * possible to answer "why can this person do that?" — the moment the rule is
 * duplicated or special-cased, nobody can explain the system any more.
 */
@Service
public class PermissionService {

    private final UserPermissionRepository userPermissionRepository;

    public PermissionService(UserPermissionRepository userPermissionRepository) {
        this.userPermissionRepository = userPermissionRepository;
    }

    /**
     * @return authority strings such as {@code COURSE:CREATE}, ready to be put in
     *         a token or handed to Spring Security
     */
    @Transactional(readOnly = true)
    public Set<String> effectivePermissionsFor(User user) {
        Set<String> effective = new LinkedHashSet<>();

        // Start from what the role gives everyone who holds it.
        user.getRole().getPermissions()
                .forEach(p -> effective.add(p.toAuthority()));

        // Layer this individual's exceptions on top. Applying grants before denies
        // is what makes deny win when both somehow exist.
        List<UserPermission> overrides = userPermissionRepository.findByUserIdWithPermission(user.getId());

        overrides.stream()
                .filter(o -> o.getEffect() == PermissionEffect.GRANT)
                .forEach(o -> effective.add(o.getPermission().toAuthority()));

        overrides.stream()
                .filter(o -> o.getEffect() == PermissionEffect.DENY)
                .forEach(o -> effective.remove(o.getPermission().toAuthority()));

        return effective;
    }
}
