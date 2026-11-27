package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * #373 (T1, T2 and T5 of #376): every status change of a citizen's incident creates, in the same
 * transaction, one notification per channel the citizen has on, ready to be sent. Push counts only
 * while the citizen has a registered device. A reassignment and a disabled account create none.
 *
 * <p>Written from the issue, the plan agreed for it, the contract ({@code docs/api/openapi.yml},
 * {@code getNotificationSettings}) and ADR-0031, not from the implementation: the data is inserted
 * with SQL, every status change goes through HTTP, and the notifications are read from the
 * database. They do not run inside a rolled-back transaction: the rows must be the ones the request
 * really committed. The rows of each test are deleted after it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@DisplayName("Citizen notifications created by a status change")
class CitizenNotificationApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-11-02T11:00:00Z");

    private static final String TITLE = "Streetlight off on Calle Larios";
    private static final String OPERATOR_NAME = "Marta Operadora Ruiz";
    private static final String TECHNICIAN_NAME = "Tomás Técnico Vega";
    private static final String DEPARTMENT_NAME = "Alumbrado Norte";
    private static final String TEAM = "Night shift 7";
    private static final String VALIDATION_NOTE = "Internal: checked with lamp map, post 118.";
    private static final String RESOLUTION_NOTES = "Internal: replaced the ballast, ticket 4411.";
    private static final String REJECTION_REASON =
            "This streetlight belongs to a private estate; please contact its owners.";
    private static final String INFORMATION_MESSAGE =
            "Which street is it on? The position on the map is in the middle of a park.";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdIncidents = new ArrayList<>();

    private UUID citizen;
    private UUID operator;
    private UUID technician;
    private UUID otherTechnician;
    private UUID category;
    private UUID department;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizen = user(Role.CITIZEN, "Carmen Ciudadana");
        operator = user(Role.MUNICIPAL_OPERATOR, OPERATOR_NAME);
        technician = user(Role.TECHNICIAN, TECHNICIAN_NAME);
        otherTechnician = user(Role.TECHNICIAN, "Otro Técnico");
        category =
                jdbc.queryForObject(
                        "INSERT INTO category (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Lighting " + UUID.randomUUID());
        department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES (?) RETURNING id",
                        UUID.class,
                        DEPARTMENT_NAME + " " + UUID.randomUUID());
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        for (UUID incident : createdIncidents) {
            TestHistories.delete(jdbc, "incident_id = ?", incident);
            jdbc.update("DELETE FROM assignment WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", category);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
        for (UUID user : createdUsers) {
            jdbc.update("DELETE FROM push_token WHERE user_id = ?", user);
            // The audit trail is append-only and references its actor: a user who acted in it
            // stays, with a random email, so it cannot clash with another test.
            jdbc.update(
                    "DELETE FROM users u WHERE u.id = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                    user);
        }
    }

    @Nested
    @DisplayName("The rows of a status change")
    class RowsOfAStatusChange {

        // T1 of #376 and F1 of the plan: the row points to the change the request just made.
        @Test
        @DisplayName("a validation leaves one pending EMAIL row for that change, never attempted")
        void aValidationLeavesOnePendingEmailRowForThatChange() throws Exception {
            // Arrange
            UUID incident = incident("REPORTED");
            Instant before = Instant.now();

            // Act
            ResultActions answer = act(operator, incident, "validation", body(0, null));

            // Assert
            answer.andExpect(status().isOk());
            List<Map<String, Object>> rows = notificationsOf(incident);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst())
                    .containsEntry("channel", "EMAIL")
                    .containsEntry("recipient_id", citizen)
                    .containsEntry("status_change_id", lastChange(incident, "VALIDATED"))
                    .containsEntry("delivery_status", "PENDING")
                    .containsEntry("attempts", 0)
                    .containsEntry("sent_at", null);
            // Due at once: the delivery job picks it up on its next run.
            assertThat(((Timestamp) rows.getFirst().get("next_attempt_at")).toInstant())
                    .isBetween(before, Instant.now());
        }

        // T1, T2 of #376 and F9 of the plan: one row per channel that is on; push only with a
        // registered device; none when both are off.
        @ParameterizedTest(name = "push {0}, email {1}, device {2} -> [{3}]")
        @CsvSource(
                delimiter = '|',
                value = {
                    "false | true  | false | EMAIL",
                    "true  | true  | true  | EMAIL PUSH",
                    "true  | true  | false | EMAIL",
                    "false | true  | true  | EMAIL",
                    "true  | false | true  | PUSH",
                    "true  | false | false | ''",
                    "false | false | true  | ''"
                })
        @DisplayName("creates one row per channel that is on, push only with a registered device")
        void createsOneRowPerChannelThatIsOn(
                boolean push, boolean email, boolean device, String expectedChannels)
                throws Exception {
            // Arrange
            channels(citizen, push, email);
            if (device) {
                registeredDevice(citizen);
            }
            UUID incident = incident("REPORTED");

            // Act
            ResultActions answer = act(operator, incident, "validation", body(0, null));

            // Assert
            answer.andExpect(status().isOk());
            String[] expected =
                    expectedChannels.isBlank() ? new String[0] : expectedChannels.split(" ");
            assertThat(notificationsOf(incident))
                    .extracting(row -> row.get("channel"))
                    .containsExactlyInAnyOrder((Object[]) expected);
        }

        @Test
        @DisplayName("both rows of one change point to that change and to the reporter")
        void bothRowsOfOneChangePointToThatChange() throws Exception {
            // Arrange
            channels(citizen, true, true);
            registeredDevice(citizen);
            UUID incident = incident("REPORTED");

            // Act
            ResultActions answer = act(operator, incident, "validation", body(0, null));

            // Assert
            answer.andExpect(status().isOk());
            UUID change = lastChange(incident, "VALIDATED");
            assertThat(notificationsOf(incident))
                    .hasSize(2)
                    .allSatisfy(
                            row ->
                                    assertThat(row)
                                            .containsEntry("status_change_id", change)
                                            .containsEntry("recipient_id", citizen)
                                            .containsEntry("delivery_status", "PENDING"));
        }

        @Test
        @DisplayName("a disabled citizen gets no row, even with every channel on")
        void aDisabledCitizenGetsNoRow() throws Exception {
            // Arrange
            channels(citizen, true, true);
            registeredDevice(citizen);
            jdbc.update("UPDATE users SET enabled = false WHERE id = ?", citizen);
            UUID incident = incident("REPORTED");

            // Act
            ResultActions answer = act(operator, incident, "validation", body(0, null));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(notificationsOf(incident)).isEmpty();
        }

        @Test
        @DisplayName("a reassignment, ASSIGNED to ASSIGNED, creates no row")
        void aReassignmentCreatesNoRow() throws Exception {
            // Arrange
            UUID incident = assignedTo(technician, "ASSIGNED");

            // Act
            ResultActions answer =
                    act(operator, incident, "assignments", assignment(0, otherTechnician, null));

            // Assert
            answer.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));
            assertThat(notificationsOf(incident)).isEmpty();
        }

        @Test
        @DisplayName("an information request, REPORTED to REPORTED, creates a row")
        void anInformationRequestCreatesARow() throws Exception {
            // Arrange
            UUID incident = incident("REPORTED");

            // Act
            ResultActions answer =
                    act(operator, incident, "information-requests", body(0, INFORMATION_MESSAGE));

            // Assert
            answer.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPORTED"));
            assertThat(notificationsOf(incident))
                    .singleElement()
                    .satisfies(
                            row ->
                                    assertThat(row)
                                            .containsEntry(
                                                    "status_change_id",
                                                    lastChange(incident, "REPORTED")));
        }
    }

    /**
     * T5 of #376: the text says which incident and its new status, and only the reason the citizen
     * already sees in the history (ADR-0020, "Citizen sees"): never internal notes, the department,
     * the team nor any staff name.
     */
    @Nested
    @DisplayName("The text of a notification")
    class TextOfANotification {

        @Test
        @DisplayName(
                "a resolution names the incident and the status, not the notes nor the technician")
        void aResolutionHasNoNotesNorTechnician() throws Exception {
            // Arrange
            UUID incident = assignedTo(technician, "IN_PROGRESS");

            // Act
            ResultActions answer =
                    act(technician, incident, "resolution", body(0, RESOLUTION_NOTES));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(messageOf(incident))
                    .contains(TITLE)
                    .containsIgnoringCase("resolved")
                    .doesNotContain(RESOLUTION_NOTES)
                    .doesNotContain("4411")
                    .doesNotContain(TECHNICIAN_NAME)
                    .doesNotContain(OPERATOR_NAME);
        }

        @Test
        @DisplayName(
                "an assignment names the incident and the status, not the department, team or staff")
        void anAssignmentHasNoDepartmentTeamNorStaff() throws Exception {
            // Arrange
            UUID incident = incident("VALIDATED");

            // Act
            ResultActions answer =
                    act(operator, incident, "assignments", assignment(0, technician, TEAM));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(messageOf(incident))
                    .contains(TITLE)
                    .containsIgnoringCase("assigned")
                    .doesNotContain(DEPARTMENT_NAME)
                    .doesNotContain(TEAM)
                    .doesNotContain(TECHNICIAN_NAME)
                    .doesNotContain(OPERATOR_NAME);
        }

        @Test
        @DisplayName("a validation with an internal note does not carry the note nor the operator")
        void aValidationHasNoNoteNorOperator() throws Exception {
            // Arrange
            UUID incident = incident("REPORTED");

            // Act
            ResultActions answer = act(operator, incident, "validation", body(0, VALIDATION_NOTE));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(messageOf(incident))
                    .contains(TITLE)
                    .containsIgnoringCase("validated")
                    .doesNotContain(VALIDATION_NOTE)
                    .doesNotContain("post 118")
                    .doesNotContain(OPERATOR_NAME);
        }

        @Test
        @DisplayName("a rejection carries its reason, which the citizen sees, but not the operator")
        void aRejectionCarriesItsReason() throws Exception {
            // Arrange
            UUID incident = incident("REPORTED");

            // Act
            ResultActions answer = act(operator, incident, "rejection", body(0, REJECTION_REASON));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(messageOf(incident))
                    .contains(TITLE)
                    .containsIgnoringCase("rejected")
                    .contains(REJECTION_REASON)
                    .doesNotContain(OPERATOR_NAME);
        }

        @Test
        @DisplayName("an information request carries its message, but not the operator")
        void anInformationRequestCarriesItsMessage() throws Exception {
            // Arrange
            UUID incident = incident("REPORTED");

            // Act
            ResultActions answer =
                    act(operator, incident, "information-requests", body(0, INFORMATION_MESSAGE));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(messageOf(incident))
                    .contains(TITLE)
                    .contains(INFORMATION_MESSAGE)
                    .doesNotContain(OPERATOR_NAME);
        }
    }

    /** A classified incident of the citizen in {@code status}, with the report in its history. */
    private UUID incident(String status) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, reported_at, updated_at)"
                                + " VALUES (?, ?, ?, 'The lamp is off.', ?, 'HIGH',"
                                + " 'Dark crossing near a school', 'MANUAL', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        category,
                        TITLE,
                        status,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(REPORTED_AT));
        createdIncidents.add(incident);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        history(incident, citizen, REPORTED_AT, null, "REPORTED");
        return incident;
    }

    /** An incident in {@code status}, actively assigned to {@code assignee}. */
    private UUID assignedTo(UUID assignee, String status) {
        UUID incident = incident(status);
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at,"
                        + " is_active) VALUES (?, ?, ?, ?, true)",
                incident,
                department,
                assignee,
                Timestamp.from(ASSIGNED_AT));
        history(incident, operator, ASSIGNED_AT, "VALIDATED", status);
        return incident;
    }

    private void history(UUID incident, UUID actor, Instant instant, String previous, String next) {
        jdbc.update(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                        + " new_status, visible_to_citizen) VALUES (?, ?, ?, ?, ?, true)",
                incident,
                actor,
                Timestamp.from(instant),
                previous,
                next);
    }

    private UUID user(Role role, String name) {
        UUID user =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                        UUID.class,
                        name,
                        role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                        role.name());
        createdUsers.add(user);
        return user;
    }

    private void channels(UUID user, boolean push, boolean email) {
        jdbc.update(
                "UPDATE users SET notify_by_push = ?, notify_by_email = ? WHERE id = ?",
                push,
                email,
                user);
    }

    private void registeredDevice(UUID user) {
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                user,
                "ExponentPushToken[" + UUID.randomUUID() + "]",
                Timestamp.from(REPORTED_AT),
                Timestamp.from(REPORTED_AT));
    }

    private List<Map<String, Object>> notificationsOf(UUID incident) {
        return jdbc.queryForList(
                "SELECT channel, recipient_id, status_change_id, delivery_status, attempts,"
                        + " next_attempt_at, sent_at, message FROM notification"
                        + " WHERE incident_id = ? ORDER BY channel",
                incident);
    }

    /** The text of the only notification of the incident (the citizen has email only). */
    private String messageOf(UUID incident) {
        List<Map<String, Object>> rows = notificationsOf(incident);
        assertThat(rows).as("notifications of %s", incident).hasSize(1);
        return (String) rows.getFirst().get("message");
    }

    /** The id of the newest history entry that leads to {@code newStatus}. */
    private UUID lastChange(UUID incident, String newStatus) {
        return jdbc.queryForObject(
                "SELECT id FROM status_change WHERE incident_id = ? AND new_status = ?"
                        + " ORDER BY instant DESC LIMIT 1",
                UUID.class,
                incident,
                newStatus);
    }

    private ResultActions act(UUID caller, UUID incident, String action, String body)
            throws Exception {
        return mvc.perform(
                post(INCIDENTS + "/" + incident + "/" + action)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, roleOf(caller)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private Role roleOf(UUID user) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, user));
    }

    /** The body of a lifecycle action; a null reason is left out. */
    private static String body(int version, String reason) {
        if (reason == null) {
            return "{\"version\": " + version + "}";
        }
        return "{\"version\": " + version + ", \"reason\": \"" + escaped(reason) + "\"}";
    }

    private String assignment(int version, UUID assignee, String team) {
        String teamField = team == null ? "" : ", \"team\": \"" + escaped(team) + "\"";
        return "{\"version\": "
                + version
                + ", \"departmentId\": \""
                + department
                + "\", \"technicianId\": \""
                + assignee
                + "\""
                + teamField
                + "}";
    }

    private static String escaped(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
