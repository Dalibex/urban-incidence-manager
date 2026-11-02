package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.UrbanpulseApplication;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests of #139 for the one-off process that creates the first administrator (#126).
 * They only use its interface, as the issue describes it: the {@code bootstrap-admin} profile,
 * added with {@code --spring.profiles.include}, and the {@code BOOTSTRAP_ADMIN_*} values.
 *
 * <p>This class's own context is a normal start of the backend with the three values set, which is
 * T7, and gives MockMvc for signing in. Each run of the process starts a separate application with
 * the profile against the same database, as {@code docker compose run} does, so it can run twice
 * and with invalid values, and its output can be captured. Values come as command-line arguments,
 * which win over a developer's {@code backend/.env}.
 */
@SpringBootTest(
        properties = {
            "BOOTSTRAP_ADMIN_NAME=Never Created",
            "BOOTSTRAP_ADMIN_EMAIL=" + FirstAdministratorIT.NORMAL_START_EMAIL,
            "BOOTSTRAP_ADMIN_PASSWORD=Never-Created-Passw0rd"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class FirstAdministratorIT {

    static final String NORMAL_START_EMAIL = "never.created@example.com";

    private static final String PROFILE = "bootstrap-admin";
    private static final String NAME = "Isabel Romero";
    private static final String PASSWORD = "First-Admin-Passw0rd";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    /** Each test starts with an email of its own and no enabled administrator. */
    private String email;

    @BeforeEach
    void noEnabledAdministrator() {
        // Accounts cannot be deleted (the audit trail refers to them), so earlier ones are
        // disabled.
        jdbc.update("UPDATE users SET enabled = false WHERE role = 'ADMINISTRATOR'");
        email = "Root." + UUID.randomUUID() + "@Example.COM";
    }

    // T1 and T8
    @Test
    void createsTheFirstAdministrator(CapturedOutput output) {
        bootstrap(values(NAME, email, PASSWORD));

        var user =
                jdbc.queryForMap(
                        "SELECT id, name, email, role, enabled, password_hash FROM users"
                                + " WHERE email = ?",
                        email.toLowerCase());
        assertThat(user)
                .containsEntry("name", NAME)
                .containsEntry("role", "ADMINISTRATOR")
                .containsEntry("enabled", true);
        assertThat((String) user.get("password_hash"))
                .startsWith("{bcrypt}")
                .doesNotContain(PASSWORD);

        var userId = (UUID) user.get("id");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_log WHERE action = 'CREATE_USER'"
                                        + " AND actor_id = ? AND target_id = ?"
                                        + " AND details -> 'after'"
                                        + " = '{\"role\": \"ADMINISTRATOR\", \"origin\": \"BOOTSTRAP\"}'::jsonb",
                                Integer.class,
                                userId,
                                userId))
                .isOne();
        assertThat(output).doesNotContain(PASSWORD);
    }

    // T2
    @Test
    void theNewAdministratorSignsIn() throws Exception {
        bootstrap(values(NAME, email, PASSWORD));

        var session =
                mvc.perform(
                                post("/api/v1/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"email": "%s", "password": "%s"}
                                                """
                                                        .formatted(email, PASSWORD)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        mvc.perform(
                        get("/api/v1/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + JsonPath.read(session, "$.accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMINISTRATOR"))
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
    }

    // T3 and T8
    @Test
    void runningItAgainChangesNothing(CapturedOutput output) {
        bootstrap(values(NAME, email, PASSWORD));
        var users = users();
        var auditEntries = auditEntries();

        bootstrap(values(NAME, email, PASSWORD));
        var otherEmail = "Other." + UUID.randomUUID() + "@example.com";
        bootstrap(values("Someone Else", otherEmail, "Another-Passw0rd-2"));

        assertThat(users()).isEqualTo(users);
        assertThat(auditEntries()).isEqualTo(auditEntries);
        assertThat(countUsers(otherEmail)).isZero();
        assertThat(output.getAll().toLowerCase()).contains("already exists");
        assertThat(output).doesNotContain(PASSWORD).doesNotContain("Another-Passw0rd-2");
    }

    // T4 and T8
    @Test
    void rejectsAPasswordThatSignUpRejects(CapturedOutput output) {
        var auditEntries = auditEntries();
        var tooShort = "Short-7";
        var tooLong = "Long-Passw0rd-" + "x".repeat(72);

        assertThat(failureOf(values(NAME, email, tooShort))).contains("password");
        assertThat(failureOf(values(NAME, email, tooLong))).contains("password");

        assertThat(countUsers(email)).isZero();
        assertThat(auditEntries()).isEqualTo(auditEntries);
        assertThat(output).doesNotContain(tooShort).doesNotContain(tooLong);
    }

    // T5
    @Test
    void rejectsOtherInvalidValues() {
        var withoutPassword = values(NAME, email, PASSWORD);
        withoutPassword.remove("BOOTSTRAP_ADMIN_PASSWORD");

        assertThat(failureOf(values(" ", email, PASSWORD))).contains("name");
        assertThat(failureOf(values(NAME, "not-an-email", PASSWORD))).contains("email");
        assertThat(failureOf(withoutPassword)).contains("password");

        assertThat(countUsers(email)).isZero();
        assertThat(countUsers("not-an-email")).isZero();
    }

    // T6: an account that already has the email is never promoted.
    @Test
    void rejectsTheEmailOfAnotherAccount() {
        var citizenId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash, role) VALUES (?, 'Ana', ?,"
                        + " 'citizen-hash', 'CITIZEN')",
                citizenId,
                email.toLowerCase());
        var auditEntries = auditEntries();

        assertThat(failureOf(values(NAME, email, PASSWORD))).isNotEmpty();

        assertThat(
                        jdbc.queryForMap(
                                "SELECT role, password_hash, enabled FROM users WHERE id = ?",
                                citizenId))
                .containsEntry("role", "CITIZEN")
                .containsEntry("password_hash", "citizen-hash")
                .containsEntry("enabled", true);
        assertThat(auditEntries()).isEqualTo(auditEntries);
    }

    // T7: this class's context started normally with the three values set.
    @Test
    void aNormalStartDoesNotRunIt() {
        assertThat(countUsers(NORMAL_START_EMAIL)).isZero();
    }

    private static Map<String, String> values(String name, String email, String password) {
        var values = new LinkedHashMap<String, String>();
        values.put("BOOTSTRAP_ADMIN_NAME", name);
        values.put("BOOTSTRAP_ADMIN_EMAIL", email);
        values.put("BOOTSTRAP_ADMIN_PASSWORD", password);
        return values;
    }

    /** Runs the process against this class's database, as {@code docker compose run} would. */
    private void bootstrap(Map<String, String> values) {
        var database = (HikariDataSource) dataSource;
        var args = new ArrayList<String>();
        args.add("--spring.profiles.include=" + PROFILE);
        args.add("--spring.datasource.url=" + database.getJdbcUrl());
        args.add("--spring.datasource.username=" + database.getUsername());
        args.add("--spring.datasource.password=" + database.getPassword());
        args.add("--spring.docker.compose.enabled=false");
        // Until the profile turns the web server off, a free port keeps the run from failing
        // because a backend already listens on 8080.
        args.add("--server.port=0");
        values.forEach((key, value) -> args.add("--" + key + "=" + value));
        try (var process =
                new SpringApplicationBuilder(UrbanpulseApplication.class)
                        .run(args.toArray(String[]::new))) {
            assertThat(process.getEnvironment().getActiveProfiles()).contains(PROFILE);
        }
    }

    /**
     * The messages of a failed run, in lower case, without the stack frames: the reason must name
     * the field, and a frame such as {@code PasswordEncoder} would hide a missing one.
     */
    private String failureOf(Map<String, String> values) {
        var failure = catchThrowable(() -> bootstrap(values));
        assertThat(failure).as("the start must fail").isNotNull();
        var messages = new ArrayList<String>();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return String.join(" | ", messages).toLowerCase();
    }

    private int countUsers(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email.toLowerCase());
    }

    private List<Map<String, Object>> users() {
        return jdbc.queryForList(
                "SELECT id, name, email, role, enabled, password_hash FROM users ORDER BY id");
    }

    private List<Map<String, Object>> auditEntries() {
        return jdbc.queryForList("SELECT id, action, target_id FROM audit_log ORDER BY id");
    }
}
