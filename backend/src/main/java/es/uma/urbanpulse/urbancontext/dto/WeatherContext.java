package es.uma.urbanpulse.urbancontext.dto;

import es.uma.urbanpulse.urbancontext.entity.Weather;

/** {@code WeatherContext}: the weather of an incident and its source. */
public record WeatherContext(Weather condition, ContextSource source) {

    /**
     * Open-Meteo is the only weather source (ADR-0018), so the source is not read from the
     * observations. {@code null} while the incident has no weather.
     */
    static WeatherContext of(Weather condition) {
        return condition == null ? null : new WeatherContext(condition, ContextSource.OPEN_METEO);
    }
}
