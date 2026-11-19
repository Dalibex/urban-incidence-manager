package es.uma.urbanpulse.urbancontext.service;

import es.uma.urbanpulse.urbancontext.entity.ExternalObservation;
import es.uma.urbanpulse.urbancontext.entity.ExternalObservationStatus;
import es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus;
import es.uma.urbanpulse.urbancontext.entity.Weather;
import es.uma.urbanpulse.urbancontext.repository.ExternalObservationRepository;
import es.uma.urbanpulse.urbancontext.repository.UrbanContextRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writes of the weather worker, each in its own short transaction, so none is open while
 * Open-Meteo answers (AGENTS.md, section 6). A separate bean from the worker, because Spring only
 * applies {@code @Transactional} to calls from outside the bean.
 */
@Component
class WeatherContextWriter {

    // The names and units of the raw values of ADR-0018, Provenance.
    static final String WEATHER_CODE = "WEATHER_CODE";
    static final String WIND_SPEED = "WIND_SPEED";
    static final String PRECIPITATION = "PRECIPITATION";

    private final UrbanContextRepository contexts;
    private final ExternalObservationRepository observations;

    WeatherContextWriter(
            UrbanContextRepository contexts, ExternalObservationRepository observations) {
        this.contexts = contexts;
        this.observations = observations;
    }

    @Transactional
    int expireReportedBefore(Instant cutoff) {
        return contexts.expirePendingReportedBefore(utc(cutoff));
    }

    /**
     * Stores the weather and its observations, if the context is still {@code PENDING}.
     *
     * @param capturedAt the instant the backend stores it, the ingestion instant of the
     *     observations too
     * @return whether this call wrote it; {@code false} if another run wrote it first
     */
    @Transactional
    boolean complete(
            UUID contextId,
            Weather weather,
            WeatherReading reading,
            String source,
            Instant capturedAt) {
        if (contexts.completeIfPending(contextId, weather, utc(capturedAt)) == 0) {
            return false;
        }
        observe(
                contextId,
                source,
                WEATHER_CODE,
                reading.weatherCode(),
                "WMO",
                reading.observedAt(),
                capturedAt);
        observe(
                contextId,
                source,
                WIND_SPEED,
                reading.windSpeedKmh(),
                "km/h",
                reading.observedAt(),
                capturedAt);
        observe(
                contextId,
                source,
                PRECIPITATION,
                reading.precipitationMm(),
                "mm",
                reading.observedAt(),
                capturedAt);
        return true;
    }

    /** Ends the context without a value, if it is still {@code PENDING}. No observation is kept. */
    @Transactional
    boolean end(UUID contextId, UrbanContextStatus status) {
        return contexts.endIfPending(contextId, status) == 1;
    }

    // A value the source did not send is not stored: unknown is not zero.
    private void observe(
            UUID contextId,
            String source,
            String type,
            Number value,
            String unit,
            Instant observedAt,
            Instant ingestedAt) {
        if (value == null) {
            return;
        }
        var observation = new ExternalObservation();
        observation.setUrbanContext(contexts.getReferenceById(contextId));
        observation.setSource(source);
        observation.setMeasurementType(type);
        observation.setValue(value.toString());
        observation.setUnit(unit);
        observation.setObservationInstant(observedAt == null ? null : utc(observedAt));
        observation.setIngestionDate(utc(ingestedAt));
        observation.setStatus(ExternalObservationStatus.VALID);
        observations.save(observation);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
