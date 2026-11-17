package es.uma.urbanpulse.urbancontext.repository;

import es.uma.urbanpulse.urbancontext.entity.UrbanContext;
import es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus;
import es.uma.urbanpulse.urbancontext.entity.Weather;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UrbanContextRepository extends JpaRepository<UrbanContext, UUID> {

    /** The oldest reports first, as the worker of ADR-0018 takes them. */
    @Query(
            """
            SELECT new es.uma.urbanpulse.urbancontext.repository.PendingContext(
                c.id, l.coordinates, i.reportedAt)
            FROM UrbanContext c JOIN c.incident i JOIN i.location l
            WHERE c.status = es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus.PENDING
            ORDER BY i.reportedAt, c.id
            """)
    List<PendingContext> findOldestPending(Limit limit);

    /**
     * Marks {@code UNAVAILABLE} every {@code PENDING} context reported before {@code cutoff}, in
     * one statement, so an old backlog never takes the place of new reports.
     *
     * @return how many changed
     */
    @Modifying
    @Query(
            """
            UPDATE UrbanContext c
            SET c.status = es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus.UNAVAILABLE
            WHERE c.status = es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus.PENDING
              AND c.incident.id IN (SELECT i.id FROM Incident i WHERE i.reportedAt < :cutoff)
            """)
    int expirePendingReportedBefore(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Stores the weather only if the context is still {@code PENDING}: with two runs or two
     * replicas, one write wins and the other changes nothing.
     *
     * @return 1 if it changed the context, 0 if another run wrote it first
     */
    @Modifying
    @Query(
            """
            UPDATE UrbanContext c
            SET c.status = es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus.COMPLETE,
                c.weather = :weather, c.capturedAt = :capturedAt
            WHERE c.id = :id
              AND c.status = es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus.PENDING
            """)
    int completeIfPending(
            @Param("id") UUID id,
            @Param("weather") Weather weather,
            @Param("capturedAt") OffsetDateTime capturedAt);

    /**
     * Ends a {@code PENDING} context without a value, as {@code ERROR} or {@code UNAVAILABLE}.
     *
     * @return 1 if it changed the context, 0 if another run wrote it first
     */
    @Modifying
    @Query(
            """
            UPDATE UrbanContext c SET c.status = :status
            WHERE c.id = :id
              AND c.status = es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus.PENDING
            """)
    int endIfPending(@Param("id") UUID id, @Param("status") UrbanContextStatus status);
}
