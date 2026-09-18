package io.github.tabmadi.support;

import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test harness for provoking races.
 *
 * <p>A concurrency test that merely starts threads in a loop rarely fails: by the time the last
 * thread starts, the first one is done, and the interleaving under test never happens. Every method
 * here parks all worker threads on the same latch and releases them together, so the contended
 * window is as wide as the machine allows.
 *
 * <p>Platform threads are deliberate. Virtual threads multiplex onto a small carrier pool, which
 * serialises exactly the overlap we are trying to create.
 */
public final class Concurrently {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private Concurrently() {}

    /** Runs {@code body} on {@code threads} threads, {@code iterations} times each. */
    public static void run(int threads, int iterations, Runnable body) {
        call(threads, () -> {
            for (int i = 0; i < iterations; i++) {
                body.run();
            }
            return null;
        });
    }

    /** Runs {@code body} once per thread, handing each one its own 0-based index. */
    public static void runIndexed(int threads, IndexedTask body) {
        AtomicInteger next = new AtomicInteger();
        call(threads, () -> {
            body.run(next.getAndIncrement());
            return null;
        });
    }

    /**
     * Runs {@code body} once on each of {@code threads} threads, released simultaneously, and
     * returns every result. Any exception thrown by any thread fails the test.
     */
    public static <T> List<T> call(int threads, Callable<T> body) {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<T> results = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> workers = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            workers.add(Thread.ofPlatform().name("concurrently-", i).start(() -> {
                try {
                    ready.countDown();
                    go.await();
                    T result = body.call();
                    if (result != null) {
                        results.add(result);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            }));
        }

        try {
            awaitOrFail(ready, "threads never reached the start line");
            go.countDown();
            if (!done.await(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                workers.forEach(Thread::interrupt);
                fail("timed out after %s -- a deadlock or a lost signal, not slowness", DEFAULT_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating workers", e);
        }

        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError(
                    "%d of %d threads failed; first failure attached".formatted(failures.size(), threads),
                    failures.getFirst());
            failures.stream().skip(1).forEach(error::addSuppressed);
            throw error;
        }
        return List.copyOf(results);
    }

    private static void awaitOrFail(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            fail(message);
        }
    }

    /** A per-thread body that needs to know which thread it is. */
    @FunctionalInterface
    public interface IndexedTask {
        void run(int index) throws Exception;
    }
}
