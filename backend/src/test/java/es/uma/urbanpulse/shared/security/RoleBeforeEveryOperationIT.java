package es.uma.urbanpulse.shared.security;

import static org.springframework.http.HttpHeaders.ACCEPT;
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
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * #286 on every operation that some role may not call: that role gets {@code 403} even when its
 * request would fail binding, validation or content negotiation ({@code 400}, {@code 406}, {@code
 * 415}, {@code 422}), because the security filters check the role before Spring MVC reads the
 * request (ADR-0020, order of errors). Each case sends one refused role with one invalid input: a
 * body that is not JSON, a page size over the limit, a typed parameter that does not convert, a
 * path id that is not a UUID, a {@code Content-Type} or an {@code Accept} the operation does not
 * take.
 *
 * <p>{@code POST /incidents}, {@code GET /audit-entries} and {@code GET /users/{id}} are in {@link
 * RoleBeforeRequestIT}, with the allowed role and the missing token. An upload over the size limit
 * needs a real server, which parses the multipart: it is in {@link RoleBeforeMultipartIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class RoleBeforeEveryOperationIT {

    private static final String API = "/api/v1";
    private static final String NOT_A_UUID = "not-a-uuid";
    private static final String NOT_JSON = "{";

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("invalidRequests")
    void aRefusedRoleWithAnInvalidRequestGetsForbidden(
            AbstractMockHttpServletRequestBuilder<?> request, Role refused) throws Exception {
        expectForbidden(request, refused);
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("requestsSpringMvcCannotMatch")
    void aRefusedRoleWithAContentTypeOrAcceptNotTakenGetsForbidden(
            AbstractMockHttpServletRequestBuilder<?> request, Role refused) throws Exception {
        expectForbidden(request, refused);
    }

    // Deny by default: an operation without a rule in ApiAuthorizationRules is refused to every
    // role, so an endpoint added without its rule is closed, not open. Spring Security answers it
    // before Spring MVC looks for a handler, so it is 403, not 404 or 405.
    @ParameterizedTest(name = "{0}")
    @MethodSource("operationsWithoutARule")
    void anOperationWithoutARuleIsForbiddenToEveryRole(
            AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        for (var role : Role.values()) {
            expectForbidden(request, role);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationsWithoutARule")
    void anOperationWithoutARuleStillAnswersUnauthorizedWithoutAToken(
            AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> invalidRequests() {
        var incident = API + "/incidents/" + UUID.randomUUID();
        return Stream.of(
                // identity
                refused(
                        "POST /users, body not JSON",
                        notJson(post(API + "/users")),
                        Role.TECHNICIAN),
                refused(
                        "PATCH /users/{id}, id not a UUID",
                        json(patch(API + "/users/" + NOT_A_UUID)),
                        Role.CITIZEN),
                // incidents
                refused(
                        "GET /incidents, size=101",
                        get(API + "/incidents").param("size", "101"),
                        Role.ANALYST),
                refused(
                        "GET /incidents/{id}, id not a UUID",
                        get(API + "/incidents/" + NOT_A_UUID),
                        Role.ANALYST),
                refused(
                        "GET /incidents/{id}/status-changes, id not a UUID",
                        get(API + "/incidents/" + NOT_A_UUID + "/status-changes"),
                        Role.ANALYST),
                refused(
                        "PATCH /incidents/{id}, body not JSON",
                        notJson(patch(incident)),
                        Role.TECHNICIAN),
                refused(
                        "GET /incidents/{id}/classification-changes, size=101",
                        get(incident + "/classification-changes").param("size", "101"),
                        Role.CITIZEN),
                refused(
                        "POST /incidents/{id}/validation, body not JSON",
                        notJson(post(incident + "/validation")),
                        Role.TECHNICIAN),
                refused(
                        "POST /incidents/{id}/rejection, body not JSON",
                        notJson(post(incident + "/rejection")),
                        Role.CITIZEN),
                refused(
                        "POST /incidents/{id}/information-requests, body not JSON",
                        notJson(post(incident + "/information-requests")),
                        Role.TECHNICIAN),
                refused(
                        "POST /incidents/{id}/assignments, body not JSON",
                        notJson(post(incident + "/assignments")),
                        Role.TECHNICIAN),
                refused(
                        "POST /incidents/{id}/acceptance, body not JSON",
                        notJson(post(incident + "/acceptance")),
                        Role.MUNICIPAL_OPERATOR),
                refused(
                        "POST /incidents/{id}/resolution, body not JSON",
                        notJson(post(incident + "/resolution")),
                        Role.ADMINISTRATOR),
                refused(
                        "POST /incidents/{id}/closure, body not JSON",
                        notJson(post(incident + "/closure")),
                        Role.CITIZEN),
                refused(
                        "POST /incidents/{id}/reopening, body not JSON",
                        notJson(post(incident + "/reopening")),
                        Role.TECHNICIAN),
                refused(
                        "GET /incidents/{id}/attachments, id not a UUID",
                        get(API + "/incidents/" + NOT_A_UUID + "/attachments"),
                        Role.ANALYST),
                refused(
                        "POST /incidents/{id}/attachments, id not a UUID",
                        multipart(API + "/incidents/" + NOT_A_UUID + "/attachments")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "photo.jpg",
                                                MediaType.IMAGE_JPEG_VALUE,
                                                new byte[] {1})),
                        Role.ANALYST),
                refused(
                        "GET /attachments/{id}/content, id not a UUID",
                        get(API + "/attachments/" + NOT_A_UUID + "/content"),
                        Role.ANALYST),
                refused(
                        "GET /incidents/{id}/comments, size=101",
                        get(incident + "/comments").param("size", "101"),
                        Role.CITIZEN),
                refused(
                        "POST /incidents/{id}/comments, body not JSON",
                        notJson(post(incident + "/comments")),
                        Role.ANALYST),
                refused(
                        "GET /incident-locations, minLatitude not a number",
                        get(API + "/incident-locations").param("minLatitude", "north"),
                        Role.CITIZEN),
                refused(
                        "GET /incident-statistics, unknown status",
                        get(API + "/incident-statistics").param("status", "NOT_A_STATUS"),
                        Role.ANALYST),
                // urban context
                refused(
                        "GET /incidents/{id}/urban-context, id not a UUID",
                        get(API + "/incidents/" + NOT_A_UUID + "/urban-context"),
                        Role.CITIZEN),
                // identity and analytics, with parameters that do not convert or are out of range
                refused(
                        "GET /users, size=101",
                        get(API + "/users").param("size", "101"),
                        Role.ANALYST),
                refused(
                        "GET /incident-reports, groupBy unknown",
                        get(API + "/incident-reports").param("groupBy", "nothing"),
                        Role.CITIZEN),
                refused(
                        "GET /incident-reports as CSV, from without its offset",
                        get(API + "/incident-reports")
                                .param("from", "2026-11-01T00:00")
                                .header(ACCEPT, "text/csv"),
                        Role.TECHNICIAN),
                // operations without parameters
                refused("GET /departments", get(API + "/departments"), Role.CITIZEN),
                refused("GET /technicians", get(API + "/technicians"), Role.TECHNICIAN));
    }

    static Stream<Arguments> requestsSpringMvcCannotMatch() {
        return Stream.of(
                // Spring MVC refuses it while it picks the handler, by the consumes of the
                // operation.
                refused(
                        "POST /incidents/{id}/attachments, Content-Type JSON (415)",
                        json(post(API + "/incidents/" + UUID.randomUUID() + "/attachments")),
                        Role.ANALYST),
                refused(
                        "POST /incidents, Content-Type text/plain (415)",
                        post(API + "/incidents").contentType(MediaType.TEXT_PLAIN).content("x"),
                        Role.ANALYST),
                refused(
                        "GET /departments, Accept image/png (406)",
                        get(API + "/departments").header(ACCEPT, MediaType.IMAGE_PNG_VALUE),
                        Role.CITIZEN));
    }

    static Stream<Arguments> operationsWithoutARule() {
        return Stream.of(
                Arguments.of(Named.of("GET /not-an-endpoint", get(API + "/not-an-endpoint"))),
                Arguments.of(
                        Named.of(
                                "DELETE /incidents/{id}",
                                delete(API + "/incidents/" + UUID.randomUUID()))));
    }

    private static Arguments refused(
            String operation, AbstractMockHttpServletRequestBuilder<?> request, Role role) {
        return Arguments.of(Named.of(operation, request), role);
    }

    private static AbstractMockHttpServletRequestBuilder<?> notJson(
            AbstractMockHttpServletRequestBuilder<?> request) {
        return request.contentType(MediaType.APPLICATION_JSON).content(NOT_JSON);
    }

    private static AbstractMockHttpServletRequestBuilder<?> json(
            AbstractMockHttpServletRequestBuilder<?> request) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private void expectForbidden(AbstractMockHttpServletRequestBuilder<?> request, Role refused)
            throws Exception {
        mvc.perform(request.header(AUTHORIZATION, tokens.bearer(refused)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }
}
