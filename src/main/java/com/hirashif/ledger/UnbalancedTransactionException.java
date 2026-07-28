package com.hirashif.ledger;

/** Thrown when a set of entries would break the zero-sum invariant. */
public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
