package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest.ReportedLocation;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.incidents.service.IncidentReportService;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cases of #289 that need committed transactions: two requests with the same {@code
 * Idempotency-Key} at the same time (T8 of #302), and a request that fails with {@code 500} before
 * its incident is saved (T7). Unlike {@code IncidentIdempotencyApiIT}, this class does not run in a
 * transaction, because a request only sees what another transaction has committed; the rows are
 * deleted after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class IncidentIdempotencyTransactionIT {

    private static final String TITLE = "Broken streetlight";
    private static final String DESCRIPTION = "The lamp is off since Monday.";
    private static final double LATITUDE = 36.7213;
    private static final double LONGITUDE = -4.4214;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private TransactionTemplate transactions;
    @Autowired private IncidentReportService reports;
    @MockitoSpyBean private IncidentRepository incidents;

    private UUID citizen;

    @BeforeEach
    void createCitizen() {
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test', ?, '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        Mockito.reset(incidents);
        var reported = "SELECT id FROM incident WHERE reporter_id = ?";
        jdbc.update("DELETE FROM status_change WHERE incident_id IN (" + reported + ")", citizen);
        jdbc.update("DELETE FROM location WHERE incident_id IN (" + reported + ")", citizen);
        jdbc.update("DELETE FROM incident WHERE reporter_id = ?", citizen);
        jdbc.update("DELETE FROM users WHERE id = ?", citizen);
    }

    // T8: the other request has saved its incident but not committed yet. This one waits for it,
    // then answers with that incident instead of creating a second one or failing on the unique
    // constraint.
    @Test
    void aRequestWaitsForTheOneWithTheSameKeyAndAnswersWithItsIncident() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<UUID> otherRequest =
                    threads.submit(
                            () ->
                                    transactions.execute(
                                            transaction -> {
                                                Incident incident =
                                                        reports.createIncident(
                                                                request(), citizen, key);
                                                saved.countDown();
                                                awaitUninterruptibly(commit);
                                                return incident.getId();
                                            }));
            assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResultActions> thisRequest = threads.submit(() -> report(key.toString()));
            waitUntilAQueryWaitsForALock();
            commit.countDown();

            UUID first = otherRequest.get(10, TimeUnit.SECONDS);
            thisRequest
                    .get(10, TimeUnit.SECONDS)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(first.toString()));
        }
        assertThat(incidentsOfTheCitizen()).isEqualTo(1);
    }

    // T7: the first request fails with 500 before its incident is saved; its transaction rolls
    // back with the key, so the retry with the same key creates the incident once.
    @Test
    void aRetryAfterAServerErrorCreatesTheIncidentOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        Mockito.doThrow(new DataAccessResourceFailureException("The database went away"))
                .when(incidents)
                .saveAndFlush(any(Incident.class));

        report(key).andExpect(status().isInternalServerError());
        assertThat(incidentsOfTheCitizen()).isZero();

        // A spied repository is an interface proxy: reset brings back its delegation to the real
        // one, which doCallRealMethod cannot reach.
        Mockito.reset(incidents);
        report(key).andExpect(status().isCreated());
        report(key).andExpect(status().isCreated());

        assertThat(incidentsOfTheCitizen()).isEqualTo(1);
    }

    private static CreateIncidentRequest request() {
        return new CreateIncidentRequest(
                TITLE, DESCRIPTION, null, new ReportedLocation(LATITUDE, LONGITUDE, null));
    }

    private ResultActions report(String key) throws Exception {
        String body =
                """
                {"title": "%s", "description": "%s",
                 "location": {"latitude": %s, "longitude": %s}}
                """
                        .formatted(TITLE, DESCRIPTION, LATITUDE, LONGITUDE);
        return mvc.perform(
                post("/api/v1/incidents")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(citizen, Role.CITIZEN))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private int incidentsOfTheCitizen() {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM incident WHERE reporter_id = ?",
                        Integer.class,
                        citizen);
        return count == null ? 0 : count;
    }

    /** Gives up after 5 seconds, so a request that never waits makes the test fail, not hang. */
    private void waitUntilAQueryWaitsForALock() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND datname = current_database()",
                                Integer.class)
                        == 0) {
            Thread.sleep(20);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
