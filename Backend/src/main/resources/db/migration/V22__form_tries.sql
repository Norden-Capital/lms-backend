-- ─────────────────────────────────────────────────────────────────────────────
-- Tries in the form funnel — decided 2026-11-17 (owner: People / Tries switch).
--
-- People (the funnel until now) counts each person once per step. Tries counts
-- every time: every form opening, every step-1 save, every step-2 click and every
-- link LeadScout gave. Nothing before this migration was recorded that way, so
-- older days show the closest numbers we have (see ChannelService.funnel).
-- ─────────────────────────────────────────────────────────────────────────────

-- Every opening of the form. The row stays one per browser, brand and day; a
-- second opening the same day adds 1 here. Old rows were one opening each.
ALTER TABLE form_visits ADD COLUMN opens INTEGER NOT NULL DEFAULT 1;

-- One line per step-1 save and per step-2 click that sent to LeadScout.
-- channel is that visit's own channel (a person can try from fb, then from seo);
-- got_link says whether that step-2 click got the offer link.
CREATE TABLE form_steps (
    id              BIGSERIAL     PRIMARY KEY,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    step            SMALLINT      NOT NULL CHECK (step IN (1, 2)),
    brand_id        BIGINT        NOT NULL REFERENCES brands (id) ON DELETE CASCADE,
    application_id  BIGINT        REFERENCES loan_applications (id) ON DELETE SET NULL,
    channel         VARCHAR(40)   NOT NULL,
    mode            VARCHAR(10)   NOT NULL,
    got_link        BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_form_steps_funnel ON form_steps (created_at, brand_id);
CREATE INDEX idx_form_steps_application ON form_steps (application_id, created_at DESC);

-- From when the tries are exact: the moment this migration ran. Before it, the
-- funnel's Tries view uses rows and LeadScout sends instead.
CREATE TABLE form_tries_start (
    only_row    BOOLEAN       PRIMARY KEY DEFAULT TRUE CHECK (only_row),
    started_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
INSERT INTO form_tries_start DEFAULT VALUES;
