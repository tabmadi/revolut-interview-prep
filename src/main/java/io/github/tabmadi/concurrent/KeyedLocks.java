package io.github.tabmadi.concurrent;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One lock per key, created on demand.
 *
 * <p>Two properties matter and both are easy to get wrong under time pressure:
 *
 * <ol>
 *   <li><b>Identity.</b> Two threads naming the same key must get the same lock object. {@code
 *       computeIfAbsent} guarantees that; a {@code get}-then-{@code put} does not.
 *   <li><b>Ordering.</b> Locking two keys is where deadlock lives. {@link #withBothLocks} imposes a total
 *       order on the keys so no two threads can ever hold half of each other's pair.
 * </ol>
 *
 * <p>Locks are never removed. Unbounded growth is acceptable when keys are bounded (accounts,
 * tenants); if keys are unbounded, swap the map for a size-capped cache and accept that two threads
 * may occasionally be handed different locks for the same key — which is a correctness bug, so the
 * real fix is reference counting, not eviction.
 *
 * @param <K> key type; must have a stable {@code hashCode}/{@code equals} and, for {@link #withBothLocks},
 *     a meaningful {@link Comparable} order
 */
public final class KeyedLocks<K extends Comparable<K>> {

    private final Map<K, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Runs {@code work} while holding the lock for {@code key}. */
    public <T> T withLock(K key, Supplier<T> work) {
        ReentrantLock lock = lockFor(key);
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code work} while holding the lock for {@code key}. */
    public void withLock(K key, Runnable work) {
        withLock(key, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} while holding the locks for both keys, always acquiring them in ascending
     * key order regardless of the order the caller passed them in.
     *
     * <p>This is the whole deadlock story for a transfer: thread A moving money from {@code x} to
     * {@code y} and thread B moving it from {@code y} to {@code x} both take {@code x} first.
     */
    public <T> T withBothLocks(K first, K second, Supplier<T> work) {
        int order = Comparator.<K>naturalOrder().compare(first, second);
        if (order == 0) {
            // Same key twice. ReentrantLock would let us take it twice, but the caller almost
            // certainly means something different by a self-referencing pair, so refuse.
            throw new IllegalArgumentException("both keys are the same: " + first);
        }
        K lower = order < 0 ? first : second;
        K higher = order < 0 ? second : first;

        ReentrantLock lowerLock = lockFor(lower);
        ReentrantLock higherLock = lockFor(higher);
        lowerLock.lock();
        try {
            higherLock.lock();
            try {
                return work.get();
            } finally {
                higherLock.unlock();
            }
        } finally {
            lowerLock.unlock();
        }
    }

    /** Number of distinct keys that have ever been locked. Diagnostics only. */
    public int size() {
        return locks.size();
    }

    private ReentrantLock lockFor(K key) {
        return locks.computeIfAbsent(key, ignored -> new ReentrantLock());
    }
}
