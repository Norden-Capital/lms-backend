package org.example.backend.dto;

/**
 * ⚠️ A stand-in, like {@link CustomerResponse}. Invented numbers for a dashboard
 * that does not exist yet, so the SEO and Email modules have something a
 * permission can actually refuse.
 */
public record OverviewMetricResponse(String label, String value, String change) {
}
