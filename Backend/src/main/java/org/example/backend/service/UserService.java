package org.example.backend.service;

import org.example.backend.dto.CreateUserRequest;
import org.example.backend.dto.MessageResponse;
import org.example.backend.dto.PageResponse;
import org.example.backend.dto.UpdateUserRequest;
import org.example.backend.dto.UserResponse;
import org.example.backend.dto.UserSummaryResponse;
import org.example.backend.entity.AuthToken;
import org.example.backend.entity.Role;
import org.example.backend.entity.Roles;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.AuthTokenRepository;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
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
            "id", "firstName", "lastName", "email", "role", "status", "mfaEnabled", "createdAt"
    );

    /**
     * The most rows one request may ask for.
     *
     * <p>The table offers 5, 10 and 15. This cap sits well above that on purpose:
     * it is not there to mirror the UI, it is there so a handwritten
     * {@code ?size=100000} cannot ask the server to build one enormous response.
     */
    private static final int MAX_PAGE_SIZE = 100;


    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final PermissionService permissionService;
    private final AccessService accessService;
    private final AccountLinkService accountLinkService;
    private final AuthTokenRepository authTokenRepository;
    private final AccountGuard accountGuard;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       UserPermissionRepository userPermissionRepository,
                       PermissionService permissionService,
                       AccessService accessService,
                       AccountLinkService accountLinkService,
                       AuthTokenRepository authTokenRepository,
                       AccountGuard accountGuard) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.permissionService = permissionService;
        this.accessService = accessService;
        this.accountLinkService = accountLinkService;
        this.authTokenRepository = authTokenRepository;
        this.accountGuard = accountGuard;
    }

    /**
     * One page of accounts, filtered and sorted in the database.
     *
     * @param search     matched against first name, last name and email
     * @param role       exact role name, or null for any
     * @param status     exact status, or null for any
     * @param mfaEnabled two-factor on or off, or null for either
     * @param sort       {@code field,direction} — see {@link #SORTABLE}
     * @param actingUserId left out of the results: your own account is managed
     *                     from your settings, not from the list of other people.
     *                     Unless they are the Owner, the Owner is left out too —
     *                     nobody sees a role above their own (decided 2026-10-19).
     */
    @Transactional(readOnly = true)
    public PageResponse<UserSummaryResponse> list(String search,
                                                  String role,
                                                  UserStatus status,
                                                  Boolean mfaEnabled,
                                                  int page,
                                                  int size,
                                                  String sort,
                                                  Long actingUserId) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                parseSort(sort)
        );

        Specification<User> visible = UserSpecifications.matching(search, role, status, mfaEnabled)
                .and((root, query, builder) -> builder.notEqual(root.get("id"), actingUserId));

        if (!accountGuard.isOwner(actingUserId)) {
            visible = visible.and((root, query, builder) ->
                    builder.notEqual(root.get("role").get("name"), Roles.OWNER));
        }

        Page<User> found = userRepository.findAll(visible, pageable);

        return PageResponse.from(found.map(UserSummaryResponse::from));
    }

    /**
     * One account in full, permissions included.
     *
     * <p>The Owner's account is not found for anybody but the Owner — the same
     * answer as an id that does not exist, so it does not even confirm there is
     * one to look at.
     */
    @Transactional(readOnly = true)
    public UserResponse get(Long id, Long actingUserId) {
        User user = userRepository.findWithRoleById(id)
                .filter(u -> !Roles.OWNER.equals(u.getRole().getName())
                        || u.getId().equals(actingUserId)
                        || accountGuard.isOwner(actingUserId))
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /**
     * Inviting somebody, with a role chosen by the inviter.
     *
     * <p>The account starts PENDING_VERIFICATION, with a password nobody knows,
     * and an invitation email goes out. The person chooses their own password from
     * that link, which makes the account ACTIVE; their first sign-in then lands
     * them in two-factor enrolment, which is mandatory.
     */
    @Transactional
    public UserResponse create(CreateUserRequest request, Long actingUserId) {
        String email = normaliseEmail(request.email());

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            throw ApiException.conflict(existing.get().getStatus() == UserStatus.PENDING_VERIFICATION
                    ? "This person was already invited. Open their account to resend the invitation."
                    : "An account with this email already exists.");
        }

        String roleName = request.role().trim().toUpperCase();

        if (Roles.OWNER.equals(roleName)) {
            throw ApiException.badRequest("There is only one Owner. Invite them as an administrator or a member.");
        }

        // Only the Owner makes administrators — decided 2026-10-19. Otherwise any
        // admin could invite more admins, whom no other admin could then remove.
        if (Roles.ADMIN.equals(roleName)) {
            accountGuard.requireOwner(actingUserId, "Only the Owner can invite administrators.");
        }

        Role role = roleRepository.findByNameWithPermissions(roleName)
                .orElseThrow(() -> ApiException.badRequest("There is no role called " + request.role() + "."));

        User user = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .phone(blankToNull(request.phone()))
                .passwordHash(accountLinkService.unusablePasswordHash())
                .role(role)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();

        userRepository.save(user);
        accountLinkService.sendInvitation(user, actingUserId);
        log.info("User id={} invited account id={} with role {}", actingUserId, user.getId(), roleName);

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    /** Editing name, email and phone. Nothing here touches what they may do. */
    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request, Long actingUserId) {
        User user = userRepository.findWithRoleById(id)
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        // Changing somebody's email is how a reset link for their account lands
        // in another inbox — so it follows the same rule as everything else.
        accountGuard.requireCanManage(user, actingUserId);

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
     *   <li><b>Not a working administrator.</b> Administrators cannot switch
     *       each other off, which also means the last one can never be lost from
     *       here. Removing an administrator is a database change, on purpose.</li>
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

        // An invited person becomes active by choosing a password from their
        // email. Switching them on here would make an account nobody can sign in
        // to, because nobody knows its password.
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION && status == UserStatus.ACTIVE) {
            throw ApiException.badRequest(
                    "This person hasn't accepted their invitation yet. Resend it instead.");
        }

        // Only the Owner switches an administrator off or back on, or cancels an
        // administrator's invitation. Nobody switches the Owner off.
        accountGuard.requireCanManage(user, actingUserId);

        user.setStatus(status);

        // Switching an account off ends its sessions rather than waiting for the
        // token to expire. Switching one back on does not touch the cut-off:
        // there is nothing to revoke, and moving it would be pointless churn.
        if (status != UserStatus.ACTIVE) {
            user.setTokensValidFrom(Instant.now());
            // Any invitation or reset link in their inbox dies with the account.
            authTokenRepository.deleteByUserIdAndType(id, AuthToken.Type.INVITE);
            authTokenRepository.deleteByUserIdAndType(id, AuthToken.Type.PASSWORD_RESET);
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
     * Refused on any administrator: one admin cannot demote another, so nobody
     * can quietly make themselves the only one.
     */
    @Transactional
    public UserResponse changeRole(Long id, String roleName, Long actingUserId) {
        if (id.equals(actingUserId)) {
            throw ApiException.forbidden(
                    "You cannot change your own role. Ask another administrator.");
        }

        User user = userRepository.findWithRoleById(id)
                .orElseThrow(() -> ApiException.notFound("No user with id " + id + "."));

        // Demoting an administrator is the Owner's alone; the Owner's own role
        // never changes.
        accountGuard.requireCanManage(user, actingUserId);

        String wanted = roleName.trim().toUpperCase();

        if (Roles.OWNER.equals(wanted)) {
            throw ApiException.badRequest("There is only one Owner, and the role can't be given to anybody else.");
        }

        // And promoting somebody to administrator, for the reason create() gives.
        if (Roles.ADMIN.equals(wanted)) {
            accountGuard.requireOwner(actingUserId, "Only the Owner can make somebody an administrator.");
        }

        if (wanted.equals(user.getRole().getName())) {
            throw ApiException.badRequest("That account already has the " + wanted + " role.");
        }

        Role role = roleRepository.findByNameWithPermissions(wanted)
                .orElseThrow(() -> ApiException.badRequest("There is no role called " + roleName + "."));

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

        // "role" is a relation; the column people mean is its name. "id" is shown
        // as the public ID, so that is what it sorts by.
        String property = switch (field) {
            case "role" -> "role.name";
            case "id" -> "publicId";
            default -> field;
        };
        return Sort.by(direction, property);
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
