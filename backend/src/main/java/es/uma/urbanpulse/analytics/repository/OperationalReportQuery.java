package es.uma.urbanpulse.analytics.repository;

import es.uma.urbanpulse.analytics.dto.IncidentReportRow;
import es.uma.urbanpulse.analytics.service.ReportGrouping;
import es.uma.urbanpulse.analytics.service.ReportPeriod;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The aggregate query of an operational report, in native SQL: JPQL has no {@code percentile_cont},
 * and computing the percentiles in Java would load every incident of the period. It reads the
 * tables of the incidents module and writes nothing (ADR-0010).
 *
 * <p>Each grouping is a fixed SQL fragment chosen by a {@code switch}, so no text of the request
 * ever reaches the SQL.
 */
@Repository
public class OperationalReportQuery {

    // percentile_cont is the continuous percentile (Excel's PERCENTILE.INC). FILTER keeps only the
    // closed incidents, and with none it gives NULL, which stays null: never 0.
    // The {placeholders} are replaced with the fixed fragments below, never with request text.
    private static final String SELECT =
            """
            SELECT {group} AS report_group,
                   count(*) AS total,
                   count(*) FILTER (WHERE i.status NOT IN ('CLOSED', 'REJECTED')) AS open_count,
                   count(*) FILTER (WHERE i.status = 'CLOSED') AS closed_count,
                   count(*) FILTER (WHERE i.status = 'REJECTED') AS rejected_count,
                   round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY {hours})
                       FILTER (WHERE i.status = 'CLOSED') AS numeric), 2) AS p50,
                   round(CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY {hours})
                       FILTER (WHERE i.status = 'CLOSED') AS numeric), 2) AS p95
            FROM incident i
            {joins}
            WHERE i.reported_at >= :from AND i.reported_at < :to
            GROUP BY report_group
            """;

    private static final String RESOLUTION_HOURS =
            "CAST(extract(epoch FROM i.closed_at - i.reported_at) / 3600 AS double precision)";

    private final JdbcClient jdbc;

    public OperationalReportQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per group with incidents reported in the period, in no particular order. */
    public List<IncidentReportRow> groups(ReportGrouping grouping, ReportPeriod period) {
        var sql =
                SELECT.replace("{group}", groupExpression(grouping))
                        .replace("{hours}", RESOLUTION_HOURS)
                        .replace("{joins}", joins(grouping));
        return jdbc.sql(sql)
                .param("from", OffsetDateTime.ofInstant(period.from(), ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(period.to(), ZoneOffset.UTC))
                .query(
                        (rs, rowNumber) ->
                                new IncidentReportRow(
                                        rs.getString("report_group"),
                                        rs.getLong("total"),
                                        rs.getLong("open_count"),
                                        rs.getLong("closed_count"),
                                        rs.getLong("rejected_count"),
                                        hours(rs.getBigDecimal("p50")),
                                        hours(rs.getBigDecimal("p95"))))
                .list();
    }

    private static String groupExpression(ReportGrouping grouping) {
        return switch (grouping) {
            case STATUS -> "i.status";
            case PRIORITY -> "i.priority";
            case CATEGORY -> "c.name";
            case DEPARTMENT -> "d.name";
            // A blank district is as unknown as a missing one (AGENTS.md, section 3).
            case DISTRICT -> "NULLIF(btrim(l.district), '')";
        };
    }

    private static String joins(ReportGrouping grouping) {
        return switch (grouping) {
            case STATUS, PRIORITY -> "";
            case CATEGORY -> "LEFT JOIN category c ON c.id = i.category_id";
            // The domain keeps at most one active assignment per incident (Incident.changeStatus).
            case DEPARTMENT ->
                    """
                    LEFT JOIN assignment a ON a.incident_id = i.id AND a.is_active
                    LEFT JOIN department d ON d.id = a.department_id
                    """;
            case DISTRICT -> "LEFT JOIN location l ON l.incident_id = i.id";
        };
    }

    private static Double hours(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
