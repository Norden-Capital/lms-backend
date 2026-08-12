-- ─────────────────────────────────────────────────────────────────────────────
-- The real permission model: the modules this platform actually has.
--
-- V2 seeded RECORD, USER and REPORT — deliberate placeholders, written before
-- anyone had decided what this system stores. It also seeded three roles. Both
-- are now wrong, and every screen in the frontend already describes what is
-- below instead. This migration makes the database agree.
--
-- Nothing in the security code changes: an authority is still RESOURCE:ACTION,
-- so @PreAuthorize, the token and PermissionService all carry on as they are.
-- Only the contents change.
-- ─────────────────────────────────────────────────────────────────────────────


-- ── 1. Modules ───────────────────────────────────────────────────────────────
--
-- `permissions.resource` was a bare string, so nothing in the database knew that
-- CUSTOMER means "Customer List", that it belongs under Customers, or where it
-- sits in the menu. The frontend invented all of that in a TypeScript file that
-- has to be edited by hand every time this one changes — this table is what lets
-- that file be deleted.
--
-- Display data lives here, once per module, rather than being repeated across
-- its five permission rows. Renaming "Customer List" is one UPDATE.

CREATE TABLE modules (
    key         VARCHAR(50)  PRIMARY KEY,
    label       VARCHAR(100) NOT NULL,
    description VARCHAR(255),

    -- The sidebar section this module appears under. Denormalised on purpose:
    -- a two-row `groups` table joined for a label nobody filters by would be
    -- ceremony. If groups ever gain properties of their own, promote it then.
    group_key   VARCHAR(50)  NOT NULL,
    group_label VARCHAR(100) NOT NULL,

    -- Menu order. Alphabetical would put Channels before Customer List and
    -- Marketing before SEO, neither of which is how anybody reads them.
    position    INT          NOT NULL,

    -- Whether these permissions can only ever come from being an administrator.
    --
    -- Management is TRUE: no member may hold USER:READ however many boxes are
    -- ticked for them. This is not the same as "MEMBER does not have it by
    -- default" — it cannot be granted to them individually either, and the API
    -- refuses rather than silently ignoring the attempt.
    admin_only  BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_modules_position UNIQUE (position)
);

INSERT INTO modules (key, label, description, group_key, group_label, position, admin_only) VALUES
    ('EMAIL_OVERVIEW',     'Email Overview',     'Email performance dashboards.',          'OVERVIEW',   'Overview',   1, FALSE),
    ('SEO_OVERVIEW',       'SEO Overview',       'Search performance dashboards.',         'OVERVIEW',   'Overview',   2, FALSE),
    ('MARKETING_OVERVIEW', 'Marketing Overview', 'Campaign and spend dashboards.',         'OVERVIEW',   'Overview',   3, FALSE),
    ('CUSTOMER',           'Customer List',      'The customer records themselves.',       'CUSTOMERS',  'Customers',  4, FALSE),
    ('CHANNEL',            'Channels',           'The routes customers arrive through.',   'CUSTOMERS',  'Customers',  5, FALSE),
    ('USER',               'Users',              'Accounts, roles and access.',            'MANAGEMENT', 'Management', 6, TRUE);


-- ── 2. The permissions themselves ────────────────────────────────────────────
--
-- USER already exists from V2 with the same five actions, so those rows are
-- kept rather than deleted and re-inserted: dropping them would cascade away
-- every role_permissions and user_permissions row pointing at them, including
-- the grants that make the current administrators administrators.
--
-- ON CONFLICT DO NOTHING covers exactly that overlap.

