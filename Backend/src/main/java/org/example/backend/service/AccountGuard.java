package org.example.backend.service;

import org.example.backend.entity.Roles;
import org.example.backend.entity.User;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Who may act on whose account — decided 2026-10-19:
 *
 * <p><b>Administrators manage members. Only the Owner manages administrators.
 * Nobody but the Owner touches the Owner's account.</b>
 *
 * <p>One place, called by every action on somebody else's account — editing
 * details, reset links, two-factor resets, role and status changes — so the rule
 * cannot be true for one action and forgotten for another. Before it, an
 * administrator could change another administrator's email, send a reset link
 * to the new address, clear their two-factor and walk into the account.
 *
 * <p>Acting on your own account is not decided here: each action has its own
 * rule for that (most refuse it, and point at your settings instead).
 */
@Component
public class AccountGuard {

    private final UserRepository userRepository;

    public AccountGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Whether the person asking is the Owner. */
    public boolean isOwner(Long userId) {
        return userRepository.findWithRoleById(userId)
                .map(u -> Roles.OWNER.equals(u.getRole().getName()))
                .orElse(false);
    }

    /**
     * Refuses unless the person asking may manage this account.
     *
     * @param target       the account being acted on, with its role loaded
     * @param actingUserId whoever is asking
     */
    public void requireCanManage(User target, Long actingUserId) {
        if (target.getId().equals(actingUserId)) {
            return;
        }

        String role = target.getRole().getName();

        if (Roles.OWNER.equals(role)) {
            throw ApiException.forbidden("Only the Owner can change the Owner's account.");
        }
        if (Roles.ADMIN.equals(role) && !isOwner(actingUserId)) {
            throw ApiException.forbidden("Only the Owner can manage administrators.");
        }
    }

    /** Refuses unless the person asking is the Owner — for giving somebody the administrator role. */
    public void requireOwner(Long actingUserId, String message) {
        if (!isOwner(actingUserId)) {
            throw ApiException.forbidden(message);
        }
    }
}
