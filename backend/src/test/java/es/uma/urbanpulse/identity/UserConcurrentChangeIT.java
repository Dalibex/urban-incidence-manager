package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * T16 of #63: two administrators change the same user at once (#191). Both changes are applied one
 * after the other, and the {@code before} of the second audit entry is the {@code after} of the
 * first.
 *
 * <p>The two requests must really overlap, so the test holds a lock on the user row in a
 * transaction of its own, sends both requests, waits until both are blocked on that lock, and only
 * then lets them go. That needs committed transactions, so this class is not rolled back: its users
 * have emails of their own and stay in the database, as in {@code AuditTrailIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class UserConcurrentChangeIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private TransactionTemplate transaction;

    @Test
    void twoChangesAtOnceAreAppliedOneAfterTheOther() throws Exception {
        var first = user(Role.ADMINISTRATOR);
        var second = user(Role.ADMINISTRATOR);
        var target = user(Role.TECHNICIAN);

        var bothWaiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder =
                CompletableFuture.runAsync(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            jdbc.queryForList(
                                                    "SELECT id FROM users WHERE id = ? FOR UPDATE",
                                                    target);
                                            bothWaiting.countDown();
                                            await(release);
                                        }));
        await(bothWaiting);

        var toAnalyst = CompletableFuture.supplyAsync(() -> changeRole(first, target, "ANALYST"));
        var toOperator =
                CompletableFuture.supplyAsync(
                        () -> changeRole(second, target, "MUNICIPAL_OPERATOR"));
        waitUntilBlocked(2);
        release.countDown();
        holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(toAnalyst.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(toOperator.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(200);

        var entries =
                jdbc.queryForList(
                        "SELECT details -> 'before' ->> 'role' AS before_role,"
                                + " details -> 'after' ->> 'role' AS after_role"
                                + " FROM audit_log WHERE target_id = ? AND action = 'CHANGE_ROLE'"
                                + " ORDER BY instant",
                        target);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("before_role")).isEqualTo("TECHNICIAN");
        // The second change saw the result of the first.
        assertThat(entries.get(1).get("before_role")).isEqualTo(entries.get(0).get("after_role"));
        assertThat(List.of(entries.get(0).get("after_role"), entries.get(1).get("after_role")))
                .containsExactlyInAnyOrder("ANALYST", "MUNICIPAL_OPERATOR");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, target))
                .isEqualTo(entries.get(1).get("after_role"));
    }

    private int changeRole(UUID administrator, UUID target, String role) {
        try {
            return mvc.perform(
                            patch("/api/v1/users/" + target)
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            tokens.bearer(administrator, Role.ADMINISTRATOR))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"role\":\"" + role + "\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until this many sessions of the database wait for a row lock. */
    private void waitUntilBlocked(int sessions) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var blocked =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
                            Integer.class);
            if (blocked != null && blocked >= sessions) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The two changes never waited for the lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, '{bcrypt}x', ?)"
                        + " RETURNING id",
                UUID.class,
                "Concurrent " + role,
                "c" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                role.name());
    }
}
