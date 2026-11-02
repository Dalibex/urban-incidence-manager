package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Location;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads go through {@code Incident.location}, the only way to reach the location of an incident.
 */
public interface LocationRepository extends JpaRepository<Location, UUID> {}
