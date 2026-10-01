package com.hirashif.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // writes a transaction and all of its entries, or none of them.
    // package private on purpose: this skips the solvency check, so it is not
    // something a caller outside the ledger should be able to reach.
    @Transactional
    long post(String description, List<Entry> entries) {
        validate(entries);
        long transactionId = newTransaction(description);
        writeEntries(transactionId, entries);
        return transactionId;
    }

    // rejects a transfer that would overdraw an account
    @Transactional
    public long postSolvent(String description, List<Entry> entries) {
        validate(entries);
        long transactionId = newTransaction(description);
        lockAccounts(entries);
        assertSolvent(entries);
        writeEntries(transactionId, entries);
        return transactionId;
    }

    // solvency checked and idempotent. the key is claimed before any business rule
    // runs, so a replay is answered as a replay even if its body would be rejected.
    @Transactional
    public long postWithKey(String key, String requestHash, String description,
            List<Entry> entries) {
        validate(entries);
        long transactionId = newTransaction(description);
        claimKey(key, requestHash, transactionId);
        lockAccounts(entries);
        assertSolvent(entries);
        writeEntries(transactionId, entries);
        return transactionId;
    }

    private long newTransaction(String description) {
        return jdbc.queryForObject(
                "INSERT INTO transactions (description) VALUES (?) RETURNING id",
                Long.class, description);
    }

    // a duplicate key trips the primary key here and takes the whole transaction down
    private void claimKey(String key, String requestHash, long transactionId) {
        jdbc.update("INSERT INTO idempotency_keys (key, request_hash, transaction_id) "
                + "VALUES (?, ?, ?)", key, requestHash, transactionId);
    }

    // lock before reading any balance, otherwise two transfers both read the old
    // balance and both decide they can afford it. every account in the transaction
    // is locked, not only the debited ones: the credit side takes a row lock too
    // when its entry is written, so a->b and b->a each held one account and waited
    // on the other. ascending id order means they queue instead.
    private void lockAccounts(List<Entry> entries) {
        entries.stream()
                .map(Entry::accountId)
                .distinct()
                .sorted()
                .forEach(accountId -> jdbc.queryForObject(
                        "SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId));
    }

    private void assertSolvent(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.amountMinor() >= 0 || allowsOverdraft(entry.accountId())) {
                continue;
            }
            long balance = balanceOf(entry.accountId());
            if (balance + entry.amountMinor() < 0) {
                throw new InsufficientFundsException("account " + entry.accountId()
                        + " has " + balance + " and cannot move " + entry.amountMinor());
            }
        }
    }

    private void writeEntries(long transactionId, List<Entry> entries) {
        for (Entry entry : entries) {
            jdbc.update(
                    "INSERT INTO entries (transaction_id, account_id, amount_minor) VALUES (?, ?, ?)",
                    transactionId, entry.accountId(), entry.amountMinor());
            // same transaction as the entry, so the cache cannot drift on this path
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?",
                    entry.amountMinor(), entry.accountId());
        }
    }

    private boolean allowsOverdraft(long accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT allows_overdraft FROM accounts WHERE id = ?", Boolean.class, accountId));
    }

    public long createAccount(String name, String currency, boolean allowsOverdraft) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency, allows_overdraft) VALUES (?, ?, ?) "
                        + "RETURNING id",
                Long.class, name, currency, allowsOverdraft);
    }

    public Optional<Account> findAccount(long accountId) {
        return jdbc.query(
                "SELECT id, name, currency, balance_minor, allows_overdraft FROM accounts "
                        + "WHERE id = ?",
                (rs, row) -> new Account(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("currency"),
                        rs.getLong("balance_minor"),
                        rs.getBoolean("allows_overdraft")),
                accountId).stream().findFirst();
    }

    public List<PostedEntry> entriesFor(long accountId, int limit) {
        return jdbc.query(
                "SELECT id, transaction_id, account_id, amount_minor, created_at FROM entries "
                        + "WHERE account_id = ? ORDER BY id DESC LIMIT ?",
                (rs, row) -> new PostedEntry(
                        rs.getLong("id"),
                        rs.getLong("transaction_id"),
                        rs.getLong("account_id"),
                        rs.getLong("amount_minor"),
                        rs.getTimestamp("created_at").toInstant()),
                accountId, limit);
    }

    // the cached column
    public long balanceOf(long accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?", Long.class, accountId);
        return balance == null ? 0L : balance;
    }

    // the truth, rebuilt from history. slower, and what reconciliation compares against.
    public long recomputedBalanceOf(long accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM entries WHERE account_id = ?",
                Long.class, accountId);
        return balance == null ? 0L : balance;
    }

    static void validate(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new UnbalancedTransactionException("a transaction needs entries");
        }
        if (entries.size() < 2) {
            throw new UnbalancedTransactionException(
                    "a transaction needs at least two entries, got " + entries.size());
        }

        long sum = 0;
        for (Entry entry : entries) {
            if (entry.amountMinor() == 0) {
                throw new UnbalancedTransactionException(
                        "entry on account " + entry.accountId() + " moves nothing");
            }
            // addExact throws instead of wrapping. a plain += lets amounts near
            // Long.MAX_VALUE overflow back to zero and look balanced.
            try {
                sum = Math.addExact(sum, entry.amountMinor());
            } catch (ArithmeticException overflow) {
                throw new UnbalancedTransactionException(
                        "entry amounts overflow a signed 64 bit total");
            }
        }

        if (sum != 0) {
            throw new UnbalancedTransactionException("entries must sum to zero, they sum to " + sum);
        }
    }
}
