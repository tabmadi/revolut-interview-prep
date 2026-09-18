package io.github.tabmadi.katas.ledger;

import java.time.Instant;

/**
 * One immutable line in an account's statement.
 *
 * <p>The statement, not the balance field, is the source of truth. A balance is a cached fold over
 * the entries; when the two disagree, the entries win and the balance is the bug. Recording {@code
 * balanceAfter} on every line makes that disagreement detectable instead of theoretical.
 *
 * @param sequence per-account, gap-free, starting at 1
 * @param transferId the movement that produced this line; the same id appears on both sides of a
 *     transfer, which is how the two halves are reconciled
 */
public record LedgerEntry(
        long sequence,
        AccountId accountId,
        TransferId transferId,
        EntryType type,
        Money amount,
        Money balanceAfter,
        Instant occurredAt) {}
