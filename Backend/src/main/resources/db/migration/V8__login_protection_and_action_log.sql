-- Login protection and the action log — decided 2026-10-19.

-- ── A two-factor code works once ────────────────────────────────────────────
--
-- A code is valid for its 30-second step and the steps either side, so the
-- same six digits used to work several times over: somebody looking over a
-- shoulder, or a code left in a screenshot, could be replayed for up to a
-- minute and a half. The last step accepted is remembered, and a code from
-- that step or an earlier one is refused.
ALTER TABLE users ADD COLUMN mfa_last_used_step BIGINT;


-- ── The action log ──────────────────────────────────────────────────────────
--
-- Who did what to whom, and when. Readable by the Owner only.
--
-- Actor and target are kept twice: as a reference, and as text frozen at the
-- moment of the action. The reference makes "everything about this person"
-- possible; the text means a line still reads correctly after a name changes,
-- and survives if the account it points at is ever removed.
CREATE TABLE action_logs (
    id           BIGSERIAL    PRIMARY KEY,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    action       VARCHAR(40)  NOT NULL,
    actor_id     BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    actor_label  VARCHAR(400),
    target_id    BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    target_label VARCHAR(400),
    details      VARCHAR(500),
    ip           VARCHAR(64)
);

-- Newest first is how the page reads; filtering by kind and by person are the
-- two questions asked of it.
CREATE INDEX idx_action_logs_created ON action_logs (created_at DESC);
CREATE INDEX idx_action_logs_action  ON action_logs (action);
CREATE INDEX idx_action_logs_target  ON action_logs (target_id);
