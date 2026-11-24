package es.uma.urbanpulse.geospatial.service;

import java.util.List;

/**
 * The places of the municipality of Málaga that match a text (#179). Nominatim is the only
 * implementation (ADR-0002).
 */
public interface PlaceProvider {

    /**
     * @param query the text to search, already validated
     * @param limit the most places to return
     * @return the places of Málaga that match, the most relevant first; empty when none does
     * @throws AddressSourceBusyException if the turn to call the source is further away than {@code
     *     urbanpulse.address.request-spacing}, the time between two calls, which is as long as a
     *     search waits
     * @throws AddressSourceUnavailableException if the source does not answer in time, or answers
     *     that it cannot now ({@code 429}, {@code 5xx})
     * @throws AddressAnswerRejectedException if the source refuses the request or its answer cannot
     *     be read
     */
    List<Place> search(String query, int limit);
}
