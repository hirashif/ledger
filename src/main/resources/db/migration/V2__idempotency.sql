-- one row per accepted request. the primary key is what makes a retry safe:
-- the second insert loses instead of moving money again.
CREATE TABLE idempotency_keys (
    key            TEXT PRIMARY KEY,
    request_hash   TEXT        NOT NULL,
    transaction_id BIGINT      NOT NULL REFERENCES transactions (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
