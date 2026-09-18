package io.github.tabmadi.katas.ttlcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.tabmadi.support.Concurrently;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ExpiringCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private <K, V> ExpiringCache<K, V> cache(Duration ttl) {
        return new ExpiringCache<>(ttl, clock);
    }

    @Nested
    @DisplayName("Caching and expiry")
    class Lifecycle {

        @Test
        @DisplayName("a value is loaded once and served from the cache afterwards")
        void loadsOnce() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));
            AtomicInteger loads = new AtomicInteger();

            assertThat(cache.get("k", key -> "v" + loads.incrementAndGet())).isEqualTo("v1");
            assertThat(cache.get("k", key -> "v" + loads.incrementAndGet())).isEqualTo("v1");
            assertThat(loads).hasValue(1);
        }

        @Test
        @DisplayName("an entry is reloaded once its ttl has elapsed")
        void reloadsAfterTtl() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));
            AtomicInteger loads = new AtomicInteger();
            cache.get("k", key -> "v" + loads.incrementAndGet());

            clock.advance(Duration.ofSeconds(59));
            assertThat(cache.get("k", key -> "v" + loads.incrementAndGet())).isEqualTo("v1");

            clock.advance(Duration.ofSeconds(1));
            assertThat(cache.get("k", key -> "v" + loads.incrementAndGet())).isEqualTo("v2");
        }

        @Test
        @DisplayName("getIfPresent never triggers a load and never returns an expired value")
        void getIfPresentIsPassive() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));
            assertThat(cache.getIfPresent("k")).isEmpty();

            cache.get("k", key -> "v");
            assertThat(cache.getIfPresent("k")).contains("v");

            clock.advance(Duration.ofMinutes(2));
            assertThat(cache.getIfPresent("k")).isEmpty();
            assertThat(cache.size())
                    .as("the expired entry is evicted on inspection")
                    .isZero();
        }

        @Test
        @DisplayName("a failed load is not cached, so the next caller retries")
        void failuresAreNotCached() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));
            AtomicInteger attempts = new AtomicInteger();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> cache.get("k", key -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("dependency down");
                    }));

            assertThat(cache.get("k", key -> "recovered")).isEqualTo("recovered");
            assertThat(attempts).hasValue(1);
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("invalidate forces the next get to reload")
        void invalidate() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));
            cache.get("k", key -> "stale");

            cache.invalidate("k");

            assertThat(cache.get("k", key -> "fresh")).isEqualTo("fresh");
        }

        @Test
        @DisplayName("a null value is a broken loader, not a cacheable result")
        void nullValuesAreRejected() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));

            assertThatNullPointerException().isThrownBy(() -> cache.get("k", key -> null));
            assertThat(cache.size()).isZero();
        }

        @Test
        @DisplayName("a non-positive ttl is rejected at construction")
        void ttlMustBePositive() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ExpiringCache<>(Duration.ZERO));
        }
    }

    @Nested
    @DisplayName("Single-flight loading")
    class Stampede {

        @RepeatedTest(10)
        @DisplayName("64 threads missing the same key trigger exactly one load")
        void oneLoadPerKey() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));
            AtomicInteger loads = new AtomicInteger();
            CountDownLatch arrived = new CountDownLatch(64);

            List<String> values = Concurrently.call(64, () -> {
                arrived.countDown();
                return cache.get("hot", key -> {
                    loads.incrementAndGet();
                    // Hold the load open until every other thread has reached the call site, so
                    // they all collide mid-flight -- the exact window a check-then-load
                    // implementation gets wrong.
                    await(arrived);
                    return "loaded";
                });
            });

            assertThat(loads)
                    .as("a stampede would show up as more than one load")
                    .hasValue(1);
            assertThat(values).hasSize(64).containsOnly("loaded");
        }

        @RepeatedTest(10)
        @DisplayName("waiters see the loader's failure instead of hanging")
        void failureIsPropagatedToWaiters() {
            ExpiringCache<String, String> cache = cache(Duration.ofMinutes(1));
            AtomicInteger failures = new AtomicInteger();

            Concurrently.run(32, 1, () -> {
                try {
                    cache.get("hot", key -> {
                        throw new IllegalStateException("dependency down");
                    });
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                }
            });

            assertThat(failures).hasValue(32);
            assertThat(cache.size()).as("nothing poisoned is left behind").isZero();
        }

        private static void await(CountDownLatch latch) {
            try {
                // Bounded: a bug here should fail the test, not wedge the build.
                if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("loader was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** A clock the test drives by hand; wall-clock sleeps make concurrency tests flaky and slow. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("test clock is UTC only");
        }
    }
}
