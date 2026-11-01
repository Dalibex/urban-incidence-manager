package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Location;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByIncidentId(UUID incidentId);
}
