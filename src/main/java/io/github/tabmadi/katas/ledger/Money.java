package io.github.tabmadi.katas.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact amount of money.
 *
 * <p>Stored as a {@code long} count of minor units (pence, cents, satoshi-like subunits) in the
 * currency's own scale. Two rules follow from working at a bank:
 *
 * <ul>
 *   <li><b>Never {@code double}.</b> {@code 0.1 + 0.2 != 0.3} in binary floating point, and a
 *       ledger that does not balance to the minor unit is not a ledger.
 *   <li><b>Never mix currencies silently.</b> Arithmetic across currencies throws; conversion is a
 *       separate operation with its own rate and its own audit trail.
 * </ul>
 *
 * <p>{@code long} minor units cover ~92 quadrillion pence, which is comfortably more than any
 * single account holds. Overflow still throws rather than wrapping.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /** Parses a major-unit decimal, e.g. {@code of("10.50", GBP)} is 1050 pence. */
    public static Money of(String majorUnits, Currency currency) {
        BigDecimal scaled =
                new BigDecimal(majorUnits).setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        return new Money(
                scaled.movePointRight(currency.getDefaultFractionDigits()).longValueExact(), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    /** Major-unit view, for humans and for messages. Never used for arithmetic. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return toDecimal() + " " + currency.getCurrencyCode();
    }
}
