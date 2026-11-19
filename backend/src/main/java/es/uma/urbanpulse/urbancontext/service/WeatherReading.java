package es.uma.urbanpulse.urbancontext.service;

import java.time.Instant;

/**
 * The raw values of one answer of a weather source. Each one is {@code null} when the source did
 * not send it: unknown is not zero.
 *
 * @param observedAt the instant the values stand for, as the source gives it
 * @param weatherCode the WMO weather code
 * @param windSpeedKmh the wind speed at 10 m, in km/h
 * @param precipitationMm the precipitation, in mm
 */
public record WeatherReading(
        Instant observedAt, Integer weatherCode, Double windSpeedKmh, Double precipitationMm) {}
