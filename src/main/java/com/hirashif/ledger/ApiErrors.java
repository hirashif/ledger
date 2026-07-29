package com.hirashif.ledger;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {

    // the request was understood and refused, which is 422 rather than 400
    @ExceptionHandler({UnbalancedTransactionException.class, InsufficientFundsException.class})
    public ResponseEntity<Map<String, String>> refused(RuntimeException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    // same key, different body. the client has a bug and should hear about it.
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    private ResponseEntity<Map<String, String>> problem(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
