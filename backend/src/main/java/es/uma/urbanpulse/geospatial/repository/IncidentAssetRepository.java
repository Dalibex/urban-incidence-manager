package es.uma.urbanpulse.geospatial.repository;

import es.uma.urbanpulse.geospatial.entity.IncidentAsset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentAssetRepository extends JpaRepository<IncidentAsset, UUID> {}
