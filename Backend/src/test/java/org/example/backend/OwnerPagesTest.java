package org.example.backend;

import org.example.backend.dto.UpdatePermissionsRequest;
import org.example.backend.entity.PublicIds;
import org.example.backend.entity.Roles;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.RoleRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.service.PermissionService;
import org.example.backend.service.UserPermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner pages as permissions (V20, 2026-11-14): the Owner gives them to an
 * administrator, action by action; nobody else can, and a member never gets them.
 */
@SpringBootTest(properties = "app.leadscout.mode=fake")
@Transactional
class OwnerPagesTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApplyTestSupport.database(registry);
    }

    @Autowired UserPermissionService permissions;
    @Autowired PermissionService effective;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;

    private User make(String role) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return users.save(User.builder()
                .publicId(PublicIds.generate())
                .firstName("Test").lastName(role)
                .email(role.toLowerCase() + "-" + tag + "@example.com")
                .passwordHash("!unused")
                .role(roles.findByName(role).orElseThrow())
                .status(UserStatus.ACTIVE)
                .build());
    }

    /** The one Owner: the bootstrap one if there is one, otherwise made here. */
    private User owner() {
        return users.findAll().stream()
                .filter(u -> Roles.OWNER.equals(u.getRole().getName()))
                .findFirst()
                .orElseGet(() -> make(Roles.OWNER));
    }

    private static UpdatePermissionsRequest grant(String... granted) {
        return new UpdatePermissionsRequest(List.of(granted), List.of(), null);
    }

    private Set<String> held(User user) {
        return effective.effectivePermissionsFor(users.findWithRoleById(user.getId()).orElseThrow());
    }

    @Test
    void aNewAdministratorHasNoOwnerPages() {
        Set<String> admin = held(make(Roles.ADMIN));
        assertThat(admin).contains("USER:READ", "CUSTOMER:READ");
        assertThat(admin).noneMatch(p -> p.startsWith("BACKUP:") || p.startsWith("BRAND:")
                || p.startsWith("CHANNEL_SETUP:") || p.startsWith("SYSTEM_HEALTH:") || p.startsWith("ACTION_LOG:"));
    }

    @Test
    void theOwnerHoldsEveryOwnerPage() {
        assertThat(held(owner())).contains("BACKUP:DOWNLOAD", "BRAND:DELETE", "CHANNEL_SETUP:CREATE",
                "SYSTEM_HEALTH:READ", "ACTION_LOG:READ");
    }

    @Test
    void theOwnerCanGiveAnAdministratorSomeOwnerPages() {
        User admin = make(Roles.ADMIN);
        permissions.replaceFor(admin.getId(), grant("BACKUP:READ", "BACKUP:DOWNLOAD", "SYSTEM_HEALTH:READ"), owner().getId());

        Set<String> now = held(admin);
        assertThat(now).contains("BACKUP:READ", "BACKUP:DOWNLOAD", "SYSTEM_HEALTH:READ", "USER:READ");
        assertThat(now).doesNotContain("BACKUP:DELETE", "BRAND:READ");

        // And take them back.
        permissions.replaceFor(admin.getId(), grant(), owner().getId());
        assertThat(held(admin)).doesNotContain("BACKUP:READ");
    }

    @Test
    void anAdministratorCannotGiveOwnerPages() {
        User giver = make(Roles.ADMIN);
        User other = make(Roles.ADMIN);
        permissions.replaceFor(giver.getId(), grant("BACKUP:READ"), owner().getId());

        assertThatThrownBy(() -> permissions.replaceFor(other.getId(), grant("BACKUP:READ"), giver.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only the Owner");
    }

    @Test
    void aMemberNeverGetsOwnerPages() {
        User member = make(Roles.MEMBER);
        assertThatThrownBy(() -> permissions.replaceFor(member.getId(), grant("BACKUP:READ"), owner().getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only administrators");
    }

    @Test
    void nothingIsTakenFromAnAdministratorOrAddedOutsideOwnerPages() {
        User admin = make(Roles.ADMIN);
        assertThatThrownBy(() -> permissions.replaceFor(admin.getId(),
                new UpdatePermissionsRequest(List.of(), List.of("USER:READ"), null), owner().getId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> permissions.replaceFor(admin.getId(), grant("BACKUP:DELETE"), owner().getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("READ");
    }

    @Test
    void theOwnerIsNeverEdited() {
        User someAdmin = make(Roles.ADMIN);
        assertThatThrownBy(() -> permissions.replaceFor(owner().getId(), grant("BACKUP:READ"), someAdmin.getId()))
                .isInstanceOf(ApiException.class);
    }
}
