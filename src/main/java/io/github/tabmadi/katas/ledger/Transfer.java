package io.github.tabmadi.katas.ledger;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The receipt for an accepted transfer.
 *
 * <p>Returned to the first caller and replayed verbatim to every retry of the same {@link
 * TransferId}, so a client that retries cannot tell the difference between "we did it" and "we
 * already did it" — which is precisely the guarantee idempotency is supposed to give.
 */
public record Transfer(
        TransferId id, @Nullable AccountId from, @Nullable AccountId to, Money amount, Instant acceptedAt) {}
