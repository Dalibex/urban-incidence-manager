package es.uma.urbanpulse.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression of #408: a query parameter the contract takes once ({@code docs/api/openapi.yml}, any
 * parameter whose schema is not an array) answers {@code 400} when it is repeated, on every
 * listing, instead of Spring binding its first value without telling the client. The parameters the
 * contract declares as arrays keep taking several values.
 *
 * <p>Every request uses a role the operation allows ({@code docs/security.md}), so the answer comes
 * from the parameters and not from the permission check. Both values of a repeated parameter are
 * valid, so only the repetition can make it a {@code 400}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class RepeatedQueryParameterIT {

    // The incident does not need to exist: the parameters are checked before it is read.
    private static final String INCIDENT = "/api/v1/incidents/" + UUID.randomUUID();
    private static final String FROM = "2031-01-01T00:00:00Z";
    private static final String TO = "2031-01-02T00:00:00Z";

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;

    static Stream<Arguments> singleValuedParameters() {
        return Stream.of(
                // listIncidents
                query(Role.MUNICIPAL_OPERATOR, "/api/v1/incidents", "page", "0", "5"),
                query(Role.MUNICIPAL_OPERATOR, "/api/v1/incidents", "size", "1", "50"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incidents",
                        "sort",
                        "reportedAt",
                        "updatedAt"),
                query(Role.MUNICIPAL_OPERATOR, "/api/v1/incidents", "reportedFrom", FROM, TO),
                query(Role.MUNICIPAL_OPERATOR, "/api/v1/incidents", "reportedTo", FROM, TO),
                // listIncidentClassificationChanges
                query(
                        Role.MUNICIPAL_OPERATOR,
                        INCIDENT + "/classification-changes",
                        "page",
                        "0",
                        "5"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        INCIDENT + "/classification-changes",
                        "size",
                        "1",
                        "50"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        INCIDENT + "/classification-changes",
                        "sort",
                        "instant",
                        "instant,desc"),
                // listIncidentComments
                query(Role.MUNICIPAL_OPERATOR, INCIDENT + "/comments", "page", "0", "5"),
                query(Role.MUNICIPAL_OPERATOR, INCIDENT + "/comments", "size", "1", "50"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        INCIDENT + "/comments",
                        "sort",
                        "createdAt",
                        "createdAt,desc"),
                // listIncidentLocations
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-locations",
                        "reportedFrom",
                        FROM,
                        TO),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-locations",
                        "reportedTo",
                        FROM,
                        TO),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-locations",
                        "minLatitude",
                        "36.68",
                        "36.70"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-locations",
                        "minLongitude",
                        "-4.52",
                        "-4.50"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-locations",
                        "maxLatitude",
                        "36.76",
                        "36.74"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-locations",
                        "maxLongitude",
                        "-4.34",
                        "-4.36"),
                // getIncidentStatistics
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-statistics",
                        "reportedFrom",
                        FROM,
                        TO),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-statistics",
                        "reportedTo",
                        FROM,
                        TO),
                // listAuditEntries
                query(Role.ADMINISTRATOR, "/api/v1/audit-entries", "page", "0", "5"),
                query(Role.ADMINISTRATOR, "/api/v1/audit-entries", "size", "1", "50"),
                query(
                        Role.ADMINISTRATOR,
                        "/api/v1/audit-entries",
                        "sort",
                        "instant",
                        "instant,asc"),
                query(Role.ADMINISTRATOR, "/api/v1/audit-entries", "from", FROM, TO),
                query(Role.ADMINISTRATOR, "/api/v1/audit-entries", "to", FROM, TO),
                query(
                        Role.ADMINISTRATOR,
                        "/api/v1/audit-entries",
                        "actorId",
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString()),
                // listUsers, which refused them before #408
                query(Role.ADMINISTRATOR, "/api/v1/users", "page", "0", "5"),
                query(Role.ADMINISTRATOR, "/api/v1/users", "size", "1", "50"),
                query(Role.ADMINISTRATOR, "/api/v1/users", "sort", "name", "email"),
                query(Role.ADMINISTRATOR, "/api/v1/users", "name", "ana", "luis"),
                query(Role.ADMINISTRATOR, "/api/v1/users", "email", "ana", "luis"));
    }

    static Stream<Arguments> repeatableParameters() {
        return Stream.of(
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incidents",
                        "status",
                        "REPORTED",
                        "VALIDATED"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-locations",
                        "priority",
                        "HIGH",
                        "LOW"),
                query(
                        Role.MUNICIPAL_OPERATOR,
                        "/api/v1/incident-statistics",
                        "categoryId",
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString()),
                query(
                        Role.ADMINISTRATOR,
                        "/api/v1/audit-entries",
                        "action",
                        "CREATE_USER",
                        "CHANGE_ROLE"),
                query(Role.ADMINISTRATOR, "/api/v1/users", "role", "CITIZEN", "ANALYST"));
    }

    @ParameterizedTest(name = "{1}?{2}={3}&{2}={4}")
    @MethodSource("singleValuedParameters")
    void aSingleValuedParameterSentTwiceIsA400(
            Role role, String path, String parameter, String first, String second)
            throws Exception {
        mvc.perform(
                        get(path)
                                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(role))
                                .param(parameter, first, second))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(
                        jsonPath("$.detail").value("The parameter " + parameter + " is repeated."));
    }

    @ParameterizedTest(name = "{1}?{2}={3}&{2}={4}")
    @MethodSource("repeatableParameters")
    void aRepeatableParameterSentTwiceIsStillAccepted(
            Role role, String path, String parameter, String first, String second)
            throws Exception {
        mvc.perform(
                        get(path)
                                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(role))
                                .param(parameter, first, second))
                .andExpect(status().isOk());
    }

    private static Arguments query(
            Role role, String path, String parameter, String first, String second) {
        return Arguments.of(role, path, parameter, first, second);
    }
}
