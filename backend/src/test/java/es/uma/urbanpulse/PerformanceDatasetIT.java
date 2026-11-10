package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

/**
 * The performance dataset of the {@code perf} profile (#84, docs/datasets.md). CI does not load the
 * default volume: this class generates a small one on its own PostGIS container, which is enough to
 * catch a schema migration or a lifecycle rule the generator no longer fits, and to check that it
 * is deterministic.
 */
@SpringBootTest(properties = "PERF_INCIDENTS=" + PerformanceDatasetIT.INCIDENTS)
@ActiveProfiles("perf")
@Import(TestcontainersConfiguration.class)
class PerformanceDatasetIT {

    static final int INCIDENTS = 400;

    private static final String DATASET = "db/perf-data/R__perf_data.sql";
    private static final String VOLUME_PLACEHOLDER = "${perf_incidents}";

    /** One citizen per twenty incidents, at least ten, and the fixed staff. */
    private static final int CITIZENS = Math.max(INCIDENTS / 20, 10);

    private static final int STAFF = 10 + 40 + 2 + 3;

    /**
     * Row count and checksum of the generated tables, the query docs/datasets.md gives to compare
     * two loads. Instants go through their epoch, so the session's time zone does not change it.
     */
    static final String CHECKSUMS =
            """
            SELECT 'incident' AS rows, count(*) AS total, md5(string_agg(id::text || status
                || coalesce(priority, '') || coalesce(category_id::text, '')
                || extract(epoch FROM reported_at) || extract(epoch FROM updated_at), ',' ORDER BY id)) AS checksum
              FROM incident
            UNION ALL
            SELECT 'status_change', count(*), md5(string_agg(id::text || new_status || actor_id::text
                || extract(epoch FROM instant), ',' ORDER BY id))
              FROM status_change
            UNION ALL
            SELECT 'location', count(*), md5(string_agg(id::text || ST_AsText(coordinates::geometry)
                || coalesce(address, ''), ',' ORDER BY id))
              FROM location
            UNION ALL
            SELECT 'users', count(*), md5(string_agg(id::text || email || password_hash, ',' ORDER BY id))
              FROM users WHERE id::text LIKE 'f0000000-%'
            ORDER BY 1
            """;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void generatesTheConfiguredVolume() {
        assertThat(count("SELECT count(*) FROM incident WHERE id::text LIKE 'f1000000-%'"))
                .isEqualTo(INCIDENTS);
        assertThat(count("SELECT count(*) FROM location")).isEqualTo(INCIDENTS);
        assertThat(count("SELECT count(*) FROM users WHERE id::text LIKE 'f0000000-%'"))
                .isEqualTo(CITIZENS + STAFF);
        for (var table : List.of("status_change", "assignment", "comment", "attachment")) {
            assertThat(count("SELECT count(*) FROM " + table)).as(table).isPositive();
        }
        assertThat(count("SELECT count(*) FROM notification")).isPositive();
    }

    @Test
    void coversEveryIncidentStatus() {
        var statuses = jdbc.queryForList("SELECT DISTINCT status FROM incident", String.class);

        assertThat(statuses)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(IncidentStatus.values()).map(Enum::name).toList());
    }

    @Test
    void everyHistoryIsMadeOfTransitionsOfTheTable() {
        new DatasetHistories(jdbc).assertEveryHistoryFollowsTheTransitionTable();
    }

    /** Only comments the API could create (#50): internal, and written by staff. */
    @Test
    void seedsOnlyInternalCommentsWrittenByStaff() {
        assertThat(
                        count(
                                "SELECT count(*) FROM comment c JOIN users u ON u.id = c.author_id"
                                        + " WHERE c.visible_to_citizen OR u.role = 'CITIZEN'"))
                .isZero();
    }

    @Test
    void spreadsTheReportsOverMonths() {
        var months =
                count(
                        "SELECT count(DISTINCT date_trunc('month', reported_at AT TIME ZONE 'UTC'))"
                                + " FROM incident");

        assertThat(months).isGreaterThanOrEqualTo(11);
    }

    @Test
    void givesAFewCitizensManyIncidents() {
        var reportsOfTheTopTenth =
                count(
                        "SELECT coalesce(sum(total), 0) FROM (SELECT count(*) AS total FROM incident"
                                + " GROUP BY reporter_id ORDER BY total DESC LIMIT "
                                + CITIZENS / 10
                                + ") AS top");

        assertThat(reportsOfTheTopTenth).isGreaterThan(INCIDENTS * 30 / 100);
    }

    @Test
    void placesEveryIncidentInMalagaAndClustersThemInTheCentre() {
        var outside =
                count(
                        "SELECT count(*) FROM location WHERE NOT ST_Intersects(coordinates,"
                                + " ST_MakeEnvelope(-4.60, 36.65, -4.30, 36.80, 4326)::geography)");
        var nearTheCentre =
                count(
                        "SELECT count(*) FROM location WHERE ST_DWithin(coordinates,"
                                + " ST_SetSRID(ST_MakePoint(-4.4210, 36.7210), 4326)::geography,"
                                + " 1500)");

        assertThat(outside).isZero();
        assertThat(nearTheCentre).isGreaterThan(INCIDENTS / 5);
    }

    @Test
    void leavesSomePlacesNotAvailable() {
        assertThat(count("SELECT count(*) FROM location WHERE address IS NULL")).isPositive();
        assertThat(count("SELECT count(*) FROM location WHERE accuracy IS NULL")).isPositive();
    }

    @Test
    void leavesTheReferenceDataUnchanged() {
        assertThat(count("SELECT count(*) FROM category WHERE is_active")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM department WHERE is_active")).isEqualTo(5);
    }

    @Test
    void loadingItAgainGivesTheSameRows() throws IOException {
        List<Map<String, Object>> firstLoad = jdbc.queryForList(CHECKSUMS);
        var script =
                new ClassPathResource(DATASET)
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace(VOLUME_PLACEHOLDER, String.valueOf(INCIDENTS));

        new ResourceDatabasePopulator(
                        new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);

        assertThat(jdbc.queryForList(CHECKSUMS)).isEqualTo(firstLoad);
        assertThat(firstLoad).allSatisfy(row -> assertThat((Long) row.get("total")).isPositive());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
