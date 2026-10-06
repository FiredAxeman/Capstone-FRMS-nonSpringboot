import java.sql.SQLException;

/**
 * A validation or concurrency conflict that must not be retried as an outage.
 */
public class RecordConflictException extends SQLException {
    public RecordConflictException(String message) {
        super(message);
    }
}
