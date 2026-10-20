-- The restore test — decided 2026-10-20.
--
-- After each copy is made, it is loaded into an empty scratch database and
-- counted against the live one. A copy that does not come back is a failure,
-- not a success. The note says what the test found, e.g.
-- "Restored 12 tables, 345 rows".
ALTER TABLE backup_runs ADD COLUMN restore_tested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE backup_runs ADD COLUMN restore_note   VARCHAR(200);
