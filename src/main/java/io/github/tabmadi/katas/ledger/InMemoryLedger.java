package io.github.tabmadi.katas.ledger;

import io.github.tabmadi.concurrent.KeyedLocks;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thread-safe in-memory {@link Ledger}.
 *
 * <h2>Concurrency design</h2>
 *
 * <p><b>Lock granularity.</b> One lock per account, not one lock for the ledger. A single global
 * lock is correct and trivially deadlock-free, but it serialises every transfer in the system —
 * throughput becomes independent of core count, which at 80M customers is the whole problem. Per
 * account, two transfers touching four distinct accounts proceed in parallel.
 *
 * <p><b>Deadlock.</b> The moment an operation needs two locks, lock-ordering is mandatory. {@code
 * transfer(A -> B)} and {@code transfer(B -> A)} running concurrently is the textbook deadly
 * embrace. {@link KeyedLocks#withBothLocks} sorts the two ids and always takes the lower one first,
 * so a cycle cannot form. The alternative — {@code tryLock} with timeout and retry — is strictly
 * worse here: it trades a deadlock for livelock and a latency tail.
 *
 * <p><b>Idempotency.</b> A {@link TransferId} is reserved with {@code putIfAbsent} of an incomplete
 * future <em>before</em> any money moves. The thread that wins the reservation does the work and
 * completes the future; every concurrent duplicate finds the existing future and blocks on the
 * winner's result rather than re-running the transfer. That covers the hard case — two retries of
 * the same request arriving simultaneously on different nodes' threads — which a "check the map,
 * then do the work, then write the map" sequence does not. A failed attempt un-reserves the key so
 * a genuine retry after a transient failure can still succeed.
 *
 * <p><b>What does not hold.</b> A reader calling {@link #balance} on two accounts sees two separate
 * snapshots, so mid-transfer it can observe a total that is briefly short by the transfer amount.
 * Every account's own history is consistent; a consistent cut across accounts would need either a
 * global read lock or MVCC with snapshot reads — which is exactly what a database gives you, and
 * the reason this class is a teaching model rather than the real thing.
 */
public final class InMemoryLedger implements Ledger {

    private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();
    private final Map<TransferId, CompletableFuture<Transfer>> transfers = new ConcurrentHashMap<>();
    private final KeyedLocks<AccountId> locks = new KeyedLocks<>();
    private final Clock clock;

    public InMemoryLedger() {
        this(Clock.systemUTC());
    }

    /** Injected clock: time is an input, and an input you cannot control is an untestable one. */
    public InMemoryLedger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void open(AccountId id, Currency currency) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Account created = new Account(id, currency);
        if (accounts.putIfAbsent(id, created) != null) {
            throw new IllegalStateException("account already exists: " + id);
        }
    }

    @Override
    public Money balance(AccountId id) {
        Account account = require(id);
        return locks.withLock(id, account::balance);
    }

    @Override
    public List<LedgerEntry> statement(AccountId id) {
        Account account = require(id);
        return locks.withLock(id, account::statement);
    }

    @Override
    public Transfer deposit(TransferId transferId, AccountId to, Money amount) {
        requirePositive(amount);
        Account account = require(to);
        return once(
                transferId,
                () -> locks.withLock(to, () -> {
                    account.requireCurrency(amount);
                    account.apply(transferId, EntryType.CREDIT, amount, clock);
                    return new Transfer(transferId, null, to, amount, clock.instant());
                }));
    }

    @Override
    public Transfer withdraw(TransferId transferId, AccountId from, Money amount) {
        requirePositive(amount);
        Account account = require(from);
        return once(
                transferId,
                () -> locks.withLock(from, () -> {
                    account.requireCanDebit(amount);
                    account.apply(transferId, EntryType.DEBIT, amount, clock);
                    return new Transfer(transferId, from, null, amount, clock.instant());
                }));
    }

    @Override
    public Transfer transfer(TransferId transferId, AccountId from, AccountId to, Money amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requirePositive(amount);
        if (from.equals(to)) {
            throw new IllegalArgumentException("cannot transfer to the same account: " + from);
        }
        Account source = require(from);
        Account destination = require(to);

        return once(
                transferId,
                () -> locks.withBothLocks(from, to, () -> {
                    // Validate both sides first: after the first apply() there is no rollback path.
                    source.requireCanDebit(amount);
                    destination.requireCurrency(amount);

                    source.apply(transferId, EntryType.DEBIT, amount, clock);
                    destination.apply(transferId, EntryType.CREDIT, amount, clock);
                    return new Transfer(transferId, from, to, amount, clock.instant());
                }));
    }

    /**
     * Runs {@code work} at most once for {@code transferId}, replaying the original result to every
     * duplicate — including duplicates that arrive while the first attempt is still in flight.
     */
    private Transfer once(TransferId transferId, Supplier<Transfer> work) {
        Objects.requireNonNull(transferId, "transferId");
        CompletableFuture<Transfer> reservation = new CompletableFuture<>();
        CompletableFuture<Transfer> winner = transfers.putIfAbsent(transferId, reservation);
        if (winner != null) {
            return join(winner);
        }
        try {
            Transfer result = work.get();
            reservation.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            // Nothing was applied, so the key must not stay burned: a client retrying after a
            // transient failure deserves a real attempt. Remove first, then fail the future, so no
            // waiter can observe the failure and re-submit before the slot is free.
            transfers.remove(transferId, reservation);
            reservation.completeExceptionally(failure);
            throw failure;
        }
    }

    private static Transfer join(CompletableFuture<Transfer> inFlight) {
        try {
            return inFlight.join();
        } catch (CompletionException e) {
            // Surface the winner's failure to the duplicate with its own stack trace attached.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private Account require(AccountId id) {
        Account account = accounts.get(Objects.requireNonNull(id, "id"));
        if (account == null) {
            throw new AccountNotFoundException(id);
        }
        return account;
    }

    private static void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive but was " + amount);
        }
    }
}
