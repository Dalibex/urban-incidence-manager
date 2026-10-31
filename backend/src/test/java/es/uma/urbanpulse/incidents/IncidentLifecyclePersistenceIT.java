package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.PersistenceTestSupport;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.Department;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.entity.PrioritySource;
import es.uma.urbanpulse.incidents.entity.StatusChange;
import es.uma.urbanpulse.incidents.entity.TransitionDetails;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The history and the assignments that {@link Incident} creates are saved with it, by cascade, so
 * saving the incident is enough to write the status, the history and the assignment together.
 */
class IncidentLifecyclePersistenceIT extends PersistenceTestSupport {

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired private IncidentRepository incidents;

    @Test
    void savingTheIncidentWritesItsHistoryAndAssignment() {
        User citizen = persistedUser(Role.CITIZEN);
        User operator = persistedUser(Role.MUNICIPAL_OPERATOR);
        User technician = persistedUser(Role.TECHNICIAN);
        Department department = persistedDepartment();

        Incident incident =
                Incident.report(
                        citizen, "Street light off", "Off since Monday.", null, REPORTED_AT);
        incident.classify(persistedCategory(), REPORTED_AT);
        incident.prioritize(
                Priority.LOW, "Side street", PrioritySource.MANUAL, operator, REPORTED_AT);
        incident = incidents.save(incident);
        incident.changeStatus(
                IncidentTransition.VALIDATION, operator, TransitionDetails.none(), LATER);
        incident.changeStatus(
                IncidentTransition.ASSIGNMENT,
                operator,
                TransitionDetails.assignment(department, technician),
                LATER);
        UUID id = incident.getId();
        flushAndClear();

        Incident found = incidents.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(found.getStatusChanges())
                .extracting(StatusChange::getNewStatus)
                .containsExactly(
                        IncidentStatus.REPORTED, IncidentStatus.VALIDATED, IncidentStatus.ASSIGNED);
        assertThat(found.isAssignedTo(technician)).isTrue();
        assertThat(
                        queryNative(
                                "SELECT associated_data ->> 'technicianId' FROM status_change"
                                        + " WHERE incident_id = ? AND new_status = 'ASSIGNED'",
                                String.class,
                                id))
                .isEqualTo(technician.getId().toString());
    }

    private User persistedUser(Role role) {
        User user = new User();
        user.setName(role + " user");
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }

    private Category persistedCategory() {
        Category category = new Category();
        category.setName("Lighting");
        entityManager.persist(category);
        return category;
    }

    private Department persistedDepartment() {
        Department department = new Department();
        department.setName("Public lighting");
        entityManager.persist(department);
        return department;
    }
}
