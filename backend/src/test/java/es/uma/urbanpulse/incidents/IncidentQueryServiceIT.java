package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.PersistenceTestSupport;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.dto.StatusChangeResponse;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.Department;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.entity.PrioritySource;
import es.uma.urbanpulse.incidents.entity.TransitionDetails;
import es.uma.urbanpulse.incidents.service.IncidentNotFoundException;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The access rule of {@code IncidentReadAccess} run as SQL against PostGIS, and the response built
 * for each role. The HTTP behaviour (status codes, paging parameters, serialization) is covered by
 * the acceptance tests of #103.
 */
class IncidentQueryServiceIT extends PersistenceTestSupport {

    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-11-03T09:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-11-04T09:00:00Z");
    private static final PageRequest FIRST_PAGE =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "reportedAt").and(Sort.by("id")));
    private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired private IncidentQueryService queries;

    private User citizen;
    private User otherCitizen;
    private User operator;
    private User technician;
    private User otherTechnician;
    private Department department;
    private Category category;

    /** Reported by the citizen, still {@code REPORTED}. */
    private Incident reported;

    /** Reported by the citizen, assigned to the technician. */
    private Incident assigned;

    /** Reported by the other citizen, assigned to the technician and then to the other one. */
    private Incident reassigned;

    @BeforeEach
    void createIncidents() {
        citizen = persistedUser(Role.CITIZEN);
        otherCitizen = persistedUser(Role.CITIZEN);
        operator = persistedUser(Role.MUNICIPAL_OPERATOR);
        technician = persistedUser(Role.TECHNICIAN);
        otherTechnician = persistedUser(Role.TECHNICIAN);
        department = new Department();
        department.setName("Public lighting");
        entityManager.persist(department);
        category = new Category();
        category.setName("Lighting");
        entityManager.persist(category);

        reported = persistedIncident(citizen, MONDAY);
        assigned = persistedIncident(citizen, TUESDAY);
        assign(assigned, technician, TUESDAY);
        reassigned = persistedIncident(otherCitizen, WEDNESDAY);
        assign(reassigned, technician, WEDNESDAY);
        assign(reassigned, otherTechnician, WEDNESDAY.plusSeconds(600));
        flushAndClear();
    }

    @Test
    void eachRoleListsOnlyTheIncidentsItMaySee() {
        assertThat(listedIds(citizen.getId(), Role.CITIZEN))
                .containsExactly(assigned.getId(), reported.getId());
        assertThat(listedIds(otherCitizen.getId(), Role.CITIZEN))
                .containsExactly(reassigned.getId());
        assertThat(listedIds(technician.getId(), Role.TECHNICIAN))
                .containsExactly(assigned.getId());
        assertThat(listedIds(otherTechnician.getId(), Role.TECHNICIAN))
                .containsExactly(reassigned.getId());
        assertThat(listedIds(operator.getId(), Role.MUNICIPAL_OPERATOR))
                .contains(reassigned.getId(), assigned.getId(), reported.getId());
        assertThat(listedIds(UUID.randomUUID(), Role.ADMINISTRATOR))
                .contains(reassigned.getId(), assigned.getId(), reported.getId());
        assertThat(listedIds(UUID.randomUUID(), Role.ANALYST)).isEmpty();
    }

    @Test
    void theStatusFilterAppliesInsideWhatTheCallerMaySee() {
        var page =
                queries.list(
                        citizen.getId(),
                        Role.CITIZEN,
                        EnumSet.of(IncidentStatus.REPORTED),
                        FIRST_PAGE);

        assertThat(page.content())
                .extracting(IncidentSummaryResponse.Citizen.class::cast)
                .extracting(IncidentSummaryResponse.Citizen::id)
                .containsExactly(reported.getId());
        assertThat(page.page().totalElements()).isEqualTo(1);
    }

    @Test
    void anIncidentTheCallerMayNotSeeIsNotFound() {
        assertThatThrownBy(() -> queries.get(reported.getId(), otherCitizen.getId(), Role.CITIZEN))
                .isInstanceOf(IncidentNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                queries.history(
                                        reassigned.getId(), technician.getId(), Role.TECHNICIAN))
                .isInstanceOf(IncidentNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                queries.get(
                                        UUID.randomUUID(),
                                        operator.getId(),
                                        Role.MUNICIPAL_OPERATOR))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    @Test
    void theCitizenGetsTheirIncidentWithoutStaffData() {
        var response = queries.get(reported.getId(), citizen.getId(), Role.CITIZEN);

        assertThat(response)
                .isInstanceOfSatisfying(
                        IncidentResponse.Citizen.class,
                        incident -> {
                            assertThat(incident.category().name()).isEqualTo("Lighting");
                            assertThat(incident.location().latitude()).isEqualTo(36.7213);
                            assertThat(incident.location().longitude()).isEqualTo(-4.4214);
                            assertThat(incident.location().district()).isNull();
                            assertThat(incident.reportedAt()).isEqualTo(MONDAY);
                        });
        assertThat(queries.get(reported.getId(), operator.getId(), Role.MUNICIPAL_OPERATOR))
                .isInstanceOfSatisfying(
                        IncidentResponse.Staff.class,
                        incident -> assertThat(incident.priority()).isEqualTo(Priority.LOW));
    }

    @Test
    void theCitizenHistoryHidesTheInternalReasonsAndTheStaffData() {
        var incident = entityManager.find(Incident.class, assigned.getId());
        incident.changeStatus(
                IncidentTransition.ACCEPTANCE, technician, TransitionDetails.none(), WEDNESDAY);
        incident.changeStatus(
                IncidentTransition.RESOLUTION,
                technician,
                TransitionDetails.withReason("Bulb replaced"),
                WEDNESDAY.plusSeconds(60));
        flushAndClear();

        var citizenView = queries.history(assigned.getId(), citizen.getId(), Role.CITIZEN);
        var staffView =
                queries.history(assigned.getId(), operator.getId(), Role.MUNICIPAL_OPERATOR);

        assertThat(citizenView)
                .allSatisfy(
                        change ->
                                assertThat(change).isInstanceOf(StatusChangeResponse.Citizen.class))
                .extracting(change -> ((StatusChangeResponse.Citizen) change).newStatus())
                .containsExactly(
                        IncidentStatus.REPORTED,
                        IncidentStatus.VALIDATED,
                        IncidentStatus.ASSIGNED,
                        IncidentStatus.IN_PROGRESS,
                        IncidentStatus.RESOLVED);
        assertThat(((StatusChangeResponse.Citizen) citizenView.getLast()).reason()).isNull();
        assertThat(staffView.getLast())
                .isInstanceOfSatisfying(
                        StatusChangeResponse.Staff.class,
                        change -> {
                            assertThat(change.reason()).isEqualTo("Bulb replaced");
                            assertThat(change.actor().id()).isEqualTo(technician.getId());
                            assertThat(change.visibleToCitizen()).isTrue();
                        });
    }

    private List<UUID> listedIds(UUID userId, Role role) {
        return queries.list(userId, role, Set.of(), FIRST_PAGE).content().stream()
                .map(
                        summary ->
                                switch (summary) {
                                    case IncidentSummaryResponse.Staff staff -> staff.id();
                                    case IncidentSummaryResponse.Citizen own -> own.id();
                                })
                .toList();
    }

    private Incident persistedIncident(User reporter, Instant reportedAt) {
        var incident =
                Incident.report(
                        reporter, "Street light off", "Off since Monday.", null, reportedAt);
        incident.classify(category, reportedAt);
        incident.prioritize(
                Priority.LOW, "Side street", PrioritySource.MANUAL, operator, reportedAt);
        entityManager.persist(incident);
        var location = new Location();
        location.setIncident(incident);
        location.setCoordinates(WGS84.createPoint(new Coordinate(-4.4214, 36.7213)));
        entityManager.persist(location);
        return incident;
    }

    /** Validates the incident if needed and assigns it, one minute apart, after {@code after}. */
    private void assign(Incident incident, User assignee, Instant after) {
        if (incident.getStatus() == IncidentStatus.REPORTED) {
            incident.changeStatus(
                    IncidentTransition.VALIDATION,
                    operator,
                    TransitionDetails.none(),
                    after.plusSeconds(60));
        }
        incident.changeStatus(
                IncidentTransition.ASSIGNMENT,
                operator,
                TransitionDetails.assignment(department, assignee),
                after.plusSeconds(120));
    }

    private User persistedUser(Role role) {
        var user = new User();
        user.setName(role + " user");
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }
}
