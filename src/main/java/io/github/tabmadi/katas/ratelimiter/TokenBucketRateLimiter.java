package io.github.tabmadi.katas.ratelimiter;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Per-key token bucket, lock-free.
 *
 * <p>A bucket holds up to {@code capacity} tokens and regains one every {@code nanosPerToken}. A
 * request takes a token or is refused; it never queues, never sleeps, never blocks a thread. That
 * last property is what makes it usable on a request path serving 80M customers: a limiter that
 * blocks converts a traffic spike into thread-pool exhaustion.
 *
 * <h2>Why no locks</h2>
 *
 * <p>The entire state of a bucket — token count and the instant it was last topped up — fits in one
 * immutable {@link State} record behind an {@link AtomicReference}. Acquisition is the standard CAS
 * retry loop: read state, compute the state it should be in now, swap it in, retry if someone beat
 * us. Under contention threads spin briefly instead of parking, and no thread can be descheduled
 * while holding something another thread needs. There is no deadlock to reason about because there
 * is no lock.
 *
 * <p><b>Refill is lazy.</b> No timer, no background thread, no per-key scheduled task: the arriving
 * request computes how many tokens accrued since the last one. A million idle keys cost a million
 * map entries and zero CPU.
 *
 * <p><b>Drift.</b> The refill timestamp advances by whole tokens' worth of nanoseconds, not to
 * "now". Rounding down to "now" on each call would discard the fractional remainder every time and
 * make the effective rate quietly lower than configured — the kind of bug that shows up as a
 * mysterious 3% of rejected traffic and nothing else.
 *
 * <p><b>Clock.</b> {@link System#nanoTime} only. {@code currentTimeMillis} is wall-clock: an NTP
 * step backwards hands every caller a free refill window, and a step forwards stalls them.
 */
public final class TokenBucketRateLimiter {

    private final Map<String, AtomicReference<State>> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final long nanosPerToken;
    private final LongSupplier nanoClock;

    /**
     * Creates a limiter backed by the system nanosecond clock.
     *
     * @param capacity maximum burst, in tokens
     * @param refillRate how many tokens are restored per {@code refillPeriod}
     * @param refillPeriod the window over which {@code refillRate} tokens accrue
     */
    public static TokenBucketRateLimiter of(long capacity, long refillRate, Duration refillPeriod) {
        return new TokenBucketRateLimiter(capacity, refillRate, refillPeriod, System::nanoTime);
    }

    TokenBucketRateLimiter(long capacity, long refillRate, Duration refillPeriod, LongSupplier nanoClock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive but was " + capacity);
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refill rate must be positive but was " + refillRate);
        }
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refill period must be positive but was " + refillPeriod);
        }
        this.capacity = capacity;
        this.nanosPerToken = Math.max(1L, refillPeriod.toNanos() / refillRate);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Takes one token for {@code key}, or returns {@code false} if the bucket is empty. */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * Takes {@code permits} tokens for {@code key}, all or nothing.
     *
     * <p>All-or-nothing matters: a partial grant would let an expensive request half-start and then
     * be refused, which is worse than refusing it up front.
     */
    public boolean tryAcquire(String key, long permits) {
        Objects.requireNonNull(key, "key");
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive but was " + permits);
        }
        if (permits > capacity) {
            // Unsatisfiable at any point in time: fail loudly rather than looping forever.
            throw new IllegalArgumentException(
                    "cannot request %d permits from a bucket of %d".formatted(permits, capacity));
        }

        AtomicReference<State> bucket = bucketFor(key);
        while (true) {
            // Seeded non-null in bucketFor and only ever CAS'd to non-null values.
            State current = Objects.requireNonNull(bucket.get());
            State refilled = refill(current, nanoClock.getAsLong());
            if (refilled.tokens < permits) {
                // Still publish the refill: the next caller should not recompute the same elapsed
                // window, and losing this CAS is harmless because it only means someone else did.
                bucket.compareAndSet(current, refilled);
                return false;
            }
            State taken = new State(refilled.tokens - permits, refilled.lastRefillNanos);
            if (bucket.compareAndSet(current, taken)) {
                return true;
            }
            // Lost the race: another thread mutated the bucket. Re-read and try again. This loop is
            // wait-free in practice because every retry follows a competitor's success.
        }
    }

    /** Tokens currently available to {@code key}. Diagnostics and tests, not a decision input. */
    public long availableTokens(String key) {
        return refill(Objects.requireNonNull(bucketFor(key).get()), nanoClock.getAsLong()).tokens;
    }

    private AtomicReference<State> bucketFor(String key) {
        return buckets.computeIfAbsent(
                key, ignored -> new AtomicReference<>(new State(capacity, nanoClock.getAsLong())));
    }

    private State refill(State state, long now) {
        long elapsed = now - state.lastRefillNanos;
        if (elapsed < nanosPerToken) {
            return state;
        }
        long accrued = elapsed / nanosPerToken;
        long tokens = Math.min(capacity, state.tokens + accrued);
        long advancedTo = tokens == capacity
                // Full bucket: further accrual is discarded, so reset the window to now rather than
                // banking credit that would let a long-idle key burst past its capacity.
                ? now
                : state.lastRefillNanos + accrued * nanosPerToken;
        return new State(tokens, advancedTo);
    }

    /** Immutable bucket state; swapped atomically so tokens and timestamp can never disagree. */
    private record State(long tokens, long lastRefillNanos) {}
}
