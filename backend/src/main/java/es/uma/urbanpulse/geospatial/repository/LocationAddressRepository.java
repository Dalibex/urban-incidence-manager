package es.uma.urbanpulse.geospatial.repository;

import es.uma.urbanpulse.incidents.entity.AddressStatus;
import es.uma.urbanpulse.incidents.entity.Location;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The address columns of {@code location}, for the address worker (#375). Every write changes the
 * row only if its address is still {@code PENDING}: with two runs or two replicas, one write wins
 * and the other changes nothing. The incident itself is never touched.
 */
public interface LocationAddressRepository extends Repository<Location, UUID> {

    /** The oldest reports first. */
    @Query(
            """
            SELECT new es.uma.urbanpulse.geospatial.repository.PendingAddress(
                l.id, l.coordinates, i.reportedAt)
            FROM Location l JOIN l.incident i
            WHERE l.addressStatus = es.uma.urbanpulse.incidents.entity.AddressStatus.PENDING
            ORDER BY i.reportedAt, l.id
            """)
    List<PendingAddress> findOldestPending(Limit limit);

    /**
     * Marks {@code UNAVAILABLE} every {@code PENDING} address reported before {@code cutoff}, in
     * one statement, so an old backlog never takes the place of new reports.
     *
     * @return how many changed
     */
    @Modifying
    @Query(
            """
            UPDATE Location l
            SET l.addressStatus = es.uma.urbanpulse.incidents.entity.AddressStatus.UNAVAILABLE
            WHERE l.addressStatus = es.uma.urbanpulse.incidents.entity.AddressStatus.PENDING
              AND l.incident.id IN (SELECT i.id FROM Incident i WHERE i.reportedAt < :cutoff)
            """)
    int expirePendingReportedBefore(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * @return 1 if it stored the address, 0 if another run wrote it first
     */
    @Modifying
    @Query(
            """
            UPDATE Location l
            SET l.addressStatus = es.uma.urbanpulse.incidents.entity.AddressStatus.AVAILABLE,
                l.address = :address, l.addressSource = :source, l.addressResolvedAt = :resolvedAt
            WHERE l.id = :id
              AND l.addressStatus = es.uma.urbanpulse.incidents.entity.AddressStatus.PENDING
            """)
    int resolveIfPending(
            @Param("id") UUID id,
            @Param("address") String address,
            @Param("source") String source,
            @Param("resolvedAt") OffsetDateTime resolvedAt);

    /**
     * Ends a {@code PENDING} address without a value, as {@code OUTSIDE_AREA} or {@code
     * UNAVAILABLE}.
     *
     * @return 1 if it changed the location, 0 if another run wrote it first
     */
    @Modifying
    @Query(
            """
            UPDATE Location l SET l.addressStatus = :status
            WHERE l.id = :id
              AND l.addressStatus = es.uma.urbanpulse.incidents.entity.AddressStatus.PENDING
            """)
    int endIfPending(@Param("id") UUID id, @Param("status") AddressStatus status);
}
