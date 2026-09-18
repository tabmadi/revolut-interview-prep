package io.github.tabmadi.katas.ratelimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.tabmadi.support.Concurrently;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    /** A hand-cranked clock: time only moves when the test says so, so nothing here is timing-flaky. */
    private final AtomicLong nanos = new AtomicLong();

    private TokenBucketRateLimiter limiter(long capacity, long perSecond) {
        return new TokenBucketRateLimiter(capacity, perSecond, Duration.ofSeconds(1), nanos::get);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Nested
    @DisplayName("Single-threaded behaviour")
    class Basics {

        @Test
        @DisplayName("a fresh key starts full and can burst to capacity")
        void burstsToCapacity() {
            TokenBucketRateLimiter limiter = limiter(5, 1);

            assertThat(grantsFor(limiter, "user-1", 10)).isEqualTo(5);
        }

        @Test
        @DisplayName("an empty bucket refuses without blocking")
        void refusesWhenEmpty() {
            TokenBucketRateLimiter limiter = limiter(1, 1);
            assertThat(limiter.tryAcquire("user-1")).isTrue();

            assertThat(limiter.tryAcquire("user-1")).isFalse();
        }

        @Test
        @DisplayName("tokens accrue with elapsed time")
        void refillsOverTime() {
            TokenBucketRateLimiter limiter = limiter(10, 10);
            assertThat(grantsFor(limiter, "user-1", 10)).isEqualTo(10);

            advance(Duration.ofMillis(300));

            assertThat(limiter.availableTokens("user-1")).isEqualTo(3);
            assertThat(grantsFor(limiter, "user-1", 10)).isEqualTo(3);
        }

        @Test
        @DisplayName("the fractional remainder is carried, not discarded")
        void refillDoesNotDrift() {
            TokenBucketRateLimiter limiter = limiter(100, 10);
            assertThat(grantsFor(limiter, "user-1", 100)).isEqualTo(100);

            // 99ms is 0.99 tokens. Twelve of those is 11.88 tokens; a limiter that rounds each
            // step down to zero would hand out none at all.
            for (int i = 0; i < 12; i++) {
                advance(Duration.ofMillis(99));
            }

            assertThat(limiter.availableTokens("user-1")).isEqualTo(11);
        }

        @Test
        @DisplayName("an idle key does not bank unlimited credit")
        void refillIsCapped() {
            TokenBucketRateLimiter limiter = limiter(5, 10);

            advance(Duration.ofHours(1));

            assertThat(grantsFor(limiter, "user-1", 100)).isEqualTo(5);
        }

        @Test
        @DisplayName("keys are limited independently")
        void keysAreIsolated() {
            TokenBucketRateLimiter limiter = limiter(2, 1);
            assertThat(grantsFor(limiter, "noisy", 5)).isEqualTo(2);

            assertThat(grantsFor(limiter, "quiet", 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("a multi-permit request is all or nothing")
        void multiPermitIsAtomic() {
            TokenBucketRateLimiter limiter = limiter(5, 1);
            assertThat(limiter.tryAcquire("user-1", 3)).isTrue();

            assertThat(limiter.tryAcquire("user-1", 3)).isFalse();
            assertThat(limiter.availableTokens("user-1")).isEqualTo(2);
        }

        @Test
        @DisplayName("impossible configurations and requests are rejected at construction or call")
        void rejectsNonsense() {
            assertThatIllegalArgumentException().isThrownBy(() -> limiter(0, 1));
            assertThatIllegalArgumentException().isThrownBy(() -> limiter(1, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> limiter(5, 1).tryAcquire("k", 0));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> limiter(5, 1).tryAcquire("k", 6))
                    .withMessageContaining("bucket of 5");
        }
    }

    @Nested
    @DisplayName("Under contention")
    class Concurrent {

        @RepeatedTest(10)
        @DisplayName("64 threads racing on one key are granted exactly capacity permits, never more")
        void neverGrantsMoreThanCapacity() {
            TokenBucketRateLimiter limiter = limiter(100, 1);
            AtomicInteger granted = new AtomicInteger();

            // 64 threads x 10 attempts = 640 requests against 100 tokens, with a frozen clock so
            // no refill can mask an over-grant. A lost update in the CAS loop shows up here.
            Concurrently.run(64, 10, () -> {
                if (limiter.tryAcquire("hot-key")) {
                    granted.incrementAndGet();
                }
            });

            assertThat(granted).hasValue(100);
            assertThat(limiter.availableTokens("hot-key")).isZero();
        }

        @RepeatedTest(10)
        @DisplayName("concurrent first-touch of the same key creates one bucket, not one per thread")
        void bucketCreationIsRaceFree() {
            TokenBucketRateLimiter limiter = limiter(1, 1);
            AtomicInteger granted = new AtomicInteger();

            Concurrently.run(64, 1, () -> {
                if (limiter.tryAcquire("cold-key")) {
                    granted.incrementAndGet();
                }
            });

            assertThat(granted).hasValue(1);
        }
    }

    private static int grantsFor(TokenBucketRateLimiter limiter, String key, int attempts) {
        int granted = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(key)) {
                granted++;
            }
        }
        return granted;
    }
}
