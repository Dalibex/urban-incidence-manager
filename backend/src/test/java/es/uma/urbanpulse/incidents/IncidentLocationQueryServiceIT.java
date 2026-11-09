package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.PersistenceTestSupport;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.dto.IncidentLocationListResponse;
import es.uma.urbanpulse.incidents.dto.IncidentPositionResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.Department;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.entity.PrioritySource;
import es.uma.urbanpulse.incidents.entity.TransitionDetails;
import es.uma.urbanpulse.incidents.service.BoundingBox;
import es.uma.urbanpulse.incidents.service.IncidentFilter;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The query of {@code listIncidentLocations} run as SQL against PostGIS: the order, the cap, the
 * bounding box and the scope of each role. The HTTP behaviour (roles that get {@code 403}, {@code
 * 401}, the {@code 400} of the parameters, the contract) belongs to the acceptance tests of #76.
 *
 * <p>Other incidents may exist in the database, so every query is narrowed with the category of the
 * test.
 */
class IncidentLocationQueryServiceIT extends PersistenceTestSupport {

    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");

    // Inside the box below: the town hall and the cathedral. Outside: Torremolinos.
    private static final Coordinate TOWN_HALL = new Coordinate(-4.4176, 36.7190);
    private static final Coordinate CATHEDRAL = new Coordinate(-4.4194, 36.7201);
    private static final Coordinate TORREMOLINOS = new Coordinate(-4.4997, 36.6219);
    private static final BoundingBox CITY_CENTRE = new BoundingBox(36.70, -4.44, 36.74, -4.40);

    @Autowired private IncidentQueryService queries;

    private User citizen;
    private User operator;
    private User technician;
    private User otherTechnician;
    private Department department;
    private Category category;
    private IncidentFilter onlyThisTest;

    @BeforeEach
    void createReferenceData() {
        citizen = persistedUser(Role.CITIZEN);
        operator = persistedUser(Role.MUNICIPAL_OPERATOR);
        technician = persistedUser(Role.TECHNICIAN);
        otherTechnician = persistedUser(Role.TECHNICIAN);
        department = new Department();
        department.setName("Public lighting " + UUID.randomUUID());
        entityManager.persist(department);
        category = new Category();
        category.setName("Lighting " + UUID.randomUUID());
        entityManager.persist(category);
        onlyThisTest =
                new IncidentFilter(
                        Set.of(), Set.of(category.getId()), Set.of(), null, null, Set.of());
    }

