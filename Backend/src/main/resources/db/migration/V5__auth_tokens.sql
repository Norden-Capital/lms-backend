-- One table for every emailed one-time link: invitations and password resets.
--
-- password_reset_tokens already had the right shape — a hash, an expiry, a used
-- marker — and nothing ever wrote to it. An invitation is the same thing with a
-- longer life, so it gains a type instead of a twin table.
--
-- Only the SHA-256 of the token is stored. The raw value exists in the email and
-- nowhere else, so reading this table does not let anybody use a link.

ALTER TABLE password_reset_tokens RENAME TO auth_tokens;
ALTER INDEX idx_password_reset_tokens_user RENAME TO idx_auth_tokens_user;

ALTER TABLE auth_tokens ADD COLUMN type VARCHAR(30) NOT NULL DEFAULT 'PASSWORD_RESET';
ALTER TABLE auth_tokens ALTER COLUMN type DROP DEFAULT;
ALTER TABLE auth_tokens
    ADD CONSTRAINT chk_auth_tokens_type CHECK (type IN ('PASSWORD_RESET', 'INVITE'));

-- "Was one just sent?" is asked before every email, to stop a button being
-- used to flood somebody's inbox.
CREATE INDEX idx_auth_tokens_user_type_created ON auth_tokens (user_id, type, created_at DESC);
