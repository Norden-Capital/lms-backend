package org.example.backend.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.example.backend.dto.SystemHealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where the numbers on the System Health screen come from.
 *
 * <p>Three sources, and none of them needs anything installed:
 *
 * <ul>
 *   <li><b>The machine</b> — {@code /proc}. Docker does not put it in a
 *       namespace, so a container reads the host's real CPU, memory and load with
 *       no mount and no extra privilege. {@code /proc/net} is the exception: that
 *       one <em>is</em> namespaced, so those counters are this container's
 *       traffic.</li>
 *   <li><b>The application</b> — Micrometer, which Actuator already registers.
 *       Read from the registry directly rather than over the HTTP endpoint: the
 *       shape stays ours, and there is no second round of authentication.</li>
 *   <li><b>The database</b> — plain SQL against the statistics views Postgres
 *       keeps anyway.</li>
 * </ul>
 *
 * <p>Every reader below fails soft. A metrics screen that throws because one
 * counter was unreadable is worse than one showing a zero next to nine real
 * figures — and on a machine that is genuinely in trouble, the unreadable ones
 * are exactly what you would lose.
 */
@Service
public class SystemMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsService.class);

    /** Linux reports memory in kB; everything on the wire is bytes. */
    private static final long KB = 1024L;

    private final MeterRegistry meters;
    private final JdbcTemplate jdbc;

    /**
     * The previous CPU reading, so a percentage can be worked out.
     *
     * <p>{@code /proc/stat} counts ticks since boot, so a single reading gives the
     * average since the machine started — which is never what anybody means. The
     * useful number is the change between two readings, so the last one is kept.
     */
    private long[] previousCpu;

    public SystemMetricsService(MeterRegistry meters, JdbcTemplate jdbc) {
        this.meters = meters;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SystemHealthResponse snapshot() {
        return new SystemHealthResponse(server(), backend(), database());
    }

    // ── The machine ─────────────────────────────────────────────────────────

    private SystemHealthResponse.Server server() {
        Map<String, Long> memory = readMeminfo();
        double[] load = readLoadAverage();
        long[] network = readNetwork();
        File root = new File("/");

        return new SystemHealthResponse.Server(
                readOsName(),
                Runtime.getRuntime().availableProcessors(),
                readCpuPercent(),
                memory.getOrDefault("MemTotal", 0L) * KB,
                memory.getOrDefault("MemAvailable", 0L) * KB,
                root.getTotalSpace(),
                root.getUsableSpace(),
                load[0],
                load[1],
                load[2],
                (int) load[3],
                readUptimeSeconds(),
                network[0],
                network[1]
        );
    }

    /**
     * The machine's distribution, not the container's.
     *
     * <p>{@code /etc/os-release} is one of the few things Docker <em>does</em>
     * namespace, so reading it directly names the base image — Alpine — rather
     * than the host. Compose mounts the host's copy read-only at
     * {@code /host/etc/os-release}, which is tried first; without it this falls
     * back and is honest about being the container's.
     */
    private String readOsName() {
        for (String path : List.of(
                "/host/etc/os-release", "/etc/os-release", "/usr/lib/os-release")) {
            try {
                for (String line : Files.readAllLines(Path.of(path))) {
                    if (line.startsWith("PRETTY_NAME=")) {
                        return line.substring(12).replace("\"", "").trim();
                    }
                }
            } catch (IOException ignored) {
                // Next candidate, then the fallback below.
            }
        }
        return System.getProperty("os.name", "Unknown");
    }

    /**
     * CPU in use, as a percentage of every core.
     *
     * <p>Worked out from the change in idle ticks against the change in total
     * ticks. The very first call has nothing to compare against and returns zero
     * rather than a made-up figure — one blank reading is better than a wrong one.
     */
    private double readCpuPercent() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).getFirst();
            String[] parts = line.trim().split("\\s+");

            long idle = Long.parseLong(parts[4]) + Long.parseLong(parts[5]);
            long total = 0;
            for (int i = 1; i < parts.length; i++) total += Long.parseLong(parts[i]);

            long[] current = {total, idle};

            if (previousCpu == null) {
                previousCpu = current;
                return 0;
            }

            long totalDelta = total - previousCpu[0];
            long idleDelta = idle - previousCpu[1];
            previousCpu = current;

            if (totalDelta <= 0) return 0;
            return Math.max(0, Math.min(100, 100.0 * (totalDelta - idleDelta) / totalDelta));
        } catch (Exception e) {
            log.debug("Could not read /proc/stat", e);
            return 0;
        }
    }

    private Map<String, Long> readMeminfo() {
        Map<String, Long> values = new java.util.HashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                String[] parts = line.split(":\\s+");
                if (parts.length < 2) continue;
                values.put(parts[0], Long.parseLong(parts[1].replace(" kB", "").trim()));
            }
        } catch (Exception e) {
            log.debug("Could not read /proc/meminfo", e);
        }
        return values;
    }

    /** 1, 5 and 15 minute averages, then the process count. */
    private double[] readLoadAverage() {
        try {
            String[] parts = Files.readString(Path.of("/proc/loadavg")).trim().split("\\s+");
            // The fourth field is "running/total"; the total is what is wanted.
            int processes = Integer.parseInt(parts[3].split("/")[1]);
            return new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    processes
            };
        } catch (Exception e) {
            log.debug("Could not read /proc/loadavg", e);
            return new double[]{0, 0, 0, 0};
        }
    }

    private long readUptimeSeconds() {
        try {
            return (long) Double.parseDouble(
                    Files.readString(Path.of("/proc/uptime")).trim().split("\\s+")[0]);
        } catch (Exception e) {
            log.debug("Could not read /proc/uptime", e);
            return 0;
        }
    }

    /**
     * Bytes in and out, skipping loopback.
     *
     * <p>⚠️ {@code /proc/net} <em>is</em> namespaced, so this is the container's
     * own interface — the application's traffic, not the machine's. Reporting it
     * as the VPS's bandwidth would be wrong.
     */
    private long[] readNetwork() {
        long in = 0;
        long out = 0;
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/net/dev"));
            for (String line : lines.subList(Math.min(2, lines.size()), lines.size())) {
                String[] parts = line.trim().split("[:\\s]+");
                if (parts.length < 10 || parts[0].equals("lo")) continue;
                in += Long.parseLong(parts[1]);
                out += Long.parseLong(parts[9]);
            }
        } catch (Exception e) {
            log.debug("Could not read /proc/net/dev", e);
        }
        return new long[]{in, out};
    }

    // ── The application ─────────────────────────────────────────────────────

    private SystemHealthResponse.Backend backend() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        long total = 0;
        long errors5xx = 0;
        long errors4xx = 0;
        double p95 = 0;
        double p99 = 0;

        for (Timer timer : meters.find("http.server.requests").timers()) {
            total += timer.count();

            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("5")) errors5xx += timer.count();
            if (status != null && status.startsWith("4")) errors4xx += timer.count();

            // Percentiles are per-timer; the worst across them is the honest
            // headline, since an average of percentiles means nothing.
            for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
                double millis = v.value(java.util.concurrent.TimeUnit.MILLISECONDS);
                if (v.percentile() == 0.95) p95 = Math.max(p95, millis);
                if (v.percentile() == 0.99) p99 = Math.max(p99, millis);
            }
        }

        // Requests since the process started, averaged over its life. Deliberately
        // not called a live rate: Micrometer's counters are cumulative, and a real
        // per-minute figure needs sampling this endpoint over time — which is the
        // browser's job, since it is the one polling.
        double perMinute = uptimeSeconds > 0 ? total / (uptimeSeconds / 60.0) : 0;

        return new SystemHealthResponse.Backend(
                uptimeSeconds,
                memory.getHeapMemoryUsage().getUsed(),
                memory.getHeapMemoryUsage().getMax(),
                gauge("process.cpu.usage") * 100,
                total,
                perMinute,
                errors5xx,
                errors4xx,
                total > 0 ? (100.0 * errors5xx / total) : 0,
                p95,
                p99,
                new SystemHealthResponse.Backend.Pool(
                        (int) gauge("hikaricp.connections.active"),
                        (int) gauge("hikaricp.connections.idle"),
                        (int) gauge("hikaricp.connections.max"),
                        (int) gauge("hikaricp.connections.pending"),
                        timerMeanMillis("hikaricp.connections.acquire")
                )
        );
    }

    /** A gauge's value, or zero if it was never registered. */
    private double gauge(String name) {
        var found = meters.find(name).gauge();
        return found == null || Double.isNaN(found.value()) ? 0 : found.value();
    }

    private double timerMeanMillis(String name) {
        var found = meters.find(name).timer();
        return found == null ? 0 : found.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ── The database ────────────────────────────────────────────────────────

    private SystemHealthResponse.Database database() {
        long size = scalar("SELECT pg_database_size(current_database())", 0L);

        // The whole cluster directory, which includes the write-ahead log and the
        // databases Postgres keeps for itself. Bigger than the figure above, and
        // the one that actually fills a disk.
        long onDisk = scalar("""
                SELECT sum(pg_database_size(datname))::bigint + COALESCE(
                    (SELECT sum(size)::bigint FROM pg_ls_waldir()), 0)
                FROM pg_database""", 0L);

        long wal = scalar("SELECT COALESCE(sum(size), 0)::bigint FROM pg_ls_waldir()", 0L);

        int active = scalar(
                "SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database()", 0);
        int running = scalar("""
                SELECT count(*)::int FROM pg_stat_activity
                WHERE datname = current_database() AND state = 'active'""", 0);
        int max = scalar("SELECT setting::int FROM pg_settings WHERE name = 'max_connections'", 100);

        // Hits over hits plus disk reads. NULLIF guards a database nobody has read
        // yet, where both are zero and the division would fail.
        double hitRatio = scalar("""
                SELECT COALESCE(
                    100.0 * sum(blks_hit) / NULLIF(sum(blks_hit) + sum(blks_read), 0),
                    100.0)
                FROM pg_stat_database WHERE datname = current_database()""", 100.0);

        long uptime = scalar(
                "SELECT EXTRACT(EPOCH FROM now() - pg_postmaster_start_time())::bigint", 0L);

        boolean slowQueries = scalar("""
                SELECT count(*)::int FROM pg_extension WHERE extname = 'pg_stat_statements'""", 0) > 0;

        return new SystemHealthResponse.Database(
                size, onDisk, wal, active, running, max, hitRatio, uptime, slowQueries, tables());
    }

    private List<SystemHealthResponse.Database.Table> tables() {
        try {
            return jdbc.query("""
                    SELECT relname,
                           n_live_tup,
                           pg_total_relation_size(relid) AS bytes
                    FROM pg_stat_user_tables
                    ORDER BY pg_total_relation_size(relid) DESC, relname
                    """,
                    (rs, row) -> new SystemHealthResponse.Database.Table(
                            rs.getString("relname"),
                            rs.getLong("n_live_tup"),
                            rs.getLong("bytes")));
        } catch (Exception e) {
            log.debug("Could not list tables", e);
            return new ArrayList<>();
        }
    }

    /** One value, or the fallback if the query fails. Never throws. */
    @SuppressWarnings("unchecked")
    private <T> T scalar(String sql, T fallback) {
        try {
            T value = (T) jdbc.queryForObject(sql, fallback.getClass());
            return value == null ? fallback : value;
        } catch (Exception e) {
            log.debug("Metric query failed: {}", sql.lines().findFirst().orElse(sql), e);
            return fallback;
        }
    }
}
