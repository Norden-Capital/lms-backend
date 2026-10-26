package org.example.backend.service;

import jakarta.servlet.http.HttpServletRequest;
import org.example.backend.entity.UserSession;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.UserSessionRepository;
import org.example.backend.security.ClientIp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions — decided 2026-10-26. Every finished sign-in is a row; its id rides
 * in the access token as "sid". Ending the row ends that sign-in at once, on
 * this server: the answer to "is it still live?" is kept for 15 seconds, like
 * AccessService, and dropped the moment a session is ended here.
 *
 * <p>Tokens issued before this existed carry no sid. They are let through
 * until they expire (24 hours at most), as before.
 */
@Service
public class SessionService {

    /** How long "is this session live?" is remembered. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    /** "Last used" is written at most this often per session — not on every request. */
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    private record Entry(boolean live, Long userId, Instant loadedAt) {
    }

    private final UserSessionRepository sessions;
    private final ClientIp clientIp;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> touched = new ConcurrentHashMap<>();

    public SessionService(UserSessionRepository sessions, ClientIp clientIp) {
        this.sessions = sessions;
        this.clientIp = clientIp;
    }

    /** A new sign-in, from the request being served. Returns the id for the token. */
    @Transactional
    public UUID start(Long userId, Duration lifetime) {
        Instant now = Instant.now();
        HttpServletRequest request = currentRequest();
        UserSession session = sessions.save(UserSession.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .createdAt(now)
                .lastSeenAt(now)
                .expiresAt(now.plus(lifetime))
                .ip(request == null ? null : truncate(clientIp.resolve(request), 64))
                .userAgent(request == null ? null : truncate(agentOf(request), 400))
                .build());
        return session.getId();
    }

    /** Whether a token carrying this sid, for this account, may still be used. */
    public boolean isLive(UUID id, Long userId) {
        Instant now = Instant.now();
        Entry cached = cache.get(id);
        if (cached == null || cached.loadedAt().plus(CACHE_TTL).isBefore(now)) {
            cached = sessions.findById(id)
                    .map(s -> new Entry(s.isLive(now), s.getUserId(), now))
                    .orElse(new Entry(false, null, now));
            cache.put(id, cached);
        }
        return cached.live() && userId.equals(cached.userId());
    }

    /** Notes that a session was used — written to the database every few minutes at most. */
    @Transactional
    public void touch(UUID id) {
        Instant now = Instant.now();
        Instant last = touched.get(id);
        if (last != null && last.plus(TOUCH_EVERY).isAfter(now)) return;
        touched.put(id, now);
        sessions.touch(id, now);
    }

    /** Someone's live sessions, most recently used first. {@code since}: their token cut-off. */
    @Transactional(readOnly = true)
    public List<UserSession> live(Long userId, Instant since) {
        return sessions.findLive(userId, Instant.now(), since);
    }

    /** Ends one of your own sessions. Someone else's id answers as not found. */
    @Transactional
    public void end(Long userId, UUID id) {
        UserSession session = sessions.findById(id)
                .filter(s -> s.getUserId().equals(userId) && s.getEndedAt() == null)
                .orElseThrow(() -> ApiException.notFound("No such session."));
        session.setEndedAt(Instant.now());
        sessions.save(session);
        cache.remove(id);
    }

    /** Ends all of someone's sessions but one. Returns how many ended. */
    @Transactional
    public int endOthers(Long userId, UUID keep) {
        int ended = keep == null
                ? sessions.endAll(userId, Instant.now())
                : sessions.endAllExcept(userId, keep, Instant.now());
        // Simplest correct thing: forget every cached answer. It refills in a query each.
        cache.clear();
        return ended;
    }

    /**
     * The browser's own description. The website forwards it in X-Client-Agent,
     * believed only with the shared secret — like X-Client-IP; otherwise the
     * request's own User-Agent.
     */
    private String agentOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Client-Agent");
        if (forwarded != null && !forwarded.isBlank() && clientIp.viaWebsite(request)) return forwarded;
        return request.getHeader("User-Agent");
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
