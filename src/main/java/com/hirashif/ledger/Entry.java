package com.hirashif.ledger;

// one side of a transaction. negative debits, positive credits, always minor units.
public record Entry(long accountId, long amountMinor) {
}
