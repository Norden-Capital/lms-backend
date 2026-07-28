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
            // parseAs rejects MFA-pending tokens here, so the half-finished login
            // step can never be used as a credential for real endpoints.
            Claims claims = jwtService.parseAs(header.substring(7), JwtService.TYPE_ACCESS);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();

            String role = claims.get(JwtService.CLAIM_ROLE, String.class);
            if (role != null) {
                // Spring's hasRole() looks for this prefix; hasAuthority() does not.
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }

            List<?> permissions = claims.get(JwtService.CLAIM_PERMISSIONS, List.class);
            if (permissions != null) {
                permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(String.valueOf(p))));
            }

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
