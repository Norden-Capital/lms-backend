-- ─────────────────────────────────────────────────────────────────────────────
-- Unsubscribes — decided 2026-11-14 (replaces the Google Sheet plan).
--
-- A brand website's /unsubscribe page sends the email and, if the person gives
-- one, a reason. One row per email and brand: unsubscribing again from the same
-- brand updates the row (reason, text, address, date) instead of adding one.
--
-- The brand is found from the website's address, like the apply form. A brand
-- with unsubscribes cannot be removed (BrandService), only switched off, so no
-- opt-out is ever lost.
--
-- client_id links the person when they had already applied. The LMS also finds
-- them by email, so a person who applies later is still shown as unsubscribed.
--
-- ip is encrypted, like the apply form's (EncryptionService).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE unsubscribes (
    id          BIGSERIAL     PRIMARY KEY,
    public_id   VARCHAR(16)   NOT NULL UNIQUE,
    email       VARCHAR(255)  NOT NULL,
    brand_id    BIGINT        NOT NULL REFERENCES brands (id),
    client_id   BIGINT        REFERENCES clients (id) ON DELETE SET NULL,
    reason      VARCHAR(20)
                CHECK (reason IN ('TOO_MANY_EMAILS', 'NOT_INTERESTED', 'ALREADY_GOT_LOAN', 'NEVER_SIGNED_UP', 'OTHER')),
    other_text  VARCHAR(300),
    ip          TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_unsubscribes_email_brand UNIQUE (email, brand_id)
);

CREATE INDEX idx_unsubscribes_list ON unsubscribes (updated_at DESC, id DESC);
CREATE INDEX idx_unsubscribes_brand ON unsubscribes (brand_id, updated_at DESC);
CREATE INDEX idx_unsubscribes_client ON unsubscribes (client_id);


-- ── The permission module ───────────────────────────────────────────────────
--
-- Under Customers, after Channels. Not admin-only and not owner-only: like the
-- Customer List, administrators hold it through their role, and a member can be
-- given it one action at a time. Position 13 because 1–12 are taken; the group
-- keeps its place in the menu (groups follow their first module).
--
-- No DELETE on purpose: removing an opt-out could mean mailing someone who
-- asked us to stop.

INSERT INTO modules (key, label, description, group_key, group_label, position, admin_only, owner_only) VALUES
    ('UNSUBSCRIBE', 'Unsubscribes', 'People who asked for no more emails.', 'CUSTOMERS', 'Customers', 13, FALSE, FALSE);

INSERT INTO permissions (resource, action, description) VALUES
    ('UNSUBSCRIBE', 'READ',   'View unsubscribes'),
    ('UNSUBSCRIBE', 'EXPORT', 'Download unsubscribes as a CSV file');

-- ⚠️ Only the UNSUBSCRIBE rows: a CROSS JOIN over every permission would hand
-- the owner pages (V20) to every administrator.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.resource = 'UNSUBSCRIBE'
WHERE r.name IN ('ADMIN', 'OWNER')
ON CONFLICT DO NOTHING;
