import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;

public class DatabaseManager {
    // Replace with your database URL, user, and password
    private static final String DB_URL = "jdbc:sqlite:C:/Users/X-BOX/DataGripProjects/FRMS/identifier.sqlite";
    private static final int MAX_RETRIES = 3;
    private Connection connection;

    // Establish a secure connection with retry logic
    public Connection establishConnection() {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            try {
                if (connection == null || connection.isClosed()) {
                    connection = DriverManager.getConnection(DB_URL);
                }
                return connection;
            } catch (SQLException e) {
                attempts++;
                System.err.println("Database connection failed. Attempt " + attempts + " of " + MAX_RETRIES);
                try {
                    Thread.sleep(1000); // 1-second backoff
                } catch (InterruptedException ignored) {}
            }
        }
        System.err.println("Connection failed: Switching to local fallback / graceful degradation.");
        return null;
    }

    // Insert a DutyPeriod linked to a Pilot username
    public boolean saveDutyPeriod(String pilotUsername, DutyPeriod dutyPeriod) {
        String sql = "INSERT INTO duty_periods (pilot_username, start_time, end_time, duration_minutes) VALUES (?, ?, ?, ?)";
        Connection conn = establishConnection();
        if (conn == null) {
            return false; // Triggers offline fallback
        }

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, pilotUsername);
            pstmt.setTimestamp(2, Timestamp.valueOf(dutyPeriod.getStartTime()));
            pstmt.setTimestamp(3, Timestamp.valueOf(dutyPeriod.getEndTime()));
            pstmt.setLong(4, dutyPeriod.calculateDuration());

            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("Error persisting DutyPeriod: " + e.getMessage());
            return false;
        }
    }

    // Load all historical DutyPeriods for a specific Pilot
    public ArrayList<DutyPeriod> loadDutyHistory(String pilotUsername) {
        ArrayList<DutyPeriod> history = new ArrayList<>();
        String sql = "SELECT start_time, end_time FROM duty_periods WHERE pilot_username = ? ORDER BY start_time ASC";
        Connection conn = establishConnection();
        if (conn == null) {
            return history;
        }

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, pilotUsername);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    LocalDateTime start = rs.getTimestamp("start_time").toLocalDateTime();
                    LocalDateTime end = rs.getTimestamp("end_time").toLocalDateTime();
                    history.add(new DutyPeriod(start, end));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error reading duty history: " + e.getMessage());
        }
        return history;
    }
}