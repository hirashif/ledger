package com.hirashif.ledger;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // writes a transaction and all of its entries, or none of them
    @Transactional
    public long post(String description, List<Entry> entries) {
        validate(entries);

        Long transactionId = jdbc.queryForObject(
                "INSERT INTO transactions (description) VALUES (?) RETURNING id",
                Long.class, description);

        for (Entry entry : entries) {
            jdbc.update(
                    "INSERT INTO entries (transaction_id, account_id, amount_minor) VALUES (?, ?, ?)",
                    transactionId, entry.accountId(), entry.amountMinor());
            // same transaction as the entry, so the cache cannot drift on this path
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?",
                    entry.amountMinor(), entry.accountId());
        }

        return transactionId;
    }

    // same as post, but claims the idempotency key in the same transaction.
    // a duplicate key blows up the whole thing, which is the point.
    @Transactional
    public long postWithKey(String key, String requestHash, String description, List<Entry> entries) {
        long transactionId = post(description, entries);
        jdbc.update(
                "INSERT INTO idempotency_keys (key, request_hash, transaction_id) VALUES (?, ?, ?)",
                key, requestHash, transactionId);
        return transactionId;
    }

    // rejects a transfer that would overdraw an account
    @Transactional
    public long postSolvent(String description, List<Entry> entries) {
        validate(entries);

        // lock every account being debited before reading its balance, otherwise two
        // transfers both read the old balance and both decide they can afford it.
        // ascending id order so two transfers over the same pair cannot deadlock.
        entries.stream()
                .filter(entry -> entry.amountMinor() < 0)
                .map(Entry::accountId)
                .distinct()
                .sorted()
                .forEach(accountId -> jdbc.queryForObject(
                        "SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId));

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

        return post(description, entries);
    }

    private boolean allowsOverdraft(long accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT allows_overdraft FROM accounts WHERE id = ?", Boolean.class, accountId));
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
