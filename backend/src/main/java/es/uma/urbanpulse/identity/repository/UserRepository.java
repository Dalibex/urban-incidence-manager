package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Locks the user row until the transaction ends ({@code SELECT ... FOR UPDATE}). Lock the user
     * before any of its refresh tokens, so two transactions never wait for each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findLockedById(UUID id);

    /** Emails are stored in lower case, so the caller passes the normalised email. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRoleAndEnabledTrue(Role role);

    /** The id breaks ties between two equal names, so the order is the same on every call. */
    List<User> findByRoleAndEnabledTrueOrderByNameAscIdAsc(Role role);
}
