package org.example.backend.service;

import org.example.backend.dto.UpdatePermissionsRequest;
import org.example.backend.dto.UserResponse;
import org.example.backend.entity.Module;
import org.example.backend.entity.Permission;
import org.example.backend.entity.PermissionEffect;
import org.example.backend.entity.User;
import org.example.backend.entity.UserPermission;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.ModuleRepository;
import org.example.backend.repository.PermissionRepository;
import org.example.backend.repository.UserPermissionRepository;
import org.example.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Changing what one person may do, beyond what their role gives them.
 *
 * <p>Four rules are enforced here, and every one of them exists because the
 * alternative is a state that looks deliberate in the database and surprises
 * somebody months later.
 */
@Service
public class UserPermissionService {

    private static final Logger log = LoggerFactory.getLogger(UserPermissionService.class);

    private static final String ADMIN_ROLE = "ADMIN";

    /** The action every other action on a module depends on. */
    private static final String VIEW_ACTION = "READ";

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final ModuleRepository moduleRepository;
    private final PermissionService permissionService;
    private final AccessService accessService;

    public UserPermissionService(UserRepository userRepository,
                                 PermissionRepository permissionRepository,
                                 UserPermissionRepository userPermissionRepository,
                                 ModuleRepository moduleRepository,
                                 PermissionService permissionService,
                                 AccessService accessService) {
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.moduleRepository = moduleRepository;
        this.permissionService = permissionService;
        this.accessService = accessService;
    }