    @Test
    void theMostRelevantComeFirstByPriorityThenNewest() {
        var oldHigh = persistedIncident(TOWN_HALL, MONDAY, Priority.HIGH);
        var newLow = persistedIncident(TOWN_HALL, MONDAY.plusSeconds(300), Priority.LOW);
        var newHigh = persistedIncident(TOWN_HALL, MONDAY.plusSeconds(200), Priority.HIGH);
        var none = persistedIncident(TOWN_HALL, MONDAY.plusSeconds(400), null);
        var medium = persistedIncident(TOWN_HALL, MONDAY.plusSeconds(100), Priority.MEDIUM);
        flushAndClear();

        var result = operatorLocations(onlyThisTest, Optional.empty());

        assertThat(result.content())
                .extracting(IncidentPositionResponse::id)
                .containsExactly(
                        newHigh.getId(),
                        oldHigh.getId(),
                        medium.getId(),
                        newLow.getId(),
                        none.getId());
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void eachPositionKeepsItsLatitudeAndLongitudeAndItsStatus() {
        var incident = persistedIncident(CATHEDRAL, MONDAY, null);
        flushAndClear();

        assertThat(operatorLocations(onlyThisTest, Optional.empty()).content())
                .containsExactly(
                        new IncidentPositionResponse(
                                incident.getId(),
                                IncidentStatus.REPORTED,
                                null,
                                CATHEDRAL.getY(),
                                CATHEDRAL.getX()));
    }

    @Test
    void theBoxLeavesOutWhatIsOutsideAndKeepsItsEdges() {
        var inside = persistedIncident(TOWN_HALL, MONDAY, null);
        var outside = persistedIncident(TORREMOLINOS, MONDAY, null);
        var onTheEdge = persistedIncident(new Coordinate(-4.40, 36.74), MONDAY, null);
        flushAndClear();

        assertThat(operatorLocations(onlyThisTest, Optional.of(CITY_CENTRE)).content())
                .extracting(IncidentPositionResponse::id)
                .containsExactlyInAnyOrder(inside.getId(), onTheEdge.getId())
                .doesNotContain(outside.getId());
    }

    @Test
    void aBoxWithNothingInsideIsAnEmptyList() {
        persistedIncident(TORREMOLINOS, MONDAY, null);
        flushAndClear();

        var result = operatorLocations(onlyThisTest, Optional.of(CITY_CENTRE));

        assertThat(result.content()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aTechnicianOnlyGetsTheirActiveAssignments() {
        // An incident needs a priority to be assigned.
        var assigned = persistedIncident(TOWN_HALL, MONDAY, Priority.LOW);
        assign(assigned, technician, MONDAY);
        var reassigned = persistedIncident(TOWN_HALL, MONDAY, Priority.LOW);
        assign(reassigned, technician, MONDAY);
        assign(reassigned, otherTechnician, MONDAY.plusSeconds(600));
        persistedIncident(TOWN_HALL, MONDAY, null);
        flushAndClear();

        var result =
                queries.locations(
                        technician.getId(), Role.TECHNICIAN, onlyThisTest, Optional.empty());

        assertThat(result.content())
                .extracting(IncidentPositionResponse::id)
                .containsExactly(assigned.getId());
    }

    @Test
    void theFiltersFindTheSameIncidentsAsTheList() {
        var high = persistedIncident(TOWN_HALL, MONDAY, Priority.HIGH);
        persistedIncident(TOWN_HALL, MONDAY, Priority.LOW);
        var assignedHigh = persistedIncident(CATHEDRAL, MONDAY, Priority.HIGH);
        assign(assignedHigh, technician, MONDAY);
        flushAndClear();
        var filter =
                new IncidentFilter(
                        Set.of(IncidentStatus.REPORTED, IncidentStatus.ASSIGNED),
                        Set.of(category.getId()),
                        Set.of(Priority.HIGH),
                        null,
                        null,
                        Set.of());

        var listed =
                queries
                        .list(
                                operator.getId(),
                                Role.MUNICIPAL_OPERATOR,
                                filter,
                                PageRequest.of(0, 100, Sort.by("id")))
                        .content()
                        .stream()
                        .map(IncidentSummaryResponse.Staff.class::cast)
                        .map(IncidentSummaryResponse.Staff::id)
                        .toList();

        assertThat(operatorLocations(filter, Optional.empty()).content())
                .extracting(IncidentPositionResponse::id)
                .containsExactlyInAnyOrderElementsOf(listed)
                .containsExactlyInAnyOrder(high.getId(), assignedHigh.getId());
    }

    @Test
    void overTheCapItIsTruncatedAndKeepsTheMostRelevant() {
        // The oldest, so only its priority puts it first.
        var high = persistedIncident(TOWN_HALL, MONDAY, Priority.HIGH);
        for (var i = 1; i < IncidentQueryService.MAX_LOCATIONS; i++) {
            persistedIncident(TOWN_HALL, MONDAY.plusSeconds(i), null);
        }
        flushAndClear();

        var atTheCap = operatorLocations(onlyThisTest, Optional.empty());

        assertThat(atTheCap.content()).hasSize(IncidentQueryService.MAX_LOCATIONS);
        assertThat(atTheCap.truncated()).isFalse();

        var newest = persistedIncident(TOWN_HALL, MONDAY.plusSeconds(100_000), null);
        flushAndClear();

        var overTheCap = operatorLocations(onlyThisTest, Optional.empty());

        assertThat(overTheCap.content()).hasSize(IncidentQueryService.MAX_LOCATIONS);
        assertThat(overTheCap.truncated()).isTrue();
        assertThat(overTheCap.content().getFirst().id()).isEqualTo(high.getId());
        assertThat(overTheCap.content().get(1).id()).isEqualTo(newest.getId());
    }

    private IncidentLocationListResponse operatorLocations(
            IncidentFilter filter, Optional<BoundingBox> box) {
        return queries.locations(operator.getId(), Role.MUNICIPAL_OPERATOR, filter, box);
    }

    private Incident persistedIncident(Coordinate position, Instant reportedAt, Priority priority) {
        var location = new Location();
        location.setCoordinates(Location.WGS84.createPoint(position));

        var incident =
                Incident.report(
                        citizen,
                        "Street light off",
                        "Off since Monday.",
                        null,
                        location,
                        reportedAt);
        incident.classify(category, reportedAt);
        if (priority != null) {
            incident.prioritize(
                    priority, "Main street", PrioritySource.MANUAL, operator, reportedAt);
        }
        // The report cascades to its location.
        entityManager.persist(incident);
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
