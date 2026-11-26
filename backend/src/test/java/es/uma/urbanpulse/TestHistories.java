package es.uma.urbanpulse;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes the history that a test wrote. The trigger of V17 refuses {@code DELETE} on {@code
 * status_change}, so the clean-up turns the triggers off for its own transaction only ({@code SET
 * LOCAL session_replication_role = replica}, which the superuser of the test database may do). It
 * also turns off the foreign keys of that transaction: delete the incidents after it, as before.
 *
 * <p>Every status change of a citizen's incident creates its notifications (#373), which reference
 * the change, and a push notification has its deliveries. They are deleted first, with the entries
 * they belong to, so no notification is left pointing to a deleted entry, and the incident and its
 * reporter can be deleted after.
 */
public final class TestHistories {

    private TestHistories() {}

    /**
     * Deletes the entries of {@code status_change} that match {@code condition}, such as {@code
     * "incident_id = ?"}, with its arguments, and the notifications and push deliveries of those
     * entries.
     */
    public static void delete(JdbcTemplate jdbc, String condition, Object... args) {
        String entries = "SELECT id FROM status_change WHERE " + condition;
        String notifications =
                "SELECT id FROM notification WHERE status_change_id IN (" + entries + ")";
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            boolean autoCommit = connection.getAutoCommit();
                            connection.setAutoCommit(false);
                            try (var off = connection.createStatement();
                                    var deliveries =
                                            connection.prepareStatement(
                                                    "DELETE FROM push_delivery WHERE notification_id"
                                                            + " IN ("
                                                            + notifications
                                                            + ")");
                                    var notificationRows =
                                            connection.prepareStatement(
                                                    "DELETE FROM notification WHERE id IN ("
                                                            + notifications
                                                            + ")");
                                    var history =
                                            connection.prepareStatement(
                                                    "DELETE FROM status_change WHERE "
                                                            + condition)) {
                                off.execute("SET LOCAL session_replication_role = replica");
                                // Children first, although the foreign keys are off here.
                                for (PreparedStatement delete :
                                        new PreparedStatement[] {
                                            deliveries, notificationRows, history
                                        }) {
                                    for (int i = 0; i < args.length; i++) {
                                        delete.setObject(i + 1, args[i]);
                                    }
                                    delete.executeUpdate();
                                }
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
