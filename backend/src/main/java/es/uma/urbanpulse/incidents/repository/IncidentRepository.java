package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Incident;
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
     * The id and title of some incidents, in one query and without loading them: an entity would
     * also load its location, one query per incident. For the labels of the audit log (#41).
     */
    @Query("select i.id as id, i.title as title from Incident i where i.id in :ids")
    List<Title> findTitlesByIdIn(@Param("ids") Collection<UUID> ids);

    /** The id and title of an incident. */
    interface Title {
        UUID getId();

        String getTitle();
    }
}
