package es.uma.urbanpulse.geospatial.service;

import es.uma.urbanpulse.geospatial.repository.LocationAddressRepository;
import es.uma.urbanpulse.incidents.entity.AddressStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writes of the address worker, each in its own short transaction, so none is open while
 * Nominatim answers (AGENTS.md, section 6). A separate bean from the worker, because Spring only
 * applies {@code @Transactional} to calls from outside the bean.
 */
@Component
class AddressWriter {

    private final LocationAddressRepository locations;

    AddressWriter(LocationAddressRepository locations) {
        this.locations = locations;
    }

    @Transactional
    void expireReportedBefore(Instant cutoff) {
        locations.expirePendingReportedBefore(utc(cutoff));
    }

    /**
     * Stores the address, if it is still {@code PENDING}: another run may have written it first.
     *
     * @param resolvedAt the instant the backend stores it
     */
    @Transactional
    void resolve(UUID locationId, String address, String source, Instant resolvedAt) {
        locations.resolveIfPending(locationId, address, source, utc(resolvedAt));
    }

    /** Ends the address without a value, if it is still {@code PENDING}. */
    @Transactional
    void end(UUID locationId, AddressStatus status) {
        locations.endIfPending(locationId, status);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
