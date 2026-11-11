package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of #63 for the user administration of #191: {@code listUsers}, {@code getUser},
 * {@code createUser} and {@code updateUser}. They cover T3 to T8, T11 and T13 to T15, T17 for these
 * endpoints, and the API side of T9 (the list and its filters). T10 is the contract, checked on
 * every response by {@link ApiContract}; T16 is in {@link UserConcurrentChangeIT}; T12 and T17 of
 * {@code GET /technicians} are in {@code TechnicianApiIT}.
 *
 * <p>Written from the issue, the contract ({@code docs/api/openapi.yml}) and {@code
 * docs/security.md}, not from the implementation. Users are inserted with SQL or created through
 * the API; a new account signs in through the real {@code POST /password-changes} and {@code POST
 * /sessions} (#165). Each test runs in a transaction that is rolled back, and the lists are always
 * filtered by a marker of the test, because other integration tests commit users.
 */
@SpringBootTest(properties = "urbanpulse.security.sign-in-attempts.max=1000")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class UserAdministrationApiIT {

    private static final String USERS = "/api/v1/users";
    private static final String SESSIONS = "/api/v1/sessions";
    private static final String REFRESH = "/api/v1/sessions/refresh";
    private static final String PASSWORD_CHANGES = "/api/v1/password-changes";
    private static final String AUDIT_ENTRIES = "/api/v1/audit-entries";

    private static final String GIVEN_PASSWORD = "Given-By-The-Admin-1";
    private static final String OWN_PASSWORD = "Only-I-Know-This-2";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;

    /** Part of every name and email of the test, so its lists never meet other users. */
    private String marker;

    private UUID administrator;

    @BeforeEach
    void createTheAdministrator() {
        marker = "m" + UUID.randomUUID().toString().substring(0, 8);
        administrator = user("Isabel Admin " + marker, Role.ADMINISTRATOR, true);
    }

    // T3: a valid staff account is created with 201, Location and the account, enabled.
    @Test
    void anAdministratorCreatesAStaffAccount() throws Exception {
        var email = "rosa." + marker + "@example.com";

        var result =
                create(body("Rosa Medina " + marker, email, GIVEN_PASSWORD, "TECHNICIAN"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.name").value("Rosa Medina " + marker))
                        .andExpect(jsonPath("$.email").value(email))
                        .andExpect(jsonPath("$.role").value("TECHNICIAN"))
                        .andExpect(jsonPath("$.enabled").value(true));
        var id = UUID.fromString(read(result, "$.id"));

        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith(USERS + "/" + id);
        adminGet(USERS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        assertThat(column(id, "password_change_required")).isEqualTo("true");
    }

    // T3: the new account must choose its own password (#165), then signs in with its role.
    @Test
    void theNewAccountSignsInWithItsRoleAfterChoosingItsPassword() throws Exception {
        var email = "rosa." + marker + "@example.com";
        create(body("Rosa Medina " + marker, email, GIVEN_PASSWORD, "ADMINISTRATOR"))
                .andExpect(status().isCreated());

        signIn(email, GIVEN_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        changePassword(email, GIVEN_PASSWORD, OWN_PASSWORD).andExpect(status().isNoContent());
        var access =
                read(signIn(email, OWN_PASSWORD).andExpect(status().isCreated()), "$.accessToken");

        mvc.perform(get(USERS + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMINISTRATOR"));
        // The token carries the role: an administrator reads the audit log.
        mvc.perform(get(AUDIT_ENTRIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
    }

    // T13: a blank name, a short password and the role CITIZEN, all in one answer, and no account.
    @Test
    void invalidDataIsRefusedWithEveryFieldError() throws Exception {
        var email = "bad." + marker + "@example.com";

        expectFieldErrors(
                create(body("   ", email, "short", "CITIZEN")), "name", "password", "role");
        expectFieldErrors(
                create(body("Ana " + marker, "not-an-email", GIVEN_PASSWORD, "ANALYST")), "email");
        expectFieldErrors(
                create(
                        "{\"name\":\"Ana\",\"email\":\""
                                + email
                                + "\",\"password\":\""
                                + GIVEN_PASSWORD
                                + "\"}"),
                "role");
        expectFieldErrors(create(body("a".repeat(101), email, GIVEN_PASSWORD, "ANALYST")), "name");

        assertThat(countUsers(email)).isZero();
    }

    // T8: an email already used, in any case, is 409 and creates nothing.
    @Test
    void aDuplicateEmailIsAConflict() throws Exception {
        var email = "dup." + marker + "@example.com";
        user("First " + marker, Role.ANALYST, true, email);

        create(body("Second " + marker, email.toUpperCase(), GIVEN_PASSWORD, "ANALYST"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));

        assertThat(countUsers(email)).isEqualTo(1);
    }

    // T4: a role change takes effect at the next refresh; the old access token keeps the old role.
    @Test
    void aRoleChangeTakesEffectAtTheNextRefresh() throws Exception {
        var account = signedInAccount(Role.TECHNICIAN);
        bearerGet(AUDIT_ENTRIES, account.access()).andExpect(status().isForbidden());

        update(account.id(), "{\"role\":\"ADMINISTRATOR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMINISTRATOR"));

        // Until the access token is refreshed, it carries the role it was issued with.
        bearerGet(AUDIT_ENTRIES, account.access()).andExpect(status().isForbidden());
        var renewed =
                read(
                        refresh(account.refresh()).andExpect(status().is2xxSuccessful()),
                        "$.accessToken");
        bearerGet(AUDIT_ENTRIES, renewed).andExpect(status().isOk());
    }

    // T4 and T15: a disabled account cannot sign in, and its refresh token no longer works.
    @Test
    void aDisabledAccountCannotSignInNorRefresh() throws Exception {
        var account = signedInAccount(Role.ANALYST);

        update(account.id(), "{\"enabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        // Disabling revokes the sessions at once, before anyone tries to use them.
        assertThat(activeRefreshTokens(account.id())).isZero();

        signIn(account.email(), OWN_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        refresh(account.refresh())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    // T11: a reactivated account signs in again, and the change is recorded as ENABLE_USER.
    @Test
    void aReactivatedAccountSignsInAgain() throws Exception {
        var account = signedInAccount(Role.ANALYST);
        update(account.id(), "{\"enabled\":false}").andExpect(status().isOk());

        update(account.id(), "{\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        signIn(account.email(), OWN_PASSWORD).andExpect(status().isCreated());
        assertThat(auditActions(account.id()))
                .containsExactlyInAnyOrder(
                        "CREATE_USER", "CHANGE_PASSWORD", "DISABLE_USER", "ENABLE_USER");
    }

    // T5: each action has its entry, with the administrator, the instant and before and after.
    @Test
    void eachActionIsAuditedWithItsActorAndValues() throws Exception {
        var email = "audit." + marker + "@example.com";
        var id =
                UUID.fromString(
                        read(
                                create(body("Pedro " + marker, email, GIVEN_PASSWORD, "TECHNICIAN"))
                                        .andExpect(status().isCreated()),
                                "$.id"));
        update(id, "{\"name\":\"Pedro Gómez " + marker + "\"}").andExpect(status().isOk());
        update(id, "{\"role\":\"MUNICIPAL_OPERATOR\"}").andExpect(status().isOk());
        update(id, "{\"enabled\":false}").andExpect(status().isOk());

        flush();
        var entries =
                jdbc.queryForList(
                        "SELECT action, actor_id, instant, target_type, details::text AS details"
                                + " FROM audit_log WHERE target_id = ? ORDER BY instant, action",
                        id);
        // One entry per kind of change; their instants may be equal, so their order is not checked.
        assertThat(entries)
                .extracting(entry -> entry.get("action"))
                .containsExactlyInAnyOrder(
                        "CREATE_USER", "UPDATE_USER", "CHANGE_ROLE", "DISABLE_USER");
        for (var entry : entries) {
            assertThat(entry.get("actor_id")).isEqualTo(administrator);
            assertThat(entry.get("instant")).isNotNull();
            assertThat(entry.get("target_type")).isEqualTo("USER");
        }
        assertThat(details(entries, "CREATE_USER"))
                .contains("\"before\": {}")
                .contains("\"role\": \"TECHNICIAN\"")
                .contains("\"passwordChangeRequired\": true");
        assertThat(details(entries, "CHANGE_ROLE"))
                .contains("\"before\": {\"role\": \"TECHNICIAN\"}")
                .contains("\"after\": {\"role\": \"MUNICIPAL_OPERATOR\"}");
        assertThat(details(entries, "DISABLE_USER"))
                .contains("\"before\": {\"enabled\": true}")
                .contains("\"after\": {\"enabled\": false}");
        // docs/security.md keeps names, emails and passwords out of the trail.
        var everything = entries.stream().map(entry -> (String) entry.get("details")).toList();
        assertThat(String.join(" ", everything))
                .doesNotContain("Pedro")
                .doesNotContain(email)
                .doesNotContain(GIVEN_PASSWORD)
                .doesNotContainIgnoringCase("hash");
    }

    // T5: a body that changes nothing answers 200 and records nothing.
    @Test
    void aChangeThatChangesNothingRecordsNothing() throws Exception {
        var id = user("Laura " + marker, Role.ANALYST, true);

        update(id, "{}").andExpect(status().isOk());
        update(id, "{\"name\":\"Laura " + marker + "\",\"role\":\"ANALYST\",\"enabled\":true}")
                .andExpect(status().isOk());

        assertThat(auditActions(id)).isEmpty();
    }

    // T8: administrators cannot demote or disable themselves; nothing changes.
    @Test
    void administratorsCannotDemoteNorDisableThemselves() throws Exception {
        expectFieldErrors(update(administrator, "{\"role\":\"ANALYST\"}"), "role");
        expectFieldErrors(update(administrator, "{\"enabled\":false}"), "enabled");
        expectFieldErrors(
                update(administrator, "{\"role\":\"ANALYST\",\"enabled\":false}"),
                "role",
                "enabled");

        assertThat(column(administrator, "role")).isEqualTo("ADMINISTRATOR");
        assertThat(column(administrator, "enabled")).isEqualTo("true");
        assertThat(auditActions(administrator)).isEmpty();
        // Renaming themselves is allowed.
        update(administrator, "{\"name\":\"Isabel Romero " + marker + "\"}")
                .andExpect(status().isOk());
    }

    // T8 and T13: a role change from or to CITIZEN, and a blank name, are 422 and change nothing.
    @Test
    void theRulesOfAChangeAreRefusedWithoutChangingAnything() throws Exception {
        var citizen = user("Ana " + marker, Role.CITIZEN, true);
        var analyst = user("Diego " + marker, Role.ANALYST, true);

        expectFieldErrors(update(citizen, "{\"role\":\"MUNICIPAL_OPERATOR\"}"), "role");
        expectFieldErrors(update(analyst, "{\"role\":\"CITIZEN\"}"), "role");
        expectFieldErrors(update(analyst, "{\"name\":\"   \"}"), "name");
        expectFieldErrors(update(analyst, "{\"name\":\"  \"}"), "name");

        assertThat(column(citizen, "role")).isEqualTo("CITIZEN");
        assertThat(column(analyst, "role")).isEqualTo("ANALYST");
        assertThat(column(analyst, "name")).isEqualTo("Diego " + marker);
        // A citizen account can still be disabled by an administrator.
        update(citizen, "{\"enabled\":false}").andExpect(status().isOk());
    }

    // T6: every other role gets 403 on every operation, whatever body or query it sends.
    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ANALYST", "CITIZEN"})
    void everyOtherRoleIsForbidden(Role role) throws Exception {
        var caller = user("Caller " + marker, role, true);
        var target = user("Target " + marker, Role.ANALYST, true);
        var bearer = tokens.bearer(caller, role);

        var requests =
                Map.of(
                        "list", get(USERS),
                        "list with an invalid query",
                                get(USERS).param("size", "500").param("unknown", "x"),
                        "read", get(USERS + "/" + target),
                        "create with an invalid body",
                                post(USERS)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body("X", "x", "1", "CITIZEN")),
                        "promote",
                                patch(USERS + "/" + target)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"role\":\"ADMINISTRATOR\"}"),
                        "rename with an invalid body",
                                patch(USERS + "/" + target)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"\"}"));
        for (var request : requests.entrySet()) {
            var response =
                    mvc.perform(request.getValue().header(HttpHeaders.AUTHORIZATION, bearer))
                            .andReturn()
                            .getResponse();
            assertThat(response.getStatus()).as(request.getKey()).isEqualTo(403);
            assertThat(response.getContentType())
                    .as(request.getKey())
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
        assertThat(column(target, "role")).isEqualTo("ANALYST");
        assertThat(auditActions(target)).isEmpty();
    }

    // T7: no response holds the hash or the password, nor a field for them.
    @Test
    void noResponseExposesThePasswordOrItsHash() throws Exception {
        var email = "pedro." + marker + "@example.com";
        var created =
                create(body("Pedro " + marker, email, GIVEN_PASSWORD, "ANALYST"))
                        .andExpect(status().isCreated());
        var id = UUID.fromString(read(created, "$.id"));
        var hash = column(id, "password_hash");

        var bodies = new ArrayList<String>();
        bodies.add(created.andReturn().getResponse().getContentAsString());
        bodies.add(text(adminGet(USERS + "/" + id)));
        bodies.add(text(adminGet(USERS + "?email=" + marker)));
        bodies.add(text(update(id, "{\"role\":\"TECHNICIAN\"}")));
        bodies.add(
                text(
                        mvc.perform(
                                get(USERS + "/me")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(
                                                        administrator, Role.ADMINISTRATOR)))));

        for (var body : bodies) {
            assertThat(body)
                    .doesNotContain(hash)
                    .doesNotContain(GIVEN_PASSWORD)
                    .doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("hash");
        }
    }

    // T9 (API side): the filters contain the text ignoring case, the roles repeat, and they
    // combine.
    @Test
    void theListFiltersByNameEmailAndRoles() throws Exception {
        var rosa = user("Rosa MEDINA " + marker, Role.TECHNICIAN, true);
        var pedro = user("Pedro Gómez " + marker, Role.MUNICIPAL_OPERATOR, true);
        var off = user("Diego Medina " + marker, Role.ANALYST, false);

        assertThat(ids("name", "medina " + marker.toUpperCase()))
                .containsExactlyInAnyOrder(rosa, off);
        assertThat(ids("email", marker, "role", "TECHNICIAN", "role", "MUNICIPAL_OPERATOR"))
                .containsExactlyInAnyOrder(rosa, pedro);
        assertThat(ids("email", marker, "role", "ANALYST")).containsExactly(off);
        // Disabled accounts are listed too.
        assertThat(ids("email", marker)).contains(off, administrator);
        // A wildcard is searched as typed.
        assertThat(ids("name", "%" + marker)).isEmpty();
        assertThat(ids("name", "_" + marker)).isEmpty();
    }

    // T9 (API side): by name by default, by email on request, and every user once across pages. The
    // users are written in an order that is not the expected one, so a lost sort fails.
    @Test
    void theListIsSortedAndStableAcrossPages() throws Exception {
        var zoe = user("Zoe " + marker, Role.ANALYST, true, "a." + marker + "@example.com");
        var alba = user("Alba " + marker, Role.ANALYST, true, "c." + marker + "@example.com");
        var mario = user("Mario " + marker, Role.ANALYST, true, "b." + marker + "@example.com");

        assertThat(ids("email", marker, "role", "ANALYST")).containsExactly(alba, mario, zoe);
        assertThat(ids("email", marker, "role", "ANALYST", "sort", "email,asc"))
                .containsExactly(zoe, mario, alba);
        assertThat(ids("email", marker, "role", "ANALYST", "sort", "name,desc"))
                .containsExactly(zoe, mario, alba);

        var paged = new ArrayList<UUID>();
        for (var page = 0; page < 3; page++) {
            paged.addAll(ids("email", marker, "role", "ANALYST", "size", "1", "page", "" + page));
        }
        assertThat(paged).containsExactly(alba, mario, zoe);
    }

    // T9 (API side): an unknown parameter or value is 400.
    @Test
    void anInvalidQueryIsABadRequest() throws Exception {
        for (var query :
                List.of(
                        "unknown=x",
                        "role=KING",
                        "size=101",
                        "size=0",
                        "page=-1",
                        "sort=role",
                        "name=")) {
            adminGet(USERS + "?" + query).andExpect(status().isBadRequest());
        }
    }

    // T14: an unknown user is 404; an id that is not a UUID is 400.
    @Test
    void anUnknownUserIsNotFound() throws Exception {
        var unknown = UUID.randomUUID();

        adminGet(USERS + "/" + unknown).andExpect(status().isNotFound());
        update(unknown, "{\"name\":\"Nobody\"}").andExpect(status().isNotFound());
        adminGet(USERS + "/not-a-uuid").andExpect(status().isBadRequest());
        mvc.perform(
                        patch(USERS + "/not-a-uuid")
                                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Nobody\"}"))
                .andExpect(status().isBadRequest());
    }

    // T17: every endpoint without a token is 401.
    @Test
    void withoutATokenEveryEndpointIsUnauthorized() throws Exception {
        var target = user("Target " + marker, Role.ANALYST, true);

        for (var request :
                List.of(
                        get(USERS),
                        get(USERS + "/" + target),
                        post(USERS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                "X " + marker,
                                                "x." + marker + "@example.com",
                                                GIVEN_PASSWORD,
                                                "ANALYST")),
                        patch(USERS + "/" + target)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false}"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertThat(column(target, "enabled")).isEqualTo("true");
    }

    // --- helpers ---

    private record SignedIn(UUID id, String email, String access, String refresh) {}

    /** An account created by the administrator, with its own password, and signed in. */
    private SignedIn signedInAccount(Role role) throws Exception {
        var email = role.name().toLowerCase() + "." + marker + "@example.com";
        var id =
                UUID.fromString(
                        read(
                                create(
                                                body(
                                                        "Account " + marker,
                                                        email,
                                                        GIVEN_PASSWORD,
                                                        role.name()))
                                        .andExpect(status().isCreated()),
                                "$.id"));
        changePassword(email, GIVEN_PASSWORD, OWN_PASSWORD).andExpect(status().isNoContent());
        var session = signIn(email, OWN_PASSWORD).andExpect(status().isCreated());
        return new SignedIn(
                id, email, read(session, "$.accessToken"), read(session, "$.refreshToken"));
    }

    private String adminBearer() {
        return tokens.bearer(administrator, Role.ADMINISTRATOR);
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, adminBearer()));
    }

    private ResultActions bearerGet(String path, String accessToken) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(
                post(USERS)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions update(UUID userId, String body) throws Exception {
        return mvc.perform(
                patch(USERS + "/" + userId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions signIn(String email, String password) throws Exception {
        return mvc.perform(
                json(
                        post(SESSIONS),
                        "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions changePassword(String email, String current, String next)
            throws Exception {
        return mvc.perform(
                json(
                        post(PASSWORD_CHANGES),
                        "{\"email\":\"%s\",\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                .formatted(email, current, next)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                json(post(REFRESH), "{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private static MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<UUID> ids(String... nameValuePairs) throws Exception {
        var request = get(USERS).header(HttpHeaders.AUTHORIZATION, adminBearer());
        for (var i = 0; i < nameValuePairs.length; i += 2) {
            request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        List<String> ids =
                JsonPath.read(
                        text(mvc.perform(request).andExpect(status().isOk())), "$.content[*].id");
        return ids.stream().map(UUID::fromString).toList();
    }

    private static void expectFieldErrors(ResultActions result, String... fields) throws Exception {
        result.andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        List<String> reported = JsonPath.read(text(result), "$.errors[*].field");
        assertThat(reported).containsExactlyInAnyOrder(fields);
    }

    private static String body(String name, String email, String password, String role) {
        return "{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\",\"role\":\"%s\"}"
                .formatted(name, email, password, role);
    }

    private static String text(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(text(result), path);
    }

    private static String details(List<Map<String, Object>> entries, String action) {
        return entries.stream()
                .filter(entry -> action.equals(entry.get("action")))
                .map(entry -> (String) entry.get("details"))
                .findFirst()
                .orElseThrow();
    }

    private UUID user(String name, Role role, boolean enabled) {
        return user(
                name,
                role,
                enabled,
                // At most 64 characters before the @, as RFC 5321 and the contract ask.
                "u" + UUID.randomUUID().toString().substring(0, 8) + "." + marker + "@example.com");
    }

    private UUID user(String name, Role role, boolean enabled, String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role, enabled) VALUES (?, ?, ?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                name,
                email,
                passwordEncoder.encode(GIVEN_PASSWORD),
                role.name(),
                enabled);
    }

    /**
     * The requests run in the transaction of the test, so what Hibernate has not written yet is
     * written before the database is read with SQL.
     */
    private void flush() {
        entityManager.flush();
    }

    private String column(UUID user, String column) {
        flush();
        return jdbc.queryForObject(
                "SELECT " + column + "::text FROM users WHERE id = ?", String.class, user);
    }

    private int countUsers(String email) {
        flush();
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
    }

    private int activeRefreshTokens(UUID user) {
        flush();
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                user);
    }

    private List<String> auditActions(UUID target) {
        flush();
        return jdbc.queryForList(
                "SELECT action FROM audit_log WHERE target_id = ? ORDER BY instant, action",
                String.class,
                target);
    }
}
