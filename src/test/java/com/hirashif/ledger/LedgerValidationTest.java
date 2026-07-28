package com.hirashif.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure validation rules. No Spring, no database, runs in milliseconds. */
class LedgerValidationTest {

    @Test
    @DisplayName("a balanced two-sided transfer is accepted")
    void acceptsBalancedTransfer() {
        assertThatCode(() -> LedgerService.validate(List.of(
                new Entry(1, -5000),
                new Entry(2, 5000))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one debit split across two credits is accepted")
    void acceptsSplitTransfer() {
        assertThatCode(() -> LedgerService.validate(List.of(
                new Entry(1, -5000),
                new Entry(2, 3000),
                new Entry(3, 2000))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> LedgerService.validate(null))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> LedgerService.validate(List.of()))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    @DisplayName("a single entry is never a transfer, even if it is somehow zero-sum")
    void rejectsSingleEntry() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(new Entry(1, -5000))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                new Entry(1, 0),
                new Entry(2, 0))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsUnbalanced() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                new Entry(1, -5000),
                new Entry(2, 4999))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    @DisplayName("overflow must not fake a balanced transaction")
    void rejectsOverflow() {
        // a naive running sum wraps around here and lands on exactly zero,
        // so a careless implementation accepts this as a valid transfer.
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                new Entry(1, Long.MAX_VALUE),
                new Entry(2, Long.MAX_VALUE),
                new Entry(3, 2))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }
}
