package io.github.tabmadi.katas.ledger;

import java.util.Currency;

/** Thrown when an operation would mix two currencies without an explicit conversion. */
public final class CurrencyMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("currency mismatch: expected %s but got %s"
                .formatted(expected.getCurrencyCode(), actual.getCurrencyCode()));
    }
}
