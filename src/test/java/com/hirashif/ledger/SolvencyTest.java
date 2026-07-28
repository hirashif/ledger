package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
class SolvencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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

        // fund alice with $100
        ledger.post("open alice", List.of(new Entry(world, -10000), new Entry(alice, 10000)));
    }

    private long newAccount(String name, boolean allowsOverdraft) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency, allows_overdraft) VALUES (?, 'USD', ?) "
                        + "RETURNING id",
                Long.class, name, allowsOverdraft);
    }

    @Test
    void allowsATransferAliceCanAfford() {
        ledger.postSolvent("alice pays bob 30", List.of(
                new Entry(alice, -3000), new Entry(bob, 3000)));

        assertThat(ledger.balanceOf(alice)).isEqualTo(7000);
    }

    @Test
    void rejectsATransferAliceCannotAfford() {
        assertThatThrownBy(() -> ledger.postSolvent("alice pays bob 150", List.of(
                new Entry(alice, -15000), new Entry(bob, 15000))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(ledger.balanceOf(alice)).isEqualTo(10000);
    }

    @Test
    void fundingAccountsAreAllowedToGoNegative() {
        assertThat(ledger.balanceOf(world)).isEqualTo(-10000);
    }

    // alice has $100. twenty threads each try to send $80. only one can be affordable.
    @Test
    void concurrentWithdrawalsCannotOverdrawTheAccount() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                startTogether.await();
                try {
                    ledger.postSolvent("alice pays bob 80", List.of(
                            new Entry(alice, -8000), new Entry(bob, 8000)));
                    succeeded.incrementAndGet();
                } catch (RuntimeException expectedForMost) {
                    // insufficient funds, or a lock conflict
                }
                return null;
            });
        }
        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(ledger.balanceOf(alice)).isEqualTo(2000);
        assertThat(ledger.balanceOf(alice)).isNotNegative();
    }
}
