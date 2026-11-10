package es.uma.urbanpulse.analytics.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The period of a report: the incidents reported from {@code from} (included) to {@code to}
 * (excluded). It is bounded, so a report never scans more than a year of incidents of the
 * operational database (#42).
 *
 * @param from start of the period, included
 * @param to end of the period, excluded
 */
public record ReportPeriod(Instant from, Instant to) {

    /** The longest period: a leap year. */
    public static final Duration MAX_LENGTH = Duration.ofDays(366);

    /**
     * @throws InvalidReportPeriodException if {@code from} is not earlier than {@code to}, or the
     *     period is longer than {@link #MAX_LENGTH}
     */
    public ReportPeriod {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw InvalidReportPeriodException.endNotAfterStart();
        }
        if (Duration.between(from, to).compareTo(MAX_LENGTH) > 0) {
            throw InvalidReportPeriodException.tooLong(MAX_LENGTH.toDays());
        }
    }
}
