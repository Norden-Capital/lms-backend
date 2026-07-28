-- ─────────────────────────────────────────────────────────────────────────────
-- Authentication & authorisation schema.
--
-- A user holds exactly one role; the role carries a set of permissions; per-user
-- exceptions layer on top. Effective permissions are:
--     (role's permissions ∪ user GRANTs) − user DENYs
-- ─────────────────────────────────────────────────────────────────────────────

-- Job titles in the company. Small, slow-changing list.
CREATE TABLE roles (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every individual action the system can allow, as a resource x action grid.
-- Adding a capability later is an INSERT here, never a schema change.
CREATE TABLE permissions (
    id          BIGSERIAL    PRIMARY KEY,
    resource    VARCHAR(50)  NOT NULL,
    action      VARCHAR(20)  NOT NULL,
    description VARCHAR(255),
    CONSTRAINT uq_permissions_resource_action UNIQUE (resource, action)
);

-- Which permissions come with which role. Change one row, every user with that
-- role is updated at once.
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id)       ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id                    BIGSERIAL    PRIMARY KEY,
    first_name            VARCHAR(100) NOT NULL,
    last_name             VARCHAR(100) NOT NULL,
    email                 VARCHAR(255) NOT NULL UNIQUE,   -- always stored lowercase
    phone                 VARCHAR(20),
    password_hash         VARCHAR(255) NOT NULL,
    role_id               BIGINT       NOT NULL REFERENCES roles(id),
    status                VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified_at     TIMESTAMPTZ,
    mfa_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret            VARCHAR(255),                    -- encrypted, not hashed
    mfa_confirmed_at      TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_status
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

CREATE INDEX idx_users_status  ON users (status);
CREATE INDEX idx_users_role_id ON users (role_id);

-- The exceptions: one person given (or denied) something their colleagues on the
-- same role don't have. UNIQUE(user_id, permission_id) makes it impossible to
-- hold both a GRANT and a DENY for the same permission.
CREATE TABLE user_permissions (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id)       ON DELETE CASCADE,
    permission_id BIGINT       NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    effect        VARCHAR(5)   NOT NULL,
    granted_by    BIGINT       REFERENCES users(id),
    granted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason        VARCHAR(255),
    CONSTRAINT uq_user_permissions_user_permission UNIQUE (user_id, permission_id),
    CONSTRAINT chk_user_permissions_effect CHECK (effect IN ('GRANT', 'DENY'))
);

CREATE INDEX idx_user_permissions_user ON user_permissions (user_id);

-- The way back in when the phone holding the authenticator app is lost.
CREATE TABLE mfa_recovery_codes (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  VARCHAR(255) NOT NULL,           -- hashed like a password
    used_at    TIMESTAMPTZ,                     -- non-null once redeemed: single use
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_mfa_recovery_codes_user ON mfa_recovery_codes (user_id);

CREATE TABLE password_reset_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,    -- raw token only ever exists in the email
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);
