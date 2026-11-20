package es.uma.urbanpulse;

import java.sql.SQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes the history that a test wrote. The trigger of V17 refuses {@code DELETE} on {@code
 * status_change}, so the clean-up turns the triggers off for its own transaction only ({@code SET
 * LOCAL session_replication_role = replica}, which the superuser of the test database may do). It
 * also turns off the foreign keys of that transaction: delete the incidents after it, as before.
 */
public final class TestHistories {

    private TestHistories() {}

    /**
     * Deletes the entries of {@code status_change} that match {@code condition}, such as {@code
     * "incident_id = ?"}, with its arguments.
     */
    public static void delete(JdbcTemplate jdbc, String condition, Object... args) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            boolean autoCommit = connection.getAutoCommit();
                            connection.setAutoCommit(false);
                            try (var off = connection.createStatement();
                                    var delete =
                                            connection.prepareStatement(
                                                    "DELETE FROM status_change WHERE "
                                                            + condition)) {
                                off.execute("SET LOCAL session_replication_role = replica");
                                for (int i = 0; i < args.length; i++) {
                                    delete.setObject(i + 1, args[i]);
                                }
                                delete.executeUpdate();
                                connection.commit();
                            } catch (SQLException | RuntimeException e) {
                                connection.rollback();
                                throw e;
                            } finally {
                                connection.setAutoCommit(autoCommit);
                            }
                            return null;
                        });
    }
}
