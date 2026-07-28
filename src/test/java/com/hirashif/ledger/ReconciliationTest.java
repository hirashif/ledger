package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ReconciliationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    LedgerService ledger;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    JdbcTemplate jdbc;

    long world;
    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE idempotency_keys, entries, transactions, accounts "
                + "RESTART IDENTITY CASCADE");
        world = newAccount("world", true);
        alice = newAccount("alice", false);
        bob = newAccount("bob", false);
        ledger.post("open alice", List.of(new Entry(world, -10000), new Entry(alice, 10000)));
    }

    private long newAccount(String name, boolean allowsOverdraft) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency, allows_overdraft) VALUES (?, 'USD', ?) "
                        + "RETURNING id",
                Long.class, name, allowsOverdraft);
    }

    @Test
    void cachedBalanceMatchesTheEntriesItCameFrom() {
        ledger.post("alice pays bob 30", List.of(new Entry(alice, -3000), new Entry(bob, 3000)));

        assertThat(ledger.balanceOf(alice)).isEqualTo(7000);
        assertThat(ledger.recomputedBalanceOf(alice)).isEqualTo(7000);
        assertThat(reconciliation.findDrift()).isEmpty();
    }

    @Test
    void driftIsDetectedWhenTheCacheIsWrong() {
        // simulate the bug: something wrote a balance without writing entries
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor + 50000 WHERE id = ?", alice);

        List<ReconciliationService.Drift> drift = reconciliation.findDrift();

        assertThat(drift).hasSize(1);
        assertThat(drift.getFirst().accountId()).isEqualTo(alice);
        assertThat(drift.getFirst().cached()).isEqualTo(60000);
        assertThat(drift.getFirst().computed()).isEqualTo(10000);
        assertThat(drift.getFirst().difference()).isEqualTo(50000);
    }

    @Test
    void repairRewritesTheCacheFromHistory() {
        jdbc.update("UPDATE accounts SET balance_minor = 999999 WHERE id = ?", bob);

        assertThat(reconciliation.repair()).isEqualTo(1);
        assertThat(reconciliation.findDrift()).isEmpty();
        assertThat(ledger.balanceOf(bob)).isZero();
    }

    @Test
    void everyAccountIsCheckedNotJustTheOnesWithEntries() {
        long untouched = newAccount("untouched", false);
        jdbc.update("UPDATE accounts SET balance_minor = 1 WHERE id = ?", untouched);

        assertThat(reconciliation.findDrift())
                .extracting(ReconciliationService.Drift::accountId)
                .containsExactly(untouched);
    }
}
