-- no balance column on purpose: a balance is derived from entries.
CREATE TABLE accounts (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT        NOT NULL,
    currency   CHAR(3)     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- one row per business event, entries hang off it
CREATE TABLE transactions (
    id          BIGSERIAL PRIMARY KEY,
    description TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- append-only. integer minor units, negative debits and positive credits.
CREATE TABLE entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES transactions (id),
    account_id     BIGINT      NOT NULL REFERENCES accounts (id),
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor <> 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX entries_account_id_idx     ON entries (account_id);
CREATE INDEX entries_transaction_id_idx ON entries (transaction_id);

-- the invariant: entries in a transaction sum to zero
CREATE FUNCTION assert_transaction_balances() RETURNS TRIGGER AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0) INTO total
    FROM entries
    WHERE transaction_id = NEW.transaction_id;

    IF total <> 0 THEN
        -- sqlstate class 23 so callers catch a constraint violation, not a generic P0001
        RAISE EXCEPTION 'transaction % does not balance: entries sum to %',
            NEW.transaction_id, total
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- deferred so this runs at commit, once every entry is written.
-- a per-row check would reject a valid transfer while it is half written.
CREATE CONSTRAINT TRIGGER entries_must_balance
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION assert_transaction_balances();

-- mistakes get a reversing transaction, never an edit
CREATE FUNCTION reject_entry_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'entries are append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_no_update_or_delete
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW
EXECUTE FUNCTION reject_entry_mutation();
