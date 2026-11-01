package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Incident;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncidentRepository
        extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    /** A page of incidents with their category in the same query, so listing has no N+1. */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<Incident> findAll(Specification<Incident> spec, Pageable pageable);
}
