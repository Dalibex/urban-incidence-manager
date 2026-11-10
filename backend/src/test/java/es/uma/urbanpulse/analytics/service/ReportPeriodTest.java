package es.uma.urbanpulse.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.shared.error.FieldError;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The bounds of a report period (#42): earlier start, at most 366 days. */
class ReportPeriodTest {

    private static final Instant START = Instant.parse("2028-01-01T00:00:00Z");

    @Test
    void acceptsAPeriodOfExactlyALeapYear() {
        var period = new ReportPeriod(START, Instant.parse("2029-01-01T00:00:00Z"));

        assertThat(period.to()).isEqualTo(Instant.parse("2029-01-01T00:00:00Z"));
    }

    @Test
    void refusesOneSecondMoreThan366Days() {
        assertThatThrownBy(() -> new ReportPeriod(START, Instant.parse("2029-01-01T00:00:01Z")))
                .isInstanceOf(InvalidReportPeriodException.class)
                .satisfies(
                        error ->
                                assertThat(((InvalidReportPeriodException) error).fieldErrors())
                                        .containsExactly(
                                                new FieldError(
                                                        "to",
                                                        "the period may not be longer than 366"
                                                                + " days")));
    }

    @Test
    void refusesAnEndBeforeTheStart() {
        assertThatThrownBy(() -> new ReportPeriod(START, START.minusSeconds(1)))
                .isInstanceOf(InvalidReportPeriodException.class)
                .satisfies(
                        error ->
                                assertThat(((InvalidReportPeriodException) error).fieldErrors())
                                        .containsExactly(
                                                new FieldError("to", "must be later than from")));
    }

    @Test
    void refusesAnEmptyPeriod() {
        assertThatThrownBy(() -> new ReportPeriod(START, START))
                .isInstanceOf(InvalidReportPeriodException.class);
    }
}
