package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checks that the incidents a dataset writes straight into the database have the history the
 * application would have written: only transitions of the ADR-0020 table, made by who may make
 * them, consistent with the incident and its assignments. Shared by the development (#83) and demo
 * (#54) datasets, which insert their rows with SQL instead of going through {@code
 * Incident.changeStatus}.
 */
final class DatasetHistories {

    private final JdbcTemplate jdbc;

    DatasetHistories(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Entry(
            Instant instant,
            IncidentStatus previous,
            IncidentStatus next,
            UUID actor,
            Role actorRole,
            String reason) {}

    void assertEveryHistoryFollowsTheTransitionTable() {
        var incidents =
                jdbc.queryForList(
                        "SELECT id, reporter_id, status, category_id, priority, reported_at,"
                                + " updated_at, closed_at FROM incident");
        assertThat(incidents).isNotEmpty();

        for (var incident : incidents) {
            var id = (UUID) incident.get("id");
            var history = history(id);
            var first = history.getFirst();
            assertThat(first.previous()).as("%s first entry", id).isNull();
            assertThat(first.next()).isEqualTo(IncidentStatus.REPORTED);
            assertThat(first.actor()).isEqualTo(incident.get("reporter_id"));
            assertThat(first.actorRole()).isEqualTo(Role.CITIZEN);
            assertThat(first.instant()).isEqualTo(instant(incident.get("reported_at")));

            for (int i = 1; i < history.size(); i++) {
                var entry = history.get(i);
                assertThat(entry.previous())
                        .as("%s entry %d", id, i)
                        .isEqualTo(history.get(i - 1).next());
                var transition = IncidentTransition.between(entry.previous(), entry.next());
                assertThat(transition).as("%s entry %d", id, i).isPresent();
                assertMayPerform(id, entry, transition.get());
                if (transition.get().requiresReason()) {
                    assertThat(entry.reason()).as("%s entry %d reason", id, i).isNotBlank();
                }
                if (transition.get() == IncidentTransition.VALIDATION) {
                    assertThat(incident.get("category_id")).as("%s category", id).isNotNull();
                    assertThat(incident.get("priority")).as("%s priority", id).isNotNull();
                }
            }

            var last = history.getLast();
            assertThat(incident.get("status")).as("%s status", id).isEqualTo(last.next().name());
            assertThat(instant(incident.get("updated_at"))).isAfterOrEqualTo(last.instant());
            if (last.next() == IncidentStatus.CLOSED) {
                assertThat(instant(incident.get("closed_at"))).isEqualTo(last.instant());
            } else {
                assertThat(incident.get("closed_at")).as("%s closed_at", id).isNull();
            }
            assertAssignmentsFollowTheHistory(id, history);
        }
    }

    /** The table says who may make the transition (ADR-0020, "Who"). */
    private void assertMayPerform(UUID incident, Entry entry, IncidentTransition transition) {
        switch (transition.performer()) {
            case OPERATOR ->
                    assertThat(entry.actorRole())
                            .as("%s %s", incident, transition)
                            .isIn(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR);
            case ASSIGNED_TECHNICIAN -> {
                var assignee =
                        jdbc.queryForList(
                                "SELECT technician_id FROM assignment WHERE incident_id = ?"
                                        + " AND assigned_at <= ? ORDER BY assigned_at DESC LIMIT 1",
                                UUID.class,
                                incident,
                                Timestamp.from(entry.instant()));
                assertThat(assignee)
                        .as("%s %s", incident, transition)
                        .containsExactly(entry.actor());
            }
        }
    }

    /** One assignment per ASSIGNMENT entry, and only the last one is active. */
    private void assertAssignmentsFollowTheHistory(UUID incident, List<Entry> history) {
        var assignedAt =
                history.stream()
                        .filter(entry -> entry.next() == IncidentStatus.ASSIGNED)
                        .map(Entry::instant)
                        .toList();
        var assignments =
                jdbc.queryForList(
                        "SELECT assigned_at, is_active FROM assignment WHERE incident_id = ?"
                                + " ORDER BY assigned_at",
                        incident);

        assertThat(assignments.stream().map(row -> instant(row.get("assigned_at"))).toList())
                .as("%s assignments", incident)
                .isEqualTo(assignedAt);
        assertThat(assignments.stream().map(row -> (Boolean) row.get("is_active")).toList())
                .as("%s active assignment", incident)
                .isEqualTo(assignedAt.stream().map(at -> at.equals(assignedAt.getLast())).toList());
    }

    private List<Entry> history(UUID incident) {
        return jdbc.query(
                "SELECT s.instant, s.previous_status, s.new_status, s.actor_id, u.role, s.reason"
                        + " FROM status_change s JOIN users u ON u.id = s.actor_id"
                        + " WHERE s.incident_id = ? ORDER BY s.instant, s.id",
                DatasetHistories::entry,
                incident);
    }

    private static Entry entry(ResultSet row, int rowNumber) throws SQLException {
        var previous = row.getString("previous_status");
        return new Entry(
                row.getTimestamp("instant").toInstant(),
                previous == null ? null : IncidentStatus.valueOf(previous),
                IncidentStatus.valueOf(row.getString("new_status")),
                row.getObject("actor_id", UUID.class),
                Role.valueOf(row.getString("role")),
                row.getString("reason"));
    }

    private static Instant instant(Object timestamp) {
        return switch (timestamp) {
            case Timestamp sql -> sql.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalArgumentException("Not a timestamp: " + timestamp);
        };
    }
}
