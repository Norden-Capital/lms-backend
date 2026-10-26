package org.example.backend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.example.backend.dto.*;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.MfaRecoveryCode;
import org.example.backend.entity.User;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.MfaRecoveryCodeRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Login, and the full two-factor lifecycle.
 *
 * <p><b>Why login is two calls.</b> The previous implementation exposed 2FA setup
 * and verification as anonymous endpoints, and the verify step handed out a real
 * token without ever checking a password — so knowing an email address was enough
 * to take over any account. Here, the second factor is only reachable with an
 * {@code mfa_pending} token, which is only issued after a correct password. There
 * is no path to an access token that skips the password.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Wrong passwords in a row before the account locks. */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** Identical for unknown emails and wrong passwords, so the API can't be used to discover who has an account. */
    private static final String GENERIC_LOGIN_FAILURE = "Wrong email or password.";


    private final UserRepository userRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TwoFactorAuthService twoFactorAuthService;
    private final EncryptionService encryptionService;
    private final PermissionService permissionService;
    private final AccountGuard accountGuard;
    private final ActionLogService actionLog;
    private final AccessService accessService;

    public AuthService(UserRepository userRepository,
                       MfaRecoveryCodeRepository recoveryCodeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TwoFactorAuthService twoFactorAuthService,
                       EncryptionService encryptionService,
                       PermissionService permissionService,
                       AccountGuard accountGuard,
                       ActionLogService actionLog,
                       AccessService accessService) {
        this.userRepository = userRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.twoFactorAuthService = twoFactorAuthService;
        this.encryptionService = encryptionService;
        this.permissionService = permissionService;
        this.accountGuard = accountGuard;
        this.actionLog = actionLog;
        this.accessService = accessService;
    }

    // There is no self-registration. Accounts are created by invitation only —
    // see UserService.create and AccountLinkService.

    // ── Login, step one: password ───────────────────────────────────────────

    /**
     * <p>noRollbackFor is what makes the lockout work. A wrong password throws,
     * and a throw rolls the transaction back — taking the failed-attempt count
     * with it. Until 2026-10-19 that meant the count never rose and nobody was
     * ever locked out; the action log's lines would have vanished the same way.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        Optional<User> found = userRepository.findByEmailWithRolePermissions(email);

        if (found.isEmpty()) {
            // Hash anyway so a missing account doesn't answer measurably faster
            // than a wrong password.
            passwordEncoder.encode(request.password());
            actionLog.record(ActionType.SIGN_IN_FAILED, (User) null, null,
                    "No account for " + email);
            throw ApiException.unauthorized(GENERIC_LOGIN_FAILURE);
        }

        User user = found.get();

        if (user.isLocked()) {
            actionLog.record(ActionType.SIGN_IN_FAILED, (User) null, user, "Tried while locked");
            throw ApiException.locked(
                    "Too many failed attempts. This account is locked until " + user.getLockedUntil() + ".");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailedAttempt(user, "Wrong password");
            throw ApiException.unauthorized(GENERIC_LOGIN_FAILURE);
        }

        // Password is right — check the account is allowed to log in at all.
        if (!user.getStatus().canLogIn()) {
            throw ApiException.forbidden("This account is " + user.getStatus().name().toLowerCase().replace('_', ' ') + ".");
        }

        if (user.isMfaEnabled()) {
            // Nothing useful is returned here: no access token, no user details.
            // The mfaToken only opens the second step.
            return LoginResponse.mfaRequired(jwtService.generateMfaPendingToken(user));
        }

        // Password was right, but there is no second factor on this account.
        //
        // Two-factor is mandatory for every role, so this is not a normal login —
        // it is a login that cannot go anywhere until enrolment is done. The token
        // handed back carries no permissions, and SecurityConfig lets it reach
        // only the enrolment endpoints.
        //
        // The gate lives here, in the token, rather than in the frontend, because
        // the frontend is not what the API asks. Hiding buttons stops nobody with
        // a terminal.
        user.setLastLoginAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        actionLog.record(ActionType.SIGN_IN, user, null, "Password — two-factor set-up still to do");

        // No permissions passed to UserResponse: what this person can currently do
        // is nothing, and saying otherwise would have the frontend render an app
        // whose every button returns 403.
        return LoginResponse.enrolmentRequired(
                jwtService.generateEnrolmentPendingToken(user),
                jwtService.getAccessTokenTtl().toSeconds(),
                UserResponse.from(user, Set.of()));
    }

    // ── Login, step two: the second factor ──────────────────────────────────

    /** noRollbackFor for the same reason as {@link #login}: the lockout must save. */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse loginTwoFactor(LoginTwoFactorRequest request) {
        Claims claims;
        try {
            claims = jwtService.parseAs(request.mfaToken(), JwtService.TYPE_MFA_PENDING);
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("Your sign-in timed out. Please sign in again.");
        }

        User user = userRepository.findById(claims.get(JwtService.CLAIM_USER_ID, Number.class).longValue())
                .orElseThrow(() -> ApiException.unauthorized("Please sign in again."));

        if (user.isLocked()) {
            actionLog.record(ActionType.SIGN_IN_FAILED, (User) null, user, "Tried while locked");
            throw ApiException.locked(
                    "Too many failed attempts. This account is locked until " + user.getLockedUntil() + ".");
        }

        if (request.hasRecoveryCode()) {
            if (!redeemRecoveryCode(user, request.recoveryCode())) {
                registerFailedAttempt(user, "Wrong recovery code");
                throw ApiException.unauthorized("Wrong code.");
            }
            actionLog.record(ActionType.RECOVERY_CODE_USED, user, null, null);
            return completeLogin(user, "Recovery code");
        }

        if (!request.hasCode()) {
            throw ApiException.badRequest("Enter the 6-digit code or a recovery code.");
        }

        // Counts towards the same lockout as passwords — a 6-digit code is
        // otherwise well within reach of brute force.
        OptionalLong step = twoFactorAuthService.matchingStep(decryptedSecret(user), request.code());
        if (step.isEmpty()) {
            registerFailedAttempt(user, "Wrong two-factor code");
            throw ApiException.unauthorized("Wrong code.");
        }
        // Single use: a code from a step already accepted is refused, so a code
        // somebody saw cannot be replayed. Counted as a failure — a replay is
        // not an innocent mistake.
        if (user.getMfaLastUsedStep() != null && step.getAsLong() <= user.getMfaLastUsedStep()) {
            registerFailedAttempt(user, "Two-factor code used twice");
            throw ApiException.unauthorized("This code was already used. Wait for a new one.");
        }
        user.setMfaLastUsedStep(step.getAsLong());

        return completeLogin(user, "Two-factor code");
    }

    // ── Two-factor setup ────────────────────────────────────────────────────

    /**
     * Issues a seed and QR for the <em>currently authenticated</em> user.
     *
     * <p>Deliberately takes no email parameter. The old version did, and was public,
     * which is precisely what allowed anyone to reset a stranger's second factor.
     */
    @Transactional
    public MfaSetupResponse startMfaSetup(Long userId) {
        User user = requireUser(userId);

        if (user.isMfaEnabled()) {
            throw ApiException.conflict("Two-factor sign-in is already on.");
        }

        String secret = twoFactorAuthService.generateSecret();
        user.setMfaSecret(encryptionService.encrypt(secret));
        user.setMfaConfirmedAt(null);
        user.setMfaLastUsedStep(null);
        userRepository.save(user);

        // Note what is NOT set here: mfaEnabled stays false until a code is
        // confirmed, so an abandoned setup can never lock anyone out.
        return new MfaSetupResponse(secret, twoFactorAuthService.generateQrCodeDataUrl(secret, user.getEmail()));
    }

    @Transactional
    public MfaConfirmResponse confirmMfaSetup(Long userId, MfaConfirmRequest request) {
        User user = requireUser(userId);

        if (user.getMfaSecret() == null) {
            throw ApiException.badRequest("Start two-factor setup before confirming a code.");
        }
        if (user.isMfaEnabled()) {
            throw ApiException.conflict("Two-factor sign-in is already on.");
        }
        OptionalLong step = twoFactorAuthService.matchingStep(decryptedSecret(user), request.code());
        if (step.isEmpty()) {
            throw ApiException.badRequest("Wrong code. Check your app and try again.");
        }

        user.setMfaEnabled(true);
        user.setMfaConfirmedAt(Instant.now());
        // The code that switched it on is spent: it cannot also sign in.
        user.setMfaLastUsedStep(step.getAsLong());
        userRepository.save(user);
        actionLog.record(ActionType.TWO_FACTOR_ENABLED, user, null, null);

        List<String> recoveryCodes = issueRecoveryCodes(user);
        log.info("Enabled 2FA for user id={}", user.getId());

        // The moment the gate opens. Up to this line the caller has been holding an
        // enrolment-pending token, authorised for nothing but this flow. Handing
        // back a real access token here is what turns "you may only enrol" into
        // "you may work" — without it they finish enrolling and stay locked out,
        // because the token they already hold cannot be changed after issue.
        Set<String> permissions = permissionService.effectivePermissionsFor(user);

        return MfaConfirmResponse.enrolled(
                "Two-factor sign-in is on. Save these codes now. You need them if you lose your phone.",
                recoveryCodes,
                jwtService.generateAccessToken(user, permissions),
                jwtService.getAccessTokenTtl().toSeconds());
    }

    /**
     * Refuses. Always.
     *
     * <p>Two-factor is mandatory for every role, so there is no such thing as
     * legitimately switching it off for yourself. The endpoint is kept rather than
     * deleted so that anything still calling it gets a clear answer instead of a
     * 404 that reads like a bug — and so this decision stays visible in the code
     * rather than only in a document nobody opens.
     *
     * <p>The real way back for someone who has lost their phone <em>and</em> their
     * recovery codes is {@link #resetMfaFor}, which another administrator performs
     * on their behalf. Deliberately not something you can do to yourself: an admin
     * able to reset their own second factor would be able to disable it, which is
     * exactly what this refusal exists to prevent.
     */
    @Transactional
    public MessageResponse disableMfa(Long userId, PasswordConfirmRequest request) {
        log.warn("Rejected an attempt to disable 2FA for user id={}", userId);

        throw ApiException.forbidden(
                "You cannot turn off two-factor sign-in. Lost your phone? Ask an administrator.");
    }

    /**
     * Clears another user's second factor so they can enrol again on a new device.
     *
     * <p>This is the only way back for someone who has lost both their phone and
     * their recovery codes. Two-factor is mandatory and cannot be switched off by
     * the person who holds it, so without an endpoint like this a single broken
     * phone makes an account permanently unreachable.
     *
     * <p>Refuses to act on the caller's own account on purpose. Resetting
     * yourself is self-disable wearing a different hat, and self-disable is
     * precisely what the mandatory-2FA policy forbids — an admin could otherwise
     * strip their own second factor at will.
     *
     * <p>Note what this hands over: until the user enrols again, their password
     * alone gets into the account. That is why it takes USER:UPDATE and why the
     * log line below is deliberately loud.
     */
    @Transactional
    public MessageResponse resetMfaFor(Long targetUserId, Long actingUserId) {
        if (targetUserId.equals(actingUserId)) {
            throw ApiException.forbidden(
                    "You cannot reset your own two-factor sign-in. Ask another administrator.");
        }

        User target = userRepository.findWithRoleById(targetUserId)
                .orElseThrow(() -> ApiException.notFound("User not found."));

        // Clearing somebody's second factor is the last step of taking over their
        // account, so it follows the same rule: only the Owner does it to an
        // administrator, and nobody does it to the Owner.
        accountGuard.requireCanManage(target, actingUserId);

        if (!target.isMfaEnabled() && target.getMfaSecret() == null) {
            throw ApiException.conflict("This account has no two-factor sign-in set up.");
        }

        target.setMfaEnabled(false);
        target.setMfaSecret(null);
        target.setMfaConfirmedAt(null);
        target.setMfaLastUsedStep(null);
        // Signs them out everywhere — added 2026-10-26. If the phone was lost or
        // stolen, a session already open on another device must not live on.
        target.setTokensValidFrom(Instant.now());
        userRepository.save(target);
        recoveryCodeRepository.deleteByUserId(target.getId());
        // Now, not when the cached access runs out.
        accessService.invalidate(target.getId());
        actionLog.record(ActionType.TWO_FACTOR_RESET, actingUserId, target, null);

        log.warn("2FA RESET: user id={} ({}) had their second factor cleared by admin id={}",
                target.getId(), target.getEmail(), actingUserId);

        return new MessageResponse(
                target.fullName() + " can now sign in with just a password, until they set up two-factor again.");
    }

    /** Replaces every existing code, so a leaked list stops working immediately. */
    @Transactional
    public MfaConfirmResponse regenerateRecoveryCodes(Long userId, PasswordConfirmRequest request) {
        User user = requireUser(userId);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Wrong password.");
        }
        if (!user.isMfaEnabled()) {
            throw ApiException.conflict("Two-factor sign-in is not on.");
        }

        actionLog.record(ActionType.RECOVERY_CODES_REGENERATED, user, null, null);

        // No token here: this caller already holds a working one.
        return MfaConfirmResponse.codesOnly(
                "New codes made. Your old codes no longer work.",
                issueRecoveryCodes(user));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private LoginResponse completeLogin(User user, String how) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        actionLog.record(ActionType.SIGN_IN, user, null, how);

        Set<String> permissions = permissionService.effectivePermissionsFor(user);

        return LoginResponse.success(
                jwtService.generateAccessToken(user, permissions),
                jwtService.getAccessTokenTtl().toSeconds(),
                UserResponse.from(user, permissions));
    }

    private void registerFailedAttempt(User user, String why) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        actionLog.record(ActionType.SIGN_IN_FAILED, (User) null, user, why);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(LOCK_DURATION));
            // Starts again from zero once the lock runs out — five more tries,
            // not one.
            user.setFailedLoginAttempts(0);
            log.warn("Locked user id={} after {} failed attempts", user.getId(), attempts);
            actionLog.record(ActionType.ACCOUNT_LOCKED, (User) null, user,
                    "Locked for " + LOCK_DURATION.toMinutes() + " minutes after "
                            + attempts + " failed attempts");
        }
        userRepository.save(user);
    }

    private boolean redeemRecoveryCode(User user, String submitted) {
        String candidate = submitted.trim().toUpperCase();

        for (MfaRecoveryCode stored : recoveryCodeRepository.findByUserIdAndUsedAtIsNull(user.getId())) {
            if (passwordEncoder.matches(candidate, stored.getCodeHash())) {
                // Stamping used_at is what makes it single-use. The row is kept
                // rather than deleted so the audit trail survives.
                stored.setUsedAt(Instant.now());
                recoveryCodeRepository.save(stored);
                log.info("User id={} logged in with a recovery code", user.getId());
                return true;
            }
        }
        return false;
    }

    private List<String> issueRecoveryCodes(User user) {
        recoveryCodeRepository.deleteByUserId(user.getId());

        List<String> plaintext = twoFactorAuthService.generateRecoveryCodes();
        plaintext.forEach(code -> recoveryCodeRepository.save(MfaRecoveryCode.builder()
                .user(user)
                .codeHash(passwordEncoder.encode(code))   // hashed, exactly like a password
                .build()));

        return plaintext;
    }

    private String decryptedSecret(User user) {
        if (user.getMfaSecret() == null) {
            throw ApiException.badRequest("This account has no two-factor sign-in set up.");
        }
        return encryptionService.decrypt(user.getMfaSecret());
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found."));
    }

    private static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
