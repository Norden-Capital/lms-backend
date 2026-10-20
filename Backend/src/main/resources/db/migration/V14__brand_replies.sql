-- Replies per brand — decided 2026-10-20.
--
-- One row each time an email is answered (Reply or Reply all) from a brand's
-- address, for the Replies column on Configuration → Brands: a line of the
-- year's four quarters and the total. Removing a brand removes its count.
CREATE TABLE brand_replies (
    id        BIGSERIAL    PRIMARY KEY,
    brand_id  BIGINT       NOT NULL REFERENCES brands(id) ON DELETE CASCADE,
    sent_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_brand_replies_brand_sent ON brand_replies (brand_id, sent_at);
