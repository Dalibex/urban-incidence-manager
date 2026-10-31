package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.service.AccountSecurityProperties.AttemptLimit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts attempts per key (an email and a client address, for example) in fixed windows: once a key
 * reaches the limit, it waits until its window ends.
 *
 * <p>The counters live in this process. That is only correct with one backend replica, which is the
 * deployment of ADR-0011; with several replicas each one would allow the limit, and the counters
 * would have to move to a shared store such as Redis (ADR-0012). A restart resets them.
 */
class AttemptLimiter {

    // Keys of expired windows are dropped once the map grows past this size, so the memory used by
    // addresses that never come back stays bounded across windows.
    private static final int PURGE_THRESHOLD = 10_000;

    private final AttemptLimit limit;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    AttemptLimiter(AttemptLimit limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /**
     * Takes one attempt for the key, or throws {@link TooManyAttemptsException} if it has none
     * left. Checking and counting are one atomic step: with a check first and a count after the
     * slow work (bcrypt), concurrent requests would all pass the check before any of them counted.
     */
    void acquire(String key) {
        var now = clock.instant();
        windows.compute(
                key,
                (ignored, window) -> {
                    if (window == null || window.hasEnded(now)) {
                        return new Window(now.plus(limit.window()), 1);
                    }
                    if (window.count() >= limit.max()) {
                        throw new TooManyAttemptsException(Duration.between(now, window.end()));
                    }
                    return new Window(window.end(), window.count() + 1);
                });
        if (windows.size() > PURGE_THRESHOLD) {
            windows.values().removeIf(window -> window.hasEnded(now));
        }
    }

    /** Gives the key all its attempts back, after a successful sign-in for example. */
    void reset(String key) {
        windows.remove(key);
    }

    private record Window(Instant end, int count) {

        boolean hasEnded(Instant now) {
            return !now.isBefore(end);
        }
    }
}
