package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.MfaChallenge;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, UUID> {

    @Query("SELECT c.user.id FROM MfaChallenge c WHERE c.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Locks the row until the transaction ends ({@code SELECT ... FOR UPDATE}), so of two
     * verifications of one challenge the second sees it spent. Lock its user first ({@link
     * UserRepository#findLockedById}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MfaChallenge> findLockedByTokenHash(String tokenHash);

    /**
     * Spends every open challenge of the user, after a confirmed enrolment or a reset. Like any
     * bulk update it bypasses the persistence context: a challenge already loaded keeps its old
     * {@code spentAt}.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE MfaChallenge c SET c.spentAt = :now"
                    + " WHERE c.user.id = :userId AND c.spentAt IS NULL AND c.expiresAt > :now")
    int spendOpen(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM MfaChallenge c WHERE c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
