package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.notifications.dto.NotificationSettings;
import es.uma.urbanpulse.notifications.dto.UpdateNotificationSettingsRequest;
import es.uma.urbanpulse.shared.error.FieldError;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The channels of the citizen's notifications (#373): {@code getNotificationSettings} and {@code
 * updateNotificationSettings}. Only citizens may call them; the security filters refuse the other
 * roles ({@code ApiAuthorizationRules}). A change applies to the notifications created after it.
 */
@Service
@Transactional
public class NotificationSettingsService {

    private static final String AT_LEAST_ONE = "Send push, email or both.";

    private final UserRepository users;

    public NotificationSettingsService(UserRepository users) {
        this.users = users;
    }

    /**
     * @throws InsufficientAuthenticationException if the user of the token does not exist
     */
    @Transactional(readOnly = true)
    public NotificationSettings settings(UUID userId) {
        return NotificationSettings.of(user(userId));
    }

    /**
     * Changes only the channels present in {@code request}.
     *
     * @throws InvalidNotificationSettingsException if it has none of them; nothing changes then
     * @throws InsufficientAuthenticationException if the user of the token does not exist
     */
    public NotificationSettings update(UUID userId, UpdateNotificationSettingsRequest request) {
        if (request.push() == null && request.email() == null) {
            throw new InvalidNotificationSettingsException(
                    List.of(
                            new FieldError("push", AT_LEAST_ONE),
                            new FieldError("email", AT_LEAST_ONE)));
        }
        // Locked, so two concurrent updates of one user's channels do not lose a change.
        var user =
                users.findLockedById(userId)
                        .orElseThrow(
                                () ->
                                        new InsufficientAuthenticationException(
                                                "The user does not exist"));
        if (request.push() != null) {
            user.setNotifyByPush(request.push());
        }
        if (request.email() != null) {
            user.setNotifyByEmail(request.email());
        }
        return NotificationSettings.of(users.saveAndFlush(user));
    }

    private User user(UUID userId) {
        // A valid token of a user that no longer exists: answered as 401, like getCurrentUser.
        return users.findById(userId)
                .orElseThrow(
                        () -> new InsufficientAuthenticationException("The user does not exist"));
    }
}
