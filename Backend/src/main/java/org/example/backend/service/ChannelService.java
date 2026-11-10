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

    /** One more person on the form today. A second visit the same day changes nothing. */
    @Transactional
    public void recordVisit(Long brandId, LocalDate day, String visitorId, String source) {
        String channel = clean(source);
        db.update("""
                INSERT INTO form_visits (day, brand_id, visitor_id, channel) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING""",
                Date.valueOf(day), brandId, visitorId, channel == null ? SEO : channel);
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

    /**
     * Visitors and the three steps per channel, from {@code first} to {@code last}
     * (both days counted). Every added channel and SEO get a line, even with nothing yet.
     */
    @Transactional(readOnly = true)
    public ApplicationDtos.Funnel funnel(LocalDate first, LocalDate last, Instant from, Instant until,
                                         Collection<String> modes, Long brandId) {
        Map<String, int[]> lines = new LinkedHashMap<>();
        lines.put(SEO, new int[4]);
        list().forEach(name -> lines.put(name, new int[4]));
        int[] total = new int[4];

        String brandRule = brandId == null ? "" : " AND brand_id = " + brandId;
        String modeList = String.join(",", modes.stream().map(mode -> "'" + mode.replace("'", "") + "'").toList());

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

        db.query("""
                SELECT channel, count(*) FROM form_visits
                 WHERE day >= ? AND day <= ?%s
                 GROUP BY GROUPING SETS ((channel), ())""".formatted(brandRule),
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
        return new ApplicationDtos.Funnel(total[0], total[1], total[2], total[3], channels);
    }
}
