-- Backups — decided 2026-10-20.
--
-- One row per attempt to copy the database, kept forever: the calendar on the
-- Backups page is drawn from these rows, and a day with no row at all is a
-- night the backup never ran ("missed").
--
-- Deleting a backup removes its files but keeps the row, marked with
-- deleted_at, so the history still shows that the copy existed.
CREATE TABLE backup_runs (
    id              BIGSERIAL    PRIMARY KEY,
    public_id       VARCHAR(16)  NOT NULL UNIQUE,
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    -- RUNNING, SUCCEEDED or FAILED.
    outcome         VARCHAR(20)  NOT NULL,
    -- SCHEDULED (the nightly one) or MANUAL ("Back up now").
    trigger_type    VARCHAR(20)  NOT NULL,
    triggered_by    BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    file_name       VARCHAR(200),
    size_bytes      BIGINT       NOT NULL DEFAULT 0,
    sha256          VARCHAR(64),
    -- Where the file is right now. Both false once it is deleted.
    on_server       BOOLEAN      NOT NULL DEFAULT FALSE,
    in_cloud        BOOLEAN      NOT NULL DEFAULT FALSE,
    error           VARCHAR(500),
    deleted_at      TIMESTAMPTZ,
    deleted_by      BIGINT       REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_backup_runs_started ON backup_runs (started_at DESC);
