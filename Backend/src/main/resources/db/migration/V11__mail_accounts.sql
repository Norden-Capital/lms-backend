-- The inbox — decided 2026-10-20.
--
-- One row per connected Gmail mailbox. The mail itself is not stored here: it
-- is read live from Gmail over IMAP each time, so the inbox always matches
-- Gmail and the database stays small.
--
-- The app password is a Google "App Password" (16 letters), encrypted with the
-- application's ENCRYPTION_KEY, and never sent back out by the API. Revoking it
-- in the Google account cuts the app off at once.
CREATE TABLE mail_accounts (
    id                 BIGSERIAL     PRIMARY KEY,
    public_id          VARCHAR(16)   NOT NULL UNIQUE,
    label              VARCHAR(80)   NOT NULL,
    email              VARCHAR(255)  NOT NULL UNIQUE,
    brand              VARCHAR(80)   NOT NULL,
    provider           VARCHAR(20)   NOT NULL DEFAULT 'gmail',
    password_encrypted VARCHAR(500)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT        REFERENCES users(id) ON DELETE SET NULL
);
