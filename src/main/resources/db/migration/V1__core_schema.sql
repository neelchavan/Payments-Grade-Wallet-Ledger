CREATE TABLE users (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL,
    email       TEXT        NOT NULL,
    phone       TEXT        NOT NULL,
    kyc_status  TEXT        NOT NULL DEFAULT 'PENDING'
                CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX users_email_uq ON users (lower(email));
CREATE UNIQUE INDEX users_phone_uq ON users (phone);

-- One table for wallets and system accounts. Balance = credits - debits.
CREATE TABLE accounts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_type  TEXT          NOT NULL
                  CHECK (account_type IN ('USER_WALLET', 'CASH_IN', 'CASH_OUT', 'FEE_REVENUE')),
    user_id       BIGINT        REFERENCES users (id),
    system_code   TEXT,
    currency      CHAR(3)       NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance       NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT accounts_owner_ck CHECK ((account_type = 'USER_WALLET') = (user_id IS NOT NULL)),
    CONSTRAINT accounts_system_code_ck CHECK ((account_type = 'USER_WALLET') = (system_code IS NULL)),
    CONSTRAINT accounts_wallet_non_negative_ck CHECK (account_type <> 'USER_WALLET' OR balance >= 0)
);

CREATE UNIQUE INDEX accounts_system_code_uq ON accounts (system_code) WHERE system_code IS NOT NULL;
CREATE INDEX accounts_user_idx ON accounts (user_id) WHERE user_id IS NOT NULL;

CREATE TABLE transfers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_type         TEXT          NOT NULL
                          CHECK (transfer_type IN ('TOPUP', 'PEER_TRANSFER', 'WITHDRAWAL', 'FEE', 'REVERSAL')),
    status                TEXT          NOT NULL
                          CHECK (status IN ('HELD', 'COMPLETED', 'REJECTED', 'REVERSED')),
    from_account_id       BIGINT        NOT NULL REFERENCES accounts (id),
    to_account_id         BIGINT        NOT NULL REFERENCES accounts (id),
    amount                NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    note                  TEXT          CHECK (char_length(note) <= 200),
    reverses_transfer_id  BIGINT        REFERENCES transfers (id),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT transfers_distinct_accounts_ck CHECK (from_account_id <> to_account_id),
    CONSTRAINT transfers_reversal_ck CHECK ((transfer_type = 'REVERSAL') = (reverses_transfer_id IS NOT NULL))
);

CREATE UNIQUE INDEX transfers_reverses_uq ON transfers (reverses_transfer_id)
    WHERE reverses_transfer_id IS NOT NULL;
CREATE INDEX transfers_from_created_idx ON transfers (from_account_id, created_at);
CREATE INDEX transfers_pair_completed_idx ON transfers (from_account_id, to_account_id)
    WHERE status = 'COMPLETED';
CREATE INDEX transfers_held_idx ON transfers (created_at) WHERE status = 'HELD';

-- Append-only: each transfer has one DEBIT and one CREDIT entry of equal amount.
CREATE TABLE ledger_entries (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  BIGINT        NOT NULL REFERENCES transfers (id),
    account_id   BIGINT        NOT NULL REFERENCES accounts (id),
    direction    TEXT          NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount       NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, created_at DESC, id DESC);
CREATE INDEX ledger_entries_transfer_idx ON ledger_entries (transfer_id);

CREATE TABLE transfer_status_history (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  BIGINT      NOT NULL REFERENCES transfers (id),
    old_status   TEXT,
    new_status   TEXT        NOT NULL,
    actor        TEXT        NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX transfer_status_history_transfer_idx ON transfer_status_history (transfer_id, id);

-- user_id is NULL for unauthenticated calls such as POST /users.
CREATE TABLE idempotency_keys (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT      REFERENCES users (id),
    key              UUID        NOT NULL,
    request_hash     TEXT        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'IN_PROGRESS'
                     CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  INTEGER,
    response_body    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX idempotency_keys_scope_uq ON idempotency_keys ((COALESCE(user_id, 0)), key);
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);

-- Rows are locked with FOR UPDATE NOWAIT so only one worker runs a job at a time.
CREATE TABLE job_locks (
    name  TEXT PRIMARY KEY
);

CREATE TABLE reconciliation_runs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status        TEXT        NOT NULL
                  CHECK (status IN ('RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED')),
    triggered_by  TEXT        NOT NULL CHECK (triggered_by IN ('SCHEDULE', 'MANUAL')),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    issue_count   INTEGER,
    explanation   TEXT
);

CREATE TABLE reconciliation_issues (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES reconciliation_runs (id),
    check_name   TEXT        NOT NULL
                 CHECK (check_name IN ('GLOBAL_BALANCE', 'TRANSFER_BALANCE', 'ACCOUNT_BALANCE', 'OVERDRAFT', 'ORPHAN')),
    entity_type  TEXT        NOT NULL CHECK (entity_type IN ('GLOBAL', 'TRANSFER', 'ACCOUNT')),
    entity_id    BIGINT,
    detail       TEXT        NOT NULL,
    explanation  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX reconciliation_issues_run_idx ON reconciliation_issues (run_id);

-- params holds the thresholds for each rule (see seed migration).
CREATE TABLE risk_rules (
    rule_code  TEXT    PRIMARY KEY CHECK (rule_code IN ('VELOCITY', 'AMOUNT_SPIKE', 'NEW_PAYEE_LARGE')),
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    action     TEXT    NOT NULL CHECK (action IN ('HOLD', 'FLAG')),
    params     JSONB   NOT NULL
);

CREATE TABLE risk_flags (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT      NOT NULL REFERENCES transfers (id),
    rule_code   TEXT        NOT NULL REFERENCES risk_rules (rule_code),
    action      TEXT        NOT NULL CHECK (action IN ('HOLD', 'FLAG')),
    detail      TEXT        NOT NULL,
    decision    TEXT        CHECK (decision IN ('APPROVED', 'REJECTED')),
    decided_by  TEXT,
    decided_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX risk_flags_transfer_idx ON risk_flags (transfer_id);
