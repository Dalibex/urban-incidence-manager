package es.uma.urbanpulse.analytics.dto;

import java.util.List;

/**
 * An operational report ({@code IncidentReportResponse} in {@code docs/api/openapi.yml}). It is not
 * paginated: the groups are few by nature.
 *
 * @param rows only the groups with incidents, in the order of {@code OperationalReportService}
 */
public record IncidentReportResponse(List<IncidentReportRow> rows) {

    public IncidentReportResponse {
        rows = List.copyOf(rows);
    }
}
