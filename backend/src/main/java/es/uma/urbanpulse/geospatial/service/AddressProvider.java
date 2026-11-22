package es.uma.urbanpulse.geospatial.service;

/**
 * The address at a point (#375). Nominatim is the only implementation; changing to another source,
 * such as the street map of Open Data Málaga, replaces it and its {@code ContextSource}, whose name
 * is the stored {@code address_source}.
 */
public interface AddressProvider {

    /** The name stored as the {@code address_source} of each resolved address. */
    String source();

    /**
     * @param latitude WGS 84, in degrees
     * @param longitude WGS 84, in degrees
     * @throws AddressSourceUnavailableException if the source does not answer in time, or answers
     *     that it cannot now ({@code 429}, {@code 5xx}): worth trying again later
     * @throws AddressAnswerRejectedException if the source refuses the request or its answer cannot
     *     be read: the same request would fail again
     */
    AddressLookup lookUp(double latitude, double longitude);
}
