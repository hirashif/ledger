package com.hirashif.ledger;

public record Account(long id, String name, String currency, long balanceMinor,
                      boolean allowsOverdraft) {
}
