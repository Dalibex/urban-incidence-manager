package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.web.RequestIdFilter;
import java.net.URI;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Part B of #286: a request that Spring Security's firewall refuses (its path, a header value or a
 * parameter name) keeps its {@code 400}, with the same Problem body, {@code MALFORMED_REQUEST} code
 * and {@code traceId} as every other error, so the client reads it like the rest and the log line
 * can be found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class FirewallRefusalIT {

    private static final String VALID_REPORT =
            """
            {"title": "Broken streetlight", "description": "The lamp is off since Monday.",
             "location": {"latitude": 36.7213, "longitude": -4.4214}}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1//incidents", "/api/v1/incidents;x=1", "/api/v1/incidents%2Fx"})
    void aRefusedPathGetsBadRequestWithTheProblemBody(String path) throws Exception {
        var result =
                mvc.perform(
                                get(URI.create(path))
                                        .header(AUTHORIZATION, tokens.bearer(Role.CITIZEN)))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("about:blank"))
                        .andExpect(jsonPath("$.title").value("Bad Request"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                        .andExpect(
                                jsonPath("$.detail")
                                        .value(SecurityProblemResponses.REJECTED_DETAIL))
                        // The refused path is not echoed back.
                        .andExpect(jsonPath("$.instance").doesNotExist())
                        .andReturn()
                        .getResponse();

        String traceId = JsonPath.read(result.getContentAsString(), "$.traceId");
        assertThat(traceId).isNotBlank().isEqualTo(result.getHeader(RequestIdFilter.HEADER));
    }

    /**
     * The firewall checks a header value or a parameter name only when something reads it, not when
     * the request arrives. Spring MVC reads {@code Idempotency-Key} and the parameter names while
     * it calls the controller, so the refusal is thrown inside it and must reach the firewall's
     * handler too, not become a {@code 500} (nor a log line with the refused value). {@code Accept}
     * already answered {@code 400} without the fix in {@code ApiExceptionHandler}; it stays here so
     * every kind of header value is covered.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("valuesRefusedWhenRead")
    void aValueRefusedWhenSpringMvcReadsItGetsBadRequestWithTheProblemBody(
            Function<String, MockHttpServletRequestBuilder> request) throws Exception {
        var citizen = citizen();

        mvc.perform(request.apply(tokens.bearer(citizen, Role.CITIZEN)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value(SecurityProblemResponses.REJECTED_DETAIL))
                .andExpect(jsonPath("$.instance").doesNotExist());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM incident WHERE reporter_id = ?",
                                Integer.class,
                                citizen))
                .isZero();
    }

    static Stream<Arguments> valuesRefusedWhenRead() {
        return Stream.of(
                refused(
                        "Accept on GET /incidents",
                        bearer ->
                                get("/api/v1/incidents")
                                        .header(AUTHORIZATION, bearer)
                                        .header(ACCEPT, "application/json\u0085")),
                refused(
                        "Idempotency-Key on POST /incidents with a valid report",
                        bearer ->
                                post("/api/v1/incidents")
                                        .header(AUTHORIZATION, bearer)
                                        .header("Idempotency-Key", "x\u0085")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(VALID_REPORT)),
                refused(
                        "a parameter name on GET /incidents",
                        bearer ->
                                get("/api/v1/incidents")
                                        .header(AUTHORIZATION, bearer)
                                        .param("st\u0085", "x")));
    }

    private static Arguments refused(
            String name, Function<String, MockHttpServletRequestBuilder> request) {
        return Arguments.of(Named.of(name, request));
    }

    private UUID citizen() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                UUID.class,
                "citizen-" + UUID.randomUUID() + "@example.com");
    }
}
