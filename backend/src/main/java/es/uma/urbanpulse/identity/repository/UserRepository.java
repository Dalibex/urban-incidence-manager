package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Emails are stored in lower case, so the caller passes the normalised email. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
