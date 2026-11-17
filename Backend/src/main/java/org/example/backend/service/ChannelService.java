package org.example.backend.service;

import org.example.backend.dto.ApplicationDtos;
import org.example.backend.entity.ActionType;
import org.example.backend.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where leads come from — decided 2026-11-02.
 *
 * <p>A channel is the link's {@code utm_source} and nothing else: every channel
 * but SEO links straight to the apply form with its tag, and search visitors
 * reach the form from the brand site with none. So no tag means SEO. A source
 * nobody added on Configuration → Channels still counts as its own channel.
 *
 * <p>The funnel counts people, not clicks: a refresh, Previous, or applying five
 * times is still one person at each step. Visitors are counted once per browser,
 * brand and day.
 */
@Service
public class ChannelService {

    public static final String SEO = "seo";

    private static final int MAX_LENGTH = 40;
    private static final String NAME_RULE = "[a-z0-9][a-z0-9_-]*";

    private final JdbcTemplate db;
    private final ActionLogService actionLog;

    public ChannelService(JdbcTemplate db, ActionLogService actionLog) {
        this.db = db;
        this.actionLog = actionLog;
    }

    /** "Email " → "email". Anything unusable is dropped, so it reads as SEO. */
    public static String clean(String source) {
        if (source == null) return null;
        String name = source.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (name.length() > MAX_LENGTH) name = name.substring(0, MAX_LENGTH);
        return name.matches(NAME_RULE) ? name : null;
    }

    /** The channel of a step 1, from the tracking the form passed on. */
    public static String of(Map<String, String> extra) {
        String name = extra == null ? null : clean(extra.get("aff_utm_source"));
        return name == null ? SEO : name;
    }

    /**
     * One more person on the form today. A second visit the same day is still one
     * visitor (and keeps its first channel), but adds an opening for the Tries view.
     */
    @Transactional
    public void recordVisit(Long brandId, LocalDate day, String visitorId, String source) {
        String channel = clean(source);
        db.update("""
                INSERT INTO form_visits (day, brand_id, visitor_id, channel) VALUES (?, ?, ?, ?)
                ON CONFLICT (day, brand_id, visitor_id) DO UPDATE SET opens = form_visits.opens + 1""",
                Date.valueOf(day), brandId, visitorId, channel == null ? SEO : channel);
    }

    /** A step-1 save, for the Tries view. {@code channel} is this visit's own. */
    @Transactional
    public void recordStepOne(Long brandId, Long applicationId, String channel, String mode) {
        db.update("INSERT INTO form_steps (step, brand_id, application_id, channel, mode) VALUES (1, ?, ?, ?, ?)",
                brandId, applicationId, channel, mode);
    }

    /**
     * A step-2 click that was sent to LeadScout, for the Tries view. Its channel is
     * that of the step 1 just before it — the same visit — else the application's.
     */
    @Transactional
    public void recordStepTwo(Long brandId, Long applicationId, String fallbackChannel, String mode, boolean gotLink) {
        db.update("""
                INSERT INTO form_steps (step, brand_id, application_id, channel, mode, got_link)
                VALUES (2, ?, ?, COALESCE((SELECT s.channel FROM form_steps s
                                           WHERE s.application_id = ? AND s.step = 1
                                           ORDER BY s.created_at DESC, s.id DESC LIMIT 1), ?), ?, ?)""",
                brandId, applicationId, applicationId, fallbackChannel, mode, gotLink);
    }

