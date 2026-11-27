package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.StatusChange;
import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import es.uma.urbanpulse.notifications.repository.NotificationRepository;
import es.uma.urbanpulse.notifications.repository.PushTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the notifications of a status change for the citizen who reported the incident (#373,
 * ADR-0031): one pending row per channel the citizen has on, push only while the citizen has a
 * registered device. It runs in the transaction of the change, so the change and its notifications
 * are saved or rolled back together; the delivery happens later, outside it.
 *
 * <p>The text names the incident and its new status, and the reason only when the citizen already
 * sees it in the history (ADR-0020, "Citizen sees"): never internal notes, staff or departments.
 */
@Service
@Transactional
public class CitizenNotifications {

    private final NotificationRepository notifications;
    private final PushTokenRepository pushTokens;
    private final Clock clock;

    public CitizenNotifications(
            NotificationRepository notifications, PushTokenRepository pushTokens, Clock clock) {
        this.notifications = notifications;
        this.pushTokens = pushTokens;
        this.clock = clock;
    }

    /**
     * Creates the notifications of {@code change}, once: calling it again for the same change adds
     * nothing. A change the citizen does not see, a reassignment ({@code ASSIGNED} to {@code
     * ASSIGNED}) and a disabled account get none.
     */
    public void statusChanged(StatusChange change) {
        if (!change.isVisibleToCitizen() || isReassignment(change)) {
            return;
        }
        var incident = change.getIncident();
        User citizen = incident.getReporter();
        if (!Boolean.TRUE.equals(citizen.getEnabled())) {
            return;
        }
        String message = message(change);
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (NotificationChannel channel : channelsOf(citizen)) {
            notifications.createPendingUnlessExists(
                    incident.getId(),
                    citizen.getId(),
                    change.getId(),
                    message,
                    channel.name(),
                    now);
        }
    }

    private List<NotificationChannel> channelsOf(User citizen) {
        var channels = new ArrayList<NotificationChannel>(2);
        if (citizen.isNotifyByEmail()) {
            channels.add(NotificationChannel.EMAIL);
        }
        if (citizen.isNotifyByPush() && pushTokens.existsByUserId(citizen.getId())) {
            channels.add(NotificationChannel.PUSH);
        }
        return channels;
    }

    private static boolean isReassignment(StatusChange change) {
        return change.getPreviousStatus() == IncidentStatus.ASSIGNED
                && change.getNewStatus() == IncidentStatus.ASSIGNED;
    }

    private static String message(StatusChange change) {
        String title = change.getIncident().getTitle();
        String reason = change.getReason();
        boolean showsReason = change.citizenSeesReason() && reason != null && !reason.isBlank();
        if (showsReason && change.getNewStatus() == change.getPreviousStatus()) {
            // The status did not change: the staff asked the citizen for more information.
            return "Your incident \"%s\" needs more information: %s".formatted(title, reason);
        }
        String text =
                "Your incident \"%s\" is now %s.".formatted(title, label(change.getNewStatus()));
        if (showsReason) {
            text += " Message: " + reason;
        }
        return text;
    }

    /** "in progress" for {@code IN_PROGRESS}, and so on. */
    private static String label(IncidentStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
