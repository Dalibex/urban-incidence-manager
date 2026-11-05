package es.uma.urbanpulse.incidents.entity;

import static es.uma.urbanpulse.incidents.entity.IncidentFixture.LATER;
import static es.uma.urbanpulse.incidents.entity.IncidentFixture.REPORTED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.error.FieldError;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** What each transition records, and the data it needs (ADR-0020). */
class IncidentTest {

    private static final OffsetDateTime LATER_UTC = OffsetDateTime.ofInstant(LATER, ZoneOffset.UTC);

    private final IncidentFixture fixture = new IncidentFixture();

    @Test
    void aReportStartsInReportedWithItsFirstHistoryEntry() {
        var incident =
                Incident.report(
                        fixture.citizen,
                        "Pothole",
                        "Deep pothole.",
                        null,
                        IncidentFixture.locationAt(36.7213, -4.4214),
                        REPORTED_AT);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.REPORTED);
        assertThat(incident.getCategory()).isNull();
        assertThat(incident.getReportedAt()).isEqualTo(REPORTED_AT.atOffset(ZoneOffset.UTC));
        assertThat(incident.getUpdatedAt()).isEqualTo(incident.getReportedAt());
        assertThat(incident.getStatusChanges())
                .singleElement()
                .satisfies(
                        first -> {
                            assertThat(first.getIncident()).isSameAs(incident);
                            assertThat(first.getActor()).isSameAs(fixture.citizen);
                            assertThat(first.getInstant()).isEqualTo(incident.getReportedAt());
                            assertThat(first.getPreviousStatus()).isNull();
                            assertThat(first.getNewStatus()).isEqualTo(IncidentStatus.REPORTED);
                            assertThat(first.getReason()).isNull();
                            assertThat(first.getVisibleToCitizen()).isTrue();
                        });
    }

    @Test
    void onlyCitizensReport() {
        assertThatThrownBy(
                        () ->
                                Incident.report(
                                        fixture.operator,
                                        "Pothole",
                                        "Deep pothole.",
                                        null,
                                        IncidentFixture.locationAt(36.7213, -4.4214),
                                        REPORTED_AT))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void aTransitionRecordsActorInstantStatusesAndReason() {
        var incident = fixture.reported();

        var change =
                incident.changeStatus(
                        IncidentTransition.REJECTION,
                        fixture.administrator,
                        TransitionDetails.withReason("Duplicate of another report"),
                        LATER);

        assertThat(change.getIncident()).isSameAs(incident);
        assertThat(change.getActor()).isSameAs(fixture.administrator);
        assertThat(change.getInstant()).isEqualTo(LATER_UTC);
        assertThat(change.getInstant().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(change.getPreviousStatus()).isEqualTo(IncidentStatus.REPORTED);
        assertThat(change.getNewStatus()).isEqualTo(IncidentStatus.REJECTED);
        assertThat(change.getReason()).isEqualTo("Duplicate of another report");
        assertThat(change.getAssociatedData()).isNull();
        assertThat(change.getVisibleToCitizen()).isTrue();
        assertThat(incident.getUpdatedAt()).isEqualTo(LATER_UTC);
    }

    @Test
    void theCitizenSeesTheReasonOfAnInformationRequestAndARejectionOnly() {
        var reopened = fixture.in(IncidentStatus.REOPENED);
        var requested = fixture.reported();
        var request =
                requested.changeStatus(
                        IncidentTransition.INFORMATION_REQUEST,
                        fixture.operator,
                        TransitionDetails.withReason("Which street number?"),
                        LATER);
        var rejection =
                requested.changeStatus(
                        IncidentTransition.REJECTION,
                        fixture.operator,
                        TransitionDetails.withReason("Duplicate"),
                        LATER);

        // Report, validation, assignment, acceptance, resolution and reopening.
        assertThat(reopened.getStatusChanges())
                .hasSize(6)
                .allSatisfy(change -> assertThat(change.citizenSeesReason()).isFalse());
        assertThat(request.citizenSeesReason()).isTrue();
        assertThat(rejection.citizenSeesReason()).isTrue();
    }

    @Test
    void anInformationRequestKeepsTheStatusAndIsMarkedInTheHistory() {
        var incident = fixture.reported();

        var change =
                incident.changeStatus(
                        IncidentTransition.INFORMATION_REQUEST,
                        fixture.operator,
                        TransitionDetails.withReason("Which street number?"),
                        LATER);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.REPORTED);
        assertThat(change.getPreviousStatus()).isEqualTo(IncidentStatus.REPORTED);
        assertThat(change.getReason()).isEqualTo("Which street number?");
        assertThat(change.getAssociatedData()).isEqualTo("{\"action\": \"INFORMATION_REQUESTED\"}");
        assertThat(change.getVisibleToCitizen()).isTrue();
    }

    @Test
    void anAssignmentKeepsDepartmentAndTechnicianInTheInternalData() {
        var incident = fixture.in(IncidentStatus.VALIDATED);

        var change =
                incident.changeStatus(
                        IncidentTransition.ASSIGNMENT,
                        fixture.operator,
                        TransitionDetails.assignment(fixture.department, fixture.technician),
                        LATER);

        assertThat(change.getAssociatedData())
                .isEqualTo(
                        "{\"departmentId\": \"%s\", \"technicianId\": \"%s\"}"
                                .formatted(fixture.department.getId(), fixture.technician.getId()));
        assertThat(incident.getAssignments())
                .singleElement()
                .satisfies(
                        assignment -> {
                            assertThat(assignment.getIncident()).isSameAs(incident);
                            assertThat(assignment.getDepartment()).isSameAs(fixture.department);
                            assertThat(assignment.getTechnician()).isSameAs(fixture.technician);
                            assertThat(assignment.getAssignedAt()).isEqualTo(LATER_UTC);
                            assertThat(assignment.isActive()).isTrue();
                        });
        assertThat(incident.isAssignedTo(fixture.technician)).isTrue();
    }

    @Test
    void aReassignmentDeactivatesThePreviousAssignment() {
        var incident = fixture.in(IncidentStatus.ASSIGNED);

        incident.changeStatus(
                IncidentTransition.ASSIGNMENT,
                fixture.operator,
                TransitionDetails.assignment(fixture.department, fixture.otherTechnician),
                LATER);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(incident.getAssignments()).hasSize(2);
        assertThat(incident.getAssignments())
                .filteredOn(Assignment::isActive)
                .singleElement()
                .extracting(Assignment::getTechnician)
                .isSameAs(fixture.otherTechnician);
        assertThat(incident.isAssignedTo(fixture.technician)).isFalse();
        assertThat(incident.isAssignedTo(fixture.otherTechnician)).isTrue();
    }

    @Test
    void anAssignmentToTheDepartmentOnlyCannotBeAccepted() {
        var incident = fixture.in(IncidentStatus.VALIDATED);
        incident.changeStatus(
                IncidentTransition.ASSIGNMENT,
                fixture.operator,
                TransitionDetails.assignment(fixture.department, null),
                LATER);

        assertThat(incident.getStatusChanges().getLast().getAssociatedData())
                .isEqualTo("{\"departmentId\": \"%s\"}".formatted(fixture.department.getId()));
        assertThatThrownBy(
                        () ->
                                incident.changeStatus(
                                        IncidentTransition.ACCEPTANCE,
                                        fixture.technician,
                                        TransitionDetails.none(),
                                        LATER))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
    }

    @Test
    void theClosureSetsTheClosingInstant() {
        var incident = fixture.in(IncidentStatus.RESOLVED);
        assertThat(incident.getClosedAt()).isNull();

        incident.changeStatus(
                IncidentTransition.CLOSURE, fixture.operator, TransitionDetails.none(), LATER);

        assertThat(incident.getClosedAt()).isEqualTo(LATER_UTC);
    }

    @Test
    void aTransitionWithoutItsReasonIsRejectedWithoutChanges() {
        var incident = fixture.reported();

        assertThatThrownBy(
                        () ->
                                incident.changeStatus(
                                        IncidentTransition.REJECTION,
                                        fixture.operator,
                                        TransitionDetails.withReason("  "),
                                        LATER))
                .isInstanceOfSatisfying(
                        InvalidIncidentDataException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("VALIDATION_FAILED");
                            assertThat(ex.fieldErrors())
                                    .extracting(FieldError::field)
                                    .containsExactly("reason");
                        });
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.REPORTED);
        assertThat(incident.getStatusChanges()).hasSize(1);
    }

    @Test
    void validationNeedsCategoryAndPriority() {
        var incident =
                Incident.report(
                        fixture.citizen,
                        "Pothole",
                        "Deep pothole.",
                        null,
                        IncidentFixture.locationAt(36.7213, -4.4214),
                        REPORTED_AT);

        assertThatThrownBy(
                        () ->
                                incident.changeStatus(
                                        IncidentTransition.VALIDATION,
                                        fixture.operator,
                                        TransitionDetails.none(),
                                        LATER))
                .isInstanceOfSatisfying(
                        InvalidIncidentDataException.class,
                        ex ->
                                assertThat(ex.fieldErrors())
                                        .extracting(FieldError::field)
                                        .containsExactly("category", "priority"));
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.REPORTED);
    }

    @Test
    void anAssignmentNeedsADepartment() {
        var incident = fixture.in(IncidentStatus.VALIDATED);

        assertThatThrownBy(
                        () ->
                                incident.changeStatus(
                                        IncidentTransition.ASSIGNMENT,
                                        fixture.operator,
                                        TransitionDetails.assignment(null, fixture.technician),
                                        LATER))
                .isInstanceOfSatisfying(
                        InvalidIncidentDataException.class,
                        ex ->
                                assertThat(ex.fieldErrors())
                                        .extracting(FieldError::field)
                                        .containsExactly("departmentId"));
        assertThat(incident.getAssignments()).isEmpty();
    }

    @Test
    void anAssignmentNeedsAnActiveDepartment() {
        var closedDepartment = IncidentFixture.department();
        closedDepartment.setIsActive(false);

        assertAssignmentRejectedOn(
                TransitionDetails.assignment(closedDepartment, fixture.technician), "departmentId");
    }

    @Test
    void onlyATechnicianCanBeAssigned() {
        assertAssignmentRejectedOn(
                TransitionDetails.assignment(fixture.department, fixture.operator), "technicianId");
        assertAssignmentRejectedOn(
                TransitionDetails.assignment(fixture.department, fixture.citizen), "technicianId");
    }

    @Test
    void aDisabledTechnicianCannotBeAssigned() {
        var disabledTechnician = IncidentFixture.user(Role.TECHNICIAN);
        disabledTechnician.setEnabled(false);

        assertAssignmentRejectedOn(
                TransitionDetails.assignment(fixture.department, disabledTechnician),
                "technicianId");
    }

    @Test
    void anAssignmentKeepsItsTeam() {
        var incident = fixture.in(IncidentStatus.VALIDATED);

        incident.changeStatus(
                IncidentTransition.ASSIGNMENT,
                fixture.operator,
                TransitionDetails.assignment(fixture.department, fixture.technician, "Night shift"),
                LATER);

        assertThat(incident.getAssignments())
                .singleElement()
                .extracting(Assignment::getTeam)
                .isEqualTo("Night shift");
    }

    @Test
    void aTeamCannotBeBlankOrTooLong() {
        assertAssignmentRejectedOn(
                TransitionDetails.assignment(fixture.department, fixture.technician, " "), "team");
        assertAssignmentRejectedOn(
                TransitionDetails.assignment(
                        fixture.department,
                        fixture.technician,
                        "x".repeat(Assignment.TEAM_MAX_LENGTH + 1)),
                "team");
    }

    @Test
    void aTransitionThatIsNotAllowedDoesNotLookUpItsData() {
        Supplier<TransitionDetails> notLookedUp =
                () -> {
                    throw new AssertionError("The data was looked up");
                };

        assertThatThrownBy(
                        () ->
                                fixture.reported()
                                        .changeStatus(
                                                IncidentTransition.ASSIGNMENT,
                                                fixture.operator,
                                                notLookedUp,
                                                LATER))
                .isInstanceOfSatisfying(
                        InvalidTransitionException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_TRANSITION"));
        assertThatThrownBy(
                        () ->
                                fixture.in(IncidentStatus.VALIDATED)
                                        .changeStatus(
                                                IncidentTransition.ASSIGNMENT,
                                                fixture.technician,
                                                notLookedUp,
                                                LATER))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void theTeamIsCountedInCharactersLikeTheContract() {
        // One emoji is one character but two UTF-16 units.
        var incident = fixture.in(IncidentStatus.VALIDATED);
        incident.changeStatus(
                IncidentTransition.ASSIGNMENT,
                fixture.operator,
                TransitionDetails.assignment(
                        fixture.department,
                        fixture.technician,
                        "\uD83D\uDE92".repeat(Assignment.TEAM_MAX_LENGTH)),
                LATER);
        assertThat(incident.activeAssignment()).isPresent();

        assertAssignmentRejectedOn(
                TransitionDetails.assignment(
                        fixture.department,
                        fixture.technician,
                        "\uD83D\uDE92".repeat(Assignment.TEAM_MAX_LENGTH + 1)),
                "team");
    }

    @Test
    void theHistoryOfAnAssignmentKeepsTheTeamEscaped() {
        var incident = fixture.in(IncidentStatus.VALIDATED);

        var change =
                incident.changeStatus(
                        IncidentTransition.ASSIGNMENT,
                        fixture.operator,
                        TransitionDetails.assignment(fixture.department, null, "A \"B\" \\ C\n"),
                        LATER);

        assertThat(change.getAssociatedData())
                .isEqualTo(
                        "{\"departmentId\": \""
                                + fixture.department.getId()
                                + "\", \"team\": \"A \\\"B\\\" \\\\ C\\u000a\"}");
    }

    @Test
    void aPriorityNeedsItsJustification() {
        var incident = fixture.reported();

        assertThatThrownBy(
                        () ->
                                incident.prioritize(
                                        Priority.HIGH,
                                        " ",
                                        PrioritySource.MANUAL,
                                        fixture.operator,
                                        LATER))
                .isInstanceOfSatisfying(
                        InvalidIncidentDataException.class,
                        ex -> {
                            assertThat(ex.fieldErrors())
                                    .extracting(FieldError::field)
                                    .containsExactly("priorityReason");
                        });
        assertThat(incident.getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(incident.getPriorityReason()).isEqualTo("Dark street");
    }

    @Test
    void aPriorityKeepsWhoSetItAndWhy() {
        var incident = fixture.reported();

        incident.prioritize(
                Priority.HIGH,
                "School nearby",
                PrioritySource.MANUAL,
                fixture.administrator,
                LATER);

        assertThat(incident.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(incident.getPriorityReason()).isEqualTo("School nearby");
        assertThat(incident.getPrioritySource()).isEqualTo(PrioritySource.MANUAL);
        assertThat(incident.getPrioritySetter()).isSameAs(fixture.administrator);
        assertThat(incident.getUpdatedAt()).isEqualTo(LATER_UTC);
    }

    /** The assignment is refused on that field and the validated incident is left as it was. */
    private void assertAssignmentRejectedOn(TransitionDetails details, String field) {
        var incident = fixture.in(IncidentStatus.VALIDATED);
        var historyBefore = incident.getStatusChanges().size();

        assertThatThrownBy(
                        () ->
                                incident.changeStatus(
                                        IncidentTransition.ASSIGNMENT,
                                        fixture.operator,
                                        details,
                                        LATER))
                .isInstanceOfSatisfying(
                        InvalidIncidentDataException.class,
                        ex ->
                                assertThat(ex.fieldErrors())
                                        .extracting(FieldError::field)
                                        .containsExactly(field));
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.VALIDATED);
        assertThat(incident.getStatusChanges()).hasSize(historyBefore);
        assertThat(incident.getAssignments()).isEmpty();
    }
}
