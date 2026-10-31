-- Clients and loan applications — decided 2026-10-31.
--
-- One row per person, known by their email, and a new application each time
-- they apply. An application is ABANDONED as soon as step 1 is saved and stays
-- the same record when step 2 turns it into a LEAD (or FAILED).
--
-- The answers hold birth dates, income and addresses, so they are stored
-- encrypted (see EncryptionService). Only what the lists must filter on is plain.
CREATE TABLE clients (
    id          BIGSERIAL     PRIMARY KEY,
    public_id   VARCHAR(16)   NOT NULL UNIQUE,
    email       VARCHAR(255)  NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- step_token is what the form holds between step 1 and step 2. public_id is what
-- the LMS shows and LeadScout gets back, so it must not open anything.
-- A brand with applications cannot be removed, only switched off.
CREATE TABLE loan_applications (
    id                BIGSERIAL     PRIMARY KEY,
    public_id         VARCHAR(16)   NOT NULL UNIQUE,
    step_token        VARCHAR(64)   NOT NULL UNIQUE,
    client_id         BIGINT        NOT NULL REFERENCES clients (id),
    brand_id          BIGINT        NOT NULL REFERENCES brands (id),
    status            VARCHAR(20)   NOT NULL
                      CHECK (status IN ('ABANDONED', 'RETURNED', 'SENDING', 'LEAD', 'FAILED')),
    mode              VARCHAR(10)   NOT NULL CHECK (mode IN ('fake', 'test', 'live')),
    requested_amount  INTEGER       NOT NULL,
    language          VARCHAR(2)    NOT NULL,
    answers           TEXT          NOT NULL,
    consent_at        TIMESTAMPTZ   NOT NULL,
    leadscout_status  VARCHAR(20),
    redirect_url      VARCHAR(2000),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_loan_applications_client ON loan_applications (client_id);
CREATE INDEX idx_loan_applications_list ON loan_applications (status, created_at DESC);
CREATE INDEX idx_loan_applications_brand ON loan_applications (brand_id, created_at DESC);

-- Every send to LeadScout and what came back, so a lead can always be explained.
-- request and response may hold personal data, so they are encrypted too.
CREATE TABLE application_events (
    id                BIGSERIAL     PRIMARY KEY,
    application_id    BIGINT        NOT NULL REFERENCES loan_applications (id) ON DELETE CASCADE,
    mode              VARCHAR(10)   NOT NULL,
    request           TEXT,
    response          TEXT,
    http_status       INTEGER,
    leadscout_status  VARCHAR(20),
    problem           VARCHAR(500),
    duration_ms       INTEGER,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_application_events_application ON application_events (application_id, created_at);
