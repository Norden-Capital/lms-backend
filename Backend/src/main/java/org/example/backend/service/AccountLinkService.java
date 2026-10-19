package org.example.backend.service;

import org.example.backend.dto.LinkInfoResponse;
import org.example.backend.dto.MessageResponse;
import org.example.backend.dto.SetPasswordWithTokenRequest;
import org.example.backend.entity.AuthToken;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.AuthTokenRepository;
import org.example.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Everything that travels as a link in an email: invitations and password resets.
 *
 * <p>Both work the same way. A random token goes into the link, only its SHA-256
 * is stored, and using it once — or letting it expire — ends it. Asking for a new
 * one deletes the old one, so only the newest email ever works.
 *
 * <p><b>None of this gets past two-factor.</b> A reset changes the password and
 * nothing else; signing in still asks for the authenticator code. So somebody who
 * gets into a person's inbox can change their password but still cannot get into
 * their account.
 *
 * <p>Emails go out after the transaction commits. Sent from inside it, a rollback
 * would leave a link in somebody's inbox that points at a token that was never
 * saved.
 */
@Service
public class AccountLinkService {

    private static final Logger log = LoggerFactory.getLogger(AccountLinkService.class);

    static final Duration INVITE_TTL = Duration.ofDays(7);
    static final Duration RESET_TTL = Duration.ofMinutes(30);

    /** A second email of the same kind within this window is refused — no inbox flooding. */
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /**
     * Identical whether or not the address has an account, so this endpoint
     * cannot be used to find out who works here.
     */
    private static final String FORGOT_ANSWER =
            "If an account exists for this email, a reset link is on its way. Check your inbox.";

    private static final String RESET_GONE =
            "This reset link has expired or was already used. Ask for a new one.";
    private static final String INVITE_GONE =
            "This invitation has expired or was already used. Ask an administrator to send a new one.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final AccessService accessService;
    private final AccountGuard accountGuard;
    private final String frontendUrl;

    public AccountLinkService(AuthTokenRepository tokenRepository,
                              UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              MailService mailService,
                              AccessService accessService,
                              AccountGuard accountGuard,
                              @Value("${app.frontend-url}") String frontendUrl) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.accessService = accessService;
        this.accountGuard = accountGuard;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    // ── Invitations ─────────────────────────────────────────────────────────

    /**
     * A password nobody knows, for an account that has not chosen one yet.
     *
     * <p>The column is NOT NULL, and an invited account is PENDING_VERIFICATION,
     * which cannot sign in anyway. This only makes sure that even if the status
     * rule were ever loosened, there is no password that would work.
     */
    public String unusablePasswordHash() {
        return passwordEncoder.encode(newToken());
    }

    /** Emails a freshly created account its invitation. Joins the caller's transaction. */
    @Transactional
    public void sendInvitation(User user, Long actingUserId) {
        String raw = issue(user, AuthToken.Type.INVITE, INVITE_TTL);
        String invitedBy = nameOf(actingUserId);

        String to = user.getEmail();
        String firstName = user.getFirstName();
        String link = frontendUrl + "/accept-invite?token=" + raw;

        afterCommit(() -> mailService.sendInvitation(to, firstName, invitedBy, link, INVITE_TTL.toDays()));
    }

    /** "Resend invite" — for somebody who lost the email, or let it expire. */
    @Transactional
    public MessageResponse resendInvitation(Long userId, Long actingUserId) {
        User user = userRepository.findWithRoleById(userId)
                .orElseThrow(() -> ApiException.notFound("No user with id " + userId + "."));

        // Re-inviting an administrator is the Owner's, like inviting one.
        accountGuard.requireCanManage(user, actingUserId);

        if (user.getStatus() != UserStatus.PENDING_VERIFICATION) {
            throw ApiException.badRequest("This person has already joined. Send a password reset link instead.");
        }
        refuseIfJustSent(user, AuthToken.Type.INVITE,
                "An invitation was just sent. Wait a minute before sending another one.");

        sendInvitation(user, actingUserId);
        log.info("User id={} resent the invitation for account id={}", actingUserId, userId);

        return new MessageResponse("Invitation sent again to " + user.getEmail() + ". The old link no longer works.");
    }

    /** What the "set up your account" page shows before the form. */
    @Transactional(readOnly = true)
    public LinkInfoResponse checkInvitation(String rawToken) {
        AuthToken token = usableInvitation(rawToken);
        User user = token.getUser();
        return new LinkInfoResponse(user.getEmail(), user.getFirstName(), token.getExpiresAt());
    }

    /**
     * The invited person choosing their password. The account becomes ACTIVE, and
     * their first sign-in takes them straight to two-factor enrolment.
     */
    @Transactional
    public MessageResponse acceptInvitation(SetPasswordWithTokenRequest request) {
        AuthToken token = usableInvitation(request.token());
        User user = token.getUser();

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(UserStatus.ACTIVE);
        // The link reached them at this address, which is the proof.
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        tokenRepository.deleteByUserIdAndType(user.getId(), AuthToken.Type.INVITE);
        accessService.invalidate(user.getId());
        log.info("User id={} accepted their invitation", user.getId());

        return new MessageResponse("Your account is ready. Sign in to finish setting up two-factor authentication.");
    }

    private AuthToken usableInvitation(String rawToken) {
        return tokenRepository.findByTokenHashAndType(hash(rawToken), AuthToken.Type.INVITE)
                .filter(AuthToken::isUsable)
                // Deactivated while the invitation was outstanding: the link dies with the account.
                .filter(t -> t.getUser().getStatus() == UserStatus.PENDING_VERIFICATION)
                .orElseThrow(() -> ApiException.badRequest(INVITE_GONE));
    }

