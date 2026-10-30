package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Assignment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {}
