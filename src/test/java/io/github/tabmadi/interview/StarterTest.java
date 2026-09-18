package io.github.tabmadi.interview;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tabmadi.support.Concurrently;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Proof that the testing environment works, and a template to type over.
 *
 * <p>Delete the bodies, keep the shape: nested classes per stage, {@code @DisplayName} stating a
 * behaviour, and a {@link Concurrently} test for anything that claims to be thread-safe.
 */
class StarterTest {

    @Nested
    @DisplayName("Stage 1")
    class StageOne {

        @Test
        @DisplayName("the toolchain compiles and asserts")
        void sanity() {
            assertThat("ready").isNotBlank();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class UnderContention {

        @RepeatedTest(3)
        @DisplayName("the race harness releases every thread at once")
        void harness() {
            AtomicLong counter = new AtomicLong();

            Concurrently.run(16, 1_000, counter::incrementAndGet);

            assertThat(counter).hasValue(16_000);
        }
    }
}
