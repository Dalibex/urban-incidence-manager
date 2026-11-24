package es.uma.urbanpulse.geospatial.service;

import java.util.Objects;

/**
 * A place of Málaga that the address search found (#179).
 *
 * @param displayName the address or place as the source writes it
 * @param latitude WGS 84, in degrees
 * @param longitude WGS 84, in degrees
 */
public record Place(String displayName, double latitude, double longitude) {

    public Place {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (!(latitude >= -90 && latitude <= 90) || !(longitude >= -180 && longitude <= 180)) {
            throw new IllegalArgumentException("The position is not a WGS 84 position");
        }
    }
}
