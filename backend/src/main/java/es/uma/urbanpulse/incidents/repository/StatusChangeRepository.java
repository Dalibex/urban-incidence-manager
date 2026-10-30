package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.StatusChange;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusChangeRepository extends JpaRepository<StatusChange, UUID> {}
