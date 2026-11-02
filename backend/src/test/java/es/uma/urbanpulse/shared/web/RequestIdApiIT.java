package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The id of a request in its response, its error body and its log lines (#131, test cases T4, T5,
 * T7 and T10 of #142). Every response is also checked against the contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@ExtendWith(OutputCaptureExtension.class)
class RequestIdApiIT {

    private static final String PASSWORD = "Correct-Horse-Battery-9";

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;

    // T4: 400 from the controllers
    @Test
    void aMalformedBodyCarriesTheId() throws Exception {
        expectTraceIdEqualsHeader(
                mvc.perform(
                                client(post("/api/v1/sessions"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("not json"))
                        .andExpect(status().isBadRequest())
                        .andReturn());
    }

    // T4: 401 from the security filters
    @Test
    void aMissingTokenCarriesTheId() throws Exception {
        expectTraceIdEqualsHeader(
                mvc.perform(get("/api/v1/users/me"))
                        .andExpect(status().isUnauthorized())
                        .andReturn());
    }

    // T4: 403 from the security filters
    @Test
    void aForbiddenRoleCarriesTheId() throws Exception {
        expectTraceIdEqualsHeader(
                mvc.perform(
                                get("/api/v1/incidents")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(Role.ANALYST)))
                        .andExpect(status().isForbidden())
                        .andReturn());
    }

    // T4: 404
    @Test
    void aMissingIncidentCarriesTheId() throws Exception {
        expectTraceIdEqualsHeader(
                mvc.perform(
                                get("/api/v1/incidents/" + UUID.randomUUID())
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(Role.MUNICIPAL_OPERATOR)))
                        .andExpect(status().isNotFound())
                        .andReturn());
    }

    // T4: 409 and 422
    @Test
    void conflictAndValidationErrorsCarryTheId() throws Exception {
        var email = "request-id-" + UUID.randomUUID() + "@example.com";
        signUp(email, PASSWORD).andExpect(status().isCreated());

        expectTraceIdEqualsHeader(
                signUp(email, PASSWORD).andExpect(status().isConflict()).andReturn());
        expectTraceIdEqualsHeader(
                signUp("other-" + email, "Short-1")
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.errors[0].field").value("password"))
                        .andReturn());
    }

    // T6 through the whole stack: the client's id comes back in the header and the body
    @Test
    void aSafeClientIdComesBackInTheHeaderAndTheBody() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(RequestIdFilter.HEADER, "web-42.retry_1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RequestIdFilter.HEADER, "web-42.retry_1"))
                .andExpect(jsonPath("$.traceId").value("web-42.retry_1"));
    }

    // T7: the unsafe value appears neither in the response nor in the logs
    @Test
    void anUnsafeClientIdIsNeverEchoed(CapturedOutput output) throws Exception {
        var unsafe = "evil value " + UUID.randomUUID();

        var result =
                mvc.perform(get("/api/v1/users/me").header(RequestIdFilter.HEADER, unsafe))
                        .andExpect(status().isUnauthorized())
                        .andReturn();

        expectTraceIdEqualsHeader(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(unsafe);
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotEqualTo(unsafe);
        assertThat(output.getAll()).doesNotContain(unsafe);
    }

    // T10
    @Test
    void aSuccessfulResponseAlsoCarriesTheId() throws Exception {
        mvc.perform(
                        get("/api/v1/categories")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(Role.MUNICIPAL_OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[0-9a-f]{32}")));
    }

    // T5: a refresh token reused is a failed request that logs a warning; its line carries the id
    @Test
    void theLogLinesOfAFailedRequestCarryItsId(CapturedOutput output) throws Exception {
        var email = "request-id-log-" + UUID.randomUUID() + "@example.com";
        signUp(email, PASSWORD).andExpect(status().isCreated());
        var session =
                mvc.perform(
                                client(post("/api/v1/sessions"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"email": "%s", "password": "%s"}
                                                """
                                                        .formatted(email, PASSWORD)))
                        .andExpect(status().isCreated())
                        .andReturn();
        String spent = JsonPath.read(session.getResponse().getContentAsString(), "$.refreshToken");
        refresh(spent, "first-refresh").andExpect(status().isOk());
        var id = "reuse-" + UUID.randomUUID();

        var reuse = refresh(spent, id).andExpect(status().isUnauthorized()).andReturn();

        assertThat(JsonPath.<String>read(reuse.getResponse().getContentAsString(), "$.traceId"))
                .isEqualTo(id);
        assertThat(output.getAll().lines().filter(line -> line.contains("presented again")))
                .isNotEmpty()
                .allMatch(line -> line.contains("[" + id + "]"));
    }

    private ResultActions signUp(String email, String password) throws Exception {
        return mvc.perform(
                client(post("/api/v1/registrations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name": "Request Id", "email": "%s", "password": "%s"}
                                """
                                        .formatted(email, password)));
    }

    private ResultActions refresh(String token, String id) throws Exception {
        return mvc.perform(
                client(post("/api/v1/sessions/refresh"))
                        .header(RequestIdFilter.HEADER, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(token)));
    }

    // Each request from its own address, so the rate limits of sign-up and sign-in never apply.
    private static MockHttpServletRequestBuilder client(MockHttpServletRequestBuilder request) {
        var id = UUID.randomUUID().hashCode();
        return request.with(
                r -> {
                    r.setRemoteAddr("10.131." + (id & 0xff) + "." + ((id >> 8) & 0xff));
                    return r;
                });
    }

    private static void expectTraceIdEqualsHeader(MvcResult result) throws Exception {
        var header = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotBlank();
        String traceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");
        assertThat(traceId).isEqualTo(header);
    }
}
