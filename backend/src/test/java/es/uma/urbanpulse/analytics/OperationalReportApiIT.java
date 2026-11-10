package es.uma.urbanpulse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of #65 for the operational report of #42 ({@code getIncidentReport}): T2 to T5,
 * T7 to T14 and T16. T1 and T6 are manual, and T10 and T15 of the screen are tests of the web.
 *
 * <p>Written from the table of #65 and the contract ({@code docs/api/openapi.yml}), not from the
 * code: the data is inserted with SQL, every check goes through HTTP, and every response is also
 * validated against the contract ({@link ApiContract}), which is the API part of T7. The expected
 * percentiles are worked out by hand with the continuous percentile of a spreadsheet ({@code
 * PERCENTILE.INC}): rank {@code p * (n - 1)} counted from 0, interpolating between neighbours.
 *
 * <p>Each test runs inside a transaction that is rolled back. Other tests may leave incidents in
 * the database, so every report asks for a period in 2034, which no other test uses.
 *
 * <p>Failure and limit cases of {@code CONTRIBUTING.md}: {@code 401}, {@code 403} and {@code 422}
 * are here, and a {@code 5xx}, no network and a double submission are tests of the screen. The
 * others do not apply: the operation only reads and has no id in its path ({@code 404}), changes
 * nothing that can conflict ({@code 409}) or be edited at the same time, is not rate limited
 * ({@code 429}; the 366-day bound is what limits its cost), and leaves nothing half done if the app
 * closes. Start-up without network is the same as a failed load for this screen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class OperationalReportApiIT {

    private static final String REPORTS = "/api/v1/incident-reports";
    private static final String TEXT_CSV = "text/csv";
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String HEADER =
            "group,total,open,closed,rejected,resolutionHoursP50,resolutionHoursP95";

    // The period of every report: March 2034, from included, to excluded.
    private static final Instant FROM = Instant.parse("2034-03-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2034-04-01T00:00:00Z");
    private static final Instant INSIDE = Instant.parse("2034-03-10T08:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;

    @BeforeEach
    void createReporter() {
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN')"
                                + " RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
    }

    // ---------------------------------------------------------------------------------------------
    // T2: groupings, with totals and percentiles worked out by hand.

    /** The incidents of the T2 dataset, so each test can name its groups. */
    private record Dataset(String lamps, String roads, String lighting, String works) {}

    /**
     * Eight incidents reported in March 2034 and three that are not: one reported just before, one
     * just at the excluded end, and one reported in February that changed and closed in March. The
     * report counts by the day an incident was reported, not by its last change. They are inserted
     * in the reverse of the expected order, so the order of the report is not the order of
     * insertion.
     *
     * <pre>
     * id  category  status       priority  district  department (active)  hours to close
     * L1  Lamps     CLOSED       HIGH      Centro    Lighting             10
     * L2  Lamps     CLOSED       HIGH      Centro    Lighting             20
     * L3  Lamps     CLOSED       LOW       Este      Lighting             40
     * L4  Lamps     IN_PROGRESS  MEDIUM    Este      Lighting             -
     * L5  Lamps     REJECTED     -         -         -                    -
     * R1  Roads     RESOLVED     MEDIUM    Centro    Works                -
     * R2  Roads     REPORTED     -         "  "      -                    -
     * N1  -         CLOSED       LOW       -         Works (Lighting      5
     *                                                 inactive, later)
     * </pre>
     */
    private Dataset t2Dataset() {
        var works = department("Works");
        var lighting = department("Lighting");
        var roads = category("Roads");
        var lamps = category("Lamps");

        var n1 = incident(null, "CLOSED", "LOW", null, INSIDE, Duration.ofHours(5));
        // The inactive assignment is the most recent one: only "active" decides the department.
        assign(n1, works.id(), true, INSIDE);
        assign(n1, lighting.id(), false, INSIDE.plusSeconds(60));
        incident(roads.id(), "REPORTED", null, "  ", INSIDE, null);
        var r1 = incident(roads.id(), "RESOLVED", "MEDIUM", "Centro", INSIDE, null);
        assign(r1, works.id(), true, INSIDE);
        incident(lamps.id(), "REJECTED", null, null, INSIDE, null);
        var l4 = incident(lamps.id(), "IN_PROGRESS", "MEDIUM", "Este", INSIDE, null);
        assign(l4, lighting.id(), true, INSIDE);
        var l3 = incident(lamps.id(), "CLOSED", "LOW", "Este", INSIDE, Duration.ofHours(40));
        assign(l3, lighting.id(), true, INSIDE);
        var l2 = incident(lamps.id(), "CLOSED", "HIGH", "Centro", INSIDE, Duration.ofHours(20));
        assign(l2, lighting.id(), true, INSIDE);
        var l1 = incident(lamps.id(), "CLOSED", "HIGH", "Centro", INSIDE, Duration.ofHours(10));
        assign(l1, lighting.id(), true, INSIDE);

        // Not in the report: the start is included and the end excluded, and a report counts the
        // day an incident was reported, even if it changed or closed inside the period.
        incident(lamps.id(), "REPORTED", null, "Centro", FROM.minusSeconds(1), null);
        incident(lamps.id(), "REPORTED", null, "Centro", TO, null);
        incident(
                lamps.id(),
                "CLOSED",
                "LOW",
                "Centro",
                FROM.minus(Duration.ofDays(10)),
                Duration.ofDays(12));

        return new Dataset(lamps.name(), roads.name(), lighting.name(), works.name());
    }

    // T2: category. Lamps closed after 10, 20 and 40 hours: P50 = 20; P95 rank 1.9 = 20 + 0.9 * 20.
    @Test
    void groupsByCategoryWithItsTotalsAndPercentiles() throws Exception {
        var data = t2Dataset();

        assertThat(report("category"))
                .containsExactly(
                        new Row(data.lamps(), 5, 1, 3, 1, 20.0, 38.0),
                        new Row(data.roads(), 2, 2, 0, 0, null, null),
                        new Row(null, 1, 0, 1, 0, 5.0, 5.0));
    }

    // T2: district. Centro closed after 10 and 20 hours: P95 rank 0.95 = 10 + 0.95 * 10. A blank
    // district is as unknown as a missing one.
    @Test
    void groupsByDistrictWithTheUnknownOnesLast() throws Exception {
        t2Dataset();

        assertThat(report("district"))
                .containsExactly(
                        new Row("Centro", 3, 1, 2, 0, 15.0, 19.5),
                        new Row("Este", 2, 1, 1, 0, 40.0, 40.0),
                        new Row(null, 3, 1, 1, 1, 5.0, 5.0));
    }

    // T2: status, in the order of the lifecycle. Closed after 5, 10, 20 and 40 hours: P50 = 15;
    // P95 rank 2.85 = 20 + 0.85 * 20.
    @Test
    void groupsByStatusInTheOrderOfTheLifecycle() throws Exception {
        t2Dataset();

        assertThat(report("status"))
                .containsExactly(
                        new Row("REPORTED", 1, 1, 0, 0, null, null),
                        new Row("REJECTED", 1, 0, 0, 1, null, null),
                        new Row("IN_PROGRESS", 1, 1, 0, 0, null, null),
                        new Row("RESOLVED", 1, 1, 0, 0, null, null),
                        new Row("CLOSED", 4, 0, 4, 0, 15.0, 37.0));
    }

    // T2: priority, in its declared order and the unclassified last. LOW closed after 5 and 40
    // hours: P50 = 22.5; P95 rank 0.95 = 5 + 0.95 * 35.
    @Test
    void groupsByPriorityWithTheUnclassifiedLast() throws Exception {
        t2Dataset();

        assertThat(report("priority"))
                .containsExactly(
                        new Row("LOW", 2, 0, 2, 0, 22.5, 38.25),
                        new Row("MEDIUM", 2, 2, 0, 0, null, null),
                        new Row("HIGH", 2, 0, 2, 0, 15.0, 19.5),
                        new Row(null, 2, 1, 0, 1, null, null));
    }

    // T2: department, by the active assignment only: N1 was in Lighting before, and is in Works
    // now.
    @Test
    void groupsByTheDepartmentOfTheActiveAssignment() throws Exception {
        var data = t2Dataset();

        assertThat(report("department"))
                .containsExactly(
                        new Row(data.lighting(), 4, 1, 3, 0, 20.0, 38.0),
                        new Row(data.works(), 2, 1, 1, 0, 5.0, 5.0),
                        new Row(null, 2, 1, 0, 1, null, null));
    }

    // T2: the hours keep two decimals, rounded: 1 h 40 min is 1.6667 hours, so 1.67 (1.66 would be
    // cut, not rounded).
    @Test
    void roundsTheHoursToTwoDecimals() throws Exception {
        var lamps = category("Lamps");
        incident(lamps.id(), "CLOSED", "LOW", null, INSIDE, Duration.ofMinutes(100));

        assertThat(report("category"))
                .containsExactly(new Row(lamps.name(), 1, 0, 1, 0, 1.67, 1.67));
    }

    // T2: an incident reported in the period and closed after it counts as closed, with its hours.
    @Test
    void anIncidentClosedAfterThePeriodCountsWithItsHours() throws Exception {
        var lamps = category("Lamps");
        incident(lamps.id(), "CLOSED", "LOW", null, TO.minusSeconds(3600), Duration.ofHours(30));

        assertThat(report("category"))
                .containsExactly(new Row(lamps.name(), 1, 0, 1, 0, 30.0, 30.0));
    }

    // T2: names go alphabetically with accents and lowercase in their place, which neither the
    // order of insertion nor a byte order of the database would give.
    @Test
    void namesGoAlphabeticallyWithAccentsAndLowercaseInPlace() throws Exception {
        for (var name : List.of("Zeta", "bancos", "árboles")) {
            incident(categoryNamed(name), "REPORTED", null, null, INSIDE, null);
        }

        assertThat(report("category"))
                .extracting(Row::group)
                .containsExactly("árboles", "bancos", "Zeta");
    }

    // ---------------------------------------------------------------------------------------------
    // T3: CSV negotiation.

    @Test
    void theCsvHasTheSameRowsAsTheJson() throws Exception {
        var data = t2Dataset();

        var response = csv("category");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(TEXT_CSV);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(
                        BOM
                                + HEADER
                                + "\r\n"
                                + data.lamps()
                                + ",5,1,3,1,20.0,38.0\r\n"
                                + data.roads()
                                + ",2,2,0,0,,\r\n"
                                + "Not available,1,0,1,0,5.0,5.0\r\n");
    }

    // T3: for every grouping, the CSV has the rows of the JSON, in the same order.
    @ParameterizedTest
    @ValueSource(strings = {"status", "category", "priority", "department", "district"})
    void everyGroupingHasTheSameRowsInBothRepresentations(String groupBy) throws Exception {
        t2Dataset();

        var fromJson =
                report(groupBy).stream()
                        .map(
                                row ->
                                        String.join(
                                                ",",
                                                row.group() == null ? "Not available" : row.group(),
                                                String.valueOf(row.total()),
                                                String.valueOf(row.open()),
                                                String.valueOf(row.closed()),
                                                String.valueOf(row.rejected()),
                                                decimal(row.resolutionHoursP50()),
                                                decimal(row.resolutionHoursP95())))
                        .toList();

        assertThat(csvRows(groupBy)).isEqualTo(fromJson);
    }

    @Test
    void theCsvQuotesCommasAndQuotes() throws Exception {
        var parks = category("Parks, \"green\" areas");
        incident(parks.id(), "REPORTED", null, null, INSIDE, null);

        assertThat(csvRows("category"))
                .containsExactly("\"" + parks.name().replace("\"", "\"\"") + "\",1,1,0,0,,");
    }

    // T3: without Accept, or with */*, the report is JSON (the contract's default).
    @ParameterizedTest
    @ValueSource(strings = {"", "*/*", "application/json"})
    void anyOtherAcceptGivesJson(String accept) throws Exception {
        var request = request(Role.ANALYST, "category");
        if (!accept.isEmpty()) {
            request = request.header(HttpHeaders.ACCEPT, accept);
        }

        var response = mvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat((List<?>) JsonPath.read(response.getContentAsString(), "$.rows")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // T4: invalid period.

    @Test
    void aPeriodOverOneYearIsA422OnTheFieldTo() throws Exception {
        var response =
                perform(
                        Role.ANALYST,
                        null,
                        "from",
                        "2034-01-01T00:00:00Z",
                        "to",
                        "2035-01-02T00:00:01Z",
                        "groupBy",
                        "status");

        expectFieldError(response, "to");
    }

    @Test
    void fromAfterToIsA422OnTheFieldTo() throws Exception {
        var response =
                perform(
                        Role.ANALYST,
                        null,
                        "from",
                        "2034-03-02T00:00:00Z",
                        "to",
                        "2034-03-01T00:00:00Z",
                        "groupBy",
                        "status");

        expectFieldError(response, "to");
    }

    @Test
    void anEmptyPeriodIsA422OnTheFieldTo() throws Exception {
        expectFieldError(
                perform(
                        Role.ANALYST,
                        null,
                        "from",
                        FROM.toString(),
                        "to",
                        FROM.toString(),
                        "groupBy",
                        "status"),
                "to");
    }

    // T4, limit: exactly 366 days is allowed, and so is an offset other than Z.
    @Test
    void aPeriodOfExactly366DaysIsAllowed() throws Exception {
        var response =
                perform(
                        Role.ANALYST,
                        null,
                        "from",
                        "2034-01-01T01:00:00+01:00",
                        "to",
                        "2035-01-02T00:00:00Z",
                        "groupBy",
                        "status");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------------
    // T5: only ANALYST and ADMINISTRATOR read reports.

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"CITIZEN", "TECHNICIAN", "MUNICIPAL_OPERATOR"})
    void otherRolesGet403(Role role) throws Exception {
        expectForbidden(mvc.perform(request(role, "status")).andReturn().getResponse());
    }

    // T5: the role is checked before the parameters, so a wrong query does not hide the 403.
    @Test
    void otherRolesGet403EvenWithAnInvalidQuery() throws Exception {
        expectForbidden(perform(Role.CITIZEN, null, "groupBy", "foo"));
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"ANALYST", "ADMINISTRATOR"})
    void analystsAndAdministratorsReadIt(Role role) throws Exception {
        assertThat(mvc.perform(request(role, "status")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------------
    // T8 and T9: a query the API cannot read.

    @Test
    void aMissingFromOrToIsA400() throws Exception {
        expectMalformed(perform(Role.ANALYST, null, "to", TO.toString(), "groupBy", "status"));
        expectMalformed(perform(Role.ANALYST, null, "from", FROM.toString(), "groupBy", "status"));
    }

    // T8, limits: an empty value, a date-time without its offset, or a year PostgreSQL cannot hold.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "2034-03-01T00:00:00",
                "+10000-01-01T00:00:00Z",
                "+300000-01-01T00:00:00Z"
            })
    void aFromThatIsNotAnInstantIsA400(String from) throws Exception {
        expectMalformed(
                perform(
                        Role.ANALYST,
                        null,
                        "from",
                        from,
                        "to",
                        TO.toString(),
                        "groupBy",
                        "status"));
    }

    // T8 and T9, limit: each parameter goes once; a repeated one is not guessed.
    @Test
    void aRepeatedParameterIsA400() throws Exception {
        expectMalformed(
                mvc.perform(request(Role.ANALYST, "status").param("groupBy", "category"))
                        .andReturn()
                        .getResponse());
        expectMalformed(
                mvc.perform(request(Role.ANALYST, "status").param("from", "2034-03-02T00:00:00Z"))
                        .andReturn()
                        .getResponse());
    }

    @Test
    void anUnknownGroupingIsA400() throws Exception {
        expectMalformed(mvc.perform(request(Role.ANALYST, "foo")).andReturn().getResponse());
        expectMalformed(perform(Role.ANALYST, null, "from", FROM.toString(), "to", TO.toString()));
    }

    // T9, limit: groupBy uses the lowercase values of the contract, and nothing else is accepted.
    @Test
    void anUppercaseGroupingOrAnUnknownParameterIsA400() throws Exception {
        expectMalformed(mvc.perform(request(Role.ANALYST, "STATUS")).andReturn().getResponse());
        expectMalformed(
                mvc.perform(request(Role.ANALYST, "status").param("district", "Centro"))
                        .andReturn()
                        .getResponse());
    }

    // ---------------------------------------------------------------------------------------------
    // T10: no data.

    @Test
    void aPeriodWithoutIncidentsHasNoRowsAndACsvWithOnlyItsHeader() throws Exception {
        assertThat(report("status")).isEmpty();

        assertThat(csv("status").getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(BOM + HEADER + "\r\n");
    }

    // ---------------------------------------------------------------------------------------------
    // T11: not available is a group of its own, never zero or empty.

    @Test
    void incidentsWithoutDistrictOrAssignmentAreNotAvailable() throws Exception {
        incident(null, "REPORTED", null, null, INSIDE, null);
        incident(null, "VALIDATED", null, "", INSIDE, null);
        // Its only assignment is no longer active: it has no department now.
        var unassigned = incident(null, "VALIDATED", null, null, INSIDE, null);
        assign(unassigned, department("Roads").id(), false, INSIDE);

        assertThat(report("district")).containsExactly(new Row(null, 3, 3, 0, 0, null, null));
        assertThat(report("department")).containsExactly(new Row(null, 3, 3, 0, 0, null, null));
        assertThat(csvRows("district")).containsExactly("Not available,3,3,0,0,,");
        assertThat(csvRows("department")).containsExactly("Not available,3,3,0,0,,");
    }

    // ---------------------------------------------------------------------------------------------
    // T12: none closed.

    @Test
    void aGroupWithoutClosedIncidentsHasNullPercentilesNeverZero() throws Exception {
        var lamps = category("Lamps");
        incident(lamps.id(), "RESOLVED", "LOW", null, INSIDE, null);
        incident(lamps.id(), "REJECTED", null, null, INSIDE, null);

        var body = json("category");

        assertThat((Object) JsonPath.read(body, "$.rows[0].resolutionHoursP50")).isNull();
        assertThat((Object) JsonPath.read(body, "$.rows[0].resolutionHoursP95")).isNull();
        assertThat(csvRows("category")).containsExactly(lamps.name() + ",2,1,0,1,,");
    }

    // ---------------------------------------------------------------------------------------------
    // T13: CSV injection.

    @Test
    void aCategoryThatStartsAsAFormulaIsEscaped() throws Exception {
        for (var name : List.of("=SUM(A1)", "+34", "-1", "@cmd")) {
            var formula = categoryNamed(name);
            incident(formula, "REPORTED", null, null, INSIDE, null);
        }

        // The order of symbols is the collator's business, not this test's: only the escape counts.
        assertThat(csvRows("category"))
                .containsExactlyInAnyOrder(
                        "'=SUM(A1),1,1,0,0,,",
                        "'+34,1,1,0,0,,",
                        "'-1,1,1,0,0,,",
                        "'@cmd,1,1,0,0,,");
    }

    // T13: every text cell is escaped, not only the category: a district or a department too.
    @Test
    void aDistrictOrDepartmentThatStartsAsAFormulaIsEscaped() throws Exception {
        var incident = incident(null, "REPORTED", null, "=HYPERLINK(\"x\")", INSIDE, null);
        var formula =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES ('@SUM(1)') RETURNING id",
                        UUID.class);
        assign(incident, formula, true, INSIDE);

        assertThat(csvRows("district")).containsExactly("\"'=HYPERLINK(\"\"x\"\")\",1,1,0,0,,");
        assertThat(csvRows("department")).containsExactly("'@SUM(1),1,1,0,0,,");
    }

    // T13: the JSON keeps the real name; only the CSV, which a spreadsheet opens, escapes it.
    @Test
    void theJsonKeepsTheNameAsItIs() throws Exception {
        var formula = categoryNamed("=1+1");
        incident(formula, "REPORTED", null, null, INSIDE, null);

        assertThat(report("category")).containsExactly(new Row("=1+1", 1, 1, 0, 0, null, null));
    }

    // ---------------------------------------------------------------------------------------------
    // T6, the part that can be automated: no personal data in any grouping or representation.

    @ParameterizedTest
    @ValueSource(strings = {"status", "category", "priority", "department", "district"})
    void theReportHoldsOnlyTheColumnsOfTheContract(String groupBy) throws Exception {
        t2Dataset();

        List<Map<String, Object>> rows = JsonPath.read(json(groupBy), "$.rows");
        assertThat(rows)
                .allSatisfy(
                        row ->
                                assertThat(row.keySet())
                                        .containsExactlyInAnyOrder(
                                                "group",
                                                "total",
                                                "open",
                                                "closed",
                                                "rejected",
                                                "resolutionHoursP50",
                                                "resolutionHoursP95"));
        for (var body :
                List.of(json(groupBy), csv(groupBy).getContentAsString(StandardCharsets.UTF_8))) {
            // The reporter's name and email, and the exact position, of every incident.
            assertThat(body).doesNotContain("Test citizen", "example.com", "-4.4214", "36.7213");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // T14: errors stay problem+json when the client asks for CSV.

    @Test
    void a422AskingForCsvIsProblemJson() throws Exception {
        expectFieldError(
                perform(
                        Role.ANALYST,
                        TEXT_CSV,
                        "from",
                        "2034-03-02T00:00:00Z",
                        "to",
                        "2034-03-01T00:00:00Z",
                        "groupBy",
                        "status"),
                "to");
    }

    @Test
    void a403AskingForCsvIsProblemJson() throws Exception {
        expectForbidden(
                mvc.perform(
                                request(Role.MUNICIPAL_OPERATOR, "status")
                                        .header(HttpHeaders.ACCEPT, TEXT_CSV))
                        .andReturn()
                        .getResponse());
    }

    @Test
    void a400AskingForCsvIsProblemJson() throws Exception {
        expectMalformed(perform(Role.ANALYST, TEXT_CSV, "groupBy", "status"));
    }

    // ---------------------------------------------------------------------------------------------
    // T16: no token.

    @ParameterizedTest
    @ValueSource(strings = {"application/json", TEXT_CSV})
    void withoutATokenIsA401(String accept) throws Exception {
        var response =
                mvc.perform(
                                get(REPORTS)
                                        .header(HttpHeaders.ACCEPT, accept)
                                        .param("from", FROM.toString())
                                        .param("to", TO.toString())
                                        .param("groupBy", "status"))
                        .andReturn()
                        .getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    // ---------------------------------------------------------------------------------------------
    // Data.

    /** One row of the report, as the contract describes it. */
    private record Row(
            String group,
            long total,
            long open,
            long closed,
            long rejected,
            Double resolutionHoursP50,
            Double resolutionHoursP95) {}

    private record Named(UUID id, String name) {}

    /** A category with a unique name, so it never meets one of another test. */
    private Named category(String name) {
        var unique = name + " " + UUID.randomUUID();
        return new Named(categoryNamed(unique), unique);
    }

    private UUID categoryNamed(String name) {
        return jdbc.queryForObject(
                "INSERT INTO category (name) VALUES (?) RETURNING id", UUID.class, name);
    }

    private Named department(String name) {
        var unique = name + " " + UUID.randomUUID();
        var id =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES (?) RETURNING id",
                        UUID.class,
                        unique);
        return new Named(id, unique);
    }

    /**
     * An incident with its location.
     *
     * @param priority may be null, as before validation
     * @param district may be null or blank, as when the address is not resolved
     * @param closedAfter the time from reported to closed of a {@code CLOSED} incident, else null
     */
    private UUID incident(
            UUID categoryId,
            String status,
            String priority,
            String district,
            Instant reportedAt,
            Duration closedAfter) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, reported_at,"
                                + " updated_at, closed_at)"
                                + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?, ?, ?,"
                                + " ?, ?, ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        categoryId,
                        status,
                        priority,
                        priority == null ? null : "Set by the test",
                        priority == null ? null : "MANUAL",
                        Timestamp.from(reportedAt),
                        // A closed incident last changed when it closed, as the lifecycle does.
                        Timestamp.from(
                                closedAfter == null ? reportedAt : reportedAt.plus(closedAfter)),
                        closedAfter == null ? null : Timestamp.from(reportedAt.plus(closedAfter)));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates, district) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography, ?)",
                incident,
                district);
        return incident;
    }

    private void assign(UUID incident, UUID department, boolean active, Instant assignedAt) {
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, assigned_at, is_active)"
                        + " VALUES (?, ?, ?, ?)",
                incident,
                department,
                Timestamp.from(assignedAt),
                active);
    }

    // ---------------------------------------------------------------------------------------------
    // Requests.

    /** The report of March 2034 as an analyst, grouped by {@code groupBy}. */
    private MockHttpServletRequestBuilder request(Role role, String groupBy) {
        return get(REPORTS)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(role))
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("groupBy", groupBy);
    }

    /** {@code GET /incident-reports} with exactly these query parameters, as name, value pairs. */
    private MockHttpServletResponse perform(Role role, String accept, String... nameValuePairs)
            throws Exception {
        var request = get(REPORTS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(role));
        if (accept != null) {
            request = request.header(HttpHeaders.ACCEPT, accept);
        }
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request = request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private String json(String groupBy) throws Exception {
        var response =
                mvc.perform(
                                request(Role.ANALYST, groupBy)
                                        .header(HttpHeaders.ACCEPT, "application/json"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Row> report(String groupBy) throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(json(groupBy), "$.rows");
        return rows.stream()
                .map(
                        row ->
                                new Row(
                                        (String) row.get("group"),
                                        ((Number) row.get("total")).longValue(),
                                        ((Number) row.get("open")).longValue(),
                                        ((Number) row.get("closed")).longValue(),
                                        ((Number) row.get("rejected")).longValue(),
                                        hours(row.get("resolutionHoursP50")),
                                        hours(row.get("resolutionHoursP95"))))
                .toList();
    }

    /** A decimal as the contract writes it in the CSV: a dot, and an empty cell when absent. */
    private static String decimal(Double value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Double hours(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private MockHttpServletResponse csv(String groupBy) throws Exception {
        return mvc.perform(request(Role.ANALYST, groupBy).header(HttpHeaders.ACCEPT, TEXT_CSV))
                .andReturn()
                .getResponse();
    }

    /** The data lines of the CSV, after checking its BOM, header and line ends. */
    private List<String> csvRows(String groupBy) throws Exception {
        var response = csv(groupBy);
        assertThat(response.getStatus()).isEqualTo(200);
        var text = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).startsWith(BOM + HEADER + "\r\n").endsWith("\r\n");
        var lines = List.of(text.substring(BOM.length()).split("\r\n"));
        return lines.subList(1, lines.size());
    }

    // ---------------------------------------------------------------------------------------------
    // Expected errors.

    private static void expectFieldError(MockHttpServletResponse response, String field)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        var body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat((List<String>) JsonPath.read(body, "$.errors[*].field")).containsExactly(field);
    }

    private static void expectMalformed(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(
                        (String)
                                JsonPath.read(
                                        response.getContentAsString(StandardCharsets.UTF_8),
                                        "$.code"))
                .isEqualTo("MALFORMED_REQUEST");
    }

    private static void expectForbidden(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }
}
