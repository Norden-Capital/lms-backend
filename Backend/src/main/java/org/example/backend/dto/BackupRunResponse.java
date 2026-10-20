package org.example.backend.dto;

import java.time.Instant;

/**
 * One backup in the log.
 *
 * @param outcome      running, succeeded or failed — lower case, as the page reads it
 * @param destination  where the file is now: "Server + Cloudflare R2", "Server",
 *                     "Cloudflare R2", "Deleted", or null when there never was one
 * @param error        why it failed, in a sentence a person can act on
 * @param downloadable a file exists and can be downloaded
 * @param trigger      scheduled (the nightly run) or manual ("Back up now")
 * @param triggeredBy  who pressed "Back up now"; null for the nightly run
 */
public record BackupRunResponse(
        String id,
        Instant startedAt,
        String outcome,
        long sizeBytes,
        long durationSeconds,
        String destination,
        String error,
        boolean downloadable,
        Instant deletedAt,
        String trigger,
        String triggeredBy
) {
}
