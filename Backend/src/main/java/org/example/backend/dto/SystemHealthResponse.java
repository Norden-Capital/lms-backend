package org.example.backend.dto;

import java.util.List;

/**
 * Everything the System Health screen shows, in one response.
 *
 * <p>One call rather than four, because the page draws them side by side and four
 * round trips would let the panels disagree about what moment they describe.
 *
 * <p>Nested to match the tabs, so the frontend does no regrouping and there is
 * one obvious place to add a field.
 */
public record SystemHealthResponse(
        Server server,
        Containers containers,
        Backend backend,
        Database database
) {

    /**
     * The machine itself.
     *
     * <p>Read from {@code /proc}, which — usefully — Docker does <em>not</em> put
     * in a namespace. The container therefore sees the host's real CPU, memory
     * and load with no mount and no privileges. The one exception is
     * {@code /proc/net}, which <em>is</em> namespaced — see
     * {@link #networkIsHost()}.
     *
     * @param cpuPercent      0–100 across all cores
     * @param memoryAvailable what is genuinely free — excludes the file cache,
     *                        which Linux counts as used and hands back on demand
     * @param uptimeSeconds   since the machine booted, not since the app started
     * @param networkIsHost   whether the two counters above are the machine's or
     *                        merely this container's. False is not a failure: it
     *                        is what happens without the mount, and the screen
     *                        says which one it is rather than guessing
     */
    public record Server(
            String os,
            int cores,
            double cpuPercent,
            long memoryTotal,
            long memoryAvailable,
            long diskTotal,
            long diskFree,
            double load1,
            double load5,
            double load15,
            int processes,
            long uptimeSeconds,
            long networkIn,
            long networkOut,
            boolean networkIsHost
    ) {
    }

    /**
     * The containers on the machine.
     *
     * <p>Docker is the only source for this, and talking to Docker means the
     * socket — which is root on the host, handed to a web application. So the
     * backend never touches it. A separate proxy holds the socket and republishes
     * a GET-only slice of the API on the internal network; this reads that.
     *
     * <p>Absent rather than empty when there is no endpoint: an empty list would
     * read as "no containers are running", which is a different and alarming
     * claim. {@code detail} carries the reason so the screen can say it out loud.
     */
    public record Containers(
            boolean available,
            String detail,
            List<Container> items
    ) {
        /**
         * @param cpuPercent  0–100 against the whole machine, the same basis as
         *                    {@code docker stats}
         * @param memoryLimit the cap set on the container, or the machine's total
         *                    when none is set — which is the case here
         * @param networkIn   cumulative bytes since the container started, not a
         *                    rate
         * @param restarts    how many times Docker has restarted it. Climbing is
         *                    the signal; the absolute number is not
         */
        public record Container(
                String id,
                String name,
                String image,
                String state,
                String status,
                String health,
                int restarts,
                long uptimeSeconds,
                double cpuPercent,
                long memoryUsed,
                long memoryLimit,
                long networkIn,
                long networkOut
        ) {
        }
    }

    /**
     * The application.
     *
     * <p>All of this comes from Micrometer, which Actuator already registers —
     * read straight from the registry rather than over the HTTP endpoint, so the
     * shape is ours and there is no second authentication hop.
     *
     * @param requestsPerMinute over the process's whole life, not a live rate —
     *                          see the service for why that is honest
     */
    public record Backend(
            long uptimeSeconds,
            long heapUsed,
            long heapMax,
            double processCpuPercent,
            long totalRequests,
            double requestsPerMinute,
            long errors5xx,
            long errors4xx,
            double error5xxRate,
            double p95Millis,
            double p99Millis,
            Pool pool
    ) {
        /** The database connection pool, which is HikariCP under the covers. */
        public record Pool(
                int active,
                int idle,
                int max,
                int pending,
                double waitMillis
        ) {
        }
    }

    /**
     * PostgreSQL.
     *
     * @param sizeBytes    the data itself, from {@code pg_database_size}
     * @param onDiskBytes  the whole directory — bigger, and the number that
     *                     actually fills a disk
     * @param cacheHitRatio 0–100
     */
    public record Database(
            long sizeBytes,
            long onDiskBytes,
            long walBytes,
            int activeConnections,
            int runningQueries,
            int maxConnections,
            double cacheHitRatio,
            long uptimeSeconds,
            boolean slowQueriesAvailable,
            List<Table> tables
    ) {
        /**
         * @param rows an estimate from the statistics collector, not a
         *             {@code COUNT(*)} — Postgres keeps no exact total, and asking
         *             for one means reading the whole table
         */
        public record Table(String name, long rows, long bytes) {
        }
    }
}
