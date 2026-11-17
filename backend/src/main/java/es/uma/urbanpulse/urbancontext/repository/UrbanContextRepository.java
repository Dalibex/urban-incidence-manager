package es.uma.urbanpulse.urbancontext.repository;

import es.uma.urbanpulse.urbancontext.entity.UrbanContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UrbanContextRepository extends JpaRepository<UrbanContext, UUID> {

    /** Empty for an incident reported before the enrichment existed (ADR-0018). */
    Optional<UrbanContext> findByIncidentId(UUID incidentId);
}
