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

    /**
     * ODbL: the attribution goes wherever an address resolved by Nominatim is shown (#375). Its
     * name is also the stored {@code address_source} of the location.
     */
    public static final ContextSource OPENSTREETMAP =
            new ContextSource(
                    "OpenStreetMap",
                    "© OpenStreetMap contributors",
                    URI.create("https://www.openstreetmap.org/copyright"));

    /**
     * The source stored with a value, by its name. Only these constants are ever stored, so another
     * name is a broken row, never shown without its attribution.
     *
     * @throws IllegalStateException if no source has that name
     */
    public static ContextSource named(String name) {
        for (var known : new ContextSource[] {OPEN_METEO, OPENSTREETMAP}) {
            if (known.name().equals(name)) {
                return known;
            }
        }
        throw new IllegalStateException("Unknown source of a stored value: " + name);
    }
}
