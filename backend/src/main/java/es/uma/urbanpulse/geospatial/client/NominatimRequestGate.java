package es.uma.urbanpulse.geospatial.client;

import es.uma.urbanpulse.geospatial.service.AddressSourceBusyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps every call of the backend to Nominatim at least {@code spacing} apart, whoever makes it:
 * the address worker (#375) and the address search (#179) share the one request per second that the
 * public Nominatim allows each application.
 *
 * <p>The turn lives in this process, which is only correct with one backend replica (ADR-0011),
 * like the counters of {@code AttemptLimiter}.
 */
final class NominatimRequestGate {

    private final Duration spacing;
    private final Clock clock;
    private Instant next = Instant.MIN;

    NominatimRequestGate(Duration spacing, Clock clock) {
        this.spacing = spacing;
        this.clock = clock;
    }

    /**
     * Takes the next free turn, however far it is: for the worker, which runs in the background and
     * can wait.
     *
     * @return how long to wait before calling; zero when the call can go now
     */
    synchronized Duration reserve() {
        var now = clock.instant();
        var turn = now.isAfter(next) ? now : next;
        next = turn.plus(spacing);
        return Duration.between(now, turn);
    }

    /**
     * Takes the next free turn only if it comes within {@code maxWait}: for a search, which a
     * person is waiting for. The worker holds the turn for one spacing after each of its calls, so
     * a search that refused to wait at all would find it taken most of the time while the worker
     * runs.
     *
     * @return how long to wait before calling, at most {@code maxWait}
     * @throws AddressSourceBusyException if the next free turn is further away, with how long until
     *     a search would get one; the turn is not taken
     */
    synchronized Duration reserveWithin(Duration maxWait) {
        var now = clock.instant();
        var turn = now.isAfter(next) ? now : next;
        var wait = Duration.between(now, turn);
        if (wait.compareTo(maxWait) > 0) {
            throw new AddressSourceBusyException(wait.minus(maxWait));
        }
        next = turn.plus(spacing);
        return wait;
    }
}
