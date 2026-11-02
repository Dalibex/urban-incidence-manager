package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance tests of #142 for the request id of #131: every response carries {@code X-Request-ID},
 * every Problem has it as {@code traceId}, and the log lines of the request carry it too. A client
 * id is kept only when it is safe; otherwise one is generated. They also pin the one shape of the
 * field errors (T3), which the refactoring must keep.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, RequestIdIT.FailingEndpoint.class})
@ExtendWith(OutputCaptureExtension.class)
class RequestIdIT {

    static final String REQUEST_ID = "X-Request-ID";
    private static final String FAILING_PATH = "/api/v1/test-only/failure";

    /** An endpoint that fails unexpectedly, the only way to get a 500 through the API. */
    @TestConfiguration
    @RestController
    static class FailingEndpoint {
        @GetMapping(FAILING_PATH)
        String fail() {
            throw new IllegalStateException("Failure on purpose for RequestIdIT");
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;
    @Autowired private JdbcTemplate jdbc;

    // T4: 400, 404, 409, 422 and 500 from the controllers, 401 and 403 from the security filters.
    @Test
    void everyProblemHasTheRequestIdAsTraceId() throws Exception {
        var email = "trace-" + UUID.randomUUID() + "@example.com";
        signUp(email, "10.142.0.1").getStatus();

        var answers =
                Map.of(
                        400,
                                call(
                                        post("/api/v1/registrations")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{"),
                                        "10.142.0.2"),
                        404,
                                call(
                                        get("/api/v1/incidents/" + UUID.randomUUID())
                                                .header(
                                                        HttpHeaders.AUTHORIZATION,
                                                        tokens.bearer(Role.MUNICIPAL_OPERATOR)),
                                        "10.142.0.3"),
                        409, signUp(email, "10.142.0.4"),
                        422,
                                call(
                                        post("/api/v1/registrations")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{}"),
                                        "10.142.0.5"),
                        500,
                                call(
                                        get(FAILING_PATH)
                                                .header(
                                                        HttpHeaders.AUTHORIZATION,
                                                        tokens.bearer(Role.CITIZEN)),
                                        "10.142.0.6"),
                        401, call(get("/api/v1/incidents"), "10.142.0.7"),
                        403,
                                call(
                                        get("/api/v1/incidents")
                                                .header(
                                                        HttpHeaders.AUTHORIZATION,
                                                        tokens.bearer(Role.ANALYST)),
                                        "10.142.0.8"));

        for (var answer : answers.entrySet()) {
            var status = answer.getKey();
            var response = answer.getValue();
            assertThat(response.getStatus()).as("status").isEqualTo(status);
            var requestId = response.getHeader(REQUEST_ID);
            assertThat(requestId).as("%d: %s header", status, REQUEST_ID).isNotBlank();
            assertThat(traceId(response)).as("%d: traceId", status).isEqualTo(requestId);
        }
    }

    // T5: the log lines of a failed request carry its id.
    @Test
    void theLogLinesOfTheRequestCarryItsId(CapturedOutput output) throws Exception {
        var response =
                call(
                        get(FAILING_PATH)
                                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(Role.CITIZEN)),
                        "10.142.1.1");

        var requestId = response.getHeader(REQUEST_ID);
        assertThat(requestId).isNotBlank();
        assertThat(
                        output.getAll()
                                .lines()
                                .filter(line -> line.contains("Unexpected error answering")))
                .isNotEmpty()
                .allMatch(line -> line.contains(requestId));
    }

    // T6
    @Test
    void keepsASafeIdSentByTheClient() throws Exception {
        var clientId = UUID.randomUUID().toString();

        var response =
                call(
                        post("/api/v1/registrations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .header(REQUEST_ID, clientId),
                        "10.142.2.1");

        assertThat(response.getHeader(REQUEST_ID)).isEqualTo(clientId);
        assertThat(traceId(response)).isEqualTo(clientId);
    }

    // T7: the value is never written back, in the response or in the logs.
    @Test
    void replacesAnUnsafeIdSentByTheClient(CapturedOutput output) throws Exception {
        var unsafe =
                List.of(
                        "x".repeat(1000),
                        "has spaces in it",
                        "forged\r\nINFO a log line that never happened",
                        "");
        var address = 1;
        for (var clientId : unsafe) {
            var response =
                    call(
                            get(FAILING_PATH)
                                    .header(HttpHeaders.AUTHORIZATION, tokens.bearer(Role.CITIZEN))
                                    .header(REQUEST_ID, clientId),
                            "10.142.3." + address++);

            var requestId = response.getHeader(REQUEST_ID);
            assertThat(requestId)
                    .as("generated for %s", clientId)
                    .isNotBlank()
                    .isNotEqualTo(clientId);
            assertThat(traceId(response)).isEqualTo(requestId);
            if (!clientId.isEmpty()) {
                assertThat(response.getContentAsString()).doesNotContain(clientId);
                assertThat(output.getAll()).doesNotContain(clientId);
            }
        }
        assertThat(output.getAll()).doesNotContain("INFO a log line that never happened");
    }

    // T8
    @Test
    void generatesADifferentIdForEachRequestWithoutOne() throws Exception {
        var first = call(get("/api/v1/incidents"), "10.142.4.1").getHeader(REQUEST_ID);
        var second = call(get("/api/v1/incidents"), "10.142.4.2").getHeader(REQUEST_ID);

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    // T9: MockMvc runs both requests on this thread, as a pooled server thread would.
    @Test
    void doesNotLeakTheIdToTheNextRequest() throws Exception {
        var clientId = UUID.randomUUID().toString();
        call(get("/api/v1/incidents").header(REQUEST_ID, clientId), "10.142.5.1");

        var next = call(get("/api/v1/incidents"), "10.142.5.2");

        assertThat(next.getHeader(REQUEST_ID)).isNotBlank().isNotEqualTo(clientId);
        assertThat(traceId(next)).isNotEqualTo(clientId);
        var context = MDC.getCopyOfContextMap();
        assertThat(context == null ? Map.of() : context).doesNotContainValue(clientId);
    }

    // T10
    @Test
    void successfulResponsesCarryTheIdToo() throws Exception {
        var response =
                call(
                        get("/api/v1/incidents")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(Role.MUNICIPAL_OPERATOR)),
                        "10.142.6.1");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(REQUEST_ID)).isNotBlank();
    }

    // T3: bean validation errors and domain field errors have the same shape.
    @Test
    void validationAndDomainFieldErrorsHaveTheSameShape() throws Exception {
        var beanValidation =
                call(
                        post("/api/v1/registrations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name": " ", "email": "a@example.com", "password": "Long-Enough-1"}
                                        """),
                        "10.142.7.1");
        var domain =
                call(
                        post("/api/v1/incidents")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(citizen(), Role.CITIZEN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"title": "Street light off", "description": "Off for days.",
                                         "categoryId": "%s",
                                         "location": {"latitude": 36.7213, "longitude": -4.4214}}
                                        """
                                                .formatted(UUID.randomUUID())),
                        "10.142.7.2");

        for (var response : List.of(beanValidation, domain)) {
            assertThat(response.getStatus()).isEqualTo(422);
            List<Map<String, Object>> errors =
                    JsonPath.read(response.getContentAsString(), "$.errors");
            assertThat(errors)
                    .isNotEmpty()
                    .allSatisfy(error -> assertThat(error).containsOnlyKeys("field", "message"));
        }
    }

