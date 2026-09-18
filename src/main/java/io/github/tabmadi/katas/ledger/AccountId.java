package io.github.tabmadi.katas.ledger;

import java.io.Serializable;
import java.util.Objects;

/**
 * Identity of an account.
 *
 * <p>A wrapper around a string costs one record and buys a compiler that will not let you pass a
 * transfer id where an account id belongs. In a payments domain that swap is a production incident,
 * not a typo.
 */
public record AccountId(String value) implements Comparable<AccountId>, Serializable {

    public AccountId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("account id must not be blank");
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    /** Total order over ids, which is what makes deadlock-free two-account locking possible. */
    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
