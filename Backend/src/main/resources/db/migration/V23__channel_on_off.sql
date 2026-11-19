-- ─────────────────────────────────────────────────────────────────────────────
-- Channels are turned off, never deleted — decided 2026-11-18 (owner).
--
-- A channel keeps its tag (name) for good: it is in old rows and in links out
-- in ads. The owner can give it a label (the name shown in the LMS) and switch
-- it on or off. Off = no longer offered for new links; visits from old links
-- still count under its own name (no SEO fallback).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE marketing_channels ADD COLUMN label      VARCHAR(60);
ALTER TABLE marketing_channels ADD COLUMN active     BOOLEAN     NOT NULL DEFAULT TRUE;
ALTER TABLE marketing_channels ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- SEO gets its own row, so its label can be edited too. It is always on.
INSERT INTO marketing_channels (name, label) VALUES ('seo', 'SEO') ON CONFLICT (name) DO NOTHING;
ALTER TABLE marketing_channels ADD CONSTRAINT seo_always_on CHECK (name <> 'seo' OR active);

-- Permissions: Edit replaces Remove.
INSERT INTO permissions (resource, action, description) VALUES
    ('CHANNEL_SETUP', 'UPDATE', 'Edit lead channels and turn them on or off');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.resource = 'CHANNEL_SETUP' AND p.action = 'UPDATE'
WHERE r.name = 'OWNER'
ON CONFLICT DO NOTHING;

-- Removing the permission also removes it from every role and user (ON DELETE CASCADE).
DELETE FROM permissions WHERE resource = 'CHANNEL_SETUP' AND action = 'DELETE';

UPDATE modules SET description = 'Add lead channels, edit them, turn them on or off.' WHERE key = 'CHANNEL_SETUP';
