package org.example.backend.service;

import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an account may do <em>right now</em>, rather than when it last signed in.
 *
 * <p><b>This is the piece that makes revocation real.</b> Permissions used to
 * travel inside the token, so authorising a request cost no queries — but a
 * permission taken away kept working until that token expired, which could be
 * hours. The screen whose entire purpose is changing somebody's access would
 * have been telling the truth only eventually.
 *
 * <p>So every request looks the account up instead. To keep that from being a
 * database round trip per request, the answer is held for a few seconds: long
 * enough that a burst of requests costs one query, short enough that
 * {@link #TTL} is the worst case even if some future code path forgets to call
 * {@link #invalidate}.
 *
 * <p>The cache is deliberately hand-rolled rather than Spring's abstraction. It
 * holds one small record per signed-in user, it is read on every request, and
 * the eviction rule is "whenever this user changes" — a purpose-built map is
 * easier to reason about than annotations whose behaviour depends on which cache
 * provider happens to be on the classpath.
 *
 * <p>⚠️ In memory, per instance. Running two copies of the backend means each
 * keeps its own, so a revocation could take up to {@link #TTL} to reach the
 * instance that did not serve the change. Fixing that properly is a shared cache
 * — worth doing when there is a second instance, not before.
 */
@Service
public class AccessService {

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);

    /**
     * How long an answer is reused.
     *
     * <p>Short on purpose. This is the window in which a revoked permission still
     * works, so it is a security number, not a performance one — and fifteen
     * seconds already collapses a page load's worth of requests into one query.
     */
    private static final Duration TTL = Duration.ofSeconds(15);

    private final UserRepository userRepository;
    private final PermissionService permissionService;

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public AccessService(UserRepository userRepository, PermissionService permissionService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
    }

    /**
     * Everything the security filter needs about an account, in one object.
     *
     * @param permissions     effective permissions — role plus grants, minus denies
     * @param role            current role name, which may differ from the token's
     * @param status          current status; only ACTIVE may use the application
     * @param tokensValidFrom tokens issued before this are refused
     */
    public record Snapshot(
            Set<String> permissions,
            String role,
            UserStatus status,
            Instant tokensValidFrom
    ) {
        /** Whether a token issued at this instant is still one of theirs. */
        public boolean accepts(Instant tokenIssuedAt) {
            // Second precision, because that is all a JWT's `iat` claim carries.
            // A token issued in the same second as a revocation survives it; that
            // is a sub-second race nobody can arrange, and erring the other way
            // would refuse the fresh token of somebody signing in at that moment.
            return tokenIssuedAt != null
                    && tokenIssuedAt.getEpochSecond() >= tokensValidFrom.getEpochSecond();
        }
    }

    private record Entry(Snapshot snapshot, Instant loadedAt) {
        boolean isFresh() {
            return loadedAt.plus(TTL).isAfter(Instant.now());
        }
    }

    /**
     * @return the account's current access, or empty if there is no such account
     */
    @Transactional(readOnly = true)
    public Optional<Snapshot> snapshotOf(Long userId) {
        Entry cached = cache.get(userId);
        if (cached != null && cached.isFresh()) {
            return Optional.of(cached.snapshot());
        }

        Optional<User> found = userRepository.findWithRoleById(userId);

        if (found.isEmpty()) {
            // Nothing cached for an account that does not exist — otherwise a
            // stream of requests bearing a token for a deleted user would fill
            // the map with empty answers.
            cache.remove(userId);
            return Optional.empty();
        }

        User user = found.get();

        Snapshot snapshot = new Snapshot(
                permissionService.effectivePermissionsFor(user),
                user.getRole().getName(),
                user.getStatus(),
                user.getTokensValidFrom()
        );

        cache.put(userId, new Entry(snapshot, Instant.now()));
        return Optional.of(snapshot);
    }

    /**
     * Drops the cached answer for one account, so the next request re-reads it.
     *
     * <p>Called from every path that changes what somebody may do: role, status,
     * permissions, password. Missing one is not a correctness disaster — the
     * entry expires within {@link #TTL} anyway — but it is the difference between
     * a change landing instantly and landing eventually.
     */
    public void invalidate(Long userId) {
        if (cache.remove(userId) != null) {
            log.debug("Access cache cleared for user id={}", userId);
        }
    }
}
