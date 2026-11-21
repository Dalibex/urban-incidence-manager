package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.AddressStatus;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.urbancontext.dto.ContextSource;

/**
 * {@code IncidentLocation}: WGS 84 position in decimal degrees and what is known about the place.
 * {@code null} means "not available", never "none". {@code addressStatus} says why there is no
 * {@code address}, and {@code addressSource} comes only with an address (#375).
 */
public record IncidentLocationResponse(
        double latitude,
        double longitude,
        Double accuracy,
        String address,
        AddressStatus addressStatus,
        ContextSource addressSource,
        String neighborhood,
        String district) {

    static IncidentLocationResponse of(Location location) {
        // A JTS point keeps x = longitude, y = latitude.
        var point = location.getCoordinates();
        var address = AddressView.of(location);
        return new IncidentLocationResponse(
                point.getY(),
                point.getX(),
                location.getAccuracy(),
                address.address(),
                address.status(),
                address.source(),
                location.getNeighborhood(),
                location.getDistrict());
    }

    /**
     * The address as the API shows it: a value only while it is {@code AVAILABLE}, with its source,
     * so a value left in the row is never shown as current.
     */
    record AddressView(String address, AddressStatus status, ContextSource source) {

        /**
         * @param location {@code null} for an incident without a location row, which the schema
         *     allows although a report always saves one: it has no address to give
         */
        static AddressView of(Location location) {
            if (location == null) {
                return new AddressView(null, AddressStatus.UNAVAILABLE, null);
            }
            var status = location.getAddressStatus();
            if (status != AddressStatus.AVAILABLE) {
                return new AddressView(null, status, null);
            }
            return new AddressView(
                    location.getAddress(),
                    status,
                    ContextSource.named(location.getAddressSource()));
        }
    }
}
