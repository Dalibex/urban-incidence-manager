package es.uma.urbanpulse.analytics.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * How an operational report groups the incidents ({@code IncidentReportGrouping} in {@code
 * docs/api/openapi.yml}). {@code department} uses the active assignment, the one that holds the
 * incident now.
 */
public enum ReportGrouping {
    STATUS("status"),
    CATEGORY("category"),
    PRIORITY("priority"),
    DEPARTMENT("department"),
    DISTRICT("district");

    private final String parameter;

    ReportGrouping(String parameter) {
        this.parameter = parameter;
    }

    /** The value of {@code groupBy} in the API, in lowercase as the contract writes it. */
    public String parameter() {
        return parameter;
    }

    /** The grouping of a {@code groupBy} value, or empty when the contract does not define it. */
    public static Optional<ReportGrouping> ofParameter(String value) {
        return Arrays.stream(values())
                .filter(grouping -> grouping.parameter.equals(value))
                .findFirst();
    }
}
