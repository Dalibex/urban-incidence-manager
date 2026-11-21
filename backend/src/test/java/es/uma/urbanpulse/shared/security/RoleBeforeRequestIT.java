package es.uma.urbanpulse.shared.security;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Part A of #286: a role that may not call an operation gets {@code 403} before Spring reads the
 * request, so a bad body or parameter never answers {@code 400} or {@code 422} to it (ADR-0020,
 * order of errors). A role that may call it still gets those errors, and a request without a token
 * still gets {@code 401} first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class RoleBeforeRequestIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String AUDIT_ENTRIES = "/api/v1/audit-entries";

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;

    @Test
    void anOperatorWhoSendsAnInvalidIncidentGetsForbiddenNotUnprocessable() throws Exception {
        expectForbidden(
                mvc.perform(
                        emptyIncident()
                                .header(AUTHORIZATION, tokens.bearer(Role.MUNICIPAL_OPERATOR))));
    }

    @Test
    void aCitizenWhoSendsAnInvalidIncidentStillGetsUnprocessable() throws Exception {
        mvc.perform(emptyIncident().header(AUTHORIZATION, tokens.bearer(Role.CITIZEN)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anInvalidIncidentWithoutATokenStillGetsUnauthorized() throws Exception {
        mvc.perform(emptyIncident()).andExpect(status().isUnauthorized());
    }

    @Test
    void anAnalystWithAPageTooLargeOnTheAuditTrailGetsForbiddenNotBadRequest() throws Exception {
        expectForbidden(
                mvc.perform(
                        get(AUDIT_ENTRIES)
                                .param("size", "101")
                                .header(AUTHORIZATION, tokens.bearer(Role.ANALYST))));
    }

    @Test
    void anAdministratorWithAPageTooLargeOnTheAuditTrailStillGetsBadRequest() throws Exception {
        mvc.perform(
                        get(AUDIT_ENTRIES)
                                .param("size", "101")
                                .header(AUTHORIZATION, tokens.bearer(Role.ADMINISTRATOR)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAnalystWithAUserIdThatIsNotAUuidGetsForbiddenNotBadRequest() throws Exception {
        expectForbidden(
                mvc.perform(
                        get("/api/v1/users/not-a-uuid")
                                .header(AUTHORIZATION, tokens.bearer(Role.ANALYST))));
    }

    private static MockHttpServletRequestBuilder emptyIncident() {
        return post(INCIDENTS).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private static void expectForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }
}
