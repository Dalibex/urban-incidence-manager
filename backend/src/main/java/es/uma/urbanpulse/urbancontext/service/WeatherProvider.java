package es.uma.urbanpulse.urbancontext.service;

/**
 * The current weather at a point (ADR-0018). Open-Meteo is the only implementation; changing to
 * another source, such as AEMET, replaces it and its {@code ContextSource}, whose name is the
 * stored {@code source}.
 */
public interface WeatherProvider {

    /** The name stored as the {@code source} of each observation. */
    String source();

    /**
     * @param latitude WGS 84, in degrees
     * @param longitude WGS 84, in degrees
     * @return what the source answered; any value it did not send is {@code null}
     * @throws WeatherSourceUnavailableException if the source does not answer in time, or answers
     *     that it cannot now ({@code 429}, {@code 5xx}): worth trying again later
     * @throws WeatherAnswerRejectedException if the source refuses the request or its answer cannot
     *     be read: the same request would fail again
     */
    WeatherReading currentWeather(double latitude, double longitude);
}