    // ── Password resets ─────────────────────────────────────────────────────

    /**
     * "Forgot password" on the sign-in screen. Always the same answer — see
     * {@link #FORGOT_ANSWER}.
     *
     * <p>Only active accounts get a link. An invited person has no password to
     * forget, and a switched-off account must not be able to come back by email.
     */
    @Transactional
    public MessageResponse forgotPassword(String email) {
        Optional<User> found = userRepository.findByEmail(email.trim().toLowerCase())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);

        found.ifPresent(user -> {
            // Pressed again within the cooldown: quietly send nothing. Telling the
            // caller "wait a minute" would confirm the account exists.
            if (wasJustSent(user, AuthToken.Type.PASSWORD_RESET)) {
                return;
            }
            sendReset(user);
            log.info("Password reset link requested for account id={}", user.getId());
        });

        return new MessageResponse(FORGOT_ANSWER);
    }

    /**
     * An administrator sending somebody a reset link. The administrator never
     * sees or chooses the password — the person does, from their own inbox.
     */
    @Transactional
    public MessageResponse sendPasswordReset(Long userId, Long actingUserId) {
        User user = userRepository.findWithRoleById(userId)
                .orElseThrow(() -> ApiException.notFound("No user with id " + userId + "."));

        // Only the Owner sends an administrator a reset link, and nobody sends
        // one to the Owner — who uses "Forgot password?" like anybody else.
        accountGuard.requireCanManage(user, actingUserId);

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw ApiException.badRequest("This person hasn't joined yet. Resend the invitation instead.");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.badRequest("This account is switched off. Turn it back on first.");
        }
        refuseIfJustSent(user, AuthToken.Type.PASSWORD_RESET,
                "A reset link was just sent. Wait a minute before sending another one.");

        sendReset(user);
        log.info("User id={} sent a password reset link to account id={}", actingUserId, userId);

        return new MessageResponse("Reset link sent to " + user.getEmail() + ". It works for "
                + RESET_TTL.toMinutes() + " minutes.");
    }

    /** What the "choose a new password" page shows before the form. */
    @Transactional(readOnly = true)
    public LinkInfoResponse checkPasswordReset(String rawToken) {
        AuthToken token = usableReset(rawToken);
        User user = token.getUser();
        return new LinkInfoResponse(user.getEmail(), user.getFirstName(), token.getExpiresAt());
    }

    /**
     * Choosing the new password.
     *
     * <p>Clears any lockout — the failed attempts belonged to the old password —
     * and ends every session the account has, so a reset after a break-in
     * actually throws the intruder out. Two-factor is untouched.
     */
    @Transactional
    public MessageResponse resetPassword(SetPasswordWithTokenRequest request) {
        AuthToken token = usableReset(request.token());
        User user = token.getUser();

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setTokensValidFrom(Instant.now());
        userRepository.save(user);

        tokenRepository.deleteByUserIdAndType(user.getId(), AuthToken.Type.PASSWORD_RESET);
        accessService.invalidate(user.getId());
        log.info("User id={} reset their password from an email link", user.getId());

        return new MessageResponse("Password changed. Sign in with your new password.");
    }

    private void sendReset(User user) {
        String raw = issue(user, AuthToken.Type.PASSWORD_RESET, RESET_TTL);

        String to = user.getEmail();
        String firstName = user.getFirstName();
        String link = frontendUrl + "/reset-password?token=" + raw;

        afterCommit(() -> mailService.sendPasswordReset(to, firstName, link, RESET_TTL.toMinutes()));
    }

    private AuthToken usableReset(String rawToken) {
        return tokenRepository.findByTokenHashAndType(hash(rawToken), AuthToken.Type.PASSWORD_RESET)
                .filter(AuthToken::isUsable)
                // Switched off after the link was sent: the link does not bring it back.
                .filter(t -> t.getUser().getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> ApiException.badRequest(RESET_GONE));
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    /** Replaces any outstanding link of this kind with a new one, and returns the raw token. */
    private String issue(User user, AuthToken.Type type, Duration ttl) {
        tokenRepository.deleteByUserIdAndType(user.getId(), type);
        // Hibernate runs inserts before deletes when it flushes. Flushing here
        // keeps the order as written: the old link is gone before the new exists.
        tokenRepository.flush();

        String raw = newToken();
        tokenRepository.save(AuthToken.builder()
                .user(user)
                .type(type)
                .tokenHash(hash(raw))
                .expiresAt(Instant.now().plus(ttl))
                .build());
        return raw;
    }

    private boolean wasJustSent(User user, AuthToken.Type type) {
        return tokenRepository.findFirstByUserIdAndTypeOrderByCreatedAtDesc(user.getId(), type)
                .map(t -> t.getCreatedAt().isAfter(Instant.now().minus(RESEND_COOLDOWN)))
                .orElse(false);
    }

    private void refuseIfJustSent(User user, AuthToken.Type type, String message) {
        if (wasJustSent(user, type)) {
            throw ApiException.tooManyRequests(message);
        }
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(User::fullName).orElse("An administrator");
    }

    /**
     * Runs once the surrounding transaction has committed, or straight away when
     * there is none. Callers capture plain values first: the entities are
     * detached by then.
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 32 random bytes, URL-safe — 43 characters, fine in a link. */
    private static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
