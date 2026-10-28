package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Category;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {}
