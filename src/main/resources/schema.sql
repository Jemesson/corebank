CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT PRIMARY KEY,
    document VARCHAR(14) NOT NULL,
    total_balance NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    balance NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS pix_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    end_to_end_id VARCHAR(100) NOT NULL UNIQUE,
    origin_account_id BIGINT REFERENCES accounts(id),
    target_pix_key VARCHAR(100) NOT NULL,
    value NUMERIC(18,2) NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pix_tx_account_created
    ON pix_transactions (origin_account_id, created_at DESC);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(255) NOT NULL,
    endpoint VARCHAR(100) NOT NULL,
    account_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_ref VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (key, endpoint)
);

CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregated_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_unprocessed
    ON outbox (id) WHERE processed_at IS NULL;

CREATE TABLE IF NOT EXISTS card_authorizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authorization_code VARCHAR(50) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    amount NUMERIC(18,2) NOT NULL,
    merchant VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_card_auth_account
    ON card_authorizations (account_id, created_at DESC);

INSERT INTO accounts (id, document, total_balance, balance, version)
VALUES (1001, '12345678901', 5000.00, 5000.00, 0)
ON CONFLICT (id) DO NOTHING;
