package es.uma.urbanpulse.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.shared.web.ApiProblemException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The filters of {@code listIncidents} that do not need the database. */
class IncidentFilterTest {

    private static final Instant NOVEMBER_1 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant NOVEMBER_8 = Instant.parse("2026-11-08T00:00:00Z");

    @Test
    void theStatusIsNotAStaffFilter() {
        var filter =
                new IncidentFilter(
                        Set.of(IncidentStatus.REPORTED), Set.of(), Set.of(), null, null, Set.of());

        assertThat(filter.usesStaffFilters()).isFalse();
        assertThat(
                        new IncidentFilter(Set.of(), Set.of(), Set.of(), null, null, Set.of())
                                .usesStaffFilters())
                .isFalse();
    }

    @Test
    void everyOtherFilterIsAStaffFilter() {
        var id = UUID.randomUUID();

        assertThat(
                        new IncidentFilter(Set.of(), Set.of(id), Set.of(), null, null, Set.of())
                                .usesStaffFilters())
                .isTrue();
        assertThat(
                        new IncidentFilter(
                                        Set.of(),
                                        Set.of(),
                                        Set.of(Priority.HIGH),
                                        null,
                                        null,
                                        Set.of())
                                .usesStaffFilters())
                .isTrue();
        assertThat(
                        new IncidentFilter(Set.of(), Set.of(), Set.of(), NOVEMBER_1, null, Set.of())
                                .usesStaffFilters())
                .isTrue();
        assertThat(
                        new IncidentFilter(Set.of(), Set.of(), Set.of(), null, NOVEMBER_8, Set.of())
                                .usesStaffFilters())
                .isTrue();
        assertThat(
                        new IncidentFilter(Set.of(), Set.of(), Set.of(), null, null, Set.of(id))
                                .usesStaffFilters())
                .isTrue();
    }

    @Test
    void aRangeWhoseStartIsEarlierThanItsEndIsAccepted() {
        var filter =
                new IncidentFilter(Set.of(), Set.of(), Set.of(), NOVEMBER_1, NOVEMBER_8, Set.of());

        assertThat(filter.reportedFrom()).isEqualTo(NOVEMBER_1);
        assertThat(filter.reportedTo()).isEqualTo(NOVEMBER_8);
    }

    @Test
    void aRangeThatStartsAtOrAfterItsEndIsABadRequest() {
        assertBadRequest(NOVEMBER_1, NOVEMBER_1);
        assertBadRequest(NOVEMBER_8, NOVEMBER_1);
    }

    private static void assertBadRequest(Instant from, Instant to) {
        assertThatThrownBy(
                        () -> new IncidentFilter(Set.of(), Set.of(), Set.of(), from, to, Set.of()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
