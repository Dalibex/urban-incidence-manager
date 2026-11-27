package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Incident;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository
        extends JpaRepository<Incident, UUID>,
                JpaSpecificationExecutor<Incident>,
                IncidentPositionRepository,
                IncidentStatisticsRepository {

    /**
     * A page of incidents with their category and location in the same query, so listing has no
     * N+1.
     */
    @Override
    @EntityGraph(attributePaths = {"category", "location"})
    Page<Incident> findAll(Specification<Incident> spec, Pageable pageable);

    /**
     * One incident with its category and location in the same query. The location is the inverse
     * side of a one-to-one, which Hibernate always loads, so without the graph it is a second
     * query.
     */
    @Override
    @EntityGraph(attributePaths = {"category", "location"})
    Optional<Incident> findOne(Specification<Incident> spec);

    /**
     * Makes PostgreSQL plan every statement of the current transaction for the values bound to it,
     * never with a generic plan, until the transaction ends. The text search (#266) needs it: a
     * generic plan cannot see the text, and walks the whole date index for one no incident has.
     *
     * @return the new value of the setting
     */
    @Query(
            value = "SELECT set_config('plan_cache_mode', 'force_custom_plan', true)",
            nativeQuery = true)
    String planForTheBoundValues();

    /**
     * Locks the incident row until the transaction ends ({@code SELECT ... FOR UPDATE}), so two
     * photos added at once are checked against the limit one after the other, and a status change
     * waits for the photo or the photo sees the new status (#275).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Incident> findLockedById(UUID id);

    /**
     * The incident a citizen reported with an {@code Idempotency-Key} (#289), with its category and
     * location, which the answer of a retry needs after the transaction ends.
     */
    @EntityGraph(attributePaths = {"category", "location"})
    @Query(
            "select i from Incident i"
                    + " where i.reporter.id = :reporterId and i.idempotencyKey.value = :key")
    Optional<Incident> findByIdempotencyKey(
            @Param("reporterId") UUID reporterId, @Param("key") UUID key);

    /**
     * The id and title of some incidents, in one query and without loading them: an entity would
     * also load its location, one query per incident. For the labels of the audit log (#41).
     */
    @Query("select i.id as id, i.title as title from Incident i where i.id in :ids")
    List<Title> findTitlesByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * The id and title of the incidents in {@code IN_PROGRESS} whose active assignment is the
     * technician's, by title. Only that technician can resolve them (ADR-0020), so the account may
     * not be disabled or lose its role while there is one (#274). It uses {@code
     * ix_assignment_active_technician}.
     */
    @Query(
            "select i.id as id, i.title as title from Incident i join i.assignments a"
                    + " where a.technician.id = :technicianId and a.isActive = true"
                    + " and i.status = es.uma.urbanpulse.incidents.entity.IncidentStatus.IN_PROGRESS"
                    + " order by i.title, i.id")
    List<Title> findTitlesInProgressFor(@Param("technicianId") UUID technicianId);

    /** The id and title of an incident. */
    interface Title {
        UUID getId();

        String getTitle();
    }
}
