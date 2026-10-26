package org.example.backend.seo;

import org.example.backend.entity.Brand;
import org.example.backend.repository.BrandRepository;
import org.example.backend.exception.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SEO Overview: what organic search brings a brand, read live from its Google
 * Analytics 4 property.
 *
 * <p><b>Nothing is stored.</b> Each answer is kept ten minutes in memory (per brand
 * and range), so opening the page again or switching back and forth costs nothing,
 * and the GA4 quota is never near its limit.
 *
 * <p><b>Every visitor, like the GA4 home page</b> (decided 2026-10-05): sessions, users,
 * engagement, the chart, countries, devices and landing pages count all traffic. The
 * search engines are organic by nature ({@code sessionDefaultChannelGroup = "Organic
 * Search"}); the chart and the totals also carry organic and direct separately, for
 * the chart's All / Organic / Direct tabs. Compared with the period just before.
 */
@Service
public class SeoService {

    static final long CACHE_MILLIS = 10 * 60 * 1000;
    private static final DateTimeFormatter GA_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** {@code id} is the brand's public id (BR-…), like everywhere else in the API. */
    public record SeoBrand(String id, String name, String logoUrl, String propertyId) {
    }

    /** Every visitor, and the ones from search (organic) and typed-in or bookmarked (direct), for the chart's tabs. */
    public record TrafficPoint(String start, long sessions, long users, long organicSessions, long organicUsers,
            long directSessions, long directUsers) {
    }

    public record ChannelTotals(long sessions, long previousSessions, long users, long previousUsers) {
    }

    public record Totals(long sessions, long previousSessions, long users, long previousUsers,
            double engagementRate, double previousEngagementRate, long keyEvents, long previousKeyEvents,
            double averageEngagementSeconds, double previousAverageEngagementSeconds,
            ChannelTotals organic, ChannelTotals direct) {
    }

    public record Channel(String channel, long sessions) {
    }

    public record LandingPage(String path, long sessions, double engagementRate, long keyEvents) {
    }

    public record Engine(String source, long sessions) {
    }

    /** {@code sessions}: every visitor (like the GA4 home page); {@code organicSessions}: the ones from search. */
    public record Country(String country, String countryId, long sessions, long organicSessions) {
    }

    public record Device(String device, long sessions) {
    }

    /**
     * {@code range}: today, yesterday, 7d, month, year or custom; {@code start}/{@code end} the dates
     * it covers; {@code step} the chart's step (HOUR, DAY, WEEK, MONTH).
     */
    public record Overview(SeoBrand brand, String range, String start, String end, String step, Instant fetchedAt,
            List<TrafficPoint> traffic, Totals totals,
            List<Channel> channels, List<LandingPage> landingPages, long landingTailSessions, List<Engine> engines,
            List<Country> countries, List<Device> devices) {
    }

    private record Cached(long until, Overview overview) {
    }

    private final Ga4Client ga4;
    private final BrandRepository brands;
    private final ZoneId zone;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SeoService(Ga4Client ga4, BrandRepository brands,
            @org.springframework.beans.factory.annotation.Value("${app.seo.timezone:America/Toronto}") String timezone) {
        this.ga4 = ga4;
        this.brands = brands;
        this.zone = ZoneId.of(timezone);
    }

    /** Switched-on brands with a GA4 property, by name: the brand switch on the page. */
    public List<SeoBrand> brands() {
        return brands.findAllByActiveTrueOrderByNameAscEmailAsc().stream()
            .filter(b -> b.getGa4PropertyId() != null)
            .map(SeoService::view)
            .toList();
    }

    public Overview overview(String brandId, String range, String from, String to) {
        SeoPeriod period = SeoPeriod.of(range, from, to, LocalDate.now(zone));
        Brand brand = brands.findByPublicId(brandId == null ? "" : brandId)
            .orElseThrow(() -> ApiException.notFound("No such brand."));
        // Same rule as the brand list above — added 2026-10-26. Checked before
        // the cache too, so switching a brand off stops it at once.
        if (!brand.isActive()) {
            throw ApiException.notFound("This brand is switched off. Turn it on in Brands.");
        }
        if (brand.getGa4PropertyId() == null) {
            throw ApiException.badRequest("This brand has no Google Analytics ID yet. Add it on Brands.");
        }
        String key = brandId + "|" + brand.getGa4PropertyId() + "|" + period.cacheKey();
        Cached hit = cache.get(key);
        if (hit != null && hit.until() > System.currentTimeMillis()) return hit.overview();

        Overview fresh = fetch(brand, period);
        cache.put(key, new Cached(System.currentTimeMillis() + CACHE_MILLIS, fresh));
        return fresh;
    }

    private Overview fetch(Brand brand, SeoPeriod period) {
        String[] current = {period.start().toString(), period.end().toString()};
        String[] previous = {period.previousStart().toString(), period.previousEnd().toString()};
        String stepDimension = switch (period.step()) {
            case HOUR -> "hour";
            case DAY -> "date";
            case WEEK -> "nthWeek";
            case MONTH -> "yearMonth";
        };
        String property = brand.getGa4PropertyId();

        List<JsonNode> first = ga4.batch(property, List.of(
            report(List.of(stepDimension), List.of("sessions", "totalUsers"), only(current), ALL, null, null),
            report(List.of(stepDimension, "sessionDefaultChannelGroup"), List.of("sessions", "totalUsers"), only(current), ORGANIC_AND_DIRECT, null, null),
            report(List.of(), List.of("sessions", "totalUsers", "engagementRate", "keyEvents", "userEngagementDuration", "activeUsers"),
                List.of(current, previous), ALL, null, null),
            report(List.of("sessionDefaultChannelGroup"), List.of("sessions", "totalUsers"), List.of(current, previous), ORGANIC_AND_DIRECT, null, null),
            report(List.of("landingPage"), List.of("sessions", "engagementRate", "keyEvents"), only(current), ALL, "sessions", 5)));
        List<JsonNode> second = ga4.batch(property, List.of(
            report(List.of("sessionDefaultChannelGroup"), List.of("sessions"), only(current), ALL, "sessions", 8),
            report(List.of("sessionSource"), List.of("sessions"), only(current), ORGANIC, "sessions", 5),
            report(List.of("countryId", "country"), List.of("sessions"), only(current), ALL, "sessions", 10),
            report(List.of("countryId"), List.of("sessions"), only(current), ORGANIC, "sessions", 250),
            report(List.of("deviceCategory"), List.of("sessions"), only(current), ALL, "sessions", null)));

        Totals totals = totals(first.get(2), first.get(3));
        List<LandingPage> pages = new ArrayList<>();
        for (JsonNode row : rows(first.get(4))) {
            pages.add(new LandingPage(dim(row, 0), longMetric(row, 0), percent(row, 1), longMetric(row, 2)));
        }
        long pageSessions = pages.stream().mapToLong(LandingPage::sessions).sum();

        List<Engine> engines = new ArrayList<>();
        for (JsonNode row : rows(second.get(1))) engines.add(new Engine(dim(row, 0), longMetric(row, 0)));
        long engineSessions = engines.stream().mapToLong(Engine::sessions).sum();
        // The engines add up to the organic total: whatever the top five leave is "other".
        long organicSessions = totals.organic().sessions();
        if (organicSessions > engineSessions && !engines.isEmpty()) engines.add(new Engine("other", organicSessions - engineSessions));

        List<Channel> channels = new ArrayList<>();
        for (JsonNode row : rows(second.get(0))) channels.add(new Channel(dim(row, 0), longMetric(row, 0)));
        // Organic first, the channel this page is about; the rest largest-first.
        channels.sort((a, b) -> a.channel().equals("Organic Search") ? -1 : b.channel().equals("Organic Search") ? 1
            : Long.compare(b.sessions(), a.sessions()));

        Map<String, Long> organicByCountry = new LinkedHashMap<>();
        for (JsonNode row : rows(second.get(3))) organicByCountry.put(dim(row, 0), longMetric(row, 0));
        List<Country> countries = new ArrayList<>();
        for (JsonNode row : rows(second.get(2))) {
            String id = dim(row, 0);
            if (!"(not set)".equals(id)) {
                countries.add(new Country(dim(row, 1), id, longMetric(row, 0), organicByCountry.getOrDefault(id, 0L)));
            }
        }
        List<Device> devices = new ArrayList<>();
        for (JsonNode row : rows(second.get(4))) devices.add(new Device(dim(row, 0), longMetric(row, 0)));

        return new Overview(view(brand), period.range(), period.start().toString(), period.end().toString(), period.step().name(),
            Instant.now(), traffic(first.get(0), first.get(1), period), totals, channels,
            pages, Math.max(0, totals.sessions() - pageSessions), engines, countries, devices);
    }

    /** Every hour, day, week or month of the period, with zeros where GA4 had nothing. */
    private static List<TrafficPoint> traffic(JsonNode all, JsonNode byChannel, SeoPeriod period) {
        // key → {sessions, users, organic sessions, organic users, direct sessions, direct users}
        Map<String, long[]> byKey = new LinkedHashMap<>();
        for (JsonNode row : rows(all)) {
            byKey.computeIfAbsent(dim(row, 0), k -> new long[6])[0] = longMetric(row, 0);
            byKey.get(dim(row, 0))[1] = longMetric(row, 1);
        }
        for (JsonNode row : rows(byChannel)) {
            long[] v = byKey.computeIfAbsent(dim(row, 0), k -> new long[6]);
            int at = "Organic Search".equals(dim(row, 1)) ? 2 : "Direct".equals(dim(row, 1)) ? 4 : -1;
            if (at < 0) continue;
            v[at] = longMetric(row, 0);
            v[at + 1] = longMetric(row, 1);
        }
        List<TrafficPoint> points = new ArrayList<>();
        switch (period.step()) {
            case HOUR -> {
                for (int h = 0; h < 24; h++) {
                    long[] v = byKey.getOrDefault(String.format("%02d", h), new long[6]);
                    points.add(new TrafficPoint(period.start() + "T" + String.format("%02d", h) + ":00", v[0], v[1], v[2], v[3], v[4], v[5]));
                }
            }
            case DAY -> {
                for (LocalDate day = period.start(); !day.isAfter(period.end()); day = day.plusDays(1)) {
                    long[] v = byKey.getOrDefault(day.format(GA_DATE), new long[6]);
                    points.add(new TrafficPoint(day.toString(), v[0], v[1], v[2], v[3], v[4], v[5]));
                }
            }
            case WEEK -> {
                long weeks = (period.days() + 6) / 7;
                for (int n = 0; n < weeks; n++) {
                    long[] v = byKey.getOrDefault(String.format("%04d", n), new long[6]);
                    points.add(new TrafficPoint(period.start().plusDays(7L * n).toString(), v[0], v[1], v[2], v[3], v[4], v[5]));
                }
            }
            case MONTH -> {
                for (LocalDate month = period.start().withDayOfMonth(1); !month.isAfter(period.end()); month = month.plusMonths(1)) {
                    long[] v = byKey.getOrDefault(month.format(DateTimeFormatter.ofPattern("yyyyMM")), new long[6]);
                    LocalDate shown = month.isBefore(period.start()) ? period.start() : month;
                    points.add(new TrafficPoint(shown.toString(), v[0], v[1], v[2], v[3], v[4], v[5]));
                }
            }
        }
        return points;
    }

    /** Two date ranges: GA4 adds a "dateRange" column (date_range_0 = current, date_range_1 = previous). */
    private static Totals totals(JsonNode report, JsonNode byChannel) {
        double[] now = new double[6];
        double[] before = new double[6];
        for (JsonNode row : rows(report)) {
            double[] target = "date_range_1".equals(dim(row, 0)) ? before : now;
            for (int i = 0; i < 6; i++) target[i] = doubleMetric(row, i);
        }
        // channel → {sessions, previous sessions, users, previous users}
        Map<String, long[]> channel = new LinkedHashMap<>();
        for (JsonNode row : rows(byChannel)) {
            long[] v = channel.computeIfAbsent(dim(row, 0), k -> new long[4]);
            boolean previous = "date_range_1".equals(dim(row, 1));
            v[previous ? 1 : 0] = longMetric(row, 0);
            v[previous ? 3 : 2] = longMetric(row, 1);
        }
        long[] o = channel.getOrDefault("Organic Search", new long[4]);
        long[] d = channel.getOrDefault("Direct", new long[4]);
        return new Totals((long) now[0], (long) before[0], (long) now[1], (long) before[1],
            oneDecimal(now[2] * 100), oneDecimal(before[2] * 100), (long) now[3], (long) before[3],
            now[5] == 0 ? 0 : oneDecimal(now[4] / now[5]), before[5] == 0 ? 0 : oneDecimal(before[4] / before[5]),
            new ChannelTotals(o[0], o[1], o[2], o[3]), new ChannelTotals(d[0], d[1], d[2], d[3]));
    }

    /** Which sessions a report counts. */
    private static final int ALL = 0;
    private static final int ORGANIC = 1;
    private static final int ORGANIC_AND_DIRECT = 2;

    private ObjectNode report(List<String> dimensions, List<String> metrics, List<String[]> ranges, int filter,
            String orderBy, Integer limit) {
        ObjectNode r = ga4.json().createObjectNode();
        ArrayNode dateRanges = r.putArray("dateRanges");
        for (String[] range : ranges) {
            dateRanges.addObject().put("startDate", range[0]).put("endDate", range[1]);
        }
        ArrayNode dims = r.putArray("dimensions");
        dimensions.forEach(d -> dims.addObject().put("name", d));
        ArrayNode mets = r.putArray("metrics");
        metrics.forEach(m -> mets.addObject().put("name", m));
        if (filter != ALL) {
            ObjectNode f = r.putObject("dimensionFilter").putObject("filter");
            f.put("fieldName", "sessionDefaultChannelGroup");
            if (filter == ORGANIC) {
                f.putObject("stringFilter").put("value", "Organic Search");
            } else {
                ArrayNode values = f.putObject("inListFilter").putArray("values");
                values.add("Organic Search");
                values.add("Direct");
            }
        }
        if (orderBy != null) {
            r.putArray("orderBys").addObject().put("desc", true).putObject("metric").put("metricName", orderBy);
        }
        if (limit != null) r.put("limit", limit);
        return r;
    }

    /** One date range. (List.of(array) would unpack the array.) */
    private static List<String[]> only(String[] range) {
        List<String[]> list = new ArrayList<>();
        list.add(range);
        return list;
    }

    private static Iterable<JsonNode> rows(JsonNode report) {
        JsonNode rows = report == null ? null : report.path("rows");
        return rows != null && rows.isArray() ? rows.valueStream().toList() : List.of();
    }

    private static String dim(JsonNode row, int index) {
        return row.path("dimensionValues").path(index).path("value").asString("");
    }

    private static double doubleMetric(JsonNode row, int index) {
        try {
            return Double.parseDouble(row.path("metricValues").path(index).path("value").asString("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longMetric(JsonNode row, int index) {
        return Math.round(doubleMetric(row, index));
    }

    /** GA4 gives rates as 0.584; the page shows 58.4 (%). One decimal. */
    private static double percent(JsonNode row, int index) {
        return oneDecimal(doubleMetric(row, index) * 100);
    }

    private static double oneDecimal(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static SeoBrand view(Brand b) {
        return new SeoBrand(b.getPublicId(), b.getName(), b.getLogoUrl(), b.getGa4PropertyId());
    }
}
