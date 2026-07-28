-- accounts hold no balance column on purpose: a balance is derived from entries.
CREATE TABLE accounts (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT        NOT NULL,
    currency   CHAR(3)     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- one row per business event ("transfer A to B"), the thing entries belong to.
CREATE TABLE transactions (
    id          BIGSERIAL PRIMARY KEY,
    description TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- the actual movements of money. append-only, integer minor units (cents).
-- negative = debit (money leaves), positive = credit (money arrives).
CREATE TABLE entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES transactions (id),
    account_id     BIGINT      NOT NULL REFERENCES accounts (id),
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor <> 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX entries_account_id_idx     ON entries (account_id);
CREATE INDEX entries_transaction_id_idx ON entries (transaction_id);

-- the invariant: a transaction's entries must sum to zero.
-- money moves between accounts, it is never created or destroyed.
CREATE FUNCTION assert_transaction_balances() RETURNS TRIGGER AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0) INTO total
    FROM entries
    WHERE transaction_id = NEW.transaction_id;

    IF total <> 0 THEN
        -- ERRCODE puts this in SQLSTATE class 23 (integrity constraint violation) instead of
        -- the generic P0001, so application code can catch it as what it actually is.
        RAISE EXCEPTION 'transaction % does not balance: entries sum to %',
            NEW.transaction_id, total
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- DEFERRABLE INITIALLY DEFERRED is the whole trick: the check runs at COMMIT,
-- once every entry is written. Checking after each INSERT would reject a valid
-- transfer, because a half-written transfer never sums to zero.
CREATE CONSTRAINT TRIGGER entries_must_balance
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION assert_transaction_balances();

-- entries are append-only. a mistake is fixed by posting a reversing
-- transaction, never by editing history. that is what makes the ledger auditable.
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
