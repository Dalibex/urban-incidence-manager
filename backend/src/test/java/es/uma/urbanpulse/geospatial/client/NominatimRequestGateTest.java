package es.uma.urbanpulse.geospatial.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.geospatial.service.AddressSourceBusyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The turn the address worker and the address search share, with a clock the test moves. */
class NominatimRequestGateTest {

    private static final Duration SPACING = Duration.ofSeconds(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
    private final NominatimRequestGate gate = new NominatimRequestGate(SPACING, clock);

    @Test
    void aSearchGoesAtOnceWhenTheTurnIsFree() {
        assertThat(gate.reserveWithin(SPACING)).isEqualTo(Duration.ZERO);
    }

    @Test
    void aSearchWaitsForATurnThatComesWithinItsLimit() {
        gate.reserve();
        clock.advance(Duration.ofMillis(300));

        assertThat(gate.reserveWithin(SPACING)).isEqualTo(Duration.ofMillis(700));
    }

    @Test
    void aSearchRightAfterACallOfTheWorkerGetsTheNextTurnInsteadOfBeingRefused() {
        // The worker holds the turn for one spacing after each call: the search takes the next.
        gate.reserve();

        assertThat(gate.reserveWithin(SPACING)).isEqualTo(SPACING);
        assertThat(gate.reserve()).isEqualTo(SPACING.multipliedBy(2));
    }

    @Test
    void aSearchIsBusyWhenItsTurnIsFurtherThanItsLimitAndSaysWhenToTryAgain() {
        gate.reserve();
        gate.reserve();
        clock.advance(Duration.ofMillis(200));

        assertThatThrownBy(() -> gate.reserveWithin(SPACING))
                .isInstanceOfSatisfying(
                        AddressSourceBusyException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMillis(800)));
    }

    @Test
    void aBusySearchDoesNotTakeATurn() {
        gate.reserve();
        gate.reserve();
        assertThatThrownBy(() -> gate.reserveWithin(SPACING))
                .isInstanceOf(AddressSourceBusyException.class);

        assertThat(gate.reserve()).isEqualTo(SPACING.multipliedBy(2));
    }

    @Test
    void theTurnIsFreeAgainOnceTheSpacingHasPassed() {
        gate.reserveWithin(SPACING);
        clock.advance(SPACING);

        assertThat(gate.reserveWithin(SPACING)).isEqualTo(Duration.ZERO);
    }

    @Test
    void reserveQueuesTheTurnsOneSpacingApart() {
        assertThat(gate.reserve()).isEqualTo(Duration.ZERO);
        assertThat(gate.reserve()).isEqualTo(SPACING);
        assertThat(gate.reserve()).isEqualTo(SPACING.multipliedBy(2));
    }

    @Test
    void aSearchMakesTheNextReserveWait() {
        gate.reserveWithin(SPACING);
        clock.advance(Duration.ofMillis(400));

        assertThat(gate.reserve()).isEqualTo(Duration.ofMillis(600));
    }

    @Test
    void reserveIsImmediateAfterTheQueueHasDrained() {
        gate.reserve();
        gate.reserve();
        clock.advance(SPACING.multipliedBy(5));

        assertThat(gate.reserve()).isEqualTo(Duration.ZERO);
    }

    @Test
    void withoutSpacingNothingIsEverBusy() {
        var unspaced = new NominatimRequestGate(Duration.ZERO, clock);

        assertThat(unspaced.reserveWithin(Duration.ZERO)).isEqualTo(Duration.ZERO);
        assertThat(unspaced.reserveWithin(Duration.ZERO)).isEqualTo(Duration.ZERO);
        assertThat(unspaced.reserve()).isEqualTo(Duration.ZERO);
        assertThat(unspaced.reserve()).isEqualTo(Duration.ZERO);
    }

    /** A clock the test moves forward, so the spacing is tested without waiting. */
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
