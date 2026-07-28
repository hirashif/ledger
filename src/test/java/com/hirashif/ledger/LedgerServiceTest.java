package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs against a real Postgres, so the schema constraints are under test too. */
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
        jdbc.update("DELETE FROM entries WHERE true");
        jdbc.update("DELETE FROM transactions WHERE true");
        jdbc.update("DELETE FROM accounts WHERE true");
        alice = newAccount("alice");
        bob = newAccount("bob");
    }

    private long newAccount(String name) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency) VALUES (?, 'USD') RETURNING id",
                Long.class, name);
    }

    @Test
    @DisplayName("a transfer moves money and leaves the total unchanged")
    void postsBalancedTransfer() {
        ledger.post("alice pays bob 50", List.of(
                new Entry(alice, -5000),
                new Entry(bob, 5000)));

        assertThat(ledger.balanceOf(alice)).isEqualTo(-5000);
        assertThat(ledger.balanceOf(bob)).isEqualTo(5000);
        assertThat(totalOfAllEntries()).isZero();
    }

    @Test
    @DisplayName("a failure partway through leaves nothing behind")
    void rollsBackOnFailure() {
        long unknownAccount = 999_999L;

        assertThatThrownBy(() -> ledger.post("transfer to nobody", List.of(
                new Entry(alice, -5000),
                new Entry(unknownAccount, 5000))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // the transactions row was written before the failing entry. if the rollback
        // did not work, an orphan transaction would be sitting here.
        assertThat(countRows("transactions")).isZero();
        assertThat(countRows("entries")).isZero();
        assertThat(ledger.balanceOf(alice)).isZero();
    }

    @Test
    @DisplayName("the database refuses an unbalanced write even if the service does not")
    void databaseEnforcesTheInvariant() {
        Long transactionId = jdbc.queryForObject(
                "INSERT INTO transactions (description) VALUES ('bypassing the service') RETURNING id",
                Long.class);

        assertThatThrownBy(() -> {
            jdbc.update("INSERT INTO entries (transaction_id, account_id, amount_minor) VALUES (?, ?, ?)",
                    transactionId, alice, -5000);
            // the deferred trigger fires when this statement's transaction commits
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long totalOfAllEntries() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM entries", Long.class);
    }

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
