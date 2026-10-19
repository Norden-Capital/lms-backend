-- The Owner: one account, above the administrators.
--
-- Administrators cannot change each other's role or switch each other off
-- (decided 2026-10-19), which left nobody able to remove an administrator
-- short of editing this database by hand. The Owner is that somebody: it holds
-- everything an administrator holds, plus deactivating an administrator.
--
-- Exactly one account has it — the bootstrap account — and nothing in the API
-- can give it to anybody else or take it away.

INSERT INTO roles (name, description)
VALUES ('OWNER', 'The account that owns the platform. Everything an administrator can do, and can deactivate administrators.');

-- Everything, like ADMIN. ⚠️ A later migration that grants new permissions to
-- ADMIN with `WHERE r.name = 'ADMIN'` must say `IN ('ADMIN', 'OWNER')` instead,
-- or the Owner silently falls behind the administrators.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'OWNER'
ON CONFLICT DO NOTHING;

-- The bootstrap account becomes the Owner: the first administrator ever made,
-- which is the one the bootstrap created on an empty database. On a database
-- with no users yet this changes nothing, and the bootstrap itself creates the
-- Owner instead (see DatabaseSeeder).
UPDATE users
SET role_id = (SELECT id FROM roles WHERE name = 'OWNER'),
    updated_at = now()
WHERE id = (
    SELECT u.id
    FROM users u
    JOIN roles r ON r.id = u.role_id
    WHERE r.name = 'ADMIN'
    ORDER BY u.created_at, u.id
    LIMIT 1
);

-- At most one Owner, enforced by the database rather than trusted to the code.
-- A unique index on a constant, limited to the Owner's rows, admits one row and
-- refuses a second. Built with the role's id because an index condition cannot
-- look anything up.
DO $$
BEGIN
    EXECUTE format(
        'CREATE UNIQUE INDEX uq_users_single_owner ON users ((true)) WHERE role_id = %s',
        (SELECT id FROM roles WHERE name = 'OWNER'));
END
$$;
