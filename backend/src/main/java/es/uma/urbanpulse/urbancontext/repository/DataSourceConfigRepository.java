package es.uma.urbanpulse.urbancontext.repository;

import es.uma.urbanpulse.urbancontext.domain.DataSourceConfig;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, UUID> {}