    /**
     * Replaces somebody's exceptions with the ones given.
     *
     * <p><b>Rule 1 — not your own account.</b> Editing your own permissions is
     * granting yourself permissions, which makes every other check here
     * decorative.
     *
     * <p><b>Rule 2 — not an administrator.</b> Administrator means everything,
     * always. A grant would be a no-op and a deny would carve a quiet hole into a
     * role that every screen shows as complete. Narrow the person by making them
     * a Member, which is a visible decision.
     *
     * <p><b>Rule 3 — admin-only modules cannot be granted individually.</b>
     * Management is not "a module Member happens not to have"; it is one no member
     * may ever hold. Refused rather than silently dropped, because a request that
     * appears to succeed and does nothing is worse than one that fails.
     *
     * <p><b>Rule 4 — nothing without read.</b> Deleting a customer you cannot open
     * is not a permission anybody means to give. Checked against the
     * <em>effective</em> set, so it holds however the grants and denies combine.
     */
    @Transactional
    public UserResponse replaceFor(Long userId, UpdatePermissionsRequest request, Long actingUserId) {
        if (userId.equals(actingUserId)) {
            throw ApiException.forbidden(
                    "You cannot change your own permissions. Ask another administrator.");
        }

        User user = userRepository.findWithRoleById(userId)
                .orElseThrow(() -> ApiException.notFound("No user with id " + userId + "."));

        if (ADMIN_ROLE.equals(user.getRole().getName())) {
            throw ApiException.badRequest(
                    "Administrators hold every permission and cannot be given exceptions. "
                            + "Change their role to Member first.");
        }

        Map<String, Permission> catalogue = permissionsByAuthority();

        Set<String> granted = resolve(request.granted(), catalogue, "granted");
        Set<String> denied = resolve(request.denied(), catalogue, "denied");

        // The unique constraint on (user_id, permission_id) would reject this at
        // the database, but as a 500 rather than an explanation.
        Set<String> both = new HashSet<>(granted);
        both.retainAll(denied);
        if (!both.isEmpty()) {
            throw ApiException.badRequest(
                    "These are both granted and denied: " + String.join(", ", new TreeSet<>(both)) + ".");
        }

        rejectAdminOnlyGrants(granted);

        Set<String> effective = effectiveAfter(user, granted, denied);
        requireReadBehindEverything(effective, catalogue);

        userPermissionRepository.deleteByUserId(userId);
        // Flushed before the inserts, or Hibernate may order the new rows ahead of
        // the delete and trip the unique constraint on a permission being re-added.
        userPermissionRepository.flush();

        User actor = userRepository.getReferenceById(actingUserId);
        String reason = request.reason() == null || request.reason().isBlank()
                ? null
                : request.reason().trim();

        granted.forEach(authority -> userPermissionRepository.save(UserPermission.builder()
                .user(user)
                .permission(catalogue.get(authority))
                .effect(PermissionEffect.GRANT)
                .grantedBy(actor)
                .reason(reason)
                .build()));

        denied.forEach(authority -> userPermissionRepository.save(UserPermission.builder()
                .user(user)
                .permission(catalogue.get(authority))
                .effect(PermissionEffect.DENY)
                .grantedBy(actor)
                .reason(reason)
                .build()));

        // Without this the change would not bite until the cached answer expired,
        // which is the whole problem this design was chosen to avoid.
        accessService.invalidate(userId);

        log.info("User id={} set {} grant(s) and {} deny/denies on account id={}",
                actingUserId, granted.size(), denied.size(), userId);

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /** Every real permission, keyed by the {@code RESOURCE:ACTION} string. */
    private Map<String, Permission> permissionsByAuthority() {
        Map<String, Permission> byAuthority = new LinkedHashMap<>();
        permissionRepository.findAll().forEach(p -> byAuthority.put(p.toAuthority(), p));
        return byAuthority;
    }

    /**
     * Checks the names are real before anything is written.
     *
     * <p>A misspelt permission would otherwise be stored happily, grant nothing,
     * and look exactly like a permission that had been given.
     */
    private Set<String> resolve(List<String> requested,
                                Map<String, Permission> catalogue,
                                String field) {

        Set<String> resolved = new TreeSet<>();
        Set<String> unknown = new TreeSet<>();

        for (String authority : requested) {
            if (authority == null || authority.isBlank()) continue;

            String trimmed = authority.trim().toUpperCase();
            if (catalogue.containsKey(trimmed)) resolved.add(trimmed);
            else unknown.add(trimmed);
        }

        if (!unknown.isEmpty()) {
            throw ApiException.badRequest(
                    "No such permission in " + field + ": " + String.join(", ", unknown) + ".");
        }

        return resolved;
    }

    /** Rule 3. */
    private void rejectAdminOnlyGrants(Set<String> granted) {
        Set<String> restricted = new HashSet<>();
        moduleRepository.findByAdminOnlyTrue().forEach(m -> restricted.add(m.getKey()));

        if (restricted.isEmpty()) return;

        Set<String> refused = new TreeSet<>();
        for (String authority : granted) {
            if (restricted.contains(moduleOf(authority))) refused.add(authority);
        }

        if (!refused.isEmpty()) {
            throw ApiException.forbidden(
                    "These can only come from being an administrator and cannot be granted to one "
                            + "person: " + String.join(", ", refused) + ".");
        }
    }

    /** Role, plus the grants, minus the denies — the same rule PermissionService applies. */
    private Set<String> effectiveAfter(User user, Set<String> granted, Set<String> denied) {
        Set<String> effective = new TreeSet<>();
        user.getRole().getPermissions().forEach(p -> effective.add(p.toAuthority()));
        effective.addAll(granted);
        effective.removeAll(denied);
        return effective;
    }

    /** Rule 4. */
    private void requireReadBehindEverything(Set<String> effective, Map<String, Permission> catalogue) {
        Set<String> modulesWithRead = new HashSet<>();
        for (String authority : effective) {
            if (VIEW_ACTION.equals(actionOf(authority))) modulesWithRead.add(moduleOf(authority));
        }

        Set<String> orphans = new TreeSet<>();
        for (String authority : effective) {
            String module = moduleOf(authority);

            if (VIEW_ACTION.equals(actionOf(authority))) continue;
            // A module with no read action of its own gates nothing.
            if (!catalogue.containsKey(module + ":" + VIEW_ACTION)) continue;

            if (!modulesWithRead.contains(module)) orphans.add(authority);
        }

        if (!orphans.isEmpty()) {
            throw ApiException.badRequest(
                    "These need read on the same module, which this account would not have: "
                            + String.join(", ", orphans) + ".");
        }
    }

    private String moduleOf(String authority) {
        int colon = authority.indexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private String actionOf(String authority) {
        int colon = authority.indexOf(':');
        return colon < 0 ? "" : authority.substring(colon + 1);
    }
}