    // ── Configuration → Channels ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<String> list() {
        return db.queryForList("SELECT name FROM marketing_channels ORDER BY name", String.class);
    }

    @Transactional
    public List<String> add(String input, Long actorId) {
        String name = clean(input);
        if (name == null || !name.equals(input == null ? "" : input.trim().toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("Use letters, numbers, - or _ only. Like: email");
        }
        if (SEO.equals(name)) throw ApiException.badRequest("SEO needs no link: no tag already means SEO.");
        if (list().contains(name)) throw ApiException.conflict(name + " is already a channel.");
        db.update("INSERT INTO marketing_channels (name) VALUES (?)", name);
        actionLog.record(ActionType.CHANNEL_ADDED, actorId, null, name);
        return list();
    }

    @Transactional
    public List<String> remove(String name, Long actorId) {
        if (db.update("DELETE FROM marketing_channels WHERE name = ?", name) == 0) {
            throw ApiException.notFound("Channel not found.");
        }
        actionLog.record(ActionType.CHANNEL_REMOVED, actorId, null, name);
        return list();
    }

    /**
     * This year's applies per channel and brand, counted like the Brands page:
     * forms sent in full (step 2 done, whatever LeadScout said), real ones only,
     * not tests. Channel → brand public id → count. Added 2026-11-10.
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Integer>> appliesThisYear() {
        Instant january = LocalDate.now(ZoneOffset.UTC).withDayOfYear(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        db.query("""
                SELECT a.channel, b.public_id, count(*)
                  FROM loan_applications a JOIN brands b ON b.id = a.brand_id
                 WHERE a.created_at >= ? AND a.mode = 'live' AND a.status IN ('SENDING', 'LEAD', 'FAILED')
                 GROUP BY a.channel, b.public_id""",
                row -> {
                    result.computeIfAbsent(row.getString(1), k -> new LinkedHashMap<>()).put(row.getString(2), row.getInt(3));
                },
                Timestamp.from(january));
        return result;
    }

    // ── Leads Overview ───────────────────────────────────────────────────

    public static final String PEOPLE = "people";
    public static final String TRIES = "tries";

    /**
     * Visitors and the three steps per channel, from {@code first} to {@code last}
     * (both days counted). Every added channel and SEO get a line, even with nothing yet.
     */
    @Transactional(readOnly = true)
    public ApplicationDtos.Funnel funnel(LocalDate first, LocalDate last, Instant from, Instant until,
                                         Collection<String> modes, Long brandId) {
        return funnel(first, last, from, until, modes, brandId, PEOPLE);
    }

    /**
     * {@code view} is {@link #PEOPLE} (each person once per step) or {@link #TRIES}
     * (every time — 2026-11-17). Tries are exact from {@code form_tries_start}; days
     * before it show the closest numbers there are: visits = visitors, step 1 =
     * applications, step 2 = sends to LeadScout, got link = applications with a link.
     */
    @Transactional(readOnly = true)
    public ApplicationDtos.Funnel funnel(LocalDate first, LocalDate last, Instant from, Instant until,
                                         Collection<String> modes, Long brandId, String view) {
        Map<String, int[]> lines = new LinkedHashMap<>();
        lines.put(SEO, new int[4]);
        list().forEach(name -> lines.put(name, new int[4]));
        int[] total = new int[4];

        String brandRule = brandId == null ? "" : " AND brand_id = " + brandId;
        String modeList = String.join(",", modes.stream().map(mode -> "'" + mode.replace("'", "") + "'").toList());
        boolean tries = TRIES.equals(view);
        Instant exactFrom = tries ? triesStart() : null;
        if (tries) {
            countTries(lines, total, from, until, exactFrom, modeList, brandId);
        } else {

        db.query("""
                SELECT channel,
                       count(DISTINCT client_id),
                       count(DISTINCT client_id) FILTER (WHERE status IN ('SENDING', 'LEAD', 'FAILED')),
                       count(DISTINCT client_id) FILTER (WHERE status = 'LEAD' AND redirect_url IS NOT NULL)
                  FROM loan_applications
                 WHERE created_at >= ? AND created_at < ? AND mode IN (%s)%s
                 GROUP BY GROUPING SETS ((channel), ())""".formatted(modeList, brandRule),
                row -> {
                    int[] line = row.getString(1) == null ? total : lines.computeIfAbsent(row.getString(1), k -> new int[4]);
                    line[1] = row.getInt(2);
                    line[2] = row.getInt(3);
                    line[3] = row.getInt(4);
                },
                Timestamp.from(from), Timestamp.from(until));
        }

        db.query("""
                SELECT channel, %s FROM form_visits
                 WHERE day >= ? AND day <= ?%s
                 GROUP BY GROUPING SETS ((channel), ())""".formatted(tries ? "sum(opens)" : "count(*)", brandRule),
                row -> {
                    int[] line = row.getString(1) == null ? total : lines.computeIfAbsent(row.getString(1), k -> new int[4]);
                    line[0] = row.getInt(2);
                },
                Date.valueOf(first), Date.valueOf(last));

        // Everyone on step 1 was on the form, even when their visit was not counted
        // (a blocked script, a form sent without the page). So visitors are never
        // fewer than step 1, and no step shows more than 100%.
        total[0] = Math.max(total[0], total[1]);
        lines.values().forEach(line -> line[0] = Math.max(line[0], line[1]));

        List<ApplicationDtos.ChannelCount> channels = new ArrayList<>();
        lines.forEach((name, line) -> channels.add(new ApplicationDtos.ChannelCount(name, line[0], line[1], line[2], line[3])));
        channels.sort(Comparator.comparingInt(ApplicationDtos.ChannelCount::step1).reversed()
                .thenComparing(ApplicationDtos.ChannelCount::channel));
        return new ApplicationDtos.Funnel(total[0], total[1], total[2], total[3], channels,
                tries ? TRIES : PEOPLE, exactFrom);
    }

    /** When the tries started to be recorded one by one (migration V22). */
    private Instant triesStart() {
        return db.queryForObject("SELECT started_at FROM form_tries_start", Timestamp.class).toInstant();
    }

    /** Steps 1–3 as tries: exact from {@code exactFrom}, the closest numbers before it. */
    private void countTries(Map<String, int[]> lines, int[] total, Instant from, Instant until, Instant exactFrom,
                            String modeList, Long brandId) {
        String brandRule = brandId == null ? "" : " AND brand_id = " + brandId;
        String appBrandRule = brandId == null ? "" : " AND a.brand_id = " + brandId;

        // Exact: one line per try.
        Instant newFrom = from.isAfter(exactFrom) ? from : exactFrom;
        if (newFrom.isBefore(until)) {
            db.query("""
                    SELECT channel,
                           count(*) FILTER (WHERE step = 1),
                           count(*) FILTER (WHERE step = 2),
                           count(*) FILTER (WHERE step = 2 AND got_link)
                      FROM form_steps
                     WHERE created_at >= ? AND created_at < ? AND mode IN (%s)%s
                     GROUP BY GROUPING SETS ((channel), ())""".formatted(modeList, brandRule),
                    row -> { add(lines, total, row.getString(1), 1, row.getInt(2), row.getInt(3), row.getInt(4)); },
                    Timestamp.from(newFrom), Timestamp.from(until));
        }

        // Before that: applications (step 1 and got link) and LeadScout sends (step 2).
        Instant oldUntil = until.isBefore(exactFrom) ? until : exactFrom;
        if (from.isBefore(oldUntil)) {
            db.query("""
                    SELECT channel,
                           count(*),
                           count(*) FILTER (WHERE status = 'LEAD' AND redirect_url IS NOT NULL)
                      FROM loan_applications
                     WHERE created_at >= ? AND created_at < ? AND mode IN (%s)%s
                     GROUP BY GROUPING SETS ((channel), ())""".formatted(modeList, brandRule),
                    row -> { add(lines, total, row.getString(1), 1, row.getInt(2), 0, row.getInt(3)); },
                    Timestamp.from(from), Timestamp.from(oldUntil));
            db.query("""
                    SELECT a.channel, count(*)
                      FROM application_events e JOIN loan_applications a ON a.id = e.application_id
                     WHERE e.created_at >= ? AND e.created_at < ? AND a.mode IN (%s)%s
                     GROUP BY GROUPING SETS ((a.channel), ())""".formatted(modeList, appBrandRule),
                    row -> { add(lines, total, row.getString(1), 2, row.getInt(2)); },
                    Timestamp.from(from), Timestamp.from(oldUntil));
        }
    }

    /** Adds counts to a channel's line (or the total, for the grouping's empty set), from {@code column} on. */
    private static void add(Map<String, int[]> lines, int[] total, String channel, int column, int... counts) {
        int[] line = channel == null ? total : lines.computeIfAbsent(channel, k -> new int[4]);
        for (int i = 0; i < counts.length; i++) line[column + i] += counts[i];
    }
}
