package org.example.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.backend.entity.UserStatus;
import org.example.backend.service.AccessService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a {@code Bearer} access token into an authenticated request.
 *
 * <p><b>The token says who you are. The database says what you may do.</b> That
 * split is deliberate and it changed: permissions used to be read straight out of
 * the token, which cost no queries but meant a permission taken away kept working
 * until that token expired. An administrator unticking a box and being told
 * "saved" while the person carried on deleting things for another hour is not a
 * permission system, it is a suggestion.
 *
 * <p>So each request loads the account's current access through
 * {@link AccessService}, which holds the answer for a few seconds. Three things
 * are checked that a token alone could never notice:
 *
 * <ul>
 *   <li><b>The account still exists.</b></li>
 *   <li><b>It is still ACTIVE.</b> Deactivating somebody now signs them out on
 *       their next request rather than whenever their token happens to run out.</li>
 *   <li><b>The token was issued after their cut-off.</b> Resetting a password
 *       bumps that cut-off, which is what finally lets a password reset end the
 *       session an intruder is already inside.</li>
 * </ul>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Granted only to a token from a fully completed login. {@code SecurityConfig}
     * demands it on everything except the handful of enrolment routes, which is
     * what makes two-factor mandatory rather than merely encouraged.
     */
    public static final String SESSION_FULL = "SESSION:FULL";

    private final JwtService jwtService;
    private final AccessService accessService;

    public JwtAuthenticationFilter(JwtService jwtService, AccessService accessService) {
        this.jwtService = jwtService;
        this.accessService = accessService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parse(header.substring(7));
            String type = claims.get(JwtService.CLAIM_TYPE, String.class);

            // MFA-pending tokens are refused here, so the half-finished login step
            // can never be used as a credential for real endpoints. Only the two
            // types below authenticate anything.
            boolean isAccess = JwtService.TYPE_ACCESS.equals(type);
            boolean isEnrolmentPending = JwtService.TYPE_ENROLMENT_PENDING.equals(type);
            if (!isAccess && !isEnrolmentPending) {
                throw new JwtException("Token type " + type + " cannot authenticate a request");
            }

            Long userId = claims.get(JwtService.CLAIM_USER_ID, Number.class).longValue();

            Optional<AccessService.Snapshot> found = accessService.snapshotOf(userId);
            if (found.isEmpty()) {
                // A valid signature for an account that is no longer there.
                throw new JwtException("No account for user id " + userId);
            }

            AccessService.Snapshot access = found.get();

            if (!access.accepts(claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant())) {
                throw new JwtException("Token predates this account's cut-off");
            }

            if (access.status() != UserStatus.ACTIVE) {
                throw new JwtException("Account is " + access.status());
            }

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();

            // The role comes from the database too, not from the token. Somebody
            // demoted mid-session should stop being an administrator at once, and
            // the token still claims they are one.
            String role = access.role();

            if (isAccess) {
                // The marker that separates a finished login from one still owing
                // an enrolment. SecurityConfig requires it on every route that is
                // not explicitly part of enrolling, so a new endpoint is closed to
                // enrolment-pending holders by default rather than by remembering.
                authorities.add(new SimpleGrantedAuthority(SESSION_FULL));

                // Spring's hasRole() looks for this prefix; hasAuthority() does not.
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));

                access.permissions()
                        .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
            }
            // An enrolment-pending token gets no authorities whatsoever — not even
            // its role. It is authenticated, which is enough to reach the enrolment
            // endpoints, and authorised for nothing.

            AuthPrincipal principal = new AuthPrincipal(
                    userId,
                    claims.getSubject(),
                    role
            );

            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // Expired, tampered with, revoked, or for an account that has since
            // been switched off. Leave the context empty and let the entry point
            // return a clean 401.
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
