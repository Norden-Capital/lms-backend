package org.example.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Everything the Backups page draws, in one call: the facts about the backups,
 * and the page of the log currently on screen.
 */
public record BackupOverviewResponse(Status status, LogPage log) {

    /**
     * @param lastSuccessAt         the newest backup that worked and still exists
     * @param storedCount           backups with a file still kept
     * @param totalRuns             every backup that ever worked, deleted ones included
     * @param destination           "Server + Cloudflare R2", or "Server only"
     * @param destinationUsedBytes  the server disk the backups are written to — used
     * @param destinationTotalBytes the same disk — its whole size
     * @param days                  oldest first, one per day, for the calendar
     * @param running               a backup is in progress right now
     */
    public record Status(
            Instant lastSuccessAt,
            Instant nextRunAt,
            String schedule,
            long storedCount,
            long storedBytes,
            long totalRuns,
            String destination,
            long destinationUsedBytes,
            long destinationTotalBytes,
            List<Day> days,
            boolean running
    ) {
    }

    /** {@code outcome} is null for a day before backups existed, or tonight's not yet due. */
    public record Day(String date, String outcome) {
    }

    /** {@code page} is 1-based and always within range. */
    public record LogPage(List<BackupRunResponse> runs, long total, int page, int size, int totalPages) {
    }
}
