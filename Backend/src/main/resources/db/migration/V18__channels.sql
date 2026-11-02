-- Where each lead came from — decided 2026-11-02.
--
-- The channel is the link's utm_source (email, facebook…), and "seo" when the
-- link had none: search visitors arrive from the brand site, untagged. Any new
-- source is a new channel by itself; the list below only gives each one its link.
ALTER TABLE loan_applications ADD COLUMN channel VARCHAR(40) NOT NULL DEFAULT 'seo';

CREATE INDEX idx_loan_applications_channel ON loan_applications (created_at, channel);

-- The channels the Owner made links for, on Configuration → Channels.
CREATE TABLE marketing_channels (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(40)   NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO marketing_channels (name) VALUES ('email');

-- People who opened the apply form: one line per browser, brand and day, so a
-- refresh is never counted twice. visitor_id is a random number the browser
-- keeps; nothing here says who the person is.
CREATE TABLE form_visits (
    day         DATE          NOT NULL,
    brand_id    BIGINT        NOT NULL REFERENCES brands (id) ON DELETE CASCADE,
    visitor_id  VARCHAR(64)   NOT NULL,
    channel     VARCHAR(40)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (day, brand_id, visitor_id)
);
