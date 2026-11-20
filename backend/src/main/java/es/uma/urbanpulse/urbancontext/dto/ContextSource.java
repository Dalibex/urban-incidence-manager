package es.uma.urbanpulse.urbancontext.dto;

import java.net.URI;

/**
 * {@code ContextSource}: where a part of the context comes from, with the attribution its licence
 * asks for.
 */
public record ContextSource(String name, String attribution, URI url) {

    /**
     * CC BY 4.0: the attribution goes wherever the weather is shown (ADR-0018). Its name is also
     * the {@code source} stored with each weather observation.
     */
    public static final ContextSource OPEN_METEO =
            new ContextSource(
                    "Open-Meteo",
                    "Weather data by Open-Meteo.com",
                    URI.create("https://open-meteo.com/"));
}
