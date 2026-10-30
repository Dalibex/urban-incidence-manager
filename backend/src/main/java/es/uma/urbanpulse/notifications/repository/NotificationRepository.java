package es.uma.urbanpulse.notifications.repository;

import es.uma.urbanpulse.notifications.entity.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {}
