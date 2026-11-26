package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #373: a change of the notification channels must not undo a change of the same account that
 * commits while it runs, such as an administrator disabling it or a password change. The {@code
 * PATCH} writes the whole row of {@code users}, so it must read that row locked.
 *
 * <p>The test pauses the real {@code PATCH} with no production seam: a side transaction disables
 * the account and does not commit, so the {@code PATCH} waits on the row lock (on its write if it
 * read the row unlocked, on its read if it locked it). Once it waits, the side transaction commits
 * and the {@code PATCH} goes on. Each test commits; its citizen keeps a random email, as in {@link
 * PushTokensOfEndedSessionsIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@DisplayName("Notification settings changed while the account changes")
class NotificationSettingsConcurrencyIT {

    private static final String SETTINGS = "/api/v1/users/me/notification-settings";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private TransactionTemplate transactions;

    @Test
    @DisplayName("a disable that commits during the PATCH is kept, and so is the new channel")
    void aDisableThatCommitsDuringThePatchIsKept() throws Exception {
        // Arrange
        UUID citizen = citizen();
        var disabledUncommitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> disable =
                    threads.submit(
                            () ->
                                    transactions.executeWithoutResult(
                                            transaction -> {
                                                jdbc.update(
                                                        "UPDATE users SET enabled = false"
                                                                + " WHERE id = ?",
                                                        citizen);
                                                disabledUncommitted.countDown();
                                                awaitUninterruptibly(release);
                                            }));
            disabledUncommitted.await(10, TimeUnit.SECONDS);
            Future<Integer> settingsChange =
                    threads.submit(
                            () ->
                                    mvc.perform(
                                                    patch(SETTINGS)
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content("{\"push\": true}")
                                                            .header(
                                                                    HttpHeaders.AUTHORIZATION,
                                                                    tokens.bearer(
                                                                            citizen, Role.CITIZEN)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            waitUntilQueriesWaitForALock(1);

            // Act
            release.countDown();
            disable.get(10, TimeUnit.SECONDS);
            int changed = settingsChange.get(10, TimeUnit.SECONDS);

            // Assert
            assertThat(changed).isEqualTo(200);
            assertThat(stored(citizen))
                    .containsEntry("enabled", false)
                    .containsEntry("notify_by_push", true);
        }
    }

    private Map<String, Object> stored(UUID user) {
        return jdbc.queryForMap("SELECT enabled, notify_by_push FROM users WHERE id = ?", user);
    }

    private UUID citizen() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                "Test citizen",
                "citizen-" + UUID.randomUUID() + "@example.com",
                Role.CITIZEN.name());
    }

    /** Gives up after 5 seconds, so a request that never waits makes the test fail, not hang. */
    private void waitUntilQueriesWaitForALock(int queries) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND datname = current_database()",
                                Integer.class)
                        < queries) {
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