INSERT INTO permissions (resource, action, description) VALUES
    ('EMAIL_OVERVIEW',     'READ',   'View the email dashboards'),
    ('EMAIL_OVERVIEW',     'EXPORT', 'Export email dashboard data'),

    ('SEO_OVERVIEW',       'READ',   'View the search dashboards'),
    ('SEO_OVERVIEW',       'EXPORT', 'Export search dashboard data'),

    ('MARKETING_OVERVIEW', 'READ',   'View the marketing dashboards'),
    ('MARKETING_OVERVIEW', 'EXPORT', 'Export marketing dashboard data'),

    ('CUSTOMER',           'CREATE', 'Add customers'),
    ('CUSTOMER',           'READ',   'View customers'),
    ('CUSTOMER',           'UPDATE', 'Edit customers'),
    ('CUSTOMER',           'DELETE', 'Delete customers'),
    ('CUSTOMER',           'EXPORT', 'Export the customer list'),

    ('CHANNEL',            'CREATE', 'Add channels'),
    ('CHANNEL',            'READ',   'View channels'),
    ('CHANNEL',            'UPDATE', 'Edit channels'),
    ('CHANNEL',            'DELETE', 'Delete channels'),
    ('CHANNEL',            'EXPORT', 'Export the channel list')
ON CONFLICT (resource, action) DO NOTHING;

-- The placeholders. Deleting them cascades through role_permissions and
-- user_permissions, which is exactly right: those rows granted access to
-- something that no longer exists.
DELETE FROM permissions WHERE resource IN ('RECORD', 'REPORT');

-- Now every permission must belong to a module. Added after the inserts so the
-- constraint is checked against the finished set rather than a half-built one.
ALTER TABLE permissions
    ADD CONSTRAINT fk_permissions_module
    FOREIGN KEY (resource) REFERENCES modules(key);


-- ── 3. Two roles, not three ──────────────────────────────────────────────────
--
-- MANAGER was a guess at a job nobody had described. The decision is two roles
-- that mean opposite things: Administrator has everything, Member has nothing
-- until it is given to them one permission at a time.
--
-- Anyone currently a MANAGER becomes a MEMBER. That is a downgrade and it is
-- deliberate — the alternative is silently promoting them to administrator.
-- Their per-person exceptions go with the role, for the same reason a role
-- change clears them in UserService: an exception is a difference from a role,
-- and theirs no longer exists.

DELETE FROM user_permissions
WHERE user_id IN (SELECT id FROM users WHERE role_id = (SELECT id FROM roles WHERE name = 'MANAGER'));

UPDATE users
SET role_id = (SELECT id FROM roles WHERE name = 'MEMBER')
WHERE role_id = (SELECT id FROM roles WHERE name = 'MANAGER');

DELETE FROM roles WHERE name = 'MANAGER';


-- ── 4. What each role grants ─────────────────────────────────────────────────

-- Administrator: everything, always. Re-run as a CROSS JOIN so the new modules
-- are included; ON CONFLICT skips what V2 already granted.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- Member: nothing. V2 gave them RECORD:READ outright, which is gone with the
-- RECORD module — this clears anything else that survived.
DELETE FROM role_permissions
WHERE role_id = (SELECT id FROM roles WHERE name = 'MEMBER');

UPDATE roles
SET description = 'Full access to every part of the system'
WHERE name = 'ADMIN';

UPDATE roles
SET description = 'Starts with nothing. Access is granted one permission at a time.'
WHERE name = 'MEMBER';


-- ── 5. Enforce the admin-only rule on data that already exists ───────────────
--
-- A per-person grant of an admin-only permission to a non-administrator should
-- not exist. The API refuses to create one; this clears any that predate the
-- rule, so the database and the rule agree from the first moment.

DELETE FROM user_permissions up
USING users u, permissions p, modules m
WHERE up.user_id = u.id
  AND up.permission_id = p.id
  AND p.resource = m.key
  AND m.admin_only = TRUE
  AND u.role_id <> (SELECT id FROM roles WHERE name = 'ADMIN');


-- ── 6. Ending a session, which nothing could do before ───────────────────────
--
-- Tokens are self-contained: once issued, nothing could take one back. So
-- resetting somebody's password did not end the session an intruder was already
-- in, and deactivating an account did not sign that person out.
--
-- Any token issued before this timestamp is refused. Set it to now() and every
-- session that account has, anywhere, stops working on the next request.
--
-- Existing rows get the epoch rather than now(), so nobody currently signed in
-- is thrown out by the migration itself.
ALTER TABLE users
    ADD COLUMN tokens_valid_from TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01T00:00:00Z';

COMMENT ON COLUMN users.tokens_valid_from IS
    'Tokens issued before this instant are refused. Bumped on password reset and deactivation.';
