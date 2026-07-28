package com.hirashif.ledger;

/**
 * One side of a transaction. Negative amounts debit an account (money leaves),
 * positive amounts credit it (money arrives). Always minor units, never a decimal.
 */
public record Entry(long accountId, long amountMinor) {
}
