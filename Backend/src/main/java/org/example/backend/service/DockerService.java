package org.example.backend.service;

import org.example.backend.dto.SystemHealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What the containers on the machine are doing.
 *
 * <p><b>The backend never touches the Docker socket.</b> Handing
 * {@code /var/run/docker.sock} to a web application is handing it root on the
 * host — anything that can talk to that socket can start a privileged container
 * and own the machine. One remote-code-execution bug in here would go from
 * "reads some metrics" to "owns the server" with no further steps.
 *
 * <p>So a separate container holds the socket and republishes a filtered,
 * GET-only slice of the Docker API on the internal compose network. This class
 * speaks plain HTTP to that. The proxy answers {@code 403} to every write —
 * restart, exec, image pull — which was verified against the running VPS rather
 * than assumed. Nothing is published to the host, so the only thing that can
 * reach it is a container on the same network.
 *
 * <p>Unreachable is a normal state, not an error: local development usually has
 * no proxy running. Every path below returns "unavailable, and here is why"
 * rather than throwing, and the screen says so instead of showing zeros.
 */
@Service
public class DockerService {

    private static final Logger log = LoggerFactory.getLogger(DockerService.class);

    /**
     * Short on purpose.
     *
     * <p>This sits inside a request a browser makes every five seconds. A Docker
     * daemon that has stopped answering must fail faster than the next poll
     * arrives, or the health screen queues up behind a thing it is trying to
     * report on.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;

    /**
     * The previous CPU counters per container, so a percentage can be worked out.
     *
     * <p>Docker reports CPU as nanoseconds consumed since the container started.
     * A single reading is therefore the average over its whole life, which for
     * something up two weeks is always near zero and never what anybody means.
     * The useful figure is the change between two readings — the same reason
     * {@link SystemMetricsService} keeps a previous {@code /proc/stat}.
     *
     * <p>Concurrent because two administrators with the page open poll
     * independently. That makes the interval between readings irregular, which
     * does not matter: the result is a ratio of two deltas measured over the same
     * interval, so it comes out right whatever that interval was.
     */
    private final Map<String, long[]> previousCpu = new ConcurrentHashMap<>();

    public DockerService(
            @Value("${docker.api.url:}") String baseUrl,
            ObjectMapper json
    ) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public SystemHealthResponse.Containers read() {
        if (baseUrl.isEmpty()) {
            return unavailable("No Docker endpoint is configured.");
        }

        JsonNode list;
        try {
            list = get("/containers/json?all=true");
        } catch (Exception e) {
            // One failed call, then give up for this poll. Carrying on would mean
            // one timeout per container, and four of those outlast the poll
            // interval — the page would fall progressively further behind.
            log.debug("Could not list containers from {}", baseUrl, e);
            return unavailable("Could not reach Docker at " + baseUrl + ".");
        }

        List<JsonNode> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (JsonNode node : list) {
            String id = node.path("Id").asString("");
            if (id.isEmpty()) continue;

            seen.add(id);
            found.add(node);
        }

        List<SystemHealthResponse.Containers.Container> items = describeAll(found);

        // Containers get removed and replaced on every deploy. Without this the
        // map grows for the life of the process, one dead entry at a time.
        previousCpu.keySet().retainAll(seen);

        // By name, and only by name.
        //
        // Busiest-first sounds better and is worse: CPU moves every poll, so the
        // cards swap places under the pointer every five seconds. Watching one
        // container means finding it again each time, and a card you are reading
        // sliding sideways is the kind of thing that makes a page feel broken.
        // Docker's own ordering is by creation time and changes on every deploy,
        // which is no more stable.
        items.sort(Comparator.comparing(SystemHealthResponse.Containers.Container::name));

        return new SystemHealthResponse.Containers(true, null, items);
    }

    /**
     * Every container at once, rather than one after another.
     *
     * <p>Each needs two more calls, and done in sequence a development machine
     * with a dozen containers on it spent 400ms of a request that used to take
     * 50. That matters more than it sounds: this endpoint is polled every five
     * seconds, so it would become the slowest thing in the application and the
     * Backend tab would report the health page as the performance problem.
     *
     * <p>Virtual threads, so a container costs a few hundred bytes rather than a
     * platform thread — the work is entirely waiting on a socket, which is what
     * they are for. Total time becomes the slowest single container instead of
     * the sum of all of them.
     */
    private List<SystemHealthResponse.Containers.Container> describeAll(List<JsonNode> summaries) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SystemHealthResponse.Containers.Container>> pending = summaries.stream()
                    .map(summary -> pool.submit(() -> describe(summary)))
                    .toList();

