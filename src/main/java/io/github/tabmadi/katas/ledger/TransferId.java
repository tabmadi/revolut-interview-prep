package io.github.tabmadi.katas.ledger;

import java.util.Objects;

/**
 * Client-supplied idempotency key for a single money movement.
 *
 * <p>The client generates it, not the server: that is what lets a client retry a request whose
 * response it never saw without moving the money twice.
 */
public record TransferId(String value) {

    public TransferId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("transfer id must not be blank");
        }
    }

    public static TransferId of(String value) {
        return new TransferId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
