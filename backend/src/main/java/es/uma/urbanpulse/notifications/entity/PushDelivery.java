package es.uma.urbanpulse.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One message of a push notification to one device, with the ticket Expo answered (ADR-0031). The
 * token is kept by value, because its {@link PushToken} may be deleted afterwards.
 */
@Entity
@Table(name = "push_delivery")
@Getter
@Setter
@NoArgsConstructor
public class PushDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(nullable = false)
    private String token;

    /** Expo's ticket id; null when Expo answered an error for this message. */
    @Column(name = "ticket_id")
    private String ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PushDeliveryStatus status;

    /** Expo's error code, such as {@code DeviceNotRegistered}, when the status is FAILED. */
    private String error;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Column(name = "receipt_checked_at")
    private OffsetDateTime receiptCheckedAt;
}
