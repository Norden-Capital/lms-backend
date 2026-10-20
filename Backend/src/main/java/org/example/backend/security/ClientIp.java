package org.example.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * Which address a request really came from — for rate limits and the action log.
 *
 * <p>Most requests do not come from the person's browser. The website calls
 * this API from its own server (Vercel), so every signed-in action arrives from
 * one of a handful of Vercel addresses. Limiting by those would make everybody
 * share one allowance.
 *
 * <p>So the website passes the visitor's address along in {@code X-Client-IP},
 * and proves the header is its own with {@code X-Proxy-Secret}, a value only it
 * and this server know ({@code PROXY_SECRET}). Without a matching secret the
 * header is ignored — anybody can send one — and the address Caddy saw is used
 * instead: Caddy replaces any {@code X-Forwarded-For} a stranger sends with the
 * address actually connecting to it.
 */
@Component
public class ClientIp {

    /** Digits, letters a–f, dots and colons: an IPv4 or IPv6 address and nothing else. */
    private static final Pattern ADDRESS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final byte[] proxySecret;

    public ClientIp(@Value("${app.proxy-secret:}") String proxySecret) {
        this.proxySecret = proxySecret.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the website's forwarding is set up on this server at all. */
    public boolean proxyConfigured() {
        return proxySecret.length > 0;
    }

    /** Whether this request came from the website's server, proven by the shared secret. */
    public boolean viaWebsite(HttpServletRequest request) {
        String sent = request.getHeader("X-Proxy-Secret");
        return proxyConfigured()
                && sent != null
                && MessageDigest.isEqual(proxySecret, sent.getBytes(StandardCharsets.UTF_8));
    }

    public String resolve(HttpServletRequest request) {
        if (viaWebsite(request)) {
            String forwarded = request.getHeader("X-Client-IP");
            if (forwarded != null && ADDRESS.matcher(forwarded.trim()).matches()) {
                return forwarded.trim();
            }
        }

        String chain = request.getHeader("X-Forwarded-For");
        if (chain != null && !chain.isBlank()) {
            String first = chain.split(",")[0].trim();
            if (ADDRESS.matcher(first).matches()) return first;
        }

        return request.getRemoteAddr();
    }
}
