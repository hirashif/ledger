package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LedgerApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerService ledger;

    long world;
    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE idempotency_keys, entries, transactions, accounts "
                + "RESTART IDENTITY CASCADE");
        world = ledger.createAccount("world", "USD", true);
        alice = ledger.createAccount("alice", "USD", false);
        bob = ledger.createAccount("bob", "USD", false);
        ledger.post("open alice", List.of(new Entry(world, -10000), new Entry(alice, 10000)));
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    @Test
    void createsAnAccount() throws Exception {
        mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "carol", "currency", "USD"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("carol"))
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void postsATransferAndMovesTheBalance() throws Exception {
        mvc.perform(post("/transfers")
                        .header("Idempotency-Key", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LedgerController.TransferRequest("alice pays bob 30",
                                List.of(new Entry(alice, -3000), new Entry(bob, 3000))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNumber());

        mvc.perform(get("/accounts/" + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(7000));
    }

    @Test
    void retryingWithTheSameKeyDoesNotMoveMoneyTwice() throws Exception {
        String payload = body(new LedgerController.TransferRequest("alice pays bob 30",
                List.of(new Entry(alice, -3000), new Entry(bob, 3000))));

        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(post("/transfers")
                            .header("Idempotency-Key", "req-retry")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated());
        }

        assertThat(ledger.balanceOf(alice)).isEqualTo(7000);
        assertThat(countTransactions()).isEqualTo(2); // the opening deposit plus one transfer
    }

    @Test
    void sameKeyWithADifferentBodyIsAConflict() throws Exception {
        mvc.perform(post("/transfers")
                        .header("Idempotency-Key", "req-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LedgerController.TransferRequest("first",
                                List.of(new Entry(alice, -3000), new Entry(bob, 3000))))))
                .andExpect(status().isCreated());

        mvc.perform(post("/transfers")
                        .header("Idempotency-Key", "req-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LedgerController.TransferRequest("second",
                                List.of(new Entry(alice, -9900), new Entry(bob, 9900))))))
                .andExpect(status().isConflict());
    }

    @Test
    void unbalancedTransferIsRejected() throws Exception {
        mvc.perform(post("/transfers")
                        .header("Idempotency-Key", "req-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LedgerController.TransferRequest("does not balance",
                                List.of(new Entry(alice, -3000), new Entry(bob, 2999))))))
                .andExpect(status().isUnprocessableEntity());
    }

    // the guard that matters: it has to fire on the path a client actually calls,
    // not just when the service method is invoked directly in a test
    @Test
    void overdraftIsRejectedThroughTheApi() throws Exception {
        mvc.perform(post("/transfers")
                        .header("Idempotency-Key", "overdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LedgerController.TransferRequest("alice pays bob 500",
                                List.of(new Entry(alice, -50000), new Entry(bob, 50000))))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(ledger.balanceOf(alice)).isEqualTo(10000);
        assertThat(ledger.balanceOf(alice)).isNotNegative();
    }

    @Test
    void missingAccountIsANotFound() throws Exception {
        mvc.perform(get("/accounts/999999")).andExpect(status().isNotFound());
    }

    @Test
    void entriesAreListedNewestFirst() throws Exception {
        mvc.perform(get("/accounts/" + alice + "/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amountMinor").value(10000));
    }

    @Test
    void reconciliationReportsNoDriftOnAHealthyLedger() throws Exception {
        mvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private long countTransactions() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class);
    }
}
