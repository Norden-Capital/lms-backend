-- ─────────────────────────────────────────────────────────────────────────────
-- The Inbox: a module of its own, with the verbs a mailbox actually has.
--
-- Everything the permission screens draw comes from these two tables, so adding
-- the module here is the whole of "add it to the frontend" as well — that is why
-- `lib/permissions.ts` was deleted in V3. Nothing in TypeScript describes the
-- model any more, so nothing in TypeScript can disagree with it.
-- ─────────────────────────────────────────────────────────────────────────────


-- ── 1. The module ────────────────────────────────────────────────────────────
--
-- Its own group rather than filed under an existing one. Inbox is reached from
-- the button in the navbar, not from the sidebar, so putting it under Overview
-- or Customers would describe a place it does not appear.
--
-- Position 7 keeps Management last. `uq_modules_position` means positions are
-- unique, so this cannot quietly land on top of another module.
--
-- admin_only FALSE, deliberately: this is a screen anybody may be given, which
-- is exactly what separates it from Management.

INSERT INTO modules (key, label, description, group_key, group_label, position, admin_only) VALUES
    ('INBOX', 'Inbox', 'Messages, and what may be done with them.', 'INBOX', 'Inbox', 7, FALSE);


-- ── 2. Its actions ───────────────────────────────────────────────────────────
--
-- Mail verbs rather than the CREATE/UPDATE/DELETE set the other modules use,
-- because this module is a mailbox and those are the things people do to mail.
--
-- ⚠️ SEND, ARCHIVE and STAR are new actions in this database. Two consequences,
-- both intended:
--
--   • The permission matrix on a user's page draws one column per action that
--     exists anywhere, so it grows from five columns to eight. Every other
--     module leaves the three new ones blank.
--   • `AccessCatalogueService.ACTION_ORDER` decides what order actions appear
--     in. Anything unlisted there sorts to the end, so the three were added to
--     it — without that they would arrive in whatever order the rows came back.
--
-- ARCHIVE and STAR are separate rather than folded into one UPDATE because they
-- are separate decisions: filing something away is not the same trust as marking
-- it important, and a single permission covering both cannot express that.

INSERT INTO permissions (resource, action, description) VALUES
    ('INBOX', 'READ',    'Open the inbox and read messages'),
    ('INBOX', 'SEND',    'Send messages'),
    ('INBOX', 'DELETE',  'Delete messages'),
    ('INBOX', 'ARCHIVE', 'Archive messages'),
    ('INBOX', 'STAR',    'Star messages');


-- ── 3. Administrator gets them, as it gets everything ────────────────────────
--
-- ⚠️ <b>This is the step that is easy to forget, and it fails silently.</b> The
-- CROSS JOIN in V3 ran once, against the permissions that existed that day. A
-- permission added later is granted to nobody at all — including Administrator,
-- whose whole definition is "everything" — and the only symptom is an
-- administrator being refused a screen with no explanation anywhere.
--
-- Written as the same CROSS JOIN rather than five explicit rows, so it stays
-- correct if this migration is ever extended.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;


-- Member is untouched on purpose. It starts with nothing and is granted one
-- permission at a time, which is the decision V3 recorded.
