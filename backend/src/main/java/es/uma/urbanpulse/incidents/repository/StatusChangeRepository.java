package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.StatusChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StatusChangeRepository extends JpaRepository<StatusChange, UUID> {

    /**
     * The history of an incident, oldest first, with each actor in the same query. {@code id}
     * breaks ties between entries of the same instant, so the order is stable.
     */
    @Query(
            """
            select s from StatusChange s join fetch s.actor
            where s.incident.id = :incidentId
            order by s.instant, s.id
            """)
    List<StatusChange> findHistory(UUID incidentId);
}
