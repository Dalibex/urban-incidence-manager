package es.uma.urbanpulse.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.analytics.dto.IncidentReportRow;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The order of the groups of a report and the values of {@code groupBy} (#42). */
class OperationalReportServiceTest {

    @Test
    void statusGroupsFollowTheLifecycleOrder() {
        assertThat(sorted(ReportGrouping.STATUS, "CLOSED", "REPORTED", "IN_PROGRESS"))
                .containsExactly("REPORTED", "IN_PROGRESS", "CLOSED");
    }

    @Test
    void priorityGroupsFollowTheirDeclaredOrderWithTheUnclassifiedLast() {
        assertThat(sorted(ReportGrouping.PRIORITY, "HIGH", null, "LOW", "MEDIUM"))
                .containsExactly("LOW", "MEDIUM", "HIGH", null);
    }

    @Test
    void namesGoAlphabeticallyWithAccentedAndLowercaseLettersInPlace() {
        assertThat(sorted(ReportGrouping.CATEGORY, "waste", null, "Árboles", "Arbustos", "Bins"))
                .containsExactly("Árboles", "Arbustos", "Bins", "waste", null);
    }

    @Test
    void namesThatDifferOnlyInCaseKeepAFixedOrder() {
        assertThat(sorted(ReportGrouping.DEPARTMENT, "waste", "Waste"))
                .isEqualTo(sorted(ReportGrouping.DEPARTMENT, "Waste", "waste"));
    }

    @Test
    void groupByAcceptsOnlyTheLowercaseValuesOfTheContract() {
        assertThat(ReportGrouping.ofParameter("district")).contains(ReportGrouping.DISTRICT);
        assertThat(ReportGrouping.ofParameter("DISTRICT")).isEmpty();
        assertThat(ReportGrouping.ofParameter("foo")).isEmpty();
    }

    private static List<String> sorted(ReportGrouping grouping, String... groups) {
        return Stream.of(groups)
                .map(group -> new IncidentReportRow(group, 1, 1, 0, 0, null, null))
                .sorted(OperationalReportService.order(grouping))
                .map(IncidentReportRow::group)
                .toList();
    }
}
