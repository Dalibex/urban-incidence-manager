package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The text search {@code q} of #266 in {@code listIncidents}, {@code listIncidentLocations} and
 * {@code getIncidentStatistics}, written from the contract ({@code TextSearch} in {@code
 * docs/api/openapi.yml}) and the acceptance criteria of the issue. The data is inserted with SQL
 * and every check goes through HTTP, validated against the contract ({@link ApiContract}).
 *
 * <p>Each test runs inside a transaction that is rolled back. Other incidents may exist in the
 * database, so every incident of a test carries a word no other incident has ({@link #marker}), and
 * every search includes it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentTextSearchApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String LOCATIONS = "/api/v1/incident-locations";
    private static final String STATISTICS = "/api/v1/incident-statistics";
    private static final Instant REPORTED_AT = Instant.parse("2031-02-01T00:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;
    private UUID otherCitizen;
    private UUID operator;
    private UUID technician;

    /** A word only the incidents of this test contain, in lower case, letters only. */
    private String marker;

    @BeforeEach
    void createUsers() {
        citizen = user(Role.CITIZEN);
        otherCitizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        technician = user(Role.TECHNICIAN);
        marker = "zq" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
    }

    @Nested
    @DisplayName("What matches")
    class WhatMatches {

        @Test
        @DisplayName("finds the text in the title or in the description")
        void findsTheTextInTheTitleOrTheDescription() throws Exception {
            UUID inTitle = incident(citizen, "REPORTED", "Farola " + marker, "Sin luz.");
            UUID inDescription = incident(citizen, "REPORTED", "Sin luz", "Farola " + marker);
            incident(citizen, "REPORTED", "Bache " + marker, "En la calzada.");

            assertThat(listed(operator, "q", "farola " + marker))
                    .containsExactlyInAnyOrder(inTitle.toString(), inDescription.toString());
        }

        @Test
        @DisplayName("ignores case and accents, in the text and in the incident")
        void ignoresCaseAndAccents() throws Exception {
            UUID truck = incident(citizen, "REPORTED", "CAMIÓN " + marker, "Mal aparcado.");

            assertThat(listed(operator, "q", "camion " + marker)).containsExactly(truck.toString());
            assertThat(listed(operator, "q", "Camión " + marker.toUpperCase()))
                    .containsExactly(truck.toString());
        }

        @Test
        @DisplayName("matches the text as one piece, not word by word")
        void matchesTheTextAsOnePiece() throws Exception {
            incident(citizen, "REPORTED", "Rota la farola " + marker, "Sin luz.");

            assertThat(listed(operator, "q", "farola rota " + marker)).isEmpty();
        }

        @Test
        @DisplayName("finds it across the words of a title, inside a word")
        void findsAPieceInsideAWord() throws Exception {
            UUID lamp = incident(citizen, "REPORTED", "Farolas " + marker + "s", "Sin luz.");

            assertThat(listed(operator, "q", "rolas " + marker)).containsExactly(lamp.toString());
        }

        @Test
        @DisplayName("reads % and _ as plain characters, not wildcards")
        void readsPercentAndUnderscoreAsPlainCharacters() throws Exception {
            UUID percent = incident(citizen, "REPORTED", "50% " + marker, "Rebaja.");
            incident(citizen, "REPORTED", "500 " + marker, "Rebaja.");
            UUID underscore = incident(citizen, "REPORTED", "a_b " + marker, "Guion bajo.");
            incident(citizen, "REPORTED", "axb " + marker, "Guion bajo.");

            assertThat(listed(operator, "q", "50% " + marker)).containsExactly(percent.toString());
            assertThat(listed(operator, "q", "a_b " + marker))
                    .containsExactly(underscore.toString());
        }

        @Test
        @DisplayName("reads a backslash as a plain character")
        void readsABackslashAsAPlainCharacter() throws Exception {
            UUID slash = incident(citizen, "REPORTED", "a\\b " + marker, "Barra.");
            incident(citizen, "REPORTED", "ab " + marker, "Barra.");

            assertThat(listed(operator, "q", "a\\b " + marker)).containsExactly(slash.toString());
        }

        @Test
        @DisplayName("ignores the spaces around the text")
        void ignoresTheSpacesAroundTheText() throws Exception {
            UUID lamp = incident(citizen, "REPORTED", "Farola " + marker, "Sin luz.");

            assertThat(listed(operator, "q", "  farola " + marker + "  "))
                    .containsExactly(lamp.toString());
        }
    }

    @Nested
    @DisplayName("With the scope and the other filters")
    class WithScopeAndFilters {

        @Test
        @DisplayName("combines with the other filters: both must match")
        void combinesWithTheOtherFilters() throws Exception {
            UUID reported = incident(citizen, "REPORTED", "Farola " + marker, "Sin luz.");
            incident(citizen, "VALIDATED", "Farola " + marker, "Sin luz.");

            assertThat(listed(operator, "q", marker, "status", "REPORTED"))
                    .containsExactly(reported.toString());
        }

        @Test
        @DisplayName("lets a citizen search their own reports, never widening what they see")
        void citizenSearchesOnlyTheirOwnReports() throws Exception {
            UUID own = incident(citizen, "REPORTED", "Farola " + marker, "Sin luz.");
            incident(otherCitizen, "REPORTED", "Farola " + marker, "Sin luz.");

            assertThat(listed(citizen, "q", marker)).containsExactly(own.toString());
        }

        @Test
        @DisplayName("lets a technician search only their active assignments")
        void technicianSearchesOnlyTheirAssignments() throws Exception {
            UUID assigned = incident(citizen, "ASSIGNED", "Farola " + marker, "Sin luz.");
            incident(citizen, "ASSIGNED", "Farola " + marker, "Sin luz.");
            assign(assigned, technician);

            assertThat(listed(technician, "q", marker)).containsExactly(assigned.toString());
        }

        @Test
        @DisplayName("pages the matching incidents with their total")
        void pagesTheMatchingIncidents() throws Exception {
            for (int i = 0; i < 3; i++) {
                incident(citizen, "REPORTED", "Farola " + marker, "Sin luz.");
            }

            list(operator, "q", marker, "size", "2", "page", "1")
                    .andExpect(status().isOk())
                    .andExpect(
                            result -> {
                                String body = result.getResponse().getContentAsString();
                                assertThat(ids(body)).hasSize(1);
                                assertThat((Integer) JsonPath.read(body, "$.page.totalElements"))
                                        .isEqualTo(3);
                            });
        }

        @Test
        @DisplayName("narrows the map to the same incidents")
        void narrowsTheMap() throws Exception {
            UUID lamp = incident(citizen, "REPORTED", "Farola " + marker, "Sin luz.");
            incident(citizen, "REPORTED", "Bache " + marker, "En la calzada.");

            String body =
                    request(LOCATIONS, operator, "q", "farola " + marker)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat(ids(body)).containsExactly(lamp.toString());
        }

        @Test
        @DisplayName("counts on the dashboard the incidents the map draws")
        void countsTheSameIncidentsAsTheMap() throws Exception {
            incident(citizen, "REPORTED", "Farola " + marker, "Sin luz.");
            incident(citizen, "VALIDATED", "Farola " + marker, "Sin luz.");
            incident(citizen, "REPORTED", "Bache " + marker, "En la calzada.");

            String body =
                    request(STATISTICS, operator, "q", "farola " + marker)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat((Integer) JsonPath.read(body, "$.total")).isEqualTo(2);
        }

        @Test
        @DisplayName("answers an empty page when nothing matches")
        void answersAnEmptyPageWhenNothingMatches() throws Exception {
            assertThat(listed(operator, "q", marker)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Invalid text")
    class InvalidText {

        @Test
        @DisplayName("rejects fewer than 3 characters once the spaces are removed")
        void rejectsATextThatIsTooShort() throws Exception {
            expectMalformed(INCIDENTS, "q", "ab");
            expectMalformed(INCIDENTS, "q", "  ab  ");
            expectMalformed(INCIDENTS, "q", "   ");
            expectMalformed(LOCATIONS, "q", "ab");
            expectMalformed(STATISTICS, "q", "ab");
        }

        @Test
        @DisplayName("accepts exactly 3 and 100 characters")
        void acceptsTheLimits() throws Exception {
            list(operator, "q", "abc").andExpect(status().isOk());
            list(operator, "q", "a".repeat(100)).andExpect(status().isOk());
            // Characters, not UTF-16 units: an emoji counts as one.
            list(operator, "q", "💡".repeat(3)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("rejects more than 100 characters")
        void rejectsATextThatIsTooLong() throws Exception {
            expectMalformed(INCIDENTS, "q", "a".repeat(101));
            expectMalformed(LOCATIONS, "q", "a".repeat(101));
            expectMalformed(STATISTICS, "q", "a".repeat(101));
        }

        @Test
        @DisplayName("rejects an empty or a repeated q")
        void rejectsAnEmptyOrRepeatedText() throws Exception {
            expectMalformed(INCIDENTS, "q", "");
            expectMalformed(INCIDENTS, "q", "farola", "q", "bache");
            expectMalformed(LOCATIONS, "q", "farola", "q", "bache");
            expectMalformed(STATISTICS, "q", "farola", "q", "bache");
        }
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                "Test " + role,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }

    private UUID incident(UUID reporter, String status, String title, String description) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                                + " RETURNING id",
                        UUID.class,
                        reporter,
                        title,
                        description,
                        status,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(REPORTED_AT));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        return incident;
    }

    private void assign(UUID incident, UUID assignedTechnician) {
        UUID department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Lighting " + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at,"
                        + " is_active) VALUES (?, ?, ?, ?, true)",
                incident,
                department,
                assignedTechnician,
                Timestamp.from(REPORTED_AT));
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    private ResultActions request(String path, UUID caller, String... nameValuePairs)
            throws Exception {
        MockHttpServletRequestBuilder request =
                get(path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, roleOf(caller)));
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request = request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions list(UUID caller, String... nameValuePairs) throws Exception {
        return request(INCIDENTS, caller, nameValuePairs);
    }

    /** The ids of the first page of {@code listIncidents}, which holds every incident of a test. */
    private List<String> listed(UUID caller, String... nameValuePairs) throws Exception {
        String[] withSize = new String[nameValuePairs.length + 2];
        System.arraycopy(nameValuePairs, 0, withSize, 0, nameValuePairs.length);
        withSize[nameValuePairs.length] = "size";
        withSize[nameValuePairs.length + 1] = "100";
        return ids(
                list(caller, withSize)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    /** The ids of a page of incidents or of the positions of the map. */
    private static List<String> ids(String body) {
        return JsonPath.read(body, "$.content[*].id");
    }

    /** {@code path} with {@code query}, as the operator, answers 400 {@code MALFORMED_REQUEST}. */
    private void expectMalformed(String path, String... query) throws Exception {
        var response = request(path, operator, query).andReturn().getResponse();
        String what = path + " " + String.join(" ", query);
        assertThat(response.getStatus()).as("status for %s", what).isEqualTo(400);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                .as("code for %s", what)
                .isEqualTo("MALFORMED_REQUEST");
    }
}
