package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import es.uma.urbanpulse.incidents.entity.InvalidTransitionException;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.entity.PrioritySource;
import es.uma.urbanpulse.incidents.entity.TransitionDetails;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.incidents.service.IncidentLifecycleService;
import es.uma.urbanpulse.incidents.service.IncidentNotFoundException;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction and the conflict control of {@link IncidentLifecycleService} (#101), against
 * PostGIS.
 *
 * <p>Unlike {@code PersistenceTestSupport}, the tests do not run inside a transaction: each call to
 * the service commits or rolls back its own, so the checks read what the database really kept. The
 * concurrent change is made deterministic without threads: another connection commits between the
 * moment the incident is read and the moment it is saved. The rows of each test are deleted after
 * it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncidentLifecycleServiceIT {

    // Before any run of the tests: the transitions take their instant from the real clock, and the
    // history is checked oldest first.
    private static final Instant REPORTED_AT = Instant.parse("2026-01-05T09:00:00Z");
    private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired private IncidentLifecycleService lifecycle;
    @Autowired private IncidentRepository incidents;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private final List<UUID> createdUsers = new ArrayList<>();
    private UUID category;
    private UUID citizen;
    private UUID otherCitizen;
    private UUID operator;
    private UUID technician;
    private UUID incident;

    @BeforeEach
    void reportAnIncident() {
        transaction.executeWithoutResult(
                status -> {
                    var lighting = new Category();
                    lighting.setName("Lighting " + UUID.randomUUID());
                    entityManager.persist(lighting);
                    category = lighting.getId();

                    var reporter = persistedUser(Role.CITIZEN);
                    var staff = persistedUser(Role.MUNICIPAL_OPERATOR);
                    citizen = reporter.getId();
                    otherCitizen = persistedUser(Role.CITIZEN).getId();
                    operator = staff.getId();
                    technician = persistedUser(Role.TECHNICIAN).getId();

                    var location = new Location();
                    location.setCoordinates(WGS84.createPoint(new Coordinate(-4.4214, 36.7213)));

                    var reported =
                            Incident.report(
                                    reporter,
                                    "Street light off",
                                    "Off since Monday.",
                                    null,
                                    location,
                                    REPORTED_AT);
                    reported.classify(lighting, REPORTED_AT);
                    reported.prioritize(
                            Priority.LOW, "Side street", PrioritySource.MANUAL, staff, REPORTED_AT);
                    entityManager.persist(reported);
                    entityManager.persist(location);
                    incident = reported.getId();
                });
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        jdbc.update("DELETE FROM status_change WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM category WHERE id = ?", category);
        createdUsers.forEach(user -> jdbc.update("DELETE FROM users WHERE id = ?", user));
    }

    @Test
    void aTransitionSavesTheStatusAndItsHistoryAndReturnsTheNewVersion() {
        int read = storedVersion();

        var response = validate(read);

        assertThat(response)
                .isInstanceOfSatisfying(
                        IncidentResponse.Staff.class,
                        staff -> assertThat(staff.version()).isEqualTo(read + 1));
        assertThat(storedStatus()).isEqualTo("VALIDATED");
        assertThat(storedHistory()).containsExactly("REPORTED", "VALIDATED");
        assertThat(storedVersion()).isEqualTo(read + 1);
    }

    @Test
    void theSecondOfTwoChangesMadeOnTheSameVersionIsAConflict() {
        int read = storedVersion();
        validate(read);

        assertThatThrownBy(() -> validate(read))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(storedHistory()).containsExactly("REPORTED", "VALIDATED");
        assertThat(storedVersion()).isEqualTo(read + 1);
    }

    @Test
    void aChangeCommittedWhileSavingIsAConflictAndNothingOfTheTransitionIsKept() {
        int read = storedVersion();

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            // The incident is read in this transaction at `read`...
                                            incidents.findById(incident).orElseThrow();
                                            // ...another user commits a change meanwhile...
                                            changeFromAnotherConnection();
                                            // ...and the service applies its own on what it read.
                                            validate(read);
                                        }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(storedStatus()).isEqualTo("REPORTED");
        assertThat(storedHistory()).containsExactly("REPORTED");
        assertThat(storedVersion()).isEqualTo(read + 1);
    }

    @Test
    void aTransitionTheTableForbidsChangesNothing() {
        int read = storedVersion();

        assertThatThrownBy(
                        () ->
                                lifecycle.changeStatus(
                                        incident,
                                        read,
                                        IncidentTransition.CLOSURE,
                                        TransitionDetails.none(),
                                        operator,
                                        Role.MUNICIPAL_OPERATOR))
                .isInstanceOf(InvalidTransitionException.class);

        assertThat(storedStatus()).isEqualTo("REPORTED");
        assertThat(storedHistory()).containsExactly("REPORTED");
        assertThat(storedVersion()).isEqualTo(read);
    }

    @Test
    void nobodyChangesAnIncidentTheyCannotSee() {
        int read = storedVersion();

        for (var caller :
                List.of(
                        new Caller(otherCitizen, Role.CITIZEN),
                        new Caller(technician, Role.TECHNICIAN),
                        new Caller(UUID.randomUUID(), Role.ANALYST))) {
            assertThatThrownBy(
                            () ->
                                    lifecycle.changeStatus(
                                            incident,
                                            read,
                                            IncidentTransition.VALIDATION,
                                            TransitionDetails.none(),
                                            caller.id(),
                                            caller.role()))
                    .as("%s", caller)
                    .isInstanceOf(IncidentNotFoundException.class);
        }
        assertThat(storedHistory()).containsExactly("REPORTED");
        assertThat(storedVersion()).isEqualTo(read);
    }

    @Test
    void theReporterCannotMakeAStaffTransition() {
        int read = storedVersion();

        assertThatThrownBy(
                        () ->
                                lifecycle.changeStatus(
                                        incident,
                                        read,
                                        IncidentTransition.VALIDATION,
                                        TransitionDetails.none(),
                                        citizen,
                                        Role.CITIZEN))
                .isInstanceOf(InvalidTransitionException.class);

        assertThat(storedHistory()).containsExactly("REPORTED");
    }

    private IncidentResponse validate(int expectedVersion) {
        return lifecycle.changeStatus(
                incident,
                expectedVersion,
                IncidentTransition.VALIDATION,
                TransitionDetails.none(),
                operator,
                Role.MUNICIPAL_OPERATOR);
    }

    /** A committed change from outside the test's transaction, as another request would make. */
    private void changeFromAnotherConnection() {
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE incident SET version = version + 1 WHERE id = ?")) {
            connection.setAutoCommit(true);
            update.setObject(1, incident);
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String storedStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM incident WHERE id = ?", String.class, incident);
    }

    private int storedVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM incident WHERE id = ?", Integer.class, incident);
    }

    private List<String> storedHistory() {
        return jdbc.queryForList(
                "SELECT new_status FROM status_change WHERE incident_id = ? ORDER BY instant, id",
                String.class,
                incident);
    }

    private User persistedUser(Role role) {
        var user = new User();
        user.setName(role + " user");
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        entityManager.persist(user);
        createdUsers.add(user.getId());
        return user;
    }

    private record Caller(UUID id, Role role) {}
}
