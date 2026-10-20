package org.example.backend.seo;

import org.example.backend.exception.ApiException;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Signs in to Google as the service account, read-only on Analytics.
 *
 * <p>The key is the JSON file Google gave when the service account was created.
 * It lives on the server only ({@code GA4_CREDENTIALS_FILE}; the deploy writes
 * it from the GA4_CREDENTIALS secret) — never in git, never in the frontend.
 *
 * <p>Google's own sign-in for servers: a short JWT signed with the key is traded
 * for an access token that lasts an hour; it is reused until five minutes before
 * it runs out. No Google library needed.
 */
@Component
public class GoogleServiceAccount {

    private static final Logger log = LoggerFactory.getLogger(GoogleServiceAccount.class);
    static final String SCOPE = "https://www.googleapis.com/auth/analytics.readonly";

    private final String credentialsFile;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private String clientEmail;
    private PrivateKey privateKey;
    private String tokenUri;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public GoogleServiceAccount(@Value("${app.seo.credentials-file:}") String credentialsFile, ObjectMapper json) {
        this.credentialsFile = credentialsFile.trim();
        this.json = json;
    }

    public boolean configured() {
        return !credentialsFile.isEmpty();
    }

    /** The service account's address, which needs Viewer access on each GA4 property. */
    public synchronized String clientEmail() {
        loadKey();
        return clientEmail;
    }

    public synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(300))) return token;
        loadKey();
        Instant now = Instant.now();
        String assertion = Jwts.builder()
            .issuer(clientEmail)
            .claim("scope", SCOPE)
            // Google wants the audience as one string, not a list.
            .audience().single(tokenUri)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(tokenUri))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Google refused the service account sign-in: {} {}", response.statusCode(), response.body());
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Google refused the Analytics key. Check the key on the server.");
            }
            JsonNode body = json.readTree(response.body());
            token = body.path("access_token").asString();
            tokenExpiresAt = now.plusSeconds(body.path("expires_in").asLong(3600));
            return token;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google did not answer. Try again soon.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google did not answer. Try again soon.");
        }
    }

    private void loadKey() {
        if (privateKey != null) return;
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Analytics is not connected yet.");
        }
        try {
            JsonNode key = json.readTree(Files.readString(Path.of(credentialsFile)));
            clientEmail = key.path("client_email").asString();
            tokenUri = key.path("token_uri").asString("https://oauth2.googleapis.com/token");
            String pem = key.path("private_key").asString()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception e) {
            log.error("Could not read the Google Analytics key file {}", credentialsFile, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "The Google Analytics key on the server is not valid.");
        }
    }
}
