package es.uma.urbanpulse.analytics.dto;

/**
 * One group of an operational report ({@code IncidentReportRow} in {@code docs/api/openapi.yml}).
 * {@code total} is the sum of {@code open}, {@code closed} and {@code rejected}.
 *
 * @param group the status, priority, or name of the category, department or district; {@code null}
 *     when it is not available, never an empty text
 * @param open incidents in a status that is not final, including {@code RESOLVED}
 * @param resolutionHoursP50 median hours from reported to closed, with two decimals; {@code null}
 *     when none of the group is closed, never {@code 0}
 * @param resolutionHoursP95 95th percentile of the same hours; {@code null} in the same case
 */
public record IncidentReportRow(
        String group,
        long total,
        long open,
        long closed,
        long rejected,
        Double resolutionHoursP50,
        Double resolutionHoursP95) {}
