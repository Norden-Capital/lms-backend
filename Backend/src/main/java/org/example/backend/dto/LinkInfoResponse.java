package org.example.backend.dto;

import java.time.Instant;

/**
 * Who an emailed link is for, so the page can say "Welcome, Sarah" and show the
 * address being set up. Only ever returned for a valid, unused link — which the
 * person holding it received at that very address.
 */
public record LinkInfoResponse(String email, String firstName, Instant expiresAt) {
}
