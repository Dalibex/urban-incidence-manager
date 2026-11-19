package es.uma.urbanpulse.urbancontext.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus;
import es.uma.urbanpulse.urbancontext.entity.Weather;
import es.uma.urbanpulse.urbancontext.repository.PendingContext;
import es.uma.urbanpulse.urbancontext.repository.UrbanContextRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.data.domain.Limit;

/** The outcomes of one run of the weather worker, with its collaborators replaced. */
class WeatherEnrichmentWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Duration MAX_DELAY = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 7;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final WeatherProvider provider = mock(WeatherProvider.class);
    private final UrbanContextRepository contexts = mock(UrbanContextRepository.class);
    private final WeatherContextWriter writer = mock(WeatherContextWriter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private WeatherEnrichmentWorker worker;

    @BeforeEach
    void createWorker() {
        when(provider.source()).thenReturn("Open-Meteo");
        worker = workerWith(true);
    }

    private WeatherEnrichmentWorker workerWith(boolean enabled) {
        var properties =
                new WeatherEnrichmentProperties(
                        enabled,
                        URI.create("http://localhost:1"),
                        null,
                        Duration.ofSeconds(10),
                        BATCH_SIZE,
                        Duration.ofSeconds(2),
                        MAX_DELAY);
        return new WeatherEnrichmentWorker(
                properties, provider, contexts, writer, Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    private static PendingContext pending(long secondsAgo) {
        return new PendingContext(
                UUID.randomUUID(),
                GEOMETRY.createPoint(new Coordinate(-4.42, 36.72)),
                NOW.minusSeconds(secondsAgo).atOffset(ZoneOffset.UTC));
    }

    private void givenPending(PendingContext... pending) {
        when(contexts.findOldestPending(any(Limit.class))).thenReturn(List.of(pending));
    }

    private static WeatherReading reading(Integer code, Double wind) {
        return new WeatherReading(NOW.minusSeconds(60), code, wind, 0.0);
    }

    private double endedContexts(UrbanContextStatus status) {
        var counter =
                meters.find("urbanpulse.weather.contexts").tag("status", status.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    private long requests(String outcome) {
        var timer = meters.find("urbanpulse.weather.requests").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void anAnswerWithAKnownCodeCompletesTheContext() {
        var context = pending(30);
        givenPending(context);
        var reading = reading(61, 10.0);
        when(provider.currentWeather(anyDouble(), anyDouble())).thenReturn(reading);
        when(writer.complete(any(), any(), any(), any(), any())).thenReturn(true);

        worker.enrichPending();

        verify(provider).currentWeather(36.72, -4.42);
        verify(writer).complete(context.id(), Weather.RAINY, reading, "Open-Meteo", NOW);
        assertThat(endedContexts(UrbanContextStatus.COMPLETE)).isEqualTo(1);
        assertThat(requests("answered")).isEqualTo(1);
    }

    @Test
    void theBatchSizeLimitsTheContextsTaken() {
        givenPending();

        worker.enrichPending();

        verify(contexts).findOldestPending(Limit.of(BATCH_SIZE));
    }

    @Test
    void strongWindOverAClearSkyIsStoredAsWindy() {
        var context = pending(30);
        givenPending(context);
        var reading = reading(0, 55.0);
        when(provider.currentWeather(anyDouble(), anyDouble())).thenReturn(reading);
        when(writer.complete(any(), any(), any(), any(), any())).thenReturn(true);

        worker.enrichPending();

        verify(writer).complete(context.id(), Weather.WINDY, reading, "Open-Meteo", NOW);
    }

    @Test
    void completingRecordsTheDelaySinceTheReport() {
        givenPending(pending(45));
        when(provider.currentWeather(anyDouble(), anyDouble())).thenReturn(reading(0, 1.0));
        when(writer.complete(any(), any(), any(), any(), any())).thenReturn(true);

        worker.enrichPending();

        var delay = meters.get("urbanpulse.weather.delay").timer();
        assertThat(delay.count()).isEqualTo(1);
        assertThat(delay.totalTime(TimeUnit.SECONDS)).isEqualTo(45);
    }

    @Test
    void anAnswerWithoutACodeEndsTheContextUnavailable() {
        var context = pending(30);
        givenPending(context);
        when(provider.currentWeather(anyDouble(), anyDouble())).thenReturn(reading(null, 5.0));
        when(writer.end(context.id(), UrbanContextStatus.UNAVAILABLE)).thenReturn(true);

        worker.enrichPending();

        verify(writer).end(context.id(), UrbanContextStatus.UNAVAILABLE);
        verify(writer, never()).complete(any(), any(), any(), any(), any());
        assertThat(endedContexts(UrbanContextStatus.UNAVAILABLE)).isEqualTo(1);
    }

    @Test
    void aCodeOutsideTheTableEndsTheContextUnavailable() {
        var context = pending(30);
        givenPending(context);
        when(provider.currentWeather(anyDouble(), anyDouble())).thenReturn(reading(100, 5.0));
        when(writer.end(context.id(), UrbanContextStatus.UNAVAILABLE)).thenReturn(true);

        worker.enrichPending();

        verify(writer).end(context.id(), UrbanContextStatus.UNAVAILABLE);
        verify(writer, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void aRejectedAnswerEndsTheContextWithError() {
        var context = pending(30);
        givenPending(context);
        when(provider.currentWeather(anyDouble(), anyDouble()))
                .thenThrow(new WeatherAnswerRejectedException("400"));
        when(writer.end(context.id(), UrbanContextStatus.ERROR)).thenReturn(true);

        worker.enrichPending();

        verify(writer).end(context.id(), UrbanContextStatus.ERROR);
        assertThat(endedContexts(UrbanContextStatus.ERROR)).isEqualTo(1);
        assertThat(requests("rejected")).isEqualTo(1);
    }

    @Test
    void anUnavailableSourceLeavesTheContextPending() {
        givenPending(pending(30));
        when(provider.currentWeather(anyDouble(), anyDouble()))
                .thenThrow(new WeatherSourceUnavailableException("503"));

        worker.enrichPending();

        verify(writer, never()).complete(any(), any(), any(), any(), any());
        verify(writer, never()).end(any(), any());
        assertThat(requests("unavailable")).isEqualTo(1);
    }

    @Test
    void oneFailureDoesNotStopTheRun() {
        var first = pending(90);
        var second = pending(60);
        var third = pending(30);
        givenPending(first, second, third);
        when(provider.currentWeather(anyDouble(), anyDouble()))
                .thenThrow(new WeatherSourceUnavailableException("timeout"))
                .thenReturn(reading(0, 1.0));
        when(writer.complete(any(), any(), any(), any(), any())).thenReturn(true);

        worker.enrichPending();

        verify(provider, times(3)).currentWeather(anyDouble(), anyDouble());
        verify(writer).complete(eq(second.id()), any(), any(), any(), any());
        verify(writer).complete(eq(third.id()), any(), any(), any(), any());
        verify(writer, never()).complete(eq(first.id()), any(), any(), any(), any());
    }

    @Test
    void twoFailuresInARowStopTheRun() {
        givenPending(pending(90), pending(60), pending(30));
        when(provider.currentWeather(anyDouble(), anyDouble()))
                .thenThrow(new WeatherSourceUnavailableException("down"));

        worker.enrichPending();

        verify(provider, times(2)).currentWeather(anyDouble(), anyDouble());
        verify(writer, never()).end(any(), any());
    }

    @Test
    void aRejectedAnswerBetweenTwoFailuresResetsTheCount() {
        givenPending(pending(120), pending(90), pending(60), pending(30));
        when(provider.currentWeather(anyDouble(), anyDouble()))
                .thenThrow(new WeatherSourceUnavailableException("down"))
                .thenThrow(new WeatherAnswerRejectedException("400"))
                .thenThrow(new WeatherSourceUnavailableException("down"))
                .thenReturn(reading(0, 1.0));
        when(writer.end(any(), any())).thenReturn(true);
        when(writer.complete(any(), any(), any(), any(), any())).thenReturn(true);

        worker.enrichPending();

        verify(provider, times(4)).currentWeather(anyDouble(), anyDouble());
    }

    @Test
    void pendingContextsOlderThanTheMaximumDelayExpireBeforeTheBatch() {
        when(writer.expireReportedBefore(NOW.minus(MAX_DELAY))).thenReturn(3);
        givenPending();

        worker.enrichPending();

        verify(writer).expireReportedBefore(NOW.minus(MAX_DELAY));
        assertThat(endedContexts(UrbanContextStatus.UNAVAILABLE)).isEqualTo(3);
    }

    @Test
    void nothingExpiredCountsNothing() {
        when(writer.expireReportedBefore(any())).thenReturn(0);
        givenPending();

        worker.enrichPending();

        assertThat(endedContexts(UrbanContextStatus.UNAVAILABLE)).isZero();
    }

    @Test
    void aLostRaceOnCompletingCountsAndRecordsNothing() {
        givenPending(pending(30));
        when(provider.currentWeather(anyDouble(), anyDouble())).thenReturn(reading(0, 1.0));
        when(writer.complete(any(), any(), any(), any(), any())).thenReturn(false);

        worker.enrichPending();

        assertThat(endedContexts(UrbanContextStatus.COMPLETE)).isZero();
        assertThat(meters.get("urbanpulse.weather.delay").timer().count()).isZero();
    }

    @Test
    void aLostRaceOnAnEndingCountsNothing() {
        givenPending(pending(30));
        when(provider.currentWeather(anyDouble(), anyDouble()))
                .thenThrow(new WeatherAnswerRejectedException("400"));
        when(writer.end(any(), any())).thenReturn(false);

        worker.enrichPending();

        assertThat(endedContexts(UrbanContextStatus.ERROR)).isZero();
    }

    @Test
    void theScheduledRunDoesNothingWhenDisabled() {
        workerWith(false).scheduledRun();

        verify(contexts, never()).findOldestPending(any());
        verify(writer, never()).expireReportedBefore(any());
    }

    @Test
    void theScheduledRunWorksWhenEnabled() {
        givenPending();

        worker.scheduledRun();

        verify(writer).expireReportedBefore(NOW.minus(MAX_DELAY));
    }
}
