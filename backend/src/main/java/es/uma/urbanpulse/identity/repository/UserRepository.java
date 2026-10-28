package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.domain.User;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {}
