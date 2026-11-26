package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import java.util.List;

/** A change of the notification channels that changes none of them ({@code 422}). */
public class InvalidNotificationSettingsException extends InvalidDataException {

    InvalidNotificationSettingsException(List<FieldError> fieldErrors) {
        super(fieldErrors);
    }
}
