package io.github.tabmadi.katas.ttlcache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A concurrent cache with per-entry TTL and single-flight loading.
 *
 * <h2>The problem it solves</h2>
 *
 * <p>Naive caching in front of a slow dependency turns one cache miss into a stampede: a popular key
 * expires, a thousand in-flight requests all miss, and a thousand identical queries hit the database
 * at once. The cache made the outage worse than no cache at all.
 *
 * <p>{@link #get(Object, Function)} guarantees that for any key, at most one load runs at a time.
 * The first caller installs an incomplete {@link CompletableFuture} as the entry and then loads;
 * concurrent callers find that future and wait on it. They all get the same value from one query.
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li><b>Expiry is lazy.</b> Entries are evicted when they are next looked at, not by a sweeper
 *       thread. That keeps the class free of background lifecycle — nothing to start, nothing to
 *       shut down — at the cost of holding dead entries for keys nobody asks about. For a bounded
 *       keyspace that trade is right; for an unbounded one, add a size cap.
 *   <li><b>A failed load is not cached.</b> The entry is removed so the next caller retries. Caching
 *       a failure would turn a one-second dependency blip into a full TTL of hard failures.
 *   <li><b>The loader runs outside any map lock.</b> Doing slow I/O inside {@code
 *       computeIfAbsent} holds a {@code ConcurrentHashMap} bin lock for the duration, blocking
 *       unrelated keys that happen to hash to the same bin. The reservation pattern here does the
 *       slow work with no lock held at all.
 * </ul>
 */
public final class ExpiringCache<K, V> {

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public ExpiringCache(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    public ExpiringCache(Duration ttl, Clock clock) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive but was " + ttl);
        }
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns the live value for {@code key}, if one is cached and unexpired. */
    public Optional<V> getIfPresent(K key) {
        Entry<V> entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry)) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        // An entry still being loaded by another thread is present but not yet readable; do not
        // block a caller who only asked whether we happen to have it.
        return entry.value.isDone() && !entry.value.isCompletedExceptionally()
                ? Optional.ofNullable(entry.value.getNow(null))
                : Optional.empty();
    }

    /**
     * Returns the cached value for {@code key}, loading it at most once across all threads.
     *
     * @throws NullPointerException if {@code loader} returns {@code null}; an absent value is not a
     *     cacheable value, and silently caching {@code null} hides a broken loader
     */
    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        while (true) {
            Entry<V> existing = entries.get(key);
            if (existing != null && !isExpired(existing)) {
                return await(existing.value);
            }
            Entry<V> reservation = new Entry<>(new CompletableFuture<>(), clock.instant());
            boolean won = existing == null
                    ? entries.putIfAbsent(key, reservation) == null
                    : entries.replace(key, existing, reservation);
            if (!won) {
                // Someone else installed an entry between our read and our write. Start over and
                // use theirs rather than racing them to load the same key twice.
                continue;
            }
            try {
                V loaded = Objects.requireNonNull(loader.apply(key), "loader returned null for key " + key);
                reservation.value.complete(loaded);
                return loaded;
            } catch (RuntimeException | Error failure) {
                entries.remove(key, reservation);
                reservation.value.completeExceptionally(failure);
                throw failure;
            }
        }
    }

    public void invalidate(K key) {
        entries.remove(Objects.requireNonNull(key, "key"));
    }

    public void invalidateAll() {
        entries.clear();
    }

    /** Number of entries held, expired-but-not-yet-evicted ones included. Diagnostics only. */
    public int size() {
        return entries.size();
    }

    private boolean isExpired(Entry<V> entry) {
        return !clock.instant().isBefore(entry.loadedAt.plus(ttl));
    }

    private static <V> V await(CompletableFuture<V> value) {
        try {
            return value.join();
        } catch (CompletionException e) {
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

    /**
     * @param value completed once the value is loaded; waiters block here rather than re-loading
     * @param loadedAt when the load was <em>started</em>, so a slow load does not extend its own TTL
     */
    private record Entry<V>(CompletableFuture<@Nullable V> value, Instant loadedAt) {}
}