    // T11: the contract describes the header.
    @Test
    void theContractDescribesTheHeader() throws Exception {
        var contract =
                new ClassPathResource("static/openapi.yml")
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(contract).contains(REQUEST_ID);
    }

    /** A citizen in the database: reporting an incident needs its reporter to exist. */
    private UUID citizen() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash, role) VALUES (?, 'Ana', ?, 'x',"
                        + " 'CITIZEN')",
                id,
                id + "@example.com");
        return id;
    }

    private MockHttpServletResponse signUp(String email, String clientAddress) throws Exception {
        return call(
                post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name": "Ana", "email": "%s", "password": "Correct-Horse-9"}
                                """
                                        .formatted(email)),
                clientAddress);
    }

    /** Each call from its own address, so the sign-up limits of other tests never answer it. */
    private MockHttpServletResponse call(
            MockHttpServletRequestBuilder request, String clientAddress) throws Exception {
        return mvc.perform(
                        request.with(
                                servlet -> {
                                    servlet.setRemoteAddr(clientAddress);
                                    return servlet;
                                }))
                .andReturn()
                .getResponse();
    }

    private static String traceId(MockHttpServletResponse response) throws Exception {
        var body = response.getContentAsString();
        if (body.isBlank() || !body.trim().startsWith("{")) {
            return null;
        }
        return JsonPath.parse(body).read("$.traceId", String.class);
    }
}
