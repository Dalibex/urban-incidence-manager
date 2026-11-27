package es.uma.urbanpulse.notifications.repository;

import es.uma.urbanpulse.notifications.entity.DeliveryStatus;
import es.uma.urbanpulse.notifications.entity.Notification;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Stores a pending notification, due at {@code now}, unless the change already has one for that
     * recipient and channel: processing the same change twice leaves one row and never throws, so
     * it can never roll back the change it runs with.
     *
     * @return 1 if it stored the row, 0 if it already existed
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO notification (incident_id, recipient_id, status_change_id, message,
                                              channel, delivery_status, attempts, next_attempt_at,
                                              created_at)
                    VALUES (:incidentId, :recipientId, :statusChangeId, :message, :channel,
                            'PENDING', 0, :now, :now)
                    ON CONFLICT (status_change_id, recipient_id, channel) DO NOTHING
                    """,
            nativeQuery = true)
    int createPendingUnlessExists(
            @Param("incidentId") UUID incidentId,
            @Param("recipientId") UUID recipientId,
            @Param("statusChangeId") UUID statusChangeId,
            @Param("message") String message,
            @Param("channel") String channel,
            @Param("now") OffsetDateTime now);

    /** The {@code PENDING} notifications due at {@code now}, the longest waiting first. */
    @Query(
            """
            SELECT new es.uma.urbanpulse.notifications.repository.PendingNotification(
                n.id, n.incident.id, n.recipient.id, n.recipient.email, n.channel, n.attempts,
                n.message)
            FROM Notification n
            WHERE n.deliveryStatus = es.uma.urbanpulse.notifications.entity.DeliveryStatus.PENDING
              AND n.nextAttemptAt <= :now
            ORDER BY n.nextAttemptAt, n.id
            """)
    List<PendingNotification> findDue(@Param("now") OffsetDateTime now, Limit limit);

    /**
     * Ends a {@code PENDING} notification as {@code DELIVERED} or {@code FAILED}.
     *
     * @return 1 if it changed the row, 0 if another run ended it first
     */
    @Modifying
    @Query(
            """
            UPDATE Notification n
            SET n.deliveryStatus = :status, n.attempts = :attempts, n.sentAt = :sentAt
            WHERE n.id = :id
              AND n.deliveryStatus = es.uma.urbanpulse.notifications.entity.DeliveryStatus.PENDING
            """)
    int endIfPending(
            @Param("id") UUID id,
            @Param("status") DeliveryStatus status,
            @Param("attempts") short attempts,
            @Param("sentAt") OffsetDateTime sentAt);

    /**
     * Leaves a {@code PENDING} notification for a later attempt.
     *
     * @return 1 if it changed the row, 0 if another run ended it first
     */
    @Modifying
    @Query(
            """
            UPDATE Notification n
            SET n.attempts = :attempts, n.nextAttemptAt = :nextAttemptAt
            WHERE n.id = :id
              AND n.deliveryStatus = es.uma.urbanpulse.notifications.entity.DeliveryStatus.PENDING
            """)
    int retryLaterIfPending(
            @Param("id") UUID id,
            @Param("attempts") short attempts,
            @Param("nextAttemptAt") OffsetDateTime nextAttemptAt);
}
