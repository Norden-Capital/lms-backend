package org.example.backend.service;

import org.example.backend.dto.CreateUserRequest;
import org.example.backend.dto.MessageResponse;
import org.example.backend.dto.PageResponse;
import org.example.backend.dto.UpdateUserRequest;
import org.example.backend.dto.UserResponse;
import org.example.backend.dto.UserSummaryResponse;
import org.example.backend.entity.Role;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.RoleRepository;
import org.example.backend.repository.UserPermissionRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.repository.UserSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * Managing other people's accounts.
 *
 * <p>Separate from {@link AuthService}, which is about a person acting on their
 * own account — signing in, enrolling in two-factor, proving who they are.
 * Everything here is somebody acting on <em>somebody else</em>, which is why most
 * methods take the acting user's id: several of the rules are about who is doing
 * the asking, not about what is being asked.
 *
 * <p>There is no delete. Accounts are switched off and stay in the table, so that
 * history survives and the email address stays taken — an account that can be
 * turned back on is a different thing from one re-created as a stranger.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * Columns a caller may sort by.
     *
     * <p>A whitelist rather than passing the parameter straight to Spring Data.
     * An unknown property there is not a 400 — it is an exception from deep inside
     * the query builder, which surfaces as a 500 on a request that was merely
     * misspelt.
     */
    private static final Set<String> SORTABLE = Set.of(
            "id", "firstName", "lastName", "email", "status", "mfaEnabled", "createdAt"
    );

    /**
     * The most rows one request may ask for.
     *
     * <p>The table offers 5, 10 and 15. This cap sits well above that on purpose:
     * it is not there to mirror the UI, it is there so a handwritten
     * {@code ?size=100000} cannot ask the server to build one enormous response.
     */
    private static final int MAX_PAGE_SIZE = 100;

    /** The role that must always have at least one working account. */
    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final PermissionService permissionService;
    private final AccessService accessService;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       UserPermissionRepository userPermissionRepository,
                       PermissionService permissionService,
                       AccessService accessService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.permissionService = permissionService;
        this.accessService = accessService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * One page of accounts, filtered and sorted in the database.
     *
     * @param search     matched against first name, last name and email
     * @param role       exact role name, or null for any
     * @param status     exact status, or null for any
     * @param mfaEnabled two-factor on or off, or null for either
     * @param sort       {@code field,direction} — see {@link #SORTABLE}
     */
    @Transactional(readOnly = true)
    public PageResponse<UserSummaryResponse> list(String search,
                                                  String role,
                                                  UserStatus status,
                                                  Boolean mfaEnabled,
                                                  int page,
                                                  int size,
                                                  String sort) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                parseSort(sort)
        );

        Page<User> found = userRepository.findAll(
                UserSpecifications.matching(search, role, status, mfaEnabled),
                pageable
        );

        return PageResponse.from(found.map(UserSummaryResponse::from));
    }

    /** One account in full, permissions included. */
    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        User user = userRepository.findWithRoleById(id)
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /**
     * Creating somebody else's account, with a role chosen by the creator.
     *
     * <p>The new account starts ACTIVE and without two-factor. That second part is
     * not a gap: two-factor is mandatory here, so their first sign-in lands them
     * in enrolment and they can do nothing else until they finish it.
     */
    @Transactional
    public UserResponse create(CreateUserRequest request, Long actingUserId) {
        String email = normaliseEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email already exists.");
        }

        String roleName = request.role().trim().toUpperCase();

        Role role = roleRepository.findByNameWithPermissions(roleName)
                .orElseThrow(() -> ApiException.badRequest("There is no role called " + request.role() + "."));

        User user = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .phone(blankToNull(request.phone()))
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(role)
                // ACTIVE rather than PENDING_VERIFICATION: nothing sends email, so
                // an account waiting on a link nobody receives would be an account
                // that can never be used.
                .status(UserStatus.ACTIVE)
                .build();

        userRepository.save(user);
        log.info("User id={} created account id={} with role {}", actingUserId, user.getId(), roleName);

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /** Editing name, email and phone. Nothing here touches what they may do. */
    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request, Long actingUserId) {
        User user = userRepository.findWithRoleById(id)
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        String email = normaliseEmail(request.email());

        // Against everyone except this account, which of course already holds the
        // address it is being saved with.
        if (userRepository.existsByEmailAndIdNot(email, id)) {
            throw ApiException.conflict("Another account already uses this email.");
        }

        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(request.phone()));

        userRepository.save(user);
        log.info("User id={} updated account id={}", actingUserId, id);

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /**
     * Switching an account off, or back on.
     *
     * <p>Two refusals, both about locking somebody out for good:
     *
     * <ul>
     *   <li><b>Not yourself.</b> Suspending your own account signs you out of the
     *       only screen that could undo it.</li>
     *   <li><b>Not the last working administrator.</b> Nothing else in this system
     *       can create one, so an organisation that switches off its last admin
     *       has no way back in short of editing the database by hand.</li>
     * </ul>
     */
    @Transactional
    public UserResponse changeStatus(Long id, UserStatus status, Long actingUserId) {
        if (id.equals(actingUserId)) {
            throw ApiException.forbidden(
                    "You cannot change your own account's status. Ask another administrator.");
        }

        User user = userRepository.findWithRoleById(id)
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        if (user.getStatus() == status) {
            throw ApiException.badRequest(
                    "That account is already " + status.name().toLowerCase() + ".");
        }

        boolean losingAnActiveAdmin = user.getStatus() == UserStatus.ACTIVE
                && status != UserStatus.ACTIVE
                && ADMIN_ROLE.equals(user.getRole().getName());

        if (losingAnActiveAdmin
                && userRepository.countByRoleNameAndStatus(ADMIN_ROLE, UserStatus.ACTIVE) <= 1) {
            throw ApiException.conflict(
                    "This is the last active administrator. Promote somebody else first.");
        }

        user.setStatus(status);

        // Switching an account off ends its sessions rather than waiting for the
        // token to expire. Switching one back on does not touch the cut-off:
        // there is nothing to revoke, and moving it would be pointless churn.
        if (status != UserStatus.ACTIVE) {
            user.setTokensValidFrom(Instant.now());
        }

        userRepository.save(user);
        accessService.invalidate(id);
        log.info("User id={} set account id={} to {}", actingUserId, id, status);

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /**
     * Moving somebody between roles.
     *
     * <p><b>This clears their permission exceptions, and that is the important
     * part.</b> An exception is stored as a difference from a role — "this Member
     * also gets exports" — so the moment the role underneath changes, the stored
     * row is describing something that no longer exists.
     *
     * <p>Leaving them would be worse than untidy. A DENY written against an
     * administrator ("everything except deleting users") survives a demotion as a
     * meaningless row, and survives a later <em>promotion</em> as a live one —
     * quietly taking the permission away from an administrator who appears, on
     * every screen that shows roles, to have it. That is exactly the kind of hole
     * nobody thinks to look for. Re-granting deliberately is cheap; discovering
     * a silent exception a year later is not.
     *
     * <p>Refused on your own account. Promoting yourself needs no explanation;
     * demoting yourself takes away the permission you would need to undo it.
     * Refused on the last active administrator for the same reason
     * {@link #changeStatus} refuses it.
     */
    @Transactional
    public UserResponse changeRole(Long id, String roleName, Long actingUserId) {
        if (id.equals(actingUserId)) {
            throw ApiException.forbidden(
                    "You cannot change your own role. Ask another administrator.");
        }

        User user = userRepository.findWithRoleById(id)
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        String wanted = roleName.trim().toUpperCase();

        if (wanted.equals(user.getRole().getName())) {
            throw ApiException.badRequest("That account already has the " + wanted + " role.");
        }

        Role role = roleRepository.findByNameWithPermissions(wanted)
                .orElseThrow(() -> ApiException.badRequest("There is no role called " + roleName + "."));

        boolean losingAnActiveAdmin = ADMIN_ROLE.equals(user.getRole().getName())
                && user.getStatus() == UserStatus.ACTIVE;

        if (losingAnActiveAdmin
                && userRepository.countByRoleNameAndStatus(ADMIN_ROLE, UserStatus.ACTIVE) <= 1) {
            throw ApiException.conflict(
                    "This is the last active administrator. Promote somebody else first.");
        }

        long cleared = userPermissionRepository.deleteByUserId(id);

        user.setRole(role);

        // A role change ends their sessions, and that is not merely tidiness.
        //
        // The role travels in the token as well as in this table. Authorisation
        // reads the table, so a demotion bites at once — but the frontend routes
        // on the claim, because middleware runs before any database is reachable
        // and is what stops a member being shown an administrator's screen for a
        // moment before it is taken away. Leaving a stale claim in circulation
        // means those two disagree, in both directions: a demoted administrator
        // still being offered Management, and a promoted member still refused it.
        //
        // Signing them out is what keeps the claim honest. It is also the normal
        // expectation — being asked to sign in again after your role changes
        // reads as the system taking the change seriously.
        user.setTokensValidFrom(Instant.now());

        userRepository.save(user);
        accessService.invalidate(id);

        log.info("User id={} changed account id={} to role {}, clearing {} permission exception(s)",
                actingUserId, id, wanted, cleared);

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /**
     * An administrator setting somebody else's password.
     *
     * <p>Also clears any lockout. Somebody who has been asked for a new password
     * has usually been trying the old one and failing, so leaving them locked out
     * would mean handing over a password that does not work yet.
     *
     * <p>Refused on your own account. Changing your own password has to prove you
     * know the current one, and this endpoint deliberately cannot ask — so
     * allowing it here would turn "administrator" into "can rewrite my own
     * password without knowing it", which is a different and much weaker thing.
     *
     * <p>⚠️ <b>Their existing sessions keep working.</b> Tokens are self-contained
     * and nothing revokes them, so a stolen session survives the password change
     * that was meant to end it. That is the token-revocation gap on the bug list,
     * and it is the reason this cannot yet be called a way to lock an intruder
     * out.
     *
     * <p>⚠️ Nothing forces a change at next sign-in. There is no
     * {@code must_change_password} column, so a temporary password is simply the
     * password until somebody says otherwise.
     */
    @Transactional
    public MessageResponse setPassword(Long id, String rawPassword, Long actingUserId) {
        if (id.equals(actingUserId)) {
            throw ApiException.forbidden(
                    "Use your own account settings to change your password — that asks for your "
                            + "current one, and this does not.");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        user.setPasswordHash(passwordEncoder.encode(rawPassword));

        // The failed attempts belonged to the old password. Carrying them over
        // would lock somebody out with credentials that have just been fixed.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);

        // Every session this account has, anywhere, stops on its next request.
        // Without this a reset changed the password while leaving whoever was
        // already inside exactly where they were — which is the opposite of what
        // somebody resetting a compromised password is trying to do.
        user.setTokensValidFrom(Instant.now());

        userRepository.save(user);
        accessService.invalidate(id);
        log.info("User id={} set a new password for account id={}", actingUserId, id);

        return new MessageResponse(
                "Password updated and existing sessions ended. Hand it over yourself — "
                        + "nothing was emailed.");
    }

    /**
     * {@code field,direction} into a {@link Sort}, refusing anything unlisted.
     *
     * <p>Falls back to newest first, which is the order somebody scanning a list of
     * accounts most often wants — the ones just created are the ones being worked
     * on.
     */
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }

        String[] parts = sort.split(",");
        String field = parts[0].trim();

        if (!SORTABLE.contains(field)) {
            throw ApiException.badRequest(
                    "Cannot sort by " + field + ". Allowed: " + String.join(", ", SORTABLE) + ".");
        }

        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        return Sort.by(direction, field);
    }

    /** Lowercase and trimmed, so Jane@x.com and jane@x.com are one account. */
    private String normaliseEmail(String email) {
        return email.trim().toLowerCase();
    }

    /** An empty phone field means no phone, not an empty string in the column. */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
