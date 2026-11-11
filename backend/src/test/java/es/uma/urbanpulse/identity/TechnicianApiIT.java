package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of #63 for {@code listTechnicians} (#190): T12 and T17 of this endpoint. Every
 * response is checked against the contract ({@link ApiContract}).
 *
 * <p>Written from the issue and the contract ({@code docs/api/openapi.yml}), not from the
 * implementation: users are inserted with SQL and every check goes through HTTP.
 *
 * <p>Other integration tests commit users that stay in the database, so the list is never compared
 * with a list made up here: it is compared with the enabled technicians the database holds, and the
 * users of each test check what must and must not be in it. Each test runs in a transaction that is
 * rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class TechnicianApiIT {

    private static final String TECHNICIANS = "/api/v1/technicians";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID operator;
    private UUID administrator;
    private UUID enabledTechnician;
    private UUID disabledTechnician;

    @BeforeEach
    void createUsers() {
        operator = user("Raúl Operator", Role.MUNICIPAL_OPERATOR, true);
        administrator = user("Isabel Administrator", Role.ADMINISTRATOR, true);
        enabledTechnician = user("Sergio Technician", Role.TECHNICIAN, true);
        disabledTechnician = user("Marta Former Technician", Role.TECHNICIAN, false);
    }

    // T12: operators and administrators get the enabled technicians, as the database holds them,
    // ordered by name.
    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"MUNICIPAL_OPERATOR", "ADMINISTRATOR"})
    void staffGetTheEnabledTechniciansOrderedByName(Role role) throws Exception {
        var caller = role == Role.ADMINISTRATOR ? administrator : operator;

        var ids = idsIn(list(caller, role).andExpect(status().isOk()));

        assertThat(ids).containsExactlyElementsOf(enabledTechniciansInTheDatabase());
        assertThat(ids).contains(enabledTechnician);
    }

    // T12: a disabled technician and the users of other roles are never listed.
    @Test
    void neitherDisabledTechniciansNorOtherRolesAreListed() throws Exception {
        var citizen = user("Ana Citizen", Role.CITIZEN, true);
        var analyst = user("Diego Analyst", Role.ANALYST, true);

        var ids = idsIn(list(operator, Role.MUNICIPAL_OPERATOR).andExpect(status().isOk()));

        assertThat(ids)
                .doesNotContain(disabledTechnician, operator, administrator, citizen, analyst);
        for (var id : ids) {
            assertThat(roleAndEnabledOf(id)).as(id.toString()).isEqualTo("TECHNICIAN true");
        }
    }

    // The contract orders the list by name. The technicians are written in an order that is neither
    // the order of their names nor the order of their ids, so the test fails if the list comes in
    // the order of the rows, by id, or in any order but the name.
    @Test
    void theListIsOrderedByName() throws Exception {
        var written = new ArrayList<UUID>();
        for (var name : List.of("Zoe", "Carmen", "Pablo", "Alba", "Teresa", "Hugo")) {
            // Ids in reverse alphabetical order of the names: Zoe has the smallest one.
            var rank = "ZTPHCA".indexOf(name.charAt(0)) + 1;
            written.add(
                    user(
                            UUID.fromString("f0000000-0000-4000-8000-00000000000" + rank),
                            name + " Technician"));
        }
        var byName = written.stream().sorted(Comparator.comparing(id -> nameOf(id))).toList();

        var ids = idsIn(list(operator, Role.MUNICIPAL_OPERATOR).andExpect(status().isOk()));

        assertThat(ids.stream().filter(written::contains).toList())
                .containsExactlyElementsOf(byName);
    }

    // T12: technicians with the same name keep one order on every request.
    @Test
    void techniciansWithTheSameNameKeepOneOrder() throws Exception {
        var first = user("Ángel Twin", Role.TECHNICIAN, true);
        var second = user("Ángel Twin", Role.TECHNICIAN, true);

        var once = idsIn(list(operator, Role.MUNICIPAL_OPERATOR));
        var twice = idsIn(list(administrator, Role.ADMINISTRATOR));

        assertThat(once).isEqualTo(twice);
        // The id breaks the tie as the database orders it, which is not the order of UUID in Java.
        var byId =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE id IN (?, ?) ORDER BY id",
                        UUID.class,
                        first,
                        second);
        assertThat(once.stream().filter(byId::contains).toList()).isEqualTo(byId);
    }

    // T12: each technician has its id and its name, and nothing else: no email and no hash.
    @Test
    void eachTechnicianHasOnlyItsIdAndName() throws Exception {
        var body =
                list(operator, Role.MUNICIPAL_OPERATOR)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<Map<String, Object>> technicians = JsonPath.read(body, "$");
        var sergio =
                technicians.stream()
                        .filter(item -> enabledTechnician.toString().equals(item.get("id")))
                        .findFirst()
                        .orElseThrow();
        assertThat(sergio)
                .containsOnlyKeys("id", "name")
                .containsEntry("name", "Sergio Technician");
        for (var technician : technicians) {
            assertThat(technician.keySet()).containsExactlyInAnyOrder("id", "name");
        }
        assertThat(body)
                .doesNotContain(emailOf(enabledTechnician), passwordHashOf(enabledTechnician));
    }

    // T12: technicians and citizens may not read the list; analysts neither (docs/security.md).
    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"TECHNICIAN", "CITIZEN", "ANALYST"})
    void otherRolesAreForbidden(Role role) throws Exception {
        var caller = role == Role.TECHNICIAN ? enabledTechnician : user("Caller", role, true);

        list(caller, role)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    // T17: without a token, or with one that is not valid.
    @Test
    void withoutAValidTokenItIsUnauthorized() throws Exception {
        mvc.perform(get(TECHNICIANS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$[0]").doesNotExist());
        mvc.perform(get(TECHNICIANS).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    private ResultActions list(UUID caller, Role role) throws Exception {
        return mvc.perform(
                get(TECHNICIANS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, role)));
    }

    private static List<UUID> idsIn(ResultActions result) throws Exception {
        List<String> ids =
                JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$[*].id");
        return ids.stream().map(UUID::fromString).toList();
    }

    /** The enabled technicians, in the order the contract asks: by name, then by id. */
    private List<UUID> enabledTechniciansInTheDatabase() {
        return jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'TECHNICIAN' AND enabled ORDER BY name, id",
                UUID.class);
    }

    private String roleAndEnabledOf(UUID user) {
        return jdbc.queryForObject(
                "SELECT role || ' ' || enabled FROM users WHERE id = ?", String.class, user);
    }

    private UUID user(String name, Role role, boolean enabled) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role, enabled) VALUES (?, ?, ?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                name,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}hash-" + UUID.randomUUID(),
                role.name(),
                enabled);
    }

    /** An enabled technician with a chosen id, so a test controls the order of the ids. */
    private UUID user(UUID id, String name) {
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                id,
                name,
                "technician-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}hash-" + UUID.randomUUID(),
                Role.TECHNICIAN.name());
        return id;
    }

    private String nameOf(UUID user) {
        return jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, user);
    }

    private String emailOf(UUID user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
    }

    private String passwordHashOf(UUID user) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, user);
    }
}
