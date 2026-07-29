package com.hirashif.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TransferService {

    private final LedgerService ledger;
    private final JdbcTemplate jdbc;

    public TransferService(LedgerService ledger, JdbcTemplate jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    // at most once per key. a retry gets the original transaction id back.
    // no "does this key exist" check first: two retries racing would both find
    // nothing and both post. let the primary key pick the winner instead.
    public long transfer(String key, String description, List<Entry> entries) {
        String hash = requestHash(description, entries);
        try {
            return ledger.postWithKey(key, hash, description, entries);
        } catch (DuplicateKeyException alreadyHandled) {
            return replay(key, hash);
        }
    }

    private long replay(String key, String hash) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT request_hash, transaction_id FROM idempotency_keys WHERE key = ?", key);
        } catch (EmptyResultDataAccessException gone) {
            throw new IdempotencyConflictException("key " + key + " conflicted but then vanished");
        }

        // same key, different request body. that is a client bug, not a retry,
        // and silently returning the old result would hide it.
        if (!hash.equals(row.get("request_hash"))) {
            throw new IdempotencyConflictException(
                    "key " + key + " was already used for a different request");
        }
        return (Long) row.get("transaction_id");
    }

    // entries are sorted so the same transfer hashes the same regardless of argument order
    static String requestHash(String description, List<Entry> entries) {
        StringBuilder canonical = new StringBuilder(description);
        entries.stream()
                .sorted(Comparator.comparingLong(Entry::accountId)
                        .thenComparingLong(Entry::amountMinor))
                .forEach(e -> canonical.append('|')
                        .append(e.accountId()).append(':').append(e.amountMinor()));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
