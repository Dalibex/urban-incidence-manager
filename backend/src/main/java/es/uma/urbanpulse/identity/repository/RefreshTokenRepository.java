package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query("SELECT t.user.id FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Locks the row until the transaction ends ({@code SELECT ... FOR UPDATE}), so a sign-out of
     * the same session waits for the refresh or the refresh sees the token revoked. Lock its user
     * first ({@link UserRepository#findLockedById}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findLockedByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Revokes the token only if it is still active, for sign-out: signing out twice keeps the
     * instant of the first revocation, and the second one changes nothing.
     *
     * <p>Like any bulk update it bypasses the persistence context: a {@code RefreshToken} already
     * loaded in the same transaction keeps its old {@code revokedAt}.
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
