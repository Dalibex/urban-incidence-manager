package es.uma.urbanpulse.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.identity.service.AccountSecurityProperties.AttemptLimit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String KEY = "ana@example.com|10.0.0.1";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
    private final AttemptLimiter limiter = new AttemptLimiter(new AttemptLimit(3, WINDOW), clock);

    @Test
    void allowsUpToTheLimit() {
        assertThatCode(this::acquireThree).doesNotThrowAnyException();
    }

    @Test
    void blocksPastTheLimitAndSaysHowLongToWait() {
        acquireThree();
        clock.advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> limiter.acquire(KEY))
                .isInstanceOfSatisfying(
                        TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofMinutes(10)));
    }

    @Test
    void aBlockedAttemptDoesNotExtendTheWindow() {
        acquireThree();
        clock.advance(Duration.ofMinutes(10));
        assertThatThrownBy(() -> limiter.acquire(KEY)).isInstanceOf(TooManyAttemptsException.class);
        clock.advance(Duration.ofMinutes(5));

        assertThatCode(() -> limiter.acquire(KEY)).doesNotThrowAnyException();
    }

    @Test
    void aNewWindowGivesAllTheAttemptsBack() {
        acquireThree();
        clock.advance(WINDOW);

        assertThatCode(this::acquireThree).doesNotThrowAnyException();
    }

    @Test
    void resetGivesAllTheAttemptsBack() {
        acquireThree();
        limiter.reset(KEY);

        assertThatCode(this::acquireThree).doesNotThrowAnyException();
    }

    @Test
    void keysAreIndependent() {
        acquireThree();

        assertThatCode(() -> limiter.acquire("other@example.com|10.0.0.1"))
                .doesNotThrowAnyException();
    }

    /** The regression of the review: a check and a later count let a burst go past the limit. */
    @Test
    void concurrentAttemptsNeverGoPastTheLimit() throws Exception {
        var attempts = 50;
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try (var pool = Executors.newFixedThreadPool(attempts)) {
            for (var i = 0; i < attempts; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        limiter.acquire(KEY);
                                        return true;
                                    } catch (TooManyAttemptsException e) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            var allowed = 0;
            for (var result : results) {
                allowed += result.get() ? 1 : 0;
            }

            assertThat(allowed).isEqualTo(3);
        }
    }

    private void acquireThree() {
        limiter.acquire(KEY);
        limiter.acquire(KEY);
        limiter.acquire(KEY);
    }

    /** A clock the test moves forward, so the window is tested without waiting. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
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
            return this;
        }
    }
}
