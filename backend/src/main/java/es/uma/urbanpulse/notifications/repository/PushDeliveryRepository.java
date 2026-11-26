package es.uma.urbanpulse.notifications.repository;

import es.uma.urbanpulse.notifications.entity.PushDelivery;
import es.uma.urbanpulse.notifications.entity.PushDeliveryStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The deliveries of the push notifications. Every write of a receipt changes the row only if it is
 * still {@code ACCEPTED}: with two runs, one write wins and the other changes nothing.
 */
public interface PushDeliveryRepository extends JpaRepository<PushDelivery, Long> {

    /**
     * The accepted tickets sent at {@code sentBefore} or earlier, not checked yet, oldest first.
     */
    @Query(
            """
            SELECT d FROM PushDelivery d
            WHERE d.status = es.uma.urbanpulse.notifications.entity.PushDeliveryStatus.ACCEPTED
              AND d.receiptCheckedAt IS NULL
              AND d.sentAt <= :sentBefore
            ORDER BY d.sentAt, d.id
            """)
    List<PushDelivery> findAwaitingReceipt(
            @Param("sentBefore") OffsetDateTime sentBefore, Limit limit);

    /**
     * Marks as checked, still {@code ACCEPTED}, the tickets sent before {@code cutoff}: Expo no
     * longer has their receipts.
     *
     * @return how many changed
     */
    @Modifying
    @Query(
            """
            UPDATE PushDelivery d SET d.receiptCheckedAt = :checkedAt
            WHERE d.status = es.uma.urbanpulse.notifications.entity.PushDeliveryStatus.ACCEPTED
              AND d.receiptCheckedAt IS NULL
              AND d.sentAt < :cutoff
            """)
    int giveUpSentBefore(
            @Param("cutoff") OffsetDateTime cutoff, @Param("checkedAt") OffsetDateTime checkedAt);

    /**
     * @return 1 if it stored the receipt, 0 if another run stored it first
     */
    @Modifying
    @Query(
            """
            UPDATE PushDelivery d
            SET d.status = :status, d.error = :error, d.receiptCheckedAt = :checkedAt
            WHERE d.id = :id
              AND d.status = es.uma.urbanpulse.notifications.entity.PushDeliveryStatus.ACCEPTED
            """)
    int endIfAccepted(
            @Param("id") Long id,
            @Param("status") PushDeliveryStatus status,
            @Param("error") String error,
            @Param("checkedAt") OffsetDateTime checkedAt);
}
