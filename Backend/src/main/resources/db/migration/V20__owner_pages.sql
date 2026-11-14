-- ─────────────────────────────────────────────────────────────────────────────
-- Owner pages as permissions — decided 2026-11-14.
--
-- Brands, Channels (setup), System Health, Backups and the Action Log used to be
-- the Owner's by role alone. Now each one is a module with its own actions, so
-- the Owner can give an administrator some of them, action by action, from that
-- administrator's "Change permissions" page.
--
-- The rules (enforced in UserPermissionService, not here):
--   • Only the Owner gives or takes them. An administrator cannot, even one who
--     was given some.
--   • Only an administrator can receive them. Never a member.
--   • A new administrator starts with none of them.
--
-- ⚠️ Granted to OWNER only, never ADMIN. ADMIN's role must not hold these, or
-- every administrator would see the owner pages.
-- ─────────────────────────────────────────────────────────────────────────────


-- ── 1. The flag ──────────────────────────────────────────────────────────────

ALTER TABLE modules ADD COLUMN owner_only BOOLEAN NOT NULL DEFAULT FALSE;


-- ── 2. The modules ───────────────────────────────────────────────────────────
--
-- One group, "Owner pages", after everything else. CHANNEL_SETUP rather than
-- CHANNEL: CHANNEL already exists under Customers and every administrator holds
-- it, so reusing it would hand the Channels page to all of them.

INSERT INTO modules (key, label, description, group_key, group_label, position, admin_only, owner_only) VALUES
    ('BRAND',         'Brands',        'Add, edit and turn off brands.',      'OWNER_PAGES', 'Owner pages',  8, FALSE, TRUE),
    ('CHANNEL_SETUP', 'Channels',      'Add and remove lead channels.',       'OWNER_PAGES', 'Owner pages',  9, FALSE, TRUE),
    ('SYSTEM_HEALTH', 'System Health', 'The server and its numbers.',         'OWNER_PAGES', 'Owner pages', 10, FALSE, TRUE),
    ('BACKUP',        'Backups',       'Make, download and delete backups.',  'OWNER_PAGES', 'Owner pages', 11, FALSE, TRUE),
    ('ACTION_LOG',    'Action Log',    'Who did what, and sign-ins.',         'OWNER_PAGES', 'Owner pages', 12, FALSE, TRUE);


-- ── 3. Their actions ─────────────────────────────────────────────────────────
--
-- DOWNLOAD is new: getting a copy of the whole database is its own decision,
-- separate from seeing that backups exist.

INSERT INTO permissions (resource, action, description) VALUES
    ('BRAND',         'READ',     'View brands'),
    ('BRAND',         'CREATE',   'Add brands'),
    ('BRAND',         'UPDATE',   'Edit brands, refresh them, turn them on or off'),
    ('BRAND',         'DELETE',   'Delete brands'),
    ('CHANNEL_SETUP', 'READ',     'View lead channels'),
    ('CHANNEL_SETUP', 'CREATE',   'Add lead channels'),
    ('CHANNEL_SETUP', 'DELETE',   'Remove lead channels'),
    ('SYSTEM_HEALTH', 'READ',     'View the server''s health'),
    ('BACKUP',        'READ',     'View backups'),
    ('BACKUP',        'CREATE',   'Make a backup now'),
    ('BACKUP',        'DOWNLOAD', 'Download a backup'),
    ('BACKUP',        'DELETE',   'Delete backups'),
    ('ACTION_LOG',    'READ',     'View the action log and sign-ins');


-- ── 4. The Owner holds them all, through the role ───────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.resource IN ('BRAND', 'CHANNEL_SETUP', 'SYSTEM_HEALTH', 'BACKUP', 'ACTION_LOG')
WHERE r.name = 'OWNER'
ON CONFLICT DO NOTHING;
