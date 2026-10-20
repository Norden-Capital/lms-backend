package org.example.backend.service;

import io.jsonwebtoken.JwtException;
import org.example.backend.dto.BackupOverviewResponse;
import org.example.backend.dto.BackupRunResponse;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.BackupRun;
import org.example.backend.entity.PublicIds;
import org.example.backend.entity.User;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.BackupRunRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies of the database — decided 2026-10-20.
 *
 * <p>Every night at 03:00 UTC, and whenever the Owner presses "Back up now":
 *
 * <ol>
 *   <li><b>Copy</b> — {@code pg_dump} writes the whole database to one file.</li>
 *   <li><b>Check</b> — {@code pg_restore --list} reads the file back. A copy
 *       that cannot be read is a failure, not a success.</li>
 *   <li><b>Keep</b> — the file stays on the server, and a second copy goes to
 *       Cloudflare R2, so losing the server does not lose the backups.</li>
 * </ol>
 *
 * <p>Nothing is deleted automatically — the Owner decided to keep everything.
 * Each run is a row in {@code backup_runs}, and a night with no row is a night
 * the backup never ran, which the page shows as "missed".
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** The nightly run. Change both together. */
    private static final String CRON = "0 0 3 * * *";
    private static final int RUN_HOUR_UTC = 3;
    private static final String SCHEDULE = "Every day at 03:00 UTC";

    /** A night counts as missed an hour after the run was due. */
    private static final Duration MISSED_AFTER = Duration.ofHours(1);

    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(30);

    private static final String CLOUD_FOLDER = "backups/";

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LABEL_STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private static final List<Integer> PAGE_SIZES = List.of(10, 25, 50);

    /** Sorting by status puts the worrying ones first, not the alphabetical ones. */
    private static final Map<BackupRun.Outcome, Integer> SEVERITY = Map.of(
            BackupRun.Outcome.FAILED, 0,
            BackupRun.Outcome.RUNNING, 2,
            BackupRun.Outcome.SUCCEEDED, 3);

    private final BackupRunRepository runRepository;
    private final UserRepository userRepository;
    private final CloudStorage cloud;
    private final ActionLogService actionLog;
    private final JwtService jwtService;
    private final TransactionTemplate tx;

    private final Path directory;
    private final String pgDump;
    private final String pgRestore;
    private final String datasourceUrl;
    private final String datasourceUser;
    private final String datasourcePassword;
    private final long cloudLimitBytes;

    /** One backup at a time. A second press while one runs is refused. */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public BackupService(BackupRunRepository runRepository,
                         UserRepository userRepository,
                         CloudStorage cloud,
                         ActionLogService actionLog,
                         JwtService jwtService,
                         PlatformTransactionManager transactionManager,
                         @Value("${app.backup.directory:/backups}") String directory,
                         @Value("${app.backup.pg-dump:pg_dump}") String pgDump,
                         @Value("${app.backup.pg-restore:pg_restore}") String pgRestore,
                         @Value("${spring.datasource.url}") String datasourceUrl,
                         @Value("${spring.datasource.username}") String datasourceUser,
                         @Value("${spring.datasource.password}") String datasourcePassword,
                         @Value("${app.backup.r2.limit-bytes:10000000000}") long cloudLimitBytes) {
        this.runRepository = runRepository;
        this.userRepository = userRepository;
        this.cloud = cloud;
        this.actionLog = actionLog;
        this.jwtService = jwtService;
        this.tx = new TransactionTemplate(transactionManager);
        this.directory = Path.of(directory);
        this.pgDump = pgDump;
        this.pgRestore = pgRestore;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUser = datasourceUser;
        this.datasourcePassword = datasourcePassword;
        this.cloudLimitBytes = cloudLimitBytes;
    }

    // ── Running a backup ────────────────────────────────────────────────────

    /** A server that stopped halfway through a backup left it "running" forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void closeAbandonedRuns() {
        Integer closed = tx.execute(status -> runRepository.closeAbandoned(Instant.now(),
                "The server restarted while this backup was running."));
        if (closed != null && closed > 0) log.warn("Closed {} backup(s) left running by a restart", closed);
    }

    @Scheduled(cron = CRON, zone = "UTC")
    public void nightly() {
        if (!busy.compareAndSet(false, true)) {
            log.warn("Nightly backup skipped — another backup is still running");
            return;
        }
        try {
            perform(createRun(BackupRun.Trigger.SCHEDULED, null).getId());
        } finally {
            busy.set(false);
        }
    }

    /**
     * "Back up now". Answers straight away with the run in progress; the work
     * carries on in the background and the page follows it.
     */
    public BackupRunResponse startNow(Long actorId) {
        if (!busy.compareAndSet(false, true)) {
            throw ApiException.conflict("A backup is already running. Wait for it to finish.");
        }
        BackupRun run;
        try {
            run = createRun(BackupRun.Trigger.MANUAL, actorId);
            actionLog.record(ActionType.BACKUP_STARTED, actorId, null, "Backup " + run.getPublicId());
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }

        Long id = run.getId();
        Thread.ofVirtual().name("backup-" + run.getPublicId()).start(() -> {
            try {
                perform(id);
            } finally {
                busy.set(false);
            }
        });
        return toResponse(run, Instant.now());
    }

    private BackupRun createRun(BackupRun.Trigger trigger, Long actorId) {
        return tx.execute(status -> runRepository.save(BackupRun.builder()
                .publicId(PublicIds.generate("BK"))
                .startedAt(Instant.now())
                .outcome(BackupRun.Outcome.RUNNING)
                .trigger(trigger)
                .triggeredBy(actorId == null ? null : userRepository.findById(actorId).orElse(null))
                .build()));
    }

    /** Copy, check, keep — and write down what happened, whatever happened. */
    private void perform(Long runId) {
        BackupRun run = tx.execute(status -> runRepository.findById(runId).orElseThrow());
        // The id as well as the time: two runs in the same second must not share a file.
        String name = "lms-" + FILE_STAMP.format(run.getStartedAt()) + "-" + run.getPublicId() + ".dump";
        Path part = directory.resolve(name + ".part");
        Path file = directory.resolve(name);

        String fileName = null;
        String sha256 = null;
        long size = 0;
        boolean onServer = false;
        boolean inCloud = false;
        String error = null;

        try {
            Files.createDirectories(directory);
            dump(part);
            check(part);
            size = Files.size(part);
            sha256 = sha256(part);
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
            fileName = name;
            onServer = true;

            if (cloud.isConfigured()) {
                try {
                    cloud.put(CLOUD_FOLDER + name, file, sha256);
                    inCloud = true;
                } catch (IOException e) {
                    // Kept on the server and downloadable, but the off-site copy is
                    // the whole point of R2 — so this is a failure the page shows.
                    error = "Saved on the server, but the copy to Cloudflare R2 failed: " + e.getMessage();
                }
            }
        } catch (BackupFailure e) {
            error = e.getMessage();
        } catch (Exception e) {
            log.error("Backup {} failed unexpectedly", run.getPublicId(), e);
            error = "Something unexpected went wrong: " + e.getMessage();
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // A leftover .part file is harmless and never listed.
            }
        }

        String finalFileName = fileName;
        String finalSha = sha256;
        long finalSize = size;
        boolean finalOnServer = onServer;
        boolean finalInCloud = inCloud;
        String finalError = error;
        tx.executeWithoutResult(status -> {
            BackupRun r = runRepository.findById(runId).orElseThrow();
            r.setFinishedAt(Instant.now());
            r.setFileName(finalFileName);
            r.setSha256(finalSha);
            r.setSizeBytes(finalSize);
            r.setOnServer(finalOnServer);
            r.setInCloud(finalInCloud);
            r.setError(finalError == null ? null : truncate(finalError, 500));
            r.setOutcome(finalError == null ? BackupRun.Outcome.SUCCEEDED : BackupRun.Outcome.FAILED);
        });

        if (error == null) log.info("Backup {} done — {} bytes, {}", run.getPublicId(), size, where(onServer, inCloud));
        else log.warn("Backup {} failed: {}", run.getPublicId(), error);
    }

    /** {@code pg_dump} in its own compressed format, which {@code pg_restore} reads. */
    private void dump(Path target) throws BackupFailure {
        Database db = database();
        ProcessBuilder builder = new ProcessBuilder(pgDump,
                "--format=custom", "--no-owner", "--no-privileges",
                "--host", db.host(), "--port", String.valueOf(db.port()),
                "--username", datasourceUser, db.name())
                .redirectOutput(target.toFile());
        builder.environment().put("PGPASSWORD", datasourcePassword);

        Result result = run(builder, "copy the database");
        if (result.exitCode() != 0) {
            throw new BackupFailure("The database could not be copied: " + firstLine(result.output()));
        }
    }

    /** Reads the file back. A copy nobody can read is not a backup. */
    private void check(Path file) throws BackupFailure, IOException {
        if (Files.size(file) == 0) throw new BackupFailure("The copy came out empty.");

        ProcessBuilder builder = new ProcessBuilder(pgRestore, "--list").redirectInput(file.toFile());
        Result result = run(builder, "check the copy");
        if (result.exitCode() != 0) {
            throw new BackupFailure("The copy was made but could not be read back: " + firstLine(result.output()));
        }
        if (!result.output().contains(" TABLE DATA ")) {
            throw new BackupFailure("The copy was made but has no data in it.");
        }
    }

    private record Result(int exitCode, String output) {
    }

    /** Runs a command with a time limit, collecting what it says. */
    private Result run(ProcessBuilder builder, String what) throws BackupFailure {
        if (builder.redirectOutput() == ProcessBuilder.Redirect.PIPE) builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new BackupFailure("Could not " + what + " — the " + builder.command().getFirst()
                    + " program is not installed on the server.");
        }

        // Read on another thread, so a chatty command never blocks on a full pipe.
        InputStream stream = builder.redirectErrorStream() ? process.getInputStream() : process.getErrorStream();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }, runnable -> Thread.ofVirtual().start(runnable));

        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new BackupFailure("Could not " + what + " — it took longer than "
                        + COMMAND_TIMEOUT.toMinutes() + " minutes.");
            }
            return new Result(process.exitValue(), output.get(1, TimeUnit.MINUTES));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BackupFailure("Could not " + what + " — the server was shutting down.");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return new Result(process.exitValue(), "");
        }
    }

    private record Database(String host, int port, String name) {
    }

    /** Where the database is, read from the same address the application uses. */
    private Database database() {
        URI uri = URI.create(datasourceUrl.replaceFirst("^jdbc:", ""));
        String name = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        return new Database(uri.getHost(), uri.getPort() == -1 ? 5432 : uri.getPort(), name);
    }

    /** A reason the page shows as it is. */
    private static class BackupFailure extends Exception {
        BackupFailure(String message) {
            super(message);
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** Everything the page draws, and the page of the log on screen. */
    @Transactional(readOnly = true)
    public BackupOverviewResponse overview(String sort, int page, int size) {
        Instant now = Instant.now();
        List<BackupRun> runs = runRepository.findAllByOrderByStartedAtDesc();

        BackupRun lastSuccess = runs.stream()
                .filter(r -> r.getOutcome() == BackupRun.Outcome.SUCCEEDED && r.hasFile())
                .findFirst().orElse(null);
        List<BackupRun> stored = runs.stream().filter(BackupRun::hasFile).toList();

        long diskTotal = 0;
        long diskUsed = 0;
        try {
            Files.createDirectories(directory);
            FileStore store = Files.getFileStore(directory);
            diskTotal = store.getTotalSpace();
            diskUsed = diskTotal - store.getUsableSpace();
        } catch (IOException e) {
            log.warn("Could not read the backup disk: {}", e.getMessage());
        }

        BackupOverviewResponse.Status status = new BackupOverviewResponse.Status(
                lastSuccess == null ? null : lastSuccess.getStartedAt(),
                nextRun(now),
                SCHEDULE,
                stored.size(),
                stored.stream().mapToLong(BackupRun::getSizeBytes).sum(),
                runs.stream().filter(r -> r.getOutcome() == BackupRun.Outcome.SUCCEEDED).count(),
                cloud.isConfigured() ? "Server + Cloudflare R2" : "Server only",
                diskUsed,
                diskTotal,
                days(runs, now),
                runs.stream().anyMatch(r -> r.getOutcome() == BackupRun.Outcome.RUNNING),
                runs.stream().filter(BackupRun::isOnServer).mapToLong(BackupRun::getSizeBytes).sum(),
                cloud.isConfigured(),
                runs.stream().filter(BackupRun::isInCloud).mapToLong(BackupRun::getSizeBytes).sum(),
                cloudLimitBytes);

        return new BackupOverviewResponse(status, logPage(runs, sort, page, size, now));
    }

    private static Instant nextRun(Instant now) {
        Instant today = LocalDate.ofInstant(now, ZoneOffset.UTC).atTime(RUN_HOUR_UTC, 0).toInstant(ZoneOffset.UTC);
        return today.isAfter(now) ? today : today.plus(Duration.ofDays(1));
    }

    /**
     * One entry per day, from the first of January of the year backups began.
     * A day with no run at all, once its run was due, is "missed".
     */
    private static List<BackupOverviewResponse.Day> days(List<BackupRun> runs, Instant now) {
        if (runs.isEmpty()) return List.of();

        Map<LocalDate, List<BackupRun>> byDay = new HashMap<>();
        for (BackupRun r : runs) {
            byDay.computeIfAbsent(LocalDate.ofInstant(r.getStartedAt(), ZoneOffset.UTC), d -> new ArrayList<>()).add(r);
        }

        LocalDate first = LocalDate.ofInstant(runs.getLast().getStartedAt(), ZoneOffset.UTC);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        List<BackupOverviewResponse.Day> days = new ArrayList<>();
        for (LocalDate day = first.withDayOfYear(1); !day.isAfter(today); day = day.plusDays(1)) {
            String outcome = null;
            List<BackupRun> that = byDay.get(day);
            if (that != null) {
                if (that.stream().anyMatch(r -> r.getOutcome() == BackupRun.Outcome.RUNNING)) outcome = "running";
                else if (that.stream().anyMatch(r -> r.getOutcome() == BackupRun.Outcome.SUCCEEDED)) outcome = "succeeded";
                else outcome = "failed";
            } else if (!day.isBefore(first)) {
                Instant due = day.atTime(RUN_HOUR_UTC, 0).toInstant(ZoneOffset.UTC).plus(MISSED_AFTER);
                if (now.isAfter(due)) outcome = "missed";
            }
            days.add(new BackupOverviewResponse.Day(day.toString(), outcome));
        }
        return days;
    }

    /** Sorted and paged here. A few hundred rows a year — no need to ask the database. */
    private BackupOverviewResponse.LogPage logPage(List<BackupRun> runs, String sort, int page, int size, Instant now) {
        String[] parts = sort == null ? new String[0] : sort.split(",");
        String key = parts.length > 0 ? parts[0] : "";
        boolean descending = parts.length > 1 && parts[1].equalsIgnoreCase("desc");

        Comparator<BackupRun> order = switch (key) {
            case "startedAt" -> Comparator.comparing(BackupRun::getStartedAt);
            case "outcome" -> Comparator.comparing(r -> SEVERITY.get(r.getOutcome()));
            case "sizeBytes" -> Comparator.comparingLong(BackupRun::getSizeBytes);
            case "durationSeconds" -> Comparator.comparingLong(r -> duration(r, now));
            default -> {
                descending = true;
                yield Comparator.comparing(BackupRun::getStartedAt);
            }
        };
        if (descending) order = order.reversed();
        // Ties newest first, so equal rows never swap places between loads.
        order = order.thenComparing(BackupRun::getStartedAt, Comparator.reverseOrder());

        List<BackupRun> sorted = runs.stream().sorted(order).toList();

        int pageSize = PAGE_SIZES.contains(size) ? size : PAGE_SIZES.getFirst();
        int totalPages = Math.max(1, (sorted.size() + pageSize - 1) / pageSize);
        int current = Math.min(Math.max(page, 1), totalPages);
        int from = (current - 1) * pageSize;

        List<BackupRunResponse> rows = sorted.subList(from, Math.min(from + pageSize, sorted.size()))
                .stream().map(r -> toResponse(r, now)).toList();
        return new BackupOverviewResponse.LogPage(rows, sorted.size(), current, pageSize, totalPages);
    }

    private static long duration(BackupRun run, Instant now) {
        Instant end = run.getFinishedAt() == null ? now : run.getFinishedAt();
        return Math.max(0, Duration.between(run.getStartedAt(), end).toSeconds());
    }

    private BackupRunResponse toResponse(BackupRun run, Instant now) {
        String destination;
        if (run.getOutcome() == BackupRun.Outcome.RUNNING) destination = null;
        else if (run.getDeletedAt() != null) destination = "Deleted";
        else destination = where(run.isOnServer(), run.isInCloud());

        User by = run.getTriggeredBy();
        return new BackupRunResponse(
                run.getPublicId(),
                run.getStartedAt(),
                run.getOutcome().name().toLowerCase(Locale.ROOT),
                run.getSizeBytes(),
                duration(run, now),
                destination,
                run.getError(),
                run.hasFile(),
                run.getDeletedAt(),
                run.getTrigger().name().toLowerCase(Locale.ROOT),
                by == null ? null : (by.getFirstName() + " " + by.getLastName()).trim());
    }

    private static String where(boolean onServer, boolean inCloud) {
        if (onServer && inCloud) return "Server + Cloudflare R2";
        if (onServer) return "Server";
        if (inCloud) return "Cloudflare R2";
        return null;
    }

    // ── Download and delete ─────────────────────────────────────────────────

    /** A two-minute link to the file. Asking for one is what the log records. */
    @Transactional
    public String downloadLink(String publicId, Long actorId) {
        BackupRun run = find(publicId);
        if (!run.hasFile()) throw ApiException.notFound("This backup has no file to download.");

        actionLog.record(ActionType.BACKUP_DOWNLOADED, actorId, null, describe(run));
        return "/api/backups/download?token=" + jwtService.generateBackupDownloadToken(run.getPublicId());
    }

    public record Download(String fileName, long size, InputStream stream) {
    }

    /** Opens the file a download link points at — from the server, or from R2 if the server copy is gone. */
    @Transactional(readOnly = true)
    public Download open(String token) {
        String publicId;
        try {
            publicId = jwtService.parseAs(token, JwtService.TYPE_BACKUP_DOWNLOAD).getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("This download link has expired. Go back and press Download again.");
        }

        BackupRun run = find(publicId);
        if (!run.hasFile()) throw ApiException.notFound("This backup has no file to download.");

        try {
            Path file = directory.resolve(run.getFileName());
            if (run.isOnServer() && Files.isRegularFile(file)) {
                return new Download(run.getFileName(), Files.size(file), Files.newInputStream(file));
            }
            if (run.isInCloud() && cloud.isConfigured()) {
                return new Download(run.getFileName(), run.getSizeBytes(), cloud.get(CLOUD_FOLDER + run.getFileName()));
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The backup file could not be opened: " + e.getMessage());
        }
        throw ApiException.notFound("The backup file is missing from the server.");
    }

    /**
     * Removes the files — on the server and in R2 — and keeps the row, so the
     * history still shows the backup existed.
     */
    @Transactional
    public BackupRunResponse delete(String publicId, Long actorId) {
        BackupRun run = find(publicId);
        if (run.getOutcome() == BackupRun.Outcome.RUNNING) {
            throw ApiException.conflict("This backup is still running. Wait for it to finish.");
        }
        if (run.getDeletedAt() != null) throw ApiException.conflict("This backup is already deleted.");

        if (run.isInCloud()) {
            if (!cloud.isConfigured()) {
                throw ApiException.conflict("This backup has a copy in Cloudflare R2, but R2 is not set up on the server any more.");
            }
            try {
                cloud.delete(CLOUD_FOLDER + run.getFileName());
            } catch (IOException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not delete the copy in Cloudflare R2: " + e.getMessage());
            }
        }
        if (run.getFileName() != null) {
            try {
                Files.deleteIfExists(directory.resolve(run.getFileName()));
            } catch (IOException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not delete the file on the server: " + e.getMessage());
            }
        }

        run.setOnServer(false);
        run.setInCloud(false);
        run.setDeletedAt(Instant.now());
        run.setDeletedBy(actorId == null ? null : userRepository.getReferenceById(actorId));
        actionLog.record(ActionType.BACKUP_DELETED, actorId, null, describe(run));
        return toResponse(run, Instant.now());
    }

    private BackupRun find(String publicId) {
        return runRepository.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("No such backup."));
    }

    /** "Backup BK-7K4F-92QX of 20 Oct 2026, 03:00 UTC (1.2 MB)", for the action log. */
    private static String describe(BackupRun run) {
        return "Backup " + run.getPublicId() + " of " + LABEL_STAMP.format(run.getStartedAt())
                + " (" + humanBytes(run.getSizeBytes()) + ")";
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String output) {
        return output.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("no reason given.");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
