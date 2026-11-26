package es.uma.urbanpulse.notifications.controller;

import es.uma.urbanpulse.notifications.dto.NotificationSettings;
import es.uma.urbanpulse.notifications.dto.UpdateNotificationSettingsRequest;
import es.uma.urbanpulse.notifications.service.NotificationSettingsService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getNotificationSettings} and {@code updateNotificationSettings} (#373). Only citizens may
 * call them: the security filters answer {@code 403} to every other role ({@code
 * ApiAuthorizationRules}).
 */
@RestController
@RequestMapping("/api/v1/users/me/notification-settings")
class NotificationSettingsController {

    private final NotificationSettingsService settings;

    NotificationSettingsController(NotificationSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    NotificationSettings get(@AuthenticationPrincipal AuthenticatedUser citizen) {
        return settings.settings(citizen.id());
    }

    @PatchMapping
    NotificationSettings update(
            @AuthenticationPrincipal AuthenticatedUser citizen,
            @RequestBody UpdateNotificationSettingsRequest request) {
        return settings.update(citizen.id(), request);
    }
}
