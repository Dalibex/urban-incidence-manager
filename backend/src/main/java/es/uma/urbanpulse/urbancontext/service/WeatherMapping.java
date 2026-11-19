package es.uma.urbanpulse.urbancontext.service;

import es.uma.urbanpulse.urbancontext.entity.Weather;
import java.util.Optional;
import java.util.Set;

/** The WMO weather code turned into the {@link Weather} of the schema, as ADR-0018 maps it. */
final class WeatherMapping {

    /** From this wind speed on, a sky without precipitation is {@code WINDY}. */
    static final double WINDY_FROM_KMH = 40;

    // Clear sky, clouds and fog: the codes without precipitation.
    private static final Set<Integer> WITHOUT_PRECIPITATION = Set.of(0, 1, 2, 3, 45, 48);

    private WeatherMapping() {}

    /**
     * @param windSpeedKmh {@code null} when the source did not send it: the code alone decides
     * @return empty for a code outside the table, never a guessed value
     */
    static Optional<Weather> of(int weatherCode, Double windSpeedKmh) {
        var weather = byCode(weatherCode);
        if (weather.isPresent()
                && windSpeedKmh != null
                && windSpeedKmh >= WINDY_FROM_KMH
                && WITHOUT_PRECIPITATION.contains(weatherCode)) {
            return Optional.of(Weather.WINDY);
        }
        return weather;
    }

    private static Optional<Weather> byCode(int code) {
        if (code == 0) {
            return Optional.of(Weather.SUNNY);
        }
        if (code == 1 || code == 2) {
            return Optional.of(Weather.PARTLY_CLOUDY);
        }
        if (code == 3) {
            return Optional.of(Weather.CLOUDY);
        }
        if (code == 45 || code == 48) {
            return Optional.of(Weather.FOGGY);
        }
        if (between(code, 51, 67) || between(code, 80, 82)) {
            return Optional.of(Weather.RAINY);
        }
        if (between(code, 71, 77) || code == 85 || code == 86) {
            return Optional.of(Weather.SNOWY);
        }
        if (between(code, 95, 99)) {
            return Optional.of(Weather.STORMY);
        }
        return Optional.empty();
    }

    private static boolean between(int code, int first, int last) {
        return code >= first && code <= last;
    }
}
