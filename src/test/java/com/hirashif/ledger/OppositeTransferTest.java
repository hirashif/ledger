package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
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

// a->b and b->a at the same moment. with only the debited account locked, each
// transfer held one row and waited on the other, and postgres aborted one of them.
@SpringBootTest
@Testcontainers
class OppositeTransferTest {

    static final int ROUNDS = 200;

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
        jdbc.execute("TRUNCATE idempotency_keys, entries, transactions, accounts "
                + "RESTART IDENTITY CASCADE");
        long world = ledger.createAccount("world", "USD", true);
        alice = ledger.createAccount("alice", "USD", false);
        bob = ledger.createAccount("bob", "USD", false);
        ledger.post("open alice", List.of(new Entry(world, -10000), new Entry(alice, 10000)));
        ledger.post("open bob", List.of(new Entry(world, -10000), new Entry(bob, 10000)));
    }

    @Test
    void oppositeTransfersOverTheSamePairDoNotDeadlock() throws Exception {
        // the barrier releases both threads together each round, so the two
        // transfers really do overlap instead of taking turns
        CyclicBarrier bothReady = new CyclicBarrier(2);
        AtomicInteger failed = new AtomicInteger();

        Thread aliceToBob = new Thread(() -> transferRepeatedly(bothReady, failed, alice, bob));
        Thread bobToAlice = new Thread(() -> transferRepeatedly(bothReady, failed, bob, alice));
        aliceToBob.start();
        bobToAlice.start();
        aliceToBob.join();
        bobToAlice.join();

        assertThat(failed).hasValue(0);
        // a dollar each way every round, so both end where they started
        assertThat(ledger.balanceOf(alice)).isEqualTo(10000);
        assertThat(ledger.balanceOf(bob)).isEqualTo(10000);
    }

    private void transferRepeatedly(CyclicBarrier bothReady, AtomicInteger failed,
            long from, long to) {
        for (int round = 0; round < ROUNDS; round++) {
            try {
                bothReady.await();
                ledger.postSolvent("round " + round,
                        List.of(new Entry(from, -100), new Entry(to, 100)));
            } catch (Exception e) {
                failed.incrementAndGet();
            }
        }
    }
}
