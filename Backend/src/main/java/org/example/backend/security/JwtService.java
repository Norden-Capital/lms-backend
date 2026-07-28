package org.example.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.backend.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Issues and reads the two kinds of token this system uses.
 *
 * <p><b>Access tokens</b> are the real thing — they carry the user's identity,
 * role and full permission list, so every subsequent request is authorised
 * without touching the database.
 *
 * <p><b>MFA-pending tokens</b> are issued after a correct password when the
 * account has 2FA switched on. They are short-lived and grant no access at all;
 * their only purpose is to prove "this person already passed the password step"
 * so the second step cannot be called on its own.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_PERMISSIONS = "perms";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_MFA_PENDING = "mfa_pending";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration mfaTokenTtl;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.access-token-ttl:PT24H}") Duration accessTokenTtl,
            @Value("${app.jwt.mfa-token-ttl:PT5M}") Duration mfaTokenTtl) {

        this.accessTokenTtl = accessTokenTtl;
        this.mfaTokenTtl = mfaTokenTtl;

        if (secret == null || secret.isBlank()) {
            // Generating a throwaway key beats shipping a default one in source
            // control: nothing secret ends up in git, and the loud warning makes
            // the missing configuration impossible to miss.
            this.key = Jwts.SIG.HS256.key().build();
            log.warn("""

                    ****************************************************************
                    No app.jwt.secret configured — generated a random signing key.
                    Every restart invalidates all issued tokens, and multiple
                    instances will reject each other's tokens.
                    Set the JWT_SECRET environment variable before deploying.
                    ****************************************************************""");
        } else {
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) {
                throw new IllegalStateException(
                        "app.jwt.secret must be at least 32 characters for HS256 signing (got " + keyBytes.length + ")");
            }
            this.key = Keys.hmacShaKeyFor(keyBytes);
        }
    }

    /** The real token: identity plus everything needed to authorise a request. */
    public String generateAccessToken(User user, Set<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_ROLE, user.getRole().getName())
                .claim(CLAIM_PERMISSIONS, List.copyOf(permissions))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * Handed out after a correct password when 2FA is on. Grants nothing — it is
     * only accepted by the second login step.
     */
    public String generateMfaPendingToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(CLAIM_TYPE, TYPE_MFA_PENDING)
                .claim(CLAIM_USER_ID, user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(mfaTokenTtl)))
                .signWith(key)
                .compact();
    }

    /** Verifies the signature and expiry. Throws {@link JwtException} if either fails. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Parses and additionally insists the token is of the expected kind. */
    public Claims parseAs(String token, String expectedType) {
        Claims claims = parse(token);
        String actual = claims.get(CLAIM_TYPE, String.class);
        if (!expectedType.equals(actual)) {
            throw new JwtException("Expected a " + expectedType + " token but got " + actual);
        }
        return claims;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }
}
