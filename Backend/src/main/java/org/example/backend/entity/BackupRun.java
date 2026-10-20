package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One attempt to copy the database — see migration V9.
 */
@Entity
@Table(name = "backup_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupRun {

    public enum Outcome { RUNNING, SUCCEEDED, FAILED }

    public enum Trigger { SCHEDULED, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Like {@code BK-7K4F-92QX}. Used in addresses instead of the numeric id. */
    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    private String publicId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Outcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20, updatable = false)
    private Trigger trigger;

    /** Who pressed "Back up now". Null for the nightly run. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by", updatable = false)
    private User triggeredBy;

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(name = "on_server", nullable = false)
    private boolean onServer;

    @Column(name = "in_cloud", nullable = false)
    private boolean inCloud;

    @Column(length = 500)
    private String error;

    /** The copy was loaded into a scratch database and its data came back. */
    @Column(name = "restore_tested", nullable = false)
    private boolean restoreTested;

    /** What the restore test found — "Restored 12 tables, 345 rows". */
    @Column(name = "restore_note", length = 200)
    private String restoreNote;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private User deletedBy;

    /** A file exists somewhere and can be downloaded. */
    public boolean hasFile() {
        return deletedAt == null && (onServer || inCloud);
    }
}
