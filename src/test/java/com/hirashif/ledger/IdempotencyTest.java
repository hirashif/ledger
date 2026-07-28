package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class IdempotencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TransferService transfers;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE idempotency_keys, entries, transactions, accounts "
                + "RESTART IDENTITY CASCADE");
        alice = newAccount("alice");
        bob = newAccount("bob");
    }

    private long newAccount(String name) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency) VALUES (?, 'USD') RETURNING id",
                Long.class, name);
    }

    private List<Entry> fiftyDollars() {
        return List.of(new Entry(alice, -5000), new Entry(bob, 5000));
    }

    @Test
    void retryWithTheSameKeyDoesNotMoveMoneyTwice() {
        long first = transfers.transfer("req-1", "alice pays bob 50", fiftyDollars());
        long second = transfers.transfer("req-1", "alice pays bob 50", fiftyDollars());

        assertThat(second).isEqualTo(first);
        assertThat(ledger.balanceOf(bob)).isEqualTo(5000);
        assertThat(countRows("transactions")).isEqualTo(1);
    }

    @Test
    void differentKeysPostSeparateTransfers() {
        transfers.transfer("req-1", "alice pays bob 50", fiftyDollars());
        transfers.transfer("req-2", "alice pays bob 50 again", fiftyDollars());

        assertThat(ledger.balanceOf(bob)).isEqualTo(10000);
        assertThat(countRows("transactions")).isEqualTo(2);
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() {
        transfers.transfer("req-1", "alice pays bob 50", fiftyDollars());

        assertThatThrownBy(() -> transfers.transfer("req-1", "alice pays bob 90",
                List.of(new Entry(alice, -9000), new Entry(bob, 9000))))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(ledger.balanceOf(bob)).isEqualTo(5000);
    }

    // the retry storm: same key from many threads at once, money moves once
    @Test
    void concurrentRetriesPostExactlyOneTransfer() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        Set<Long> returned = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                startTogether.await();
                returned.add(transfers.transfer("req-1", "alice pays bob 50", fiftyDollars()));
                return null;
            });
        }
        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(returned).hasSize(1);
        assertThat(countRows("transactions")).isEqualTo(1);
        assertThat(ledger.balanceOf(bob)).isEqualTo(5000);
    }

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
