package es.uma.urbanpulse.geospatial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.geospatial.dto.PlaceResponse;
import es.uma.urbanpulse.identity.service.AccountSecurityProperties.AttemptLimit;
import es.uma.urbanpulse.identity.service.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The address search of #179 with a stub of Nominatim and a clock the test moves. */
class PlaceSearchServiceTest {

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final int CACHE_SIZE = 3;
    private static final AttemptLimit SEARCHES = new AttemptLimit(10, Duration.ofMinutes(1));
    private static final Place LARIOS = new Place("Calle Larios, Málaga", 36.7213, -4.4214);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
    private final StubProvider provider = new StubProvider();
    private final PlaceSearchService service =
            new PlaceSearchService(
                    provider, new PlaceSearchProperties(SEARCHES, CACHE_TTL, CACHE_SIZE), clock);
    private final UUID user = UUID.randomUUID();

    @Test
    void theSourcePlacesAreAnsweredWithTheirNameAndPosition() {
        provider.answer = List.of(LARIOS, new Place("Calle Larga, Málaga", 36.7, -4.4));

        assertThat(service.search("larios", user))
                .containsExactly(
                        new PlaceResponse("Calle Larios, Málaga", 36.7213, -4.4214),
                        new PlaceResponse("Calle Larga, Málaga", 36.7, -4.4));
    }

    @Test
    void theSourceIsAskedForAtMostFivePlaces() {
        service.search("larios", user);

        assertThat(provider.lastLimit).isEqualTo(5);
        assertThat(provider.lastQuery).isEqualTo("larios");
    }

    @Test
    void aRepeatedSearchIsAnsweredFromTheCacheWithoutCallingTheSource() {
        provider.answer = List.of(LARIOS);
        var first = service.search("larios", user);

        var second = service.search("larios", user);

        assertThat(second).isEqualTo(first);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void theCacheIgnoresCaseAndTheSpacesInsideTheText() {
        service.search("Calle Larios", user);

        service.search("calle   LARIOS", user);

        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void aSearchThatDiffersInAccentsIsAnotherSearch() {
        service.search("Alameda Principal", user);

        service.search("Alamedá Principal", user);

        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void anEmptyAnswerIsCachedToo() {
        provider.answer = List.of();

        assertThat(service.search("nowhere", user)).isEmpty();
        assertThat(service.search("nowhere", user)).isEmpty();

        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void theCacheExpiresAfterItsTimeToLive() {
        service.search("larios", user);
        clock.advance(CACHE_TTL.minusSeconds(1));
        service.search("larios", user);
        assertThat(provider.calls).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        service.search("larios", user);

        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void theCacheDropsTheLeastRecentlyUsedSearchPastItsSize() {
        service.search("one", user);
        service.search("two", user);
        service.search("three", user);
        // "one" is used again, so "two" is now the least recently used.
        service.search("one", user);
        service.search("four", user);
        assertThat(provider.calls).isEqualTo(4);

        service.search("one", user);
        service.search("three", user);
        service.search("four", user);
        assertThat(provider.calls).isEqualTo(4);

        service.search("two", user);
        assertThat(provider.calls).isEqualTo(5);
    }

    @Test
    void theEleventhSearchOfAUserInTheWindowIsTooManyAttempts() {
        for (int i = 0; i < 10; i++) {
            service.search("search " + i, user);
        }
        clock.advance(Duration.ofSeconds(20));

        assertThatThrownBy(() -> service.search("search 11", user))
                .isInstanceOfSatisfying(
                        TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(40)));
        assertThat(provider.calls).isEqualTo(10);
    }

    @Test
    void aCachedSearchCountsAgainstTheLimitOfTheUser() {
        for (int i = 0; i < 10; i++) {
            service.search("larios", user);
        }

        assertThatThrownBy(() -> service.search("larios", user))
                .isInstanceOf(TooManyAttemptsException.class);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void anotherUserCanSearchWhenOneHasReachedTheLimit() {
        for (int i = 0; i < 10; i++) {
            service.search("larios", user);
        }

        assertThatCode(() -> service.search("larios", UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void theLimitIsGivenBackWhenTheWindowEnds() {
        for (int i = 0; i < 10; i++) {
            service.search("larios", user);
        }
        clock.advance(SEARCHES.window());

        assertThatCode(() -> service.search("larios", user)).doesNotThrowAnyException();
    }

    @Test
    void aBusySourceIsTooManyAttemptsWithTheWaitOfTheSource() {
        provider.failure = new AddressSourceBusyException(Duration.ofMillis(700));

        assertThatThrownBy(() -> service.search("larios", user))
                .isInstanceOfSatisfying(
                        TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofMillis(700)));
    }

    @Test
    void anUnavailableSourceIsGeocodingUnavailable() {
        provider.failure = new AddressSourceUnavailableException("Nominatim answered 503");

        assertThatThrownBy(() -> service.search("larios", user))
                .isInstanceOfSatisfying(
                        GeocodingUnavailableException.class,
                        e -> {
                            assertThat(e.code()).contains("GEOCODING_UNAVAILABLE");
                            assertThat(e.status().value()).isEqualTo(503);
                        });
    }

    @Test
    void anAnswerTheSourceGaveThatCannotBeReadIsGeocodingUnavailable() {
        provider.failure = new AddressAnswerRejectedException("Nominatim answered a bad place");

        assertThatThrownBy(() -> service.search("larios", user))
                .isInstanceOfSatisfying(
                        GeocodingUnavailableException.class,
                        e -> assertThat(e.code()).contains("GEOCODING_UNAVAILABLE"));
    }

    @Test
    void aFailureIsNotCachedSoTheNextSearchCallsTheSourceAgain() {
        provider.failure = new AddressSourceUnavailableException("Nominatim answered 503");
        assertThatThrownBy(() -> service.search("larios", user))
                .isInstanceOf(GeocodingUnavailableException.class);

        provider.failure = null;
        provider.answer = List.of(LARIOS);

        assertThat(service.search("larios", user)).hasSize(1);
        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void aSearchRefusedBecauseTheSourceWasBusyDoesNotCountAgainstTheUser() {
        provider.failure = new AddressSourceBusyException(Duration.ofMillis(700));
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> service.search("larios", user))
                    .isInstanceOf(TooManyAttemptsException.class);
        }
        provider.failure = null;

        for (int i = 0; i < 10; i++) {
            service.search("larios " + i, user);
        }
        assertThatThrownBy(() -> service.search("larios", user))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void theCacheTakesANoBreakSpaceAsASpace() {
        service.search("Calle Larios", user);

        service.search("Calle\u00A0Larios", user);

        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void aBusySourceIsNotCachedEither() {
        provider.failure = new AddressSourceBusyException(Duration.ofMillis(700));
        assertThatThrownBy(() -> service.search("larios", user))
                .isInstanceOf(TooManyAttemptsException.class);

        provider.failure = null;

        assertThatCode(() -> service.search("larios", user)).doesNotThrowAnyException();
        assertThat(provider.calls).isEqualTo(2);
    }

    /** A source that answers what the test says and counts the calls. */
    private static final class StubProvider implements PlaceProvider {

        List<Place> answer = List.of();
        RuntimeException failure;
        int calls;
        String lastQuery;
        int lastLimit;

        @Override
        public List<Place> search(String query, int limit) {
            calls++;
            lastQuery = query;
            lastLimit = limit;
            if (failure != null) {
                throw failure;
            }
            return new ArrayList<>(answer);
        }
    }

    /** A clock the test moves forward, so the cache and the window are tested without waiting. */
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
