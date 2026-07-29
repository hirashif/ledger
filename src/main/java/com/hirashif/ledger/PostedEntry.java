package com.hirashif.ledger;

import java.time.Instant;

public record PostedEntry(long id, long transactionId, long accountId, long amountMinor,
                          Instant createdAt) {
}
