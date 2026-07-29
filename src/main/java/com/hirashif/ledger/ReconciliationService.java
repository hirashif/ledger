package com.hirashif.ledger;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// the cached balance and the entries it came from can disagree. this finds out.
@Service
public class ReconciliationService {

    public record Drift(long accountId, String name, long cached, long computed) {

        public long difference() {
            return cached - computed;
        }
    }

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // rebuilds every balance from history and reports the ones that disagree
    public List<Drift> findDrift() {
        return jdbc.query("""
                SELECT a.id,
                       a.name,
                       a.balance_minor AS cached,
                       COALESCE(SUM(e.amount_minor), 0) AS computed
                FROM accounts a
                LEFT JOIN entries e ON e.account_id = a.id
                GROUP BY a.id, a.name, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(e.amount_minor), 0)
                ORDER BY a.id
                """,
                (rs, row) -> new Drift(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getLong("cached"),
                        rs.getLong("computed")));
    }

    // entries are the source of truth, so repair means rewriting the cache.
    // production should alert first, silent self-healing hides the real bug.
    @Transactional
    public int repair() {
        return jdbc.update("""
                UPDATE accounts a
                SET balance_minor = COALESCE(
                    (SELECT SUM(amount_minor) FROM entries e WHERE e.account_id = a.id), 0)
                WHERE a.balance_minor <> COALESCE(
                    (SELECT SUM(amount_minor) FROM entries e WHERE e.account_id = a.id), 0)
                """);
    }
}
