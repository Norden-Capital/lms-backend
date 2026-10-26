-- Sessions — decided 2026-10-26. One row per finished sign-in, so a person can
-- see where they are signed in and end any of those sign-ins. The access token
-- carries the row's id ("sid"); a token whose row is ended stops working.
CREATE TABLE user_sessions (
    id            UUID         PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    ended_at      TIMESTAMPTZ,
    ip            VARCHAR(64),
    user_agent    VARCHAR(400)
);

CREATE INDEX idx_user_sessions_user ON user_sessions (user_id);
