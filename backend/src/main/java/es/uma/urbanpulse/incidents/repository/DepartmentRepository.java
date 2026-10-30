package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Department;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {}
