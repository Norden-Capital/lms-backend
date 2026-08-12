package org.example.backend.service;

import org.example.backend.dto.PermissionCatalogueResponse;
import org.example.backend.dto.RoleResponse;
import org.example.backend.entity.Module;
import org.example.backend.entity.Permission;
import org.example.backend.repository.ModuleRepository;
import org.example.backend.repository.PermissionRepository;
import org.example.backend.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading the permission model: what modules exist, and what each role grants.
 *
 * <p>Exists so the frontend can stop keeping its own copy. Every screen that
 * draws a permission grid described the model by hand in TypeScript, which meant
 * a module added to the database was silently missing from the UI until somebody
 * remembered to edit both.
 *
 * <p>Read-only, and it has to be. Nothing changes what a role grants —
 * {@code role_permissions} is written by migration and never by the application.
 * A role that can be edited at runtime is one nobody can reason about six months
 * later, because the answer to "what does Member mean" stops being in the repo.
 */
@Service
public class AccessCatalogueService {

    /**
     * Read first, then the rest.
     *
     * <p>Every other action on a module depends on being able to see it, so a
     * prerequisite listed second reads as an afterthought. Fixed here rather than
     * in each screen, so the three grids cannot disagree about the order.
     */
    private static final List<String> ACTION_ORDER =
            List.of("READ", "CREATE", "UPDATE", "DELETE", "EXPORT");

    private final ModuleRepository moduleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    public AccessCatalogueService(ModuleRepository moduleRepository,
                                  PermissionRepository permissionRepository,
                                  RoleRepository roleRepository) {
        this.moduleRepository = moduleRepository;
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
    }

    /** Every module, grouped by sidebar section, in menu order. */
    @Transactional(readOnly = true)
    public PermissionCatalogueResponse catalogue() {
        Map<String, List<String>> actionsByModule = new LinkedHashMap<>();

        for (Permission permission : permissionRepository.findAll()) {
            actionsByModule
                    .computeIfAbsent(permission.getResource(), key -> new ArrayList<>())
                    .add(permission.getAction());
        }

        // LinkedHashMap so groups come out in the order their first module appears,
        // which — since modules are ordered by position — is menu order.
        Map<String, PermissionCatalogueResponse.Group> groups = new LinkedHashMap<>();

        for (Module module : moduleRepository.findAllByOrderByPositionAsc()) {
            List<String> actions = actionsByModule.getOrDefault(module.getKey(), List.of())
                    .stream()
                    .sorted(Comparator.comparingInt(action -> {
                        int index = ACTION_ORDER.indexOf(action);
                        // Anything unlisted sorts to the end rather than to the
                        // front, so a new action added to the database appears
                        // without quietly displacing read.
                        return index < 0 ? Integer.MAX_VALUE : index;
                    }))
                    .toList();

            PermissionCatalogueResponse.Module entry = new PermissionCatalogueResponse.Module(
                    module.getKey(),
                    module.getLabel(),
                    module.getDescription(),
                    module.isAdminOnly(),
                    actions
            );

            PermissionCatalogueResponse.Group group = groups.computeIfAbsent(
                    module.getGroupKey(),
                    key -> new PermissionCatalogueResponse.Group(key, module.getGroupLabel(), new ArrayList<>())
            );

            group.modules().add(entry);
        }

        return new PermissionCatalogueResponse(List.copyOf(groups.values()));
    }

    /** Every role, with what it grants on its own. */
    @Transactional(readOnly = true)
    public List<RoleResponse> roles() {
        return roleRepository.findAll().stream()
                .map(role -> new RoleResponse(
                        role.getName(),
                        role.getDescription(),
                        role.getPermissions().stream()
                                .map(Permission::toAuthority)
                                .sorted()
                                .toList()
                ))
                // Most permissions first, so Administrator leads. Alphabetically
                // it would too, which is luck rather than a reason.
                .sorted(Comparator.comparingInt((RoleResponse r) -> r.permissions().size()).reversed())
                .toList();
    }
}
