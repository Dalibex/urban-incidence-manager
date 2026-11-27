package es.uma.urbanpulse.notifications.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The configuration of the delivery of the notifications (ADR-0031). */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationsConfig {}
