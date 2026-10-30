package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.RefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes the token only if it is still active and says whether it did. The condition and the
     * change are one statement, so of two requests using the same token at once only one gets
     * {@code 1}: PostgreSQL locks the row and re-checks the condition for the second one.
     *
     * <p>Like any bulk update it bypasses the persistence context: a {@code RefreshToken} already
     * loaded in the same transaction keeps its old {@code revokedAt}. Nothing reads it afterwards,
     * and not clearing the context keeps the caller's other entities managed.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE RefreshToken t SET t.revokedAt = :now"
                    + " WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE RefreshToken t SET t.revokedAt = :now"
                    + " WHERE t.user.id = :userId AND t.revokedAt IS NULL")
    int revokeAllActive(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);
}
