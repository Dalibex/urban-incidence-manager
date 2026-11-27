package es.uma.urbanpulse.notifications.repository;

import es.uma.urbanpulse.notifications.entity.PushToken;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    boolean existsByUserId(UUID userId);

    /** The tokens of these users, in the order they were first registered. */
    @Query(
            """
            SELECT new es.uma.urbanpulse.notifications.repository.RecipientToken(t.user.id, t.token)
            FROM PushToken t
            WHERE t.user.id IN :userIds
            ORDER BY t.id
            """)
    List<RecipientToken> findTokensOf(@Param("userIds") Collection<UUID> userIds);

    /**
     * Stores the token for the user, or, if it is already stored, moves it to the user and renews
     * {@code last_registered_at}; {@code created_at} keeps the first registration.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO push_token (user_id, token, created_at, last_registered_at)
                    VALUES (:userId, :token, :now, :now)
                    ON CONFLICT (token) DO UPDATE
                    SET user_id = EXCLUDED.user_id, last_registered_at = EXCLUDED.last_registered_at
                    """,
            nativeQuery = true)
    void register(
            @Param("userId") UUID userId,
            @Param("token") String token,
            @Param("now") OffsetDateTime now);

    /**
     * @return how many it deleted
     */
    @Modifying
    @Query("DELETE FROM PushToken t WHERE t.user.id = :userId")
    int deleteAllOf(@Param("userId") UUID userId);

    /**
     * @return how many it deleted, 0 or 1
     */
    @Modifying
    @Query("DELETE FROM PushToken t WHERE t.token = :token")
    int deleteByToken(@Param("token") String token);

    /**
     * Deletes the token unless its device registered it again at {@code before} or later.
     *
     * @return how many it deleted, 0 or 1
     */
    @Modifying
    @Query("DELETE FROM PushToken t WHERE t.token = :token AND t.lastRegisteredAt < :before")
    int deleteIfRegisteredBefore(
            @Param("token") String token, @Param("before") OffsetDateTime before);

    /**
     * @return how many it deleted
     */
    @Modifying
    @Query("DELETE FROM PushToken t WHERE t.lastRegisteredAt < :cutoff")
    int deleteRegisteredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
