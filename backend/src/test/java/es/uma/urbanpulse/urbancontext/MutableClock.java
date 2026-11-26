package es.uma.urbanpulse.urbancontext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The real time plus an offset the test moves forward, so a report can grow older than {@code
 * WEATHER_MAX_DELAY} without waiting an hour (#309, T8 and T12). Access tokens are issued before
 * the clock moves, so they stay valid.
 */
public final class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The tests only need UTC");
    }
}
