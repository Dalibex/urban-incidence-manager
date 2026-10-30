package es.uma.urbanpulse;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base class of the persistence tests of the JPA entities and repositories (#21).
 *
 * <p>Each test runs against the PostGIS container of {@link TestcontainersConfiguration}, with the
 * schema created only by Flyway and Hibernate in {@code ddl-auto=validate} (ADR-0014), inside a
 * transaction that is rolled back at the end, so tests never see each other's data.
 *
 * <p>It uses the same configuration as the other {@code @SpringBootTest} integration tests, so
 * Spring reuses their application context and container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
public abstract class PersistenceTestSupport {

    @Autowired protected EntityManager entityManager;

    @Autowired private JdbcTemplate jdbc;

    /**
     * Writes the pending changes and empties the persistence context. Call it after saving and
     * before reading back: otherwise {@code findById} returns the object from Hibernate's
     * first-level cache and the test never reads from the database.
     */
    protected void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Reads one value with native SQL in the test transaction, after flushing the pending changes.
     * Use it to check how a column is really stored (an enum name, the coordinates of a point).
     */
    protected <T> T queryNative(String sql, Class<T> type, Object... args) {
        entityManager.flush();
        return jdbc.queryForObject(sql, type, args);
    }
}
