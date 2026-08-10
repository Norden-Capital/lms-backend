package org.example.backend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.example.backend.dto.*;
import org.example.backend.entity.MfaRecoveryCode;
import org.example.backend.entity.Role;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.MfaRecoveryCodeRepository;
import org.example.backend.repository.RoleRepository;
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
import java.util.Set;

/**
 * Registration, login, and the full two-factor lifecycle.
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
    private static final String GENERIC_LOGIN_FAILURE = "Invalid email or password.";

    private static final String DEFAULT_ROLE = "MEMBER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TwoFactorAuthService twoFactorAuthService;
    private final EncryptionService encryptionService;
    private final PermissionService permissionService;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       MfaRecoveryCodeRepository recoveryCodeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TwoFactorAuthService twoFactorAuthService,
                       EncryptionService encryptionService,
                       PermissionService permissionService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.twoFactorAuthService = twoFactorAuthService;
        this.encryptionService = encryptionService;
        this.permissionService = permissionService;
    }

    // ── Registration ────────────────────────────────────────────────────────

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email already exists.");
        }

        Role role = roleRepository.findByName(DEFAULT_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Role " + DEFAULT_ROLE + " is missing — check the V2 seed migration ran"));

        User user = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .phone(blankToNull(request.phone()))
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(role)
                // Email verification isn't wired up yet (no mail server configured),
                // so accounts start usable. Once SMTP exists this becomes
                // PENDING_VERIFICATION and the emailed link flips it to ACTIVE.
                .status(UserStatus.ACTIVE)
                .build();

        userRepository.save(user);
        log.info("Registered new user id={} with role {}", user.getId(), role.getName());

        return UserResponse.from(user, permissionService.effectivePermissionsFor(user));
    }

    // ── Login, step one: password ───────────────────────────────────────────

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        Optional<User> found = userRepository.findByEmailWithRolePermissions(email);

        if (found.isEmpty()) {
            // Hash anyway so a missing account doesn't answer measurably faster
            // than a wrong password.
            passwordEncoder.encode(request.password());
            throw ApiException.unauthorized(GENERIC_LOGIN_FAILURE);
        }

        User user = found.get();

        if (user.isLocked()) {
            throw ApiException.locked(
                    "Too many failed attempts. This account is locked until " + user.getLockedUntil() + ".");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailedAttempt(user);
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

        return completeLogin(user);
    }

    // ── Login, step two: the second factor ──────────────────────────────────

    @Transactional
    public LoginResponse loginTwoFactor(LoginTwoFactorRequest request) {
        Claims claims;
        try {
            claims = jwtService.parseAs(request.mfaToken(), JwtService.TYPE_MFA_PENDING);
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("This login session has expired. Please sign in again.");
        }

        User user = userRepository.findById(claims.get(JwtService.CLAIM_USER_ID, Number.class).longValue())
                .orElseThrow(() -> ApiException.unauthorized("This login session is no longer valid."));

        if (user.isLocked()) {
            throw ApiException.locked(
                    "Too many failed attempts. This account is locked until " + user.getLockedUntil() + ".");
        }

        boolean verified;
        if (request.hasRecoveryCode()) {
            verified = redeemRecoveryCode(user, request.recoveryCode());
        } else if (request.hasCode()) {
            verified = twoFactorAuthService.isCodeValid(decryptedSecret(user), request.code());
        } else {
            throw ApiException.badRequest("Provide either the 6-digit code or a recovery code.");
        }

        if (!verified) {
            // Counts towards the same lockout as passwords — a 6-digit code is
            // otherwise well within reach of brute force.
            registerFailedAttempt(user);
            throw ApiException.unauthorized("That code is not valid.");
        }

        return completeLogin(user);
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
            throw ApiException.conflict("Two-factor authentication is already enabled. Disable it first to re-enrol.");
        }

        String secret = twoFactorAuthService.generateSecret();
        user.setMfaSecret(encryptionService.encrypt(secret));
        user.setMfaConfirmedAt(null);
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
            throw ApiException.conflict("Two-factor authentication is already enabled.");
        }
        if (!twoFactorAuthService.isCodeValid(decryptedSecret(user), request.code())) {
            throw ApiException.badRequest("That code is not valid. Check your authenticator app and try again.");
        }

        user.setMfaEnabled(true);
        user.setMfaConfirmedAt(Instant.now());
        userRepository.save(user);

        List<String> recoveryCodes = issueRecoveryCodes(user);
        log.info("Enabled 2FA for user id={}", user.getId());

        return new MfaConfirmResponse(
                "Two-factor authentication is now enabled. Save these recovery codes somewhere safe — "
                        + "they are shown once and are the only way back in if you lose your phone.",
                recoveryCodes);
    }

    /** Switching 2FA off re-checks the password: being logged in is not sufficient. */
    @Transactional
    public MessageResponse disableMfa(Long userId, PasswordConfirmRequest request) {
        User user = requireUser(userId);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("That password is not correct.");
        }
        if (!user.isMfaEnabled()) {
            throw ApiException.conflict("Two-factor authentication is not enabled.");
        }

        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaConfirmedAt(null);
        userRepository.save(user);
        recoveryCodeRepository.deleteByUserId(user.getId());

        log.info("Disabled 2FA for user id={}", user.getId());
        return new MessageResponse("Two-factor authentication has been disabled.");
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
                    "You cannot reset your own two-factor authentication. Ask another administrator.");
        }

        User target = requireUser(targetUserId);

        if (!target.isMfaEnabled() && target.getMfaSecret() == null) {
            throw ApiException.conflict("Two-factor authentication is not set up for this account.");
        }

        target.setMfaEnabled(false);
        target.setMfaSecret(null);
        target.setMfaConfirmedAt(null);
        userRepository.save(target);
        recoveryCodeRepository.deleteByUserId(target.getId());

        log.warn("2FA RESET: user id={} ({}) had their second factor cleared by admin id={}",
                target.getId(), target.getEmail(), actingUserId);

        return new MessageResponse(
                target.fullName() + " can sign in with their password alone until they enrol again.");
    }

    /** Replaces every existing code, so a leaked list stops working immediately. */
    @Transactional
    public MfaConfirmResponse regenerateRecoveryCodes(Long userId, PasswordConfirmRequest request) {
        User user = requireUser(userId);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("That password is not correct.");
        }
        if (!user.isMfaEnabled()) {
            throw ApiException.conflict("Two-factor authentication is not enabled.");
        }

        return new MfaConfirmResponse(
                "New recovery codes generated. Your previous codes no longer work.",
                issueRecoveryCodes(user));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private LoginResponse completeLogin(User user) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        Set<String> permissions = permissionService.effectivePermissionsFor(user);

        return LoginResponse.success(
                jwtService.generateAccessToken(user, permissions),
                jwtService.getAccessTokenTtl().toSeconds(),
                UserResponse.from(user, permissions));
    }

    private void registerFailedAttempt(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(LOCK_DURATION));
            log.warn("Locked user id={} after {} failed attempts", user.getId(), attempts);
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
            throw ApiException.badRequest("Two-factor authentication is not set up for this account.");
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

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
