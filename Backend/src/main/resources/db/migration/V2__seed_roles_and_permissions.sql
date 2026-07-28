-- ─────────────────────────────────────────────────────────────────────────────
-- Reference data: the permission grid, the roles, and what each role can do.
--
-- This is static data the application depends on, so it lives in a migration
-- rather than in Java. Adding a resource later means adding rows here in a new
-- migration file — never editing this one, once it has run somewhere real.
--
-- NAMING: these names are deliberately domain-neutral placeholders. RECORD is
-- the thing this platform stores; rename it once the real domain noun is settled.
-- Avoid inventing domain vocabulary here — a wrong noun baked into a migration
-- and a permission grid is expensive to unpick later.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO permissions (resource, action, description) VALUES
    ('RECORD', 'CREATE', 'Create new records'),
    ('RECORD', 'READ',   'View records'),
    ('RECORD', 'UPDATE', 'Edit existing records'),
    ('RECORD', 'DELETE', 'Delete records'),
    ('RECORD', 'EXPORT', 'Export record data'),

    ('USER',   'CREATE', 'Create user accounts'),
    ('USER',   'READ',   'View user accounts'),
    ('USER',   'UPDATE', 'Edit user accounts'),
    ('USER',   'DELETE', 'Deactivate user accounts'),
    ('USER',   'EXPORT', 'Export the user list'),

    ('REPORT', 'READ',   'View reports'),
    ('REPORT', 'EXPORT', 'Export reports');

INSERT INTO roles (name, description) VALUES
    ('ADMIN',   'Full access to every part of the system'),
    ('MANAGER', 'Creates and maintains records, and reads reports'),
    ('MEMBER',  'Reads records. Nothing else.');

-- ADMIN: everything.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN';

-- MANAGER: owns record content, reads people and reports. Cannot delete records
-- or touch user accounts.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON (
       (p.resource = 'RECORD' AND p.action IN ('CREATE', 'READ', 'UPDATE'))
    OR (p.resource = 'USER'   AND p.action = 'READ')
    OR (p.resource = 'REPORT' AND p.action IN ('READ', 'EXPORT'))
)
WHERE r.name = 'MANAGER';

-- MEMBER: reads records. Nothing else.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON (p.resource = 'RECORD' AND p.action = 'READ')
WHERE r.name = 'MEMBER';
