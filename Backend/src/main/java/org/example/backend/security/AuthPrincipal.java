package org.example.backend.security;

/**
 * Who is making the current request, as read from the access token.
 *
 * <p>Injected into controllers with {@code @AuthenticationPrincipal}. Carrying the
 * id as well as the email means an endpoint that acts on "me" never has to look
 * the user up by email first.
 */
/** {@code sessionId}: the sign-in behind the token; null on tokens from before sessions existed. */
public record AuthPrincipal(Long userId, String email, String role, java.util.UUID sessionId) {
}
