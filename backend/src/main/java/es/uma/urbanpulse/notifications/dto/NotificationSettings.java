package es.uma.urbanpulse.notifications.dto;

import es.uma.urbanpulse.identity.entity.User;

/** {@code NotificationSettings} of the contract: the channels of the citizen's notifications. */
public record NotificationSettings(boolean push, boolean email) {

    public static NotificationSettings of(User user) {
        return new NotificationSettings(user.isNotifyByPush(), user.isNotifyByEmail());
    }
}