            List<SystemHealthResponse.Containers.Container> items = new ArrayList<>();
            for (Future<SystemHealthResponse.Containers.Container> future : pending) {
                try {
                    items.add(future.get());
                } catch (Exception e) {
                    // One container's detail, not the tab. Every call inside
                    // describe already fails soft, so reaching here means
                    // something unexpected — worth dropping the row over, not
                    // the page.
                    log.debug("Could not describe a container", e);
                }
            }
            return items;
        }
    }

    /** One container, from the list entry plus its own endpoints. */
    private SystemHealthResponse.Containers.Container describe(JsonNode summary) {
        String id = summary.path("Id").asString("");
        String state = summary.path("State").asString("unknown");

        // Stopped containers consume nothing and have nothing to report, so they
        // cost no round trips: the list entry already carries Docker's own
        // sentence about them, which says more than a row of zeros would. Only
        // what is running — or trying to — gets asked.
        boolean live = state.equals("running") || state.equals("restarting");

        JsonNode inspect = live
                ? getQuietly("/containers/" + id + "/json")
                : MissingNode.getInstance();

        // one-shot skips Docker's built-in second sample. Without it every call
        // blocks a full second while the daemon measures a delta for us — 1.05s
        // against 0.04s, measured — and we keep our own previous reading anyway.
        JsonNode stats = live
                ? getQuietly("/containers/" + id + "/stats?stream=false&one-shot=true")
                : MissingNode.getInstance();

        long[] network = network(stats);

        return new SystemHealthResponse.Containers.Container(
                id.substring(0, Math.min(12, id.length())),
                name(summary),
                summary.path("Image").asString(""),
                state,
                summary.path("Status").asString(""),
                inspect.path("State").path("Health").path("Status").asString(""),
                inspect.path("RestartCount").asInt(0),
                uptimeSeconds(inspect),
                cpuPercent(id, stats),
                memoryUsed(stats),
                stats.path("memory_stats").path("limit").asLong(0),
                network[0],
                network[1]
        );
    }

    /** Docker prefixes every name with a slash, and allows more than one. */
    private String name(JsonNode summary) {
        JsonNode names = summary.path("Names");
        String first = names.isArray() && !names.isEmpty() ? names.get(0).asString("") : "";
        return first.startsWith("/") ? first.substring(1) : first;
    }

    /**
     * Share of the whole machine, which is what {@code docker stats} reports.
     *
     * <p>Both counters are cumulative nanoseconds — the container's own CPU time
     * and the machine's across every core. Dividing the two deltas gives the
     * share of one core; multiplying by the core count restates it against the
     * machine, so a fully busy container on a four-core box reads 25%, not 100%.
     *
     * <p>The first reading has nothing to subtract from and returns zero rather
     * than the lifetime average, which would be a real number describing a
     * question nobody asked.
     */
    private double cpuPercent(String id, JsonNode stats) {
        JsonNode cpu = stats.path("cpu_stats");
        long total = cpu.path("cpu_usage").path("total_usage").asLong(0);
        long system = cpu.path("system_cpu_usage").asLong(0);
        int cores = cpu.path("online_cpus").asInt(Runtime.getRuntime().availableProcessors());

        if (total <= 0 || system <= 0) return 0;

        long[] last = previousCpu.put(id, new long[]{total, system});
        if (last == null) return 0;

        double totalDelta = total - last[0];
        double systemDelta = system - last[1];
        if (totalDelta <= 0 || systemDelta <= 0) return 0;

        return Math.max(0, Math.min(100, (totalDelta / systemDelta) * cores * 100));
    }

    /**
     * Memory the container actually needs.
     *
     * <p>{@code usage} includes the page cache, which is file contents Linux is
     * holding on to and will hand straight back under pressure. Counting it makes
     * every container that has ever read a file look near its limit. Docker's own
     * CLI subtracts {@code inactive_file} for exactly this reason, so this
     * matches what {@code docker stats} shows.
     */
    private long memoryUsed(JsonNode stats) {
        JsonNode memory = stats.path("memory_stats");
        long usage = memory.path("usage").asLong(0);
        long inactiveFile = memory.path("stats").path("inactive_file").asLong(0);
        return Math.max(0, usage - inactiveFile);
    }

    /** Bytes in and out, summed over every interface the container has. */
    private long[] network(JsonNode stats) {
        long in = 0;
        long out = 0;
        for (JsonNode iface : stats.path("networks")) {
            in += iface.path("rx_bytes").asLong(0);
            out += iface.path("tx_bytes").asLong(0);
        }
        return new long[]{in, out};
    }

    /** Since this container last started — which a restart resets, deliberately. */
    private long uptimeSeconds(JsonNode inspect) {
        String startedAt = inspect.path("State").path("StartedAt").asString("");
        if (startedAt.isEmpty()) return 0;
        try {
            long seconds = Duration.between(Instant.parse(startedAt), Instant.now()).getSeconds();
            return Math.max(0, seconds);
        } catch (Exception e) {
            // A container that has never run reports a zero timestamp Java will
            // not parse. Not an error, and not worth a log line every five seconds.
            return 0;
        }
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(READ_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Docker answered " + response.statusCode() + " for " + path);
        }
        return json.readTree(response.body());
    }

    /**
     * The same, but a failure costs one container's detail rather than the tab.
     *
     * <p>Returns a missing node, so every {@code path(...)} below it walks off
     * into defaults instead of throwing.
     */
    private JsonNode getQuietly(String path) {
        try {
            return get(path);
        } catch (Exception e) {
            log.debug("Docker call failed: {}", path, e);
            return MissingNode.getInstance();
        }
    }

    private SystemHealthResponse.Containers unavailable(String detail) {
        return new SystemHealthResponse.Containers(false, detail, List.of());
    }
}
