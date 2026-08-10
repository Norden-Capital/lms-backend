package org.example.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@code Bearer} access token into an authenticated request.
 *
 * <p>Permissions travel inside the token, so authorising a request costs no
 * database queries. The trade-off is that a permission change only takes effect
 * when the user's next token is issued.
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

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
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

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();

            // Read outside the branch below: the principal carries it either way,
            // so that "who am I" still answers correctly while enrolment is owed.
            // Holding the role is not the same as being allowed to use it — the
            // ROLE_ authority granting that is only added for access tokens.
            String role = claims.get(JwtService.CLAIM_ROLE, String.class);

            if (isAccess) {
                // The marker that separates a finished login from one still owing
                // an enrolment. SecurityConfig requires it on every route that is
                // not explicitly part of enrolling, so a new endpoint is closed to
                // enrolment-pending holders by default rather than by remembering.
                authorities.add(new SimpleGrantedAuthority(SESSION_FULL));

                if (role != null) {
                    // Spring's hasRole() looks for this prefix; hasAuthority() does not.
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }

                List<?> permissions = claims.get(JwtService.CLAIM_PERMISSIONS, List.class);
                if (permissions != null) {
                    permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(String.valueOf(p))));
                }
            }
            // An enrolment-pending token gets no authorities whatsoever — not even
            // its role. It is authenticated, which is enough to reach the enrolment
            // endpoints, and authorised for nothing.

            AuthPrincipal principal = new AuthPrincipal(
                    claims.get(JwtService.CLAIM_USER_ID, Number.class).longValue(),
                    claims.getSubject(),
                    role
            );

            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // Expired, tampered with, or the wrong kind of token. Leave the context
            // empty and let the entry point return a clean 401.
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
