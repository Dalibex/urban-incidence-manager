package es.uma.urbanpulse.urbancontext.repository;

import es.uma.urbanpulse.urbancontext.entity.ExternalObservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalObservationRepository extends JpaRepository<ExternalObservation, UUID> {}
