package org.example.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How often one visitor may try the doors that do not need an account —
 * decided 2026-10-19.
 *
 * <p>The per-account lockout stops guessing one person's password. This stops a
 * single visitor guessing across many accounts, flooding "Forgot password?", or
 * trying invitation links in bulk. Counted per address ({@link ClientIp}), in
 * fixed windows, in memory: there is one server, and a restart forgetting the
 * counts is harmless.
 *
 * <p>Until the website's forwarding is configured ({@code PROXY_SECRET}), every
 * visitor arriving through the website shares the website server's address, so
 * the limits are relaxed tenfold rather than shutting everybody out together.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private record Rule(String name, int limit, Duration window) {
    }

    private static final Rule SIGN_IN = new Rule("sign-in", 10, Duration.ofMinutes(5));
    private static final Rule FORGOT = new Rule("forgot-password", 5, Duration.ofMinutes(15));
    private static final Rule LINKS = new Rule("email-links", 20, Duration.ofMinutes(15));
    private static final Rule APPLY = new Rule("apply-form", 30, Duration.ofMinutes(15));

    /** POST paths and the allowance each one draws from. */
    private static final Map<String, Rule> RULES = Map.of(
            "/api/auth/login", SIGN_IN,
            "/api/auth/login/2fa", SIGN_IN,
            "/api/auth/password/forgot", FORGOT,
            "/api/auth/password/reset/check", LINKS,
            "/api/auth/password/reset", LINKS,
            "/api/auth/invitation/check", LINKS,
            "/api/auth/invitation/accept", LINKS
    );

    private static final String APPLY_PATH = "/api/public/applications";

    private static final String REFUSAL =
            "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":"
                    + "\"Too many attempts. Please wait a few minutes and try again.\"}";

    private record Window(long startedAt, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger sinceCleanup = new AtomicInteger();
    private final ClientIp clientIp;

    public RateLimitFilter(ClientIp clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || ruleFor(request.getRequestURI()) == null;
    }

    /** The second step's address holds a token, so the apply form is matched by its start. */
    private static Rule ruleFor(String path) {
        return path.equals(APPLY_PATH) || path.startsWith(APPLY_PATH + "/") ? APPLY : RULES.get(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Rule rule = ruleFor(request.getRequestURI());
        String address = clientIp.resolve(request);
        int limit = clientIp.proxyConfigured() ? rule.limit() : rule.limit() * 10;

        long now = System.currentTimeMillis();
        long windowMs = rule.window().toMillis();
        Window window = windows.compute(rule.name() + "|" + address, (key, current) ->
                current == null || now - current.startedAt() >= windowMs
                        ? new Window(now, new AtomicInteger())
                        : current);

        if (window.count().incrementAndGet() > limit) {
            long retryAfter = Math.max(1, (window.startedAt() + windowMs - now) / 1000);
            log.warn("Rate limit '{}' reached for {} on {}", rule.name(), address, request.getRequestURI());
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(REFUSAL);
            return;
        }

        forgetExpiredNowAndThen(now);
        chain.doFilter(request, response);
    }

    /** Old windows are dropped every so often, so the map cannot grow without end. */
    private void forgetExpiredNowAndThen(long now) {
        if (sinceCleanup.incrementAndGet() < 1_000) return;
        sinceCleanup.set(0);
        long longest = Duration.ofMinutes(15).toMillis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= longest);
    }
}
