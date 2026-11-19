package es.uma.urbanpulse.urbancontext.service;

import es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus;
import es.uma.urbanpulse.urbancontext.repository.PendingContext;
import es.uma.urbanpulse.urbancontext.repository.UrbanContextRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fills the weather of the {@code PENDING} contexts, as the mechanism of ADR-0018 says. A context
 * that waits survives a restart, because it is a row; the next run takes it.
 *
 * <p>Retries are bounded by time: a context whose call fails stays {@code PENDING} for the next run
 * until {@code WEATHER_MAX_DELAY}. One failure does not stop the run, so a context whose call fails
 * does not hold back the others; two in a row mean that the source itself is failing, and the rest
 * of the batch waits.
 */
@Service
public class WeatherEnrichmentWorker {

    private static final Logger log = LoggerFactory.getLogger(WeatherEnrichmentWorker.class);

    /** Two failures in a row mean that the source is failing, not one context. */
    static final int FAILURES_IN_A_ROW_THAT_STOP_A_RUN = 2;

    private final WeatherEnrichmentProperties properties;
    private final WeatherProvider provider;
    private final UrbanContextRepository contexts;
    private final WeatherContextWriter writer;
    private final Clock clock;
    // Registered once here, so the worker never keeps the registry (SpotBugs EI_EXPOSE_REP2).
    private final Timer answered;
    private final Timer unavailable;
    private final Timer rejected;
    private final Map<UrbanContextStatus, Counter> ended = new EnumMap<>(UrbanContextStatus.class);
    private final Timer delay;

    public WeatherEnrichmentWorker(
            WeatherEnrichmentProperties properties,
            WeatherProvider provider,
            UrbanContextRepository contexts,
            WeatherContextWriter writer,
            Clock clock,
            MeterRegistry meters) {
        this.properties = properties;
        this.provider = provider;
        this.contexts = contexts;
        this.writer = writer;
        this.clock = clock;
        this.answered = request("answered", meters);
        this.unavailable = request("unavailable", meters);
        this.rejected = request("rejected", meters);
        for (var status :
                List.of(
                        UrbanContextStatus.COMPLETE,
                        UrbanContextStatus.ERROR,
                        UrbanContextStatus.UNAVAILABLE)) {
            ended.put(
                    status,
                    Counter.builder("urbanpulse.weather.contexts")
                            .description("Urban contexts the weather worker ended, by status")
                            .tag("status", status.name())
                            .register(meters));
        }
        // For the evaluation of ADR-0018: 90 % COMPLETE within 60 s of the report.
        this.delay =
                Timer.builder("urbanpulse.weather.delay")
                        .description("Time from the report of an incident to its stored weather")
                        .register(meters);
    }

    @Scheduled(
            initialDelayString = "${urbanpulse.weather.interval}",
            fixedDelayString = "${urbanpulse.weather.interval}")
    void scheduledRun() {
        if (properties.enabled()) {
            enrichPending();
        }
    }

    /** One run of the worker. Public for the tests, which call it instead of waiting. */
    public void enrichPending() {
        var cutoff = clock.instant().minus(properties.maxDelay());
        var expired = writer.expireReportedBefore(cutoff);
        if (expired > 0) {
            ended.get(UrbanContextStatus.UNAVAILABLE).increment(expired);
        }

        var failuresInARow = 0;
        for (var pending : contexts.findOldestPending(Limit.of(properties.batchSize()))) {
            if (enrichAndTellIfTheSourceAnswered(pending)) {
                failuresInARow = 0;
                continue;
            }
            failuresInARow++;
            if (failuresInARow == FAILURES_IN_A_ROW_THAT_STOP_A_RUN) {
                log.warn(
                        "Weather enrichment stopped this run: {} failures in a row",
                        failuresInARow);
                return;
            }
        }
    }

    /**
     * @return {@code false} only if the source did not answer, so the context waits for the next
     *     run
     */
    private boolean enrichAndTellIfTheSourceAnswered(PendingContext pending) {
        var sample = Timer.start();
        WeatherReading reading;
        try {
            reading = provider.currentWeather(pending.latitude(), pending.longitude());
        } catch (WeatherSourceUnavailableException e) {
            sample.stop(unavailable);
            // The context id is enough to find the incident; the position of a report is not
            // written to the log.
            log.warn("No weather for urban context {}: {}", pending.id(), e.getMessage());
            return false;
        } catch (WeatherAnswerRejectedException e) {
            sample.stop(rejected);
            log.warn("Weather refused for urban context {}: {}", pending.id(), e.getMessage());
            end(pending, UrbanContextStatus.ERROR);
            return true;
        }
        sample.stop(answered);

        var weather =
                reading.weatherCode() == null
                        ? null
                        : WeatherMapping.of(reading.weatherCode(), reading.windSpeedKmh())
                                .orElse(null);
        if (weather == null) {
            end(pending, UrbanContextStatus.UNAVAILABLE);
            return true;
        }
        var capturedAt = clock.instant();
        // The writer has committed when it returns, so only stored weather is measured.
        if (writer.complete(pending.id(), weather, reading, provider.source(), capturedAt)) {
            ended.get(UrbanContextStatus.COMPLETE).increment();
            delay.record(Duration.between(pending.reportedAt().toInstant(), capturedAt));
        }
        return true;
    }

    private void end(PendingContext pending, UrbanContextStatus status) {
        if (writer.end(pending.id(), status)) {
            ended.get(status).increment();
        }
    }

    private static Timer request(String outcome, MeterRegistry meters) {
        return Timer.builder("urbanpulse.weather.requests")
                .description("Calls to the weather source, by outcome")
                .tag("outcome", outcome)
                .register(meters);
    }
}
