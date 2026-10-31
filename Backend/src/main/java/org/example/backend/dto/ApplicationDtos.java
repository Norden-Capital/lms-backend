package org.example.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What the LMS pages show about clients and their applications. */
public final class ApplicationDtos {

    private ApplicationDtos() {
    }

    /** One line in the Leads, Abandoned or Failed list. Null name or phone means it could not be read. */
    public record Row(String id, String clientId, String email, String name, String phone, String brand,
                      String status, String leadscoutStatus, int amount, String language, boolean test,
                      Instant createdAt, Instant updatedAt) {
    }

    /** A client, with every time they applied, newest first. */
    public record ClientDetail(String id, String email, Instant createdAt, List<ApplicationDetail> applications) {
    }

    public record ApplicationDetail(String id, String brand, String status, String leadscoutStatus, String redirectUrl,
                                    int amount, String language, boolean test, Instant consentAt,
                                    Instant createdAt, Instant updatedAt, String ip, String userAgent,
                                    Map<String, Object> answers, Map<String, Object> tracking, List<Send> sends) {
    }

    /** One send to LeadScout and what came back. */
    public record Send(Instant at, String mode, Integer httpStatus, String leadscoutStatus, String problem,
                       Integer durationMs, String request, String response) {
    }

    /** A brand, for the filter on the lists. */
    public record BrandOption(String id, String name) {
    }

    public record Overview(int days, boolean includeTest, int leads, int abandoned, int failed,
                           Map<String, Integer> leadscout, List<Day> daily, List<BrandCount> brands) {
    }

    /** One day of the chart, with no gaps: a day with nothing still has its line. */
    public record Day(String date, int leads, int abandoned, int failed) {
    }

    public record BrandCount(String id, String name, int leads, int abandoned, int failed) {
    }
}
