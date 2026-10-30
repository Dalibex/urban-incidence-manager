package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.RefreshToken;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {}
