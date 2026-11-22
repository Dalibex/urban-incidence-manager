package es.uma.urbanpulse.geospatial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.uma.urbanpulse.geospatial.repository.LocationAddressRepository;
import es.uma.urbanpulse.geospatial.repository.PendingAddress;
import es.uma.urbanpulse.incidents.entity.AddressStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.data.domain.Limit;

/** The outcomes of one run of the address worker, with its collaborators replaced. */
class AddressEnrichmentWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Duration MAX_DELAY = Duration.ofHours(24);
    private static final int BATCH_SIZE = 7;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final String SOURCE = "OpenStreetMap";

    private final AddressProvider provider = mock(AddressProvider.class);
    private final LocationAddressRepository locations = mock(LocationAddressRepository.class);
    private final AddressWriter writer = mock(AddressWriter.class);
    private AddressEnrichmentWorker worker;

    @BeforeEach
    void createWorker() {
        when(provider.source()).thenReturn(SOURCE);
        worker = workerWith(true, Duration.ZERO);
    }

    private AddressEnrichmentWorker workerWith(boolean enabled, Duration spacing) {
        var properties =
                new AddressEnrichmentProperties(
                        enabled,
                        URI.create("http://localhost:1"),
                        null,
                        "UrbanPulse tests",
                        Duration.ofSeconds(30),
                        BATCH_SIZE,
                        spacing,
                        Duration.ofSeconds(3),
                        MAX_DELAY);
        return new AddressEnrichmentWorker(
                properties, provider, locations, writer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PendingAddress pending(long secondsAgo) {
        return new PendingAddress(
                UUID.randomUUID(),
                GEOMETRY.createPoint(new Coordinate(-4.4214, 36.7213)),
                NOW.minusSeconds(secondsAgo).atOffset(ZoneOffset.UTC));
    }

    private void givenPending(PendingAddress... pending) {
        when(locations.findOldestPending(any(Limit.class))).thenReturn(List.of(pending));
    }

    @Test
    void aFoundAddressIsStoredWithItsSourceAndInstant() {
        var location = pending(30);
        givenPending(location);
        when(provider.lookUp(36.7213, -4.4214))
                .thenReturn(new AddressLookup.Found("Calle Larios, 5, 29005 Málaga"));

        worker.resolvePending();

        verify(writer).resolve(location.locationId(), "Calle Larios, 5, 29005 Málaga", SOURCE, NOW);
    }

    @Test
    void theBatchSizeLimitsTheLocationsTaken() {
        givenPending();

        worker.resolvePending();

        verify(locations).findOldestPending(Limit.of(BATCH_SIZE));
    }

    @Test
    void aPositionOutsideTheAreaEndsOutsideTheArea() {
        var location = pending(30);
        givenPending(location);
        when(provider.lookUp(anyDouble(), anyDouble())).thenReturn(new AddressLookup.OutsideArea());

        worker.resolvePending();

        verify(writer).end(location.locationId(), AddressStatus.OUTSIDE_AREA);
        verify(writer, never()).resolve(any(), any(), any(), any());
    }

    @Test
    void aPlaceWithoutAStreetEndsUnavailable() {
        var location = pending(30);
        givenPending(location);
        when(provider.lookUp(anyDouble(), anyDouble())).thenReturn(new AddressLookup.NoStreet());

        worker.resolvePending();

        verify(writer).end(location.locationId(), AddressStatus.UNAVAILABLE);
    }

    @Test
    void aRejectedAnswerEndsUnavailable() {
        var location = pending(30);
        givenPending(location);
        when(provider.lookUp(anyDouble(), anyDouble()))
                .thenThrow(new AddressAnswerRejectedException("Nominatim answered 400"));

        worker.resolvePending();

        verify(writer).end(location.locationId(), AddressStatus.UNAVAILABLE);
    }

    @Test
    void anUnavailableSourceLeavesTheAddressPending() {
        givenPending(pending(30));
        when(provider.lookUp(anyDouble(), anyDouble()))
                .thenThrow(new AddressSourceUnavailableException("Nominatim answered 503"));

        worker.resolvePending();

        verify(writer, never()).resolve(any(), any(), any(), any());
        verify(writer, never()).end(any(), any());
    }

    @Test
    void oneFailureDoesNotStopTheRun() {
        var second = pending(20);
        givenPending(pending(30), second);
        when(provider.lookUp(anyDouble(), anyDouble()))
                .thenThrow(new AddressSourceUnavailableException("Nominatim answered 503"))
                .thenReturn(new AddressLookup.Found("Calle Larios, Málaga"));

        worker.resolvePending();

        verify(writer).resolve(eq(second.locationId()), any(), any(), any());
    }

    @Test
    void twoFailuresInARowStopTheRun() {
        givenPending(pending(30), pending(20), pending(10));
        when(provider.lookUp(anyDouble(), anyDouble()))
                .thenThrow(new AddressSourceUnavailableException("Nominatim answered 503"));

        worker.resolvePending();

        verify(provider, times(AddressEnrichmentWorker.FAILURES_IN_A_ROW_THAT_STOP_A_RUN))
                .lookUp(anyDouble(), anyDouble());
    }

    @Test
    void pendingAddressesOlderThanTheMaximumDelayExpireBeforeTheBatch() {
        givenPending();

        worker.resolvePending();

        verify(writer).expireReportedBefore(NOW.minus(MAX_DELAY));
    }

    @Test
    void callsAreSpacedByTheRequestSpacing() {
        worker = workerWith(true, Duration.ofMillis(150));
        givenPending(pending(30), pending(20), pending(10));
        when(provider.lookUp(anyDouble(), anyDouble()))
                .thenReturn(new AddressLookup.Found("Calle Larios, Málaga"));

        long start = System.nanoTime();
        worker.resolvePending();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Two waits between three calls, none before the first.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(300);
        verify(provider, times(3)).lookUp(anyDouble(), anyDouble());
    }

    @Test
    void theScheduledRunDoesNothingWhenDisabled() {
        workerWith(false, Duration.ZERO).scheduledRun();

        verify(writer, never()).expireReportedBefore(any());
        verify(locations, never()).findOldestPending(any());
    }

    @Test
    void theScheduledRunWorksWhenEnabled() {
        givenPending();

        worker.scheduledRun();

        verify(locations).findOldestPending(any());
    }
}
