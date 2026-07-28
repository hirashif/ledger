package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// runs against a real postgres so the schema constraints are under test too
@SpringBootTest
@Testcontainers
class LedgerServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        // truncate, not delete: the append-only trigger blocks DELETE on entries,
        // and row level triggers do not fire on truncate.
        jdbc.execute("TRUNCATE entries, transactions, accounts RESTART IDENTITY CASCADE");
        alice = newAccount("alice");
        bob = newAccount("bob");
    }

    private long newAccount(String name) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency) VALUES (?, 'USD') RETURNING id",
                Long.class, name);
    }

    @Test
    void transferMovesMoneyAndLeavesTheTotalUnchanged() {
        ledger.post("alice pays bob 50", List.of(
                new Entry(alice, -5000),
                new Entry(bob, 5000)));

        assertThat(ledger.balanceOf(alice)).isEqualTo(-5000);
        assertThat(ledger.balanceOf(bob)).isEqualTo(5000);
        assertThat(totalOfAllEntries()).isZero();
    }

    @Test
    void failurePartwayThroughLeavesNothingBehind() {
        long unknownAccount = 999_999L;

        assertThatThrownBy(() -> ledger.post("transfer to nobody", List.of(
                new Entry(alice, -5000),
                new Entry(unknownAccount, 5000))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // the transactions row goes in before the failing entry, so an orphan
        // would be sitting here if the rollback did not work
        assertThat(countRows("transactions")).isZero();
        assertThat(countRows("entries")).isZero();
        assertThat(ledger.balanceOf(alice)).isZero();
    }

    // bypasses the service to prove the database enforces the invariant on its own
    @Test
    void databaseRejectsAnUnbalancedWrite() {
        Long transactionId = jdbc.queryForObject(
                "INSERT INTO transactions (description) VALUES ('bypassing the service') RETURNING id",
                Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO entries (transaction_id, account_id, amount_minor) VALUES (?, ?, ?)",
                transactionId, alice, -5000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long totalOfAllEntries() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM entries", Long.class);
    }

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
