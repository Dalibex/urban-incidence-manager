package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The whole <em>Permission Matrix</em> of {@code docs/security.md} in one place (#286): every
 * operation that needs a token, called by every role. A role the matrix does not list gets {@code
 * 403}; a role it lists gets anything but {@code 403} or {@code 401}. So a rule of {@link
 * ApiAuthorizationRules} widened to an extra role or narrowed to one fewer fails here.
 *
 * <p>The expected roles are copied from the document, not from the rules, so the test compares two
 * independent sources. The rows themselves are checked against the operations Spring MVC really
 * maps ({@link #everyOperationThatNeedsATokenHasItsRow}), so a new operation cannot be left out of
 * the matrix unnoticed. A cell that says "own" or "assigned" still allows the role: the service
 * narrows it with the data of the resource. To keep that check out of the way, every request is one
 * Spring MVC refuses before the service runs (a path id that is not a UUID, a body that is not
 * JSON, a page size over the limit, a parameter that does not convert), so an allowed role gets
 * {@code 400} and a {@code 403} can only come from the role rule. The few operations without any
 * input are sent as they are: none of them checks a resource. The user of a test token does not
 * exist, so {@code GET /users/me} answers {@code 401} to an allowed role (see {@link Outcome}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class RoleMatrixIT {

    private static final String API = "/api/v1";
    private static final String NOT_A_UUID = "not-a-uuid";
    private static final String NOT_JSON = "{";

    // The columns of the matrix, as docs/security.md writes them.
    private static final Set<Role> EVERY_ROLE = EnumSet.allOf(Role.class);
    private static final Set<Role> ADMINISTRATOR_ONLY = EnumSet.of(Role.ADMINISTRATOR);

    // The operations of the contract marked `security: []`, as SecurityConfig lists them.
    private static final Set<String> PUBLIC_OPERATIONS =
            Set.of(
                    "POST " + API + "/registrations",
                    "POST " + API + "/sessions",
                    "POST " + API + "/sessions/refresh",
                    "POST " + API + "/password-changes",
                    "POST " + API + "/mfa-enrollments",
                    "POST " + API + "/mfa-verifications");

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    enum Outcome {
        FORBIDDEN,
        NOT_FORBIDDEN,
        // The user of a test token does not exist, and GET /users/me answers a valid token of a
        // user that does not exist with 401 (CurrentUserController, docs/api/openapi.yml). That
        // 401 comes after the role rule let the request through, so only 403 tells a refusal.
        NOT_FORBIDDEN_UNKNOWN_USER
    }

    @ParameterizedTest(name = "{0} as {1}: {2}")
    @MethodSource("everyOperationAndRole")
    void eachRoleGetsWhatThePermissionMatrixSays(
            Supplier<AbstractMockHttpServletRequestBuilder<?>> operation,
            Role role,
            Outcome expected)
            throws Exception {
        var result = mvc.perform(operation.get().header(AUTHORIZATION, tokens.bearer(role)));

        if (expected == Outcome.FORBIDDEN) {
            result.andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403));
        } else if (expected == Outcome.NOT_FORBIDDEN) {
            assertThat(result.andReturn().getResponse().getStatus())
                    .as("an allowed role is neither refused nor taken as anonymous")
                    .isNotIn(HttpStatus.FORBIDDEN.value(), HttpStatus.UNAUTHORIZED.value());
        } else {
            assertThat(result.andReturn().getResponse().getStatus())
                    .as("an allowed role is not refused")
                    .isNotEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    /**
     * The rows are written by hand, so a new operation without its row would go untested here. This
     * compares them with every operation Spring MVC maps under {@code /api/v1}, except the public
     * ones: an operation without a row, or a row without an operation, fails.
     */
    @Test
    void everyOperationThatNeedsATokenHasItsRow() {
        var mapped =
                handlerMapping.getHandlerMethods().keySet().stream()
                        .flatMap(RoleMatrixIT::operationsOf)
                        .filter(operation -> operation.contains(" " + API + "/"))
                        .filter(operation -> !PUBLIC_OPERATIONS.contains(operation))
                        .collect(Collectors.toSet());
        var rows = matrix().map(row -> mappingOf(row.operation())).collect(Collectors.toSet());

        assertThat(rows)
                .as("the rows of the matrix and the operations Spring MVC maps under %s", API)
                .containsExactlyInAnyOrderElementsOf(mapped);
    }

    // "GET /api/v1/incidents/{}" for each method and path of a mapping.
    private static Stream<String> operationsOf(RequestMappingInfo mapping) {
        var methods = mapping.getMethodsCondition().getMethods();
        return mapping.getPatternValues().stream()
                .flatMap(
                        path ->
                                methods.isEmpty()
                                        ? Stream.of("ANY " + withoutVariableNames(path))
                                        : methods.stream()
                                                .map(m -> m + " " + withoutVariableNames(path)));
    }

    // "GET /incidents/{id}, id not a UUID" → "GET /api/v1/incidents/{}".
    private static String mappingOf(String operation) {
        var methodAndPath = operation.split(",")[0].split(" ");
        return methodAndPath[0] + " " + withoutVariableNames(API + methodAndPath[1]);
    }

    private static String withoutVariableNames(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    static Stream<Arguments> everyOperationAndRole() {
        return matrix().flatMap(RoleMatrixIT::casesForEveryRole);
    }

    // One row per operation of the matrix that needs a token, with the roles of its cells.
    private static Stream<Row> matrix() {
        var incident = API + "/incidents/" + NOT_A_UUID;
        var anyIncident = API + "/incidents/" + UUID.randomUUID();
        return Stream.of(
                // Sessions and users.
                row(
                        "DELETE /sessions/current",
                        () -> delete(API + "/sessions/current"),
                        EVERY_ROLE),
                rowOfTheCurrentUser("GET /users/me", () -> get(API + "/users/me"), EVERY_ROLE),
                // Notification channels of the citizen (#373).
                rowOfTheCurrentUser(
                        "GET /users/me/notification-settings",
                        () -> get(API + "/users/me/notification-settings"),
                        roles(Role.CITIZEN)),
                row(
                        "PATCH /users/me/notification-settings, body not JSON",
                        () -> notJson(patch(API + "/users/me/notification-settings")),
                        roles(Role.CITIZEN)),
                // The push token of the citizen's phone (#373).
                row(
                        "POST /push-tokens, body not JSON",
                        () -> notJson(post(API + "/push-tokens")),
                        roles(Role.CITIZEN)),
                row(
                        "POST /push-token-deletions, body not JSON",
                        () -> notJson(post(API + "/push-token-deletions")),
                        roles(Role.CITIZEN)),
                row(
                        "GET /users, size=101",
                        () -> get(API + "/users").param("size", "101"),
                        ADMINISTRATOR_ONLY),
                row(
                        "POST /users, body not JSON",
                        () -> notJson(post(API + "/users")),
                        ADMINISTRATOR_ONLY),
                row(
                        "GET /users/{id}, id not a UUID",
                        () -> get(API + "/users/" + NOT_A_UUID),
                        ADMINISTRATOR_ONLY),
                row(
                        "PATCH /users/{id}, id not a UUID",
                        () -> json(patch(API + "/users/" + NOT_A_UUID)),
                        ADMINISTRATOR_ONLY),
                row(
                        "POST /users/{id}/mfa-resets, id not a UUID",
                        () -> json(post(API + "/users/" + NOT_A_UUID + "/mfa-resets")),
                        ADMINISTRATOR_ONLY),
                // Catalogues.
                row("GET /categories", () -> get(API + "/categories"), EVERY_ROLE),
                row(
                        "GET /departments",
                        () -> get(API + "/departments"),
                        roles(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR)),
                row(
                        "GET /technicians",
                        () -> get(API + "/technicians"),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                // Incidents.
                row(
                        "GET /incidents, size=101",
                        () -> get(API + "/incidents").param("size", "101"),
                        roles(
                                Role.CITIZEN,
                                Role.MUNICIPAL_OPERATOR,
                                Role.TECHNICIAN,
                                Role.ADMINISTRATOR)),
                row(
                        "POST /incidents, body not JSON",
                        () -> notJson(post(API + "/incidents")),
                        roles(Role.CITIZEN)),
                row(
                        "GET /incidents/{id}, id not a UUID",
                        () -> get(incident),
                        roles(
                                Role.CITIZEN,
                                Role.MUNICIPAL_OPERATOR,
                                Role.TECHNICIAN,
                                Role.ADMINISTRATOR)),
                row(
                        "PATCH /incidents/{id}, body not JSON",
                        () -> notJson(patch(anyIncident)),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                row(
                        "GET /incidents/{id}/classification-changes, id not a UUID",
                        () -> get(incident + "/classification-changes"),
                        roles(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR)),
                row(
                        "GET /incidents/{id}/status-changes, id not a UUID",
                        () -> get(incident + "/status-changes"),
                        roles(
                                Role.CITIZEN,
                                Role.MUNICIPAL_OPERATOR,
                                Role.TECHNICIAN,
                                Role.ADMINISTRATOR)),
                // Lifecycle.
                row(
                        "POST /incidents/{id}/validation, body not JSON",
                        () -> notJson(post(anyIncident + "/validation")),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                row(
                        "POST /incidents/{id}/rejection, body not JSON",
                        () -> notJson(post(anyIncident + "/rejection")),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                row(
                        "POST /incidents/{id}/information-requests, body not JSON",
                        () -> notJson(post(anyIncident + "/information-requests")),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                row(
                        "POST /incidents/{id}/assignments, body not JSON",
                        () -> notJson(post(anyIncident + "/assignments")),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                row(
                        "POST /incidents/{id}/acceptance, body not JSON",
                        () -> notJson(post(anyIncident + "/acceptance")),
                        roles(Role.TECHNICIAN)),
                row(
                        "POST /incidents/{id}/resolution, body not JSON",
                        () -> notJson(post(anyIncident + "/resolution")),
                        roles(Role.TECHNICIAN)),
                row(
                        "POST /incidents/{id}/closure, body not JSON",
                        () -> notJson(post(anyIncident + "/closure")),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                row(
                        "POST /incidents/{id}/reopening, body not JSON",
                        () -> notJson(post(anyIncident + "/reopening")),
                        roles(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR)),
                // Evidence.
                row(
                        "GET /incidents/{id}/attachments, id not a UUID",
                        () -> get(incident + "/attachments"),
                        roles(
                                Role.CITIZEN,
                                Role.MUNICIPAL_OPERATOR,
                                Role.TECHNICIAN,
                                Role.ADMINISTRATOR)),
                row(
                        "POST /incidents/{id}/attachments, id not a UUID",
                        () -> multipart(incident + "/attachments").file(photo()),
                        roles(
                                Role.CITIZEN,
                                Role.MUNICIPAL_OPERATOR,
                                Role.TECHNICIAN,
                                Role.ADMINISTRATOR)),
                row(
                        "GET /attachments/{id}/content, id not a UUID",
                        () -> get(API + "/attachments/" + NOT_A_UUID + "/content"),
                        roles(
                                Role.CITIZEN,
                                Role.MUNICIPAL_OPERATOR,
                                Role.TECHNICIAN,
                                Role.ADMINISTRATOR)),
                // Collaboration and context.
                row(
                        "GET /incidents/{id}/comments, id not a UUID",
                        () -> get(incident + "/comments"),
                        roles(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR)),
                row(
                        "POST /incidents/{id}/comments, body not JSON",
                        () -> notJson(post(anyIncident + "/comments")),
                        roles(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR)),
                row(
                        "GET /incidents/{id}/urban-context, id not a UUID",
                        () -> get(incident + "/urban-context"),
                        roles(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR)),
                // Map, statistics, audit and reports.
                row(
                        "GET /incident-locations, minLatitude not a number",
                        () -> get(API + "/incident-locations").param("minLatitude", "north"),
                        roles(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR)),
                row(
                        "GET /incident-statistics, unknown status",
                        () -> get(API + "/incident-statistics").param("status", "NOT_A_STATUS"),
                        roles(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR)),
                row(
                        "GET /audit-entries, size=101",
                        () -> get(API + "/audit-entries").param("size", "101"),
                        ADMINISTRATOR_ONLY),
                row(
                        "GET /incident-reports, groupBy unknown",
                        () -> get(API + "/incident-reports").param("groupBy", "nothing"),
                        roles(Role.ADMINISTRATOR, Role.ANALYST)),
                // Address search of the report form (#179). Too short, so it never calls
                // Nominatim.
                row(
                        "GET /places, q too short",
                        () -> get(API + "/places").param("q", "ab"),
                        roles(Role.CITIZEN)));
    }

    private static Stream<Arguments> casesForEveryRole(Row row) {
        return Arrays.stream(Role.values())
                .map(
                        role ->
                                Arguments.of(
                                        Named.of(row.operation(), row.request()),
                                        role,
                                        row.allowed().contains(role)
                                                ? row.whenAllowed()
                                                : Outcome.FORBIDDEN));
    }

    private record Row(
            String operation,
            Supplier<AbstractMockHttpServletRequestBuilder<?>> request,
            Set<Role> allowed,
            Outcome whenAllowed) {}

    private static Row row(
            String operation,
            Supplier<AbstractMockHttpServletRequestBuilder<?>> request,
            Set<Role> allowed) {
        return new Row(operation, request, allowed, Outcome.NOT_FORBIDDEN);
    }

    private static Row rowOfTheCurrentUser(
            String operation,
            Supplier<AbstractMockHttpServletRequestBuilder<?>> request,
            Set<Role> allowed) {
        return new Row(operation, request, allowed, Outcome.NOT_FORBIDDEN_UNKNOWN_USER);
    }

    private static Set<Role> roles(Role first, Role... rest) {
        return EnumSet.of(first, rest);
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile(
                "file", "photo.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[] {1});
    }

    private static AbstractMockHttpServletRequestBuilder<?> notJson(
            AbstractMockHttpServletRequestBuilder<?> request) {
        return request.contentType(MediaType.APPLICATION_JSON).content(NOT_JSON);
    }

    private static AbstractMockHttpServletRequestBuilder<?> json(
            AbstractMockHttpServletRequestBuilder<?> request) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{}");
    }
}
