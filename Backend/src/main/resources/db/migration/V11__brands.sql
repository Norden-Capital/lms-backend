-- Brands — decided 2026-10-20.
--
-- Every brand's address forwards into the one inbox mailbox (Cloudflare Email
-- Routing). Mail sent to a brand's address is tagged with it, and answers go
-- out from that address — which must also be in the mailbox's Gmail
-- "Send mail as" list. Managed by the Owner on the Configuration page.
CREATE TABLE brands (
    id          BIGSERIAL     PRIMARY KEY,
    public_id   VARCHAR(16)   NOT NULL UNIQUE,
    name        VARCHAR(80)   NOT NULL,
    email       VARCHAR(255)  NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
