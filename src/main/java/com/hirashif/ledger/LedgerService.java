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
        }

        return transactionId;
    }

    public long balanceOf(long accountId) {
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
