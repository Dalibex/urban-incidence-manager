package es.uma.urbanpulse.analytics.service;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import java.util.List;

/**
 * The period of a report is readable but not valid ({@code 422 VALIDATION_FAILED}). Both errors
 * name the field {@code to}, so a form shows them under the end of the period.
 */
public class InvalidReportPeriodException extends InvalidDataException {

    static final String FIELD = "to";

    private InvalidReportPeriodException(String message) {
        super(List.of(new FieldError(FIELD, message)));
    }

    static InvalidReportPeriodException endNotAfterStart() {
        return new InvalidReportPeriodException("must be later than from");
    }

    static InvalidReportPeriodException tooLong(long maxDays) {
        return new InvalidReportPeriodException(
                "the period may not be longer than " + maxDays + " days");
    }
}
