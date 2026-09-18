package io.github.tabmadi.katas.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Mutable state of one account.
 *
 * <p>Deliberately not thread-safe on its own. Every method here assumes the caller already holds the
 * account's lock, which keeps the locking policy in exactly one place ({@link InMemoryLedger})
 * instead of smeared across two classes where the interaction between them is nobody's job.
 */
final class Account {

    private final AccountId id;
    private final Currency currency;
    private final List<LedgerEntry> entries = new ArrayList<>();

    private Money balance;

    Account(AccountId id, Currency currency) {
        this.id = id;
        this.currency = currency;
        this.balance = Money.zero(currency);
    }

    AccountId id() {
        return id;
    }

    Currency currency() {
        return currency;
    }

    /** Caller holds the lock. */
    Money balance() {
        return balance;
    }

    /** Caller holds the lock. */
    List<LedgerEntry> statement() {
        return List.copyOf(entries);
    }

    /**
     * Throws unless this account can absorb a debit of {@code amount}.
     *
     * <p>Split out from {@link #apply} so a transfer can validate both sides while holding both
     * locks, before mutating either. Validate-then-mutate is what makes the operation atomic: once
     * the first mutation lands there is no failure path left to roll back.
     */
    void requireCanDebit(Money amount) {
        requireCurrency(amount);
        if (balance.minus(amount).isNegative()) {
            throw new InsufficientFundsException(id, balance, amount);
        }
    }

    void requireCurrency(Money amount) {
        if (!currency.equals(amount.currency())) {
            throw new CurrencyMismatchException(currency, amount.currency());
        }
    }

    /** Caller holds the lock, and has already validated. */
    LedgerEntry apply(TransferId transferId, EntryType type, Money amount, Clock clock) {
        balance = type == EntryType.CREDIT ? balance.plus(amount) : balance.minus(amount);
        Instant now = clock.instant();
        LedgerEntry entry = new LedgerEntry(entries.size() + 1L, id, transferId, type, amount, balance, now);
        entries.add(entry);
        return entry;
    }
}
