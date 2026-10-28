package es.uma.urbanpulse.geospatial.repository;

import es.uma.urbanpulse.geospatial.domain.UrbanAsset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UrbanAssetRepository extends JpaRepository<UrbanAsset, UUID> {}
