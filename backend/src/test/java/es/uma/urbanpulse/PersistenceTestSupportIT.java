package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;

/** The conditions that every persistence test of #21 relies on. */
class PersistenceTestSupportIT extends PersistenceTestSupport {

    // A point in Málaga (WGS 84): PostGIS stores X = longitude and Y = latitude.
    private static final double MALAGA_LONGITUDE = -4.4214;
    private static final double MALAGA_LATITUDE = 36.7213;
    private static final double COORDINATE_TOLERANCE = 1e-9;

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void runsEveryTestInATransactionThatIsRolledBack() {
        assertThat(TestTransaction.isActive()).isTrue();
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
    }

    @Test
    void validatesTheEntitiesAgainstTheFlywaySchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void queryNativeSeesTheRowsWrittenInTheTestTransaction() {
        String email = "persistence-" + UUID.randomUUID() + "@example.test";
        jdbc.update(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES ('Test Citizen', ?, 'not-a-real-hash', 'CITIZEN')",
                email);

        flushAndClear();

        assertThat(queryNative("SELECT count(*) FROM users WHERE email = ?", Long.class, email))
                .isEqualTo(1L);
        assertThat(queryNative("SELECT role FROM users WHERE email = ?", String.class, email))
                .isEqualTo("CITIZEN");
    }

    @Test
    void queryNativeReadsPointsWithLongitudeAsX() {
        String point = "ST_SetSRID(ST_MakePoint(?, ?), 4326)";

        assertThat(
                        queryNative(
                                "SELECT ST_X(" + point + ")",
                                Double.class,
                                MALAGA_LONGITUDE,
                                MALAGA_LATITUDE))
                .isCloseTo(MALAGA_LONGITUDE, within(COORDINATE_TOLERANCE));
        assertThat(
                        queryNative(
                                "SELECT ST_Y(" + point + ")",
                                Double.class,
                                MALAGA_LONGITUDE,
                                MALAGA_LATITUDE))
                .isCloseTo(MALAGA_LATITUDE, within(COORDINATE_TOLERANCE));
        assertThat(
                        queryNative(
                                "SELECT ST_SRID(" + point + ")",
                                Integer.class,
                                MALAGA_LONGITUDE,
                                MALAGA_LATITUDE))
                .isEqualTo(4326);
    }
}
