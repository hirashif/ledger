package com.hirashif.ledger;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LedgerController {

    public record NewAccount(String name, String currency, Boolean allowsOverdraft) {
    }

    public record TransferRequest(String description, List<Entry> entries) {
    }

    private final LedgerService ledger;
    private final TransferService transfers;
    private final ReconciliationService reconciliation;

    public LedgerController(LedgerService ledger, TransferService transfers,
            ReconciliationService reconciliation) {
        this.ledger = ledger;
        this.transfers = transfers;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/accounts")
    public ResponseEntity<Account> createAccount(@RequestBody NewAccount request) {
        long id = ledger.createAccount(
                request.name(),
                request.currency() == null ? "USD" : request.currency(),
                Boolean.TRUE.equals(request.allowsOverdraft()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ledger.findAccount(id).orElseThrow());
    }

    @GetMapping("/accounts/{id}")
    public Account account(@PathVariable long id) {
        return ledger.findAccount(id)
                .orElseThrow(() -> new AccountNotFoundException("no account " + id));
    }

    @GetMapping("/accounts/{id}/entries")
    public List<PostedEntry> entries(@PathVariable long id,
            @RequestParam(defaultValue = "100") int limit) {
        ledger.findAccount(id).orElseThrow(() -> new AccountNotFoundException("no account " + id));
        return ledger.entriesFor(id, limit);
    }

    // the idempotency key is required: a money endpoint should not be callable
    // in a way that cannot be safely retried
    @PostMapping("/transfers")
    public ResponseEntity<Map<String, Long>> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransferRequest request) {
        long transactionId = transfers.transfer(
                idempotencyKey, request.description(), request.entries());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("transactionId", transactionId));
    }

    @GetMapping("/reconciliation")
    public List<ReconciliationService.Drift> drift() {
        return reconciliation.findDrift();
    }

    // /health as well as /healthz: Google's frontend reserves the exact path
    // /healthz on Cloud Run and answers it before the request reaches the container
    @GetMapping({ "/healthz", "/health" })
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }
}
