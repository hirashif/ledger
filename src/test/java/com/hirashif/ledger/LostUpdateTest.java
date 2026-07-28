package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// two connections driven by hand, so the interleaving is forced instead of hoped for
@SpringBootTest
@Testcontainers
class LostUpdateTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    DataSource dataSource;

    @Autowired
    LedgerService ledger;

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
    void bothTransactionsReadTheSameBalanceAndBothWithdraw() throws Exception {
        try (Connection a = dataSource.getConnection();
                Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            // both check the balance before either one writes
            long seenByA = balance(a, alice);
            long seenByB = balance(b, alice);
            assertThat(seenByA).isEqualTo(10000);
            assertThat(seenByB).isEqualTo(10000);

            // each decides $80 is affordable, because on its own it is
            withdraw(a, "a pays bob 80");
            withdraw(b, "b pays bob 80");
            a.commit();
            b.commit();
        }

        assertThat(ledger.balanceOf(alice)).isEqualTo(-6000);
    }

    // the fix: the second reader cannot start checking until the first one commits
    @Test
    void lockingSerializesTwoTransfersOverTheSameAccount() throws Exception {
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        AtomicBoolean secondFinished = new AtomicBoolean(false);

        Thread first = new Thread(() -> {
            try (Connection a = dataSource.getConnection()) {
                a.setAutoCommit(false);
                lockAccount(a, alice);
                firstHoldsTheLock.countDown();
                firstMayCommit.await();
                withdraw(a, "first pays bob 80");
                a.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        first.start();
        assertThat(firstHoldsTheLock.await(5, TimeUnit.SECONDS)).isTrue();

        Thread second = new Thread(() -> {
            try {
                ledger.postSolvent("second pays bob 80",
                        List.of(new Entry(alice, -8000), new Entry(bob, 8000)));
            } catch (InsufficientFundsException expected) {
                // alice only had $100, the first transfer already took $80
            } finally {
                secondFinished.set(true);
            }
        });
        second.start();

        // it is stuck on the row lock, not racing ahead on a stale balance
        Thread.sleep(500);
        assertThat(secondFinished).isFalse();

        firstMayCommit.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(ledger.balanceOf(alice)).isEqualTo(2000);
        assertThat(ledger.balanceOf(alice)).isNotNegative();
    }

    private void lockAccount(Connection c, long accountId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    private long balance(Connection c, long accountId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM entries WHERE account_id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void withdraw(Connection c, String description) throws Exception {
        long transactionId;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions (description) VALUES (?) RETURNING id")) {
            ps.setString(1, description);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                transactionId = rs.getLong(1);
            }
        }
        insertEntry(c, transactionId, alice, -8000);
        insertEntry(c, transactionId, bob, 8000);
    }

    private void insertEntry(Connection c, long transactionId, long accountId, long amount)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO entries (transaction_id, account_id, amount_minor) VALUES (?, ?, ?)",
                Statement.NO_GENERATED_KEYS)) {
            ps.setLong(1, transactionId);
            ps.setLong(2, accountId);
            ps.setLong(3, amount);
            ps.executeUpdate();
        }
    }
}
