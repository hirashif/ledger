-- summing every entry an account has ever had is O(n) and only gets slower.
-- this column is a cache of that sum, written in the same transaction as the entries.
ALTER TABLE accounts ADD COLUMN balance_minor BIGINT NOT NULL DEFAULT 0;

UPDATE accounts a
SET balance_minor = COALESCE(
    (SELECT SUM(amount_minor) FROM entries e WHERE e.account_id = a.id), 0);
