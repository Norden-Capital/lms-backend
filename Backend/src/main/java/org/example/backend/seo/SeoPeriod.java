package org.example.backend.seo;

import org.example.backend.exception.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The period the SEO page looks at, and the one it is compared with.
 *
 * <p>Today, Yesterday, the last 7 days, this month, this year, or a custom range
 * (oneBase, decided 2026-10-05; the LMS since 2026-10-20). The comparison is always the period of the same length
 * just before. The chart's step follows the length: hours for one day, days up to
 * a month, weeks up to four months, months beyond.
 *
 * <p>Dates are the business's calendar ({@code app.seo.timezone}).
 */
public record SeoPeriod(String range, LocalDate start, LocalDate end, LocalDate previousStart, LocalDate previousEnd,
        Step step) {

    public enum Step { HOUR, DAY, WEEK, MONTH }

    /** GA4 keeps nothing older than its launch. */
    static final LocalDate EARLIEST = LocalDate.of(2015, 8, 14);

    public static SeoPeriod of(String range, String from, String to, LocalDate today) {
        String r = range == null || range.isBlank() ? "7d" : range.trim().toLowerCase();
        return switch (r) {
            case "today" -> span("today", today, today);
            case "yesterday" -> span("yesterday", today.minusDays(1), today.minusDays(1));
            case "7d" -> span("7d", today.minusDays(7), today.minusDays(1));
            case "month" -> span("month", today.withDayOfMonth(1), today);
            case "year" -> span("year", today.withDayOfYear(1), today);
            case "custom" -> custom(from, to, today);
            default -> throw ApiException.badRequest("Unknown period: " + range);
        };
    }

    private static SeoPeriod custom(String from, String to, LocalDate today) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(from == null ? "" : from.trim());
            end = LocalDate.parse(to == null ? "" : to.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Choose a start and an end date.");
        }
        if (end.isBefore(start)) throw ApiException.badRequest("The end date is before the start date.");
        if (end.isAfter(today)) throw ApiException.badRequest("The end date is in the future.");
        if (start.isBefore(EARLIEST)) throw ApiException.badRequest("Google Analytics has no data before 14 Aug 2015.");
        if (ChronoUnit.DAYS.between(start, end) > 3 * 366) throw ApiException.badRequest("Choose at most three years.");
        return span("custom", start, end);
    }

    /** The period, the same number of days just before it, and the chart's step. */
    private static SeoPeriod span(String range, LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        Step step = days == 1 ? Step.HOUR : days <= 31 ? Step.DAY : days <= 124 ? Step.WEEK : Step.MONTH;
        // "This year" reads best by month even in its first weeks.
        if (range.equals("year") && days > 31) step = Step.MONTH;
        return new SeoPeriod(range, start, end, start.minusDays(days), start.minusDays(1), step);
    }

    public long days() {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    String cacheKey() {
        return range + "|" + start + "|" + end;
    }
}
