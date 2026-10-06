import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Embedded SQL persistence, additive migrations, transactional CRUD, and durable audit records.
 */
@SuppressWarnings("SqlResolve") // The schema is created at runtime; integration tests verify it against SQLite.
public class DatabaseManager {
    private final String databaseUrl;
    private final Path databasePath;

    public DatabaseManager() throws SQLException, IOException {
        this(resolveDatabasePath());
    }

    public DatabaseManager(Path path) throws SQLException, IOException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC is missing. Import pom.xml as a Maven project and reload dependencies.", exception);
        }
        this.databasePath = path.toAbsolutePath().normalize();
        Files.createDirectories(databasePath.getParent());
        this.databaseUrl = "jdbc:sqlite:" + databasePath;
        initializeSchema();
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    protected Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 250");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > 2) {
                    throw new SQLException("This database belongs to a newer FRMS version.");
                }
            }
            statement.execute("PRAGMA journal_mode = WAL");
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE IF NOT EXISTS pilots (username TEXT PRIMARY KEY, password_hash TEXT NOT NULL,"
                    + " role TEXT NOT NULL, cumulative_flight_hours INTEGER NOT NULL CHECK(cumulative_flight_hours >= 0),"
                    + " certification_status TEXT NOT NULL, base_assignment TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS duty_periods (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " pilot_username TEXT NOT NULL REFERENCES pilots(username) ON DELETE CASCADE,"
                    + " start_time TEXT NOT NULL, end_time TEXT NOT NULL,"
                    + " duration_minutes INTEGER NOT NULL CHECK(duration_minutes > 0))");
                ensureColumn(connection, "pilots", "display_name", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(connection, "pilots", "zone_id", "TEXT NOT NULL DEFAULT '" + ZoneId.systemDefault().getId() + "'");
                Map<String, String> columns = Map.ofEntries(
                    Map.entry("record_key", "TEXT"), Map.entry("revision", "INTEGER NOT NULL DEFAULT 0"),
                    Map.entry("release_time", "TEXT"), Map.entry("zone_id", "TEXT NOT NULL DEFAULT '" + ZoneId.systemDefault().getId() + "'"),
                    Map.entry("flight_known", "INTEGER NOT NULL DEFAULT 0"), Map.entry("acclimated", "INTEGER NOT NULL DEFAULT 1"),
                    Map.entry("sleep_confirmed", "INTEGER NOT NULL DEFAULT 0"), Map.entry("fit_for_duty", "INTEGER NOT NULL DEFAULT 0"),
                    Map.entry("special_operation", "INTEGER NOT NULL DEFAULT 0"), Map.entry("extension_minutes", "INTEGER NOT NULL DEFAULT 0"),
                    Map.entry("unforeseen", "INTEGER NOT NULL DEFAULT 0"), Map.entry("pic_approved", "INTEGER NOT NULL DEFAULT 0"),
                    Map.entry("carrier_approved", "INTEGER NOT NULL DEFAULT 0"), Map.entry("notes", "TEXT NOT NULL DEFAULT ''"));
                for (Map.Entry<String, String> column : columns.entrySet()) {
                    ensureColumn(connection, "duty_periods", column.getKey(), column.getValue());
                }
                statement.executeUpdate("UPDATE pilots SET display_name = username WHERE display_name = ''");
                statement.executeUpdate("UPDATE duty_periods SET release_time = end_time WHERE release_time IS NULL");
                List<Long> legacyIds = new ArrayList<>();
                try (ResultSet result = statement.executeQuery("SELECT id FROM duty_periods WHERE record_key IS NULL")) {
                    while (result.next()) {
                        legacyIds.add(result.getLong(1));
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("UPDATE duty_periods SET record_key = ? WHERE id = ?")) {
                    for (long id : legacyIds) {
                        update.setString(1, UUID.randomUUID().toString());
                        update.setLong(2, id);
                        update.addBatch();
                    }
                    update.executeBatch();
                }
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_duty_record_key ON duty_periods(record_key)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_duty_pilot_start ON duty_periods(pilot_username, start_time)");
                statement.execute("CREATE TABLE IF NOT EXISTS flight_legs (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " duty_key TEXT NOT NULL REFERENCES duty_periods(record_key) ON DELETE CASCADE,"
                    + " start_epoch INTEGER NOT NULL, end_epoch INTEGER NOT NULL CHECK(end_epoch > start_epoch))");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_flight_duty ON flight_legs(duty_key)");
                statement.execute("CREATE TABLE IF NOT EXISTS rest_periods (record_key TEXT PRIMARY KEY, revision INTEGER NOT NULL DEFAULT 0,"
                    + " pilot_username TEXT NOT NULL REFERENCES pilots(username) ON DELETE CASCADE, start_time TEXT NOT NULL,"
                    + " end_time TEXT NOT NULL, zone_id TEXT NOT NULL, sleep_minutes INTEGER NOT NULL CHECK(sleep_minutes >= 0), notes TEXT NOT NULL)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_rest_pilot ON rest_periods(pilot_username)");
                statement.execute("CREATE TABLE IF NOT EXISTS processed_commands (command_id TEXT PRIMARY KEY, completed_at TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS alerts (alert_key TEXT PRIMARY KEY,"
                    + " pilot_username TEXT NOT NULL REFERENCES pilots(username) ON DELETE CASCADE, source_key TEXT NOT NULL,"
                    + " rule TEXT NOT NULL, severity TEXT NOT NULL, message TEXT NOT NULL, created_at TEXT NOT NULL,"
                    + " acknowledged_by TEXT, acknowledgement TEXT, resolved INTEGER NOT NULL DEFAULT 0)");
                statement.execute("CREATE TABLE IF NOT EXISTS audit_events (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT NOT NULL,"
                    + " actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL)");
                normalizeLegacyIdentities(connection);
                statement.executeUpdate("UPDATE pilots SET role=CASE LOWER(role) WHEN 'administrator' THEN 'Administrator' "
                    + "WHEN 'dispatcher' THEN 'Dispatcher' WHEN 'chief flight instructor' THEN 'Dispatcher' ELSE 'Pilot' END");
                statement.execute("PRAGMA user_version = 2");
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        // Identifiers and definitions here come exclusively from the fixed migration above.
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void normalizeLegacyIdentities(Connection connection) throws SQLException {
        Map<String, String> canonical = new HashMap<>();
        Map<String, String> originals = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT username FROM pilots")) {
            while (result.next()) {
                String original = result.getString(1);
                String normalized = original.trim().toLowerCase(java.util.Locale.ROOT);
                if (!normalized.matches("[a-z0-9._-]{1,64}") || originals.putIfAbsent(normalized, original) != null) {
                    throw new SQLException("Legacy usernames need a unique, valid account identifier before migration. Original records were preserved.");
                }
                if (!original.equals(normalized)) {
                    canonical.put(original, normalized);
                }
            }
        }
        if (canonical.isEmpty()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA defer_foreign_keys=ON");
        }
        for (Map.Entry<String, String> entry : canonical.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE pilots SET username=? WHERE username=?")) {
                statement.setString(1, entry.getValue());
                statement.setString(2, entry.getKey());
                statement.executeUpdate();
            }
            for (String table : List.of("duty_periods", "rest_periods", "alerts")) {
                try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table + " SET pilot_username=? WHERE pilot_username=?")) {
                    statement.setString(1, entry.getValue());
                    statement.setString(2, entry.getKey());
                    statement.executeUpdate();
                }
            }
        }
    }

    public void savePilot(Pilot pilot) throws SQLException {
        try (Connection connection = openConnection()) {
            persistPilot(connection, pilot, true);
        }
    }

    private void persistPilot(Connection connection, Pilot pilot, boolean upsert) throws SQLException {
        String sql = "INSERT INTO pilots (username,password_hash,role,cumulative_flight_hours,certification_status,"
            + "base_assignment,display_name,zone_id) VALUES (?,?,?,?,?,?,?,?)";
        if (upsert) {
            sql += " ON CONFLICT(username) DO UPDATE SET password_hash=excluded.password_hash,role=excluded.role,"
                + "cumulative_flight_hours=excluded.cumulative_flight_hours,certification_status=excluded.certification_status,"
                + "base_assignment=excluded.base_assignment,display_name=excluded.display_name,zone_id=excluded.zone_id";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pilot.getUsername());
            statement.setString(2, pilot.getPasswordHash());
            statement.setString(3, pilot.getRole());
            statement.setInt(4, pilot.getCumulativeFlightHours());
            statement.setString(5, pilot.getCertificationStatus());
            statement.setString(6, pilot.getBaseAssignment());
            statement.setString(7, pilot.getDisplayName());
            statement.setString(8, pilot.getZoneId());
            statement.executeUpdate();
        }
    }

    public Pilot findUser(String username) throws SQLException {
        try (Connection connection = openConnection()) {
            return findUser(connection, username);
        }
    }

    private Pilot findUser(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM pilots WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readPilot(result) : null;
            }
        }
    }

    private Pilot readPilot(ResultSet result) throws SQLException {
        return new Pilot(result.getString("username"), result.getString("password_hash"), result.getString("role"),
            result.getInt("cumulative_flight_hours"), result.getString("certification_status"),
            result.getString("base_assignment"), result.getString("display_name"), result.getString("zone_id"));
    }

    public List<Pilot> loadPilots() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM pilots ORDER BY username")) {
            List<Pilot> users = new ArrayList<>();
            while (result.next()) {
                users.add(readPilot(result));
            }
            return users;
        }
    }

    public FleetData loadFleet() throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            FleetData data = loadFleet(connection);
            connection.commit();
            return data;
        }
    }

    private FleetData loadFleet(Connection connection) throws SQLException {
        Map<String, Pilot> users = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT * FROM pilots")) {
            while (result.next()) {
                Pilot user = readPilot(result);
                users.put(user.getUsername(), user);
            }
        }
        Map<String, List<FlightLeg>> flights = loadLegs(connection);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM duty_periods ORDER BY start_time")) {
            while (result.next()) {
                Pilot pilot = users.get(result.getString("pilot_username"));
                if (pilot != null) {
                    pilot.logDutyPeriod(readDuty(result, flights));
                }
            }
        }
        Map<String, List<RestPeriod>> rests = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM rest_periods ORDER BY start_time")) {
            while (result.next()) {
                rests.computeIfAbsent(result.getString("pilot_username"), key -> new ArrayList<>()).add(readRest(result));
            }
        }
        rests.replaceAll((key, value) -> List.copyOf(value));
        return new FleetData(users, rests);
    }

    private Map<String, List<FlightLeg>> loadLegs(Connection connection) throws SQLException {
        Map<String, List<FlightLeg>> legs = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM flight_legs ORDER BY start_epoch")) {
            while (result.next()) {
                legs.computeIfAbsent(result.getString("duty_key"), key -> new ArrayList<>())
                    .add(new FlightLeg(Instant.ofEpochSecond(result.getLong("start_epoch")),
                        Instant.ofEpochSecond(result.getLong("end_epoch"))));
            }
        }
        return legs;
    }

    private DutyPeriod readDuty(ResultSet result, Map<String, List<FlightLeg>> flights) throws SQLException {
        String key = result.getString("record_key");
        return new DutyPeriod(key, result.getInt("revision"), LocalDateTime.parse(result.getString("start_time")),
            LocalDateTime.parse(result.getString("end_time")), LocalDateTime.parse(result.getString("release_time")),
            result.getString("zone_id"), result.getBoolean("flight_known") ? flights.getOrDefault(key, List.of()) : null,
            result.getBoolean("acclimated"), result.getBoolean("sleep_confirmed"), result.getBoolean("fit_for_duty"),
            result.getBoolean("special_operation"), result.getInt("extension_minutes"), result.getBoolean("unforeseen"),
            result.getBoolean("pic_approved"), result.getBoolean("carrier_approved"), result.getString("notes"));
    }

    private RestPeriod readRest(ResultSet result) throws SQLException {
        return new RestPeriod(result.getString("record_key"), result.getInt("revision"),
            LocalDateTime.parse(result.getString("start_time")), LocalDateTime.parse(result.getString("end_time")),
            result.getString("zone_id"), result.getInt("sleep_minutes"), result.getString("notes"));
    }

    public List<DutyPeriod> loadDutyHistory(String username) throws SQLException {
        Pilot user = loadFleet().users().get(username);
        return user == null ? List.of() : user.getDutyHistory();
    }

    public List<RestPeriod> loadRestHistory(String username) throws SQLException {
        return loadFleet().rest().getOrDefault(username, List.of());
    }

    public void saveDutyPeriod(String username, DutyPeriod duty) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                validateIntervals(loadFleet(connection), username, duty, null);
                insertDuty(connection, username, duty);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void insertDuty(Connection connection, String username, DutyPeriod duty) throws SQLException {
        String sql = "INSERT INTO duty_periods (pilot_username,record_key,revision,start_time,end_time,release_time,zone_id,"
            + "duration_minutes,flight_known,acclimated,sleep_confirmed,fit_for_duty,special_operation,extension_minutes,"
            + "unforeseen,pic_approved,carrier_approved,notes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, duty.getRecordKey());
            statement.setInt(3, duty.getRevision());
            bindDuty(statement, duty, 4);
            statement.executeUpdate();
        }
        insertLegs(connection, duty);
    }

    private void bindDuty(PreparedStatement statement, DutyPeriod duty, int offset) throws SQLException {
        statement.setString(offset++, duty.getStartTime().toString());
        statement.setString(offset++, duty.getEndTime().toString());
        statement.setString(offset++, duty.getReleaseTime().toString());
        statement.setString(offset++, duty.getZoneId());
        statement.setLong(offset++, duty.calculateDuration());
        statement.setBoolean(offset++, duty.hasFlightData());
        statement.setBoolean(offset++, duty.isAcclimated());
        statement.setBoolean(offset++, duty.isSleepOpportunityConfirmed());
        statement.setBoolean(offset++, duty.isFitForDuty());
        statement.setBoolean(offset++, duty.isSpecialOperation());
        statement.setInt(offset++, duty.getExtensionMinutes());
        statement.setBoolean(offset++, duty.isUnforeseen());
        statement.setBoolean(offset++, duty.isPicApproved());
        statement.setBoolean(offset++, duty.isCarrierApproved());
        statement.setString(offset, duty.getNotes());
    }

    private void insertLegs(Connection connection, DutyPeriod duty) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO flight_legs (duty_key,start_epoch,end_epoch) VALUES (?,?,?)")) {
            for (FlightLeg flight : duty.getFlights()) {
                statement.setString(1, duty.getRecordKey());
                statement.setLong(2, flight.start().getEpochSecond());
                statement.setLong(3, flight.end().getEpochSecond());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Processed command ID, operational mutation, and audit entry commit together.
     */
    public void applyWrite(PendingWrite write) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement processed = connection.prepareStatement("SELECT 1 FROM processed_commands WHERE command_id=?")) {
                    processed.setString(1, write.id());
                    try (ResultSet result = processed.executeQuery()) {
                        if (result.next()) {
                            connection.commit();
                            return;
                        }
                    }
                }
                Pilot actor = requireUser(connection, write.actor());
                requireOperationalPermission(actor, write.pilotUsername());
                Pilot target = requireUser(connection, write.pilotUsername());
                if (target.role() != Role.PILOT) {
                    throw new RecordConflictException("Operational records require a pilot account.");
                }
                FleetData data = loadFleet(connection);
                validateIntervals(data, write.pilotUsername(), write.duty(), write.rest());
                switch (write.operation()) {
                    case ADD_DUTY -> insertDuty(connection, write.pilotUsername(), write.duty());
                    case EDIT_DUTY -> updateDuty(connection, write);
                    case DELETE_DUTY -> deleteVersioned(connection, "duty_periods", write);
                    case ADD_REST -> insertRest(connection, write.pilotUsername(), write.rest());
                    case EDIT_REST -> updateRest(connection, write);
                    case DELETE_REST -> deleteVersioned(connection, "rest_periods", write);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO processed_commands (command_id,completed_at) VALUES (?,?)")) {
                    statement.setString(1, write.id());
                    statement.setString(2, Instant.now().toString());
                    statement.executeUpdate();
                }
                audit(connection, write.actor(), write.operation().name(), write.pilotUsername(), "Record " + write.targetKey());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    static void validateIntervals(FleetData data, String username, DutyPeriod duty, RestPeriod rest) throws RecordConflictException {
        Pilot user = data.users().get(username);
        if (user == null) {
            throw new RecordConflictException("Pilot account does not exist.");
        }
        List<DutyPeriod> history = user.getDutyHistory();
        List<RestPeriod> rests = data.rest().getOrDefault(username, List.of());
        if (duty != null) {
            boolean overlap = history.stream().filter(item -> !item.getRecordKey().equals(duty.getRecordKey()))
                .anyMatch(item -> intersects(item.getStartInstant(), item.getReleaseInstant(), duty.getStartInstant(), duty.getReleaseInstant()));
            if (overlap) {
                throw new RecordConflictException("This duty overlaps another duty or postflight duty.");
            }
            if (rests.stream().anyMatch(item -> intersects(item.startInstant(), item.endInstant(), duty.getStartInstant(), duty.getReleaseInstant()))) {
                throw new RecordConflictException("This duty overlaps a recorded rest period. Correct the rest record first.");
            }
        }
        if (rest != null) {
            if (history.stream().anyMatch(item -> intersects(item.getStartInstant(), item.getReleaseInstant(), rest.startInstant(), rest.endInstant()))) {
                throw new RecordConflictException("Rest cannot overlap duty or postflight duty.");
            }
            if (rests.stream().filter(item -> !item.recordKey().equals(rest.recordKey()))
                .anyMatch(item -> intersects(item.startInstant(), item.endInstant(), rest.startInstant(), rest.endInstant()))) {
                throw new RecordConflictException("This rest overlaps another rest record.");
            }
        }
    }

    private static boolean intersects(Instant start, Instant end, Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && end.isAfter(otherStart);
    }

    private void updateDuty(Connection connection, PendingWrite write) throws SQLException {
        String sql = "UPDATE duty_periods SET start_time=?,end_time=?,release_time=?,zone_id=?,duration_minutes=?,"
            + "flight_known=?,acclimated=?,sleep_confirmed=?,fit_for_duty=?,special_operation=?,extension_minutes=?,"
            + "unforeseen=?,pic_approved=?,carrier_approved=?,notes=?,revision=revision+1"
            + " WHERE record_key=? AND revision=? AND pilot_username=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDuty(statement, write.duty(), 1);
            statement.setString(16, write.targetKey());
            statement.setInt(17, write.expectedRevision());
            statement.setString(18, write.pilotUsername());
            requireChanged(statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM flight_legs WHERE duty_key=?")) {
            statement.setString(1, write.targetKey());
            statement.executeUpdate();
        }
        insertLegs(connection, write.duty());
    }

    private void insertRest(Connection connection, String username, RestPeriod rest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO rest_periods (record_key,revision,pilot_username,start_time,end_time,zone_id,sleep_minutes,notes) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setString(1, rest.recordKey());
            statement.setInt(2, rest.revision());
            statement.setString(3, username);
            bindRest(statement, rest, 4);
            statement.executeUpdate();
        }
    }

    private void bindRest(PreparedStatement statement, RestPeriod rest, int offset) throws SQLException {
        statement.setString(offset++, rest.startTime().toString());
        statement.setString(offset++, rest.endTime().toString());
        statement.setString(offset++, rest.zoneId());
        statement.setInt(offset++, rest.sleepMinutes());
        statement.setString(offset, rest.notes());
    }

    private void updateRest(Connection connection, PendingWrite write) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE rest_periods SET start_time=?,end_time=?,zone_id=?,sleep_minutes=?,notes=?,revision=revision+1"
                + " WHERE record_key=? AND revision=? AND pilot_username=?")) {
            bindRest(statement, write.rest(), 1);
            statement.setString(6, write.targetKey());
            statement.setInt(7, write.expectedRevision());
            statement.setString(8, write.pilotUsername());
            requireChanged(statement.executeUpdate());
        }
    }

    private void deleteVersioned(Connection connection, String table, PendingWrite write) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE record_key=? AND revision=? AND pilot_username=?")) {
            statement.setString(1, write.targetKey());
            statement.setInt(2, write.expectedRevision());
            statement.setString(3, write.pilotUsername());
            requireChanged(statement.executeUpdate());
        }
    }

    private void requireChanged(int count) throws RecordConflictException {
        if (count != 1) {
            throw new RecordConflictException("This record changed or was deleted elsewhere. Refresh and review the pending change.");
        }
    }

    private Pilot requireUser(Connection connection, String username) throws SQLException {
        Pilot user = findUser(connection, username);
        if (user == null) {
            throw new RecordConflictException("This account is no longer available. Sign in again.");
        }
        return user;
    }

    private void requireOperationalPermission(Pilot actor, String target) throws RecordConflictException {
        if (!actor.role().managesFleet() && !actor.getUsername().equals(target)) {
            throw new RecordConflictException("Pilot accounts can access only their own operational records.");
        }
    }

    public void saveAccount(String actorUsername, Pilot profile, boolean update) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Pilot actor = requireUser(connection, actorUsername);
                if (actor.role() != Role.ADMINISTRATOR) {
                    throw new RecordConflictException("Only administrators manage accounts.");
                }
                Pilot existing = findUser(connection, profile.getUsername());
                if (update && existing == null) {
                    throw new RecordConflictException("This account no longer exists.");
                }
                if (!update && existing != null) {
                    throw new RecordConflictException("This username already exists.");
                }
                if (existing != null && existing.role() == Role.PILOT && profile.role() != Role.PILOT) {
                    try (PreparedStatement records = connection.prepareStatement(
                        "SELECT (SELECT COUNT(*) FROM duty_periods WHERE pilot_username=?) + (SELECT COUNT(*) FROM rest_periods WHERE pilot_username=?)")) {
                        records.setString(1, profile.getUsername());
                        records.setString(2, profile.getUsername());
                        try (ResultSet result = records.executeQuery()) {
                            if (result.next() && result.getInt(1) > 0) {
                                throw new RecordConflictException("An account with operational records must retain the Pilot role.");
                            }
                        }
                    }
                }
                if (existing != null && existing.role() == Role.ADMINISTRATOR && profile.role() != Role.ADMINISTRATOR) {
                    protectLastAdmin(connection);
                    if (actorUsername.equals(profile.getUsername())) {
                        throw new RecordConflictException("You cannot remove your own administrator role.");
                    }
                }
                persistPilot(connection, profile, update);
                audit(connection, actorUsername, update ? "EDIT_ACCOUNT" : "ADD_ACCOUNT", profile.getUsername(), profile.getRole());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void deleteAccount(String actorUsername, String username) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Pilot actor = requireUser(connection, actorUsername);
                if (actor.role() != Role.ADMINISTRATOR) {
                    throw new RecordConflictException("Only administrators delete accounts.");
                }
                if (actorUsername.equals(username)) {
                    throw new RecordConflictException("You cannot delete your signed-in account.");
                }
                Pilot target = requireUser(connection, username);
                if (target.role() == Role.ADMINISTRATOR) {
                    protectLastAdmin(connection);
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pilots WHERE username=?")) {
                    statement.setString(1, username);
                    requireChanged(statement.executeUpdate());
                }
                audit(connection, actorUsername, "DELETE_ACCOUNT", username, "Profile and operational records deleted");
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void protectLastAdmin(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM pilots WHERE role='Administrator'")) {
            if (result.next() && result.getInt(1) <= 1) {
                throw new RecordConflictException("At least one administrator must remain.");
            }
        }
    }

    public void changePassword(String actorUsername, String username, String hash) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Pilot actor = requireUser(connection, actorUsername);
                if (actor.role() != Role.ADMINISTRATOR && !actorUsername.equals(username)) {
                    throw new RecordConflictException("You cannot change another account's password.");
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE pilots SET password_hash=? WHERE username=?")) {
                    statement.setString(1, hash);
                    statement.setString(2, username);
                    requireChanged(statement.executeUpdate());
                }
                audit(connection, actorUsername, "CHANGE_PASSWORD", username, "Credential updated");
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void synchronizeAlerts(List<Alert> findings) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                java.util.Set<String> current = new java.util.HashSet<>();
                findings.forEach(alert -> current.add(alert.key()));
                List<String> resolved = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT alert_key FROM alerts WHERE resolved=0")) {
                    while (result.next()) {
                        if (!current.contains(result.getString(1))) {
                            resolved.add(result.getString(1));
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE alerts SET resolved=1 WHERE alert_key=?")) {
                    for (String key : resolved) {
                        statement.setString(1, key);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                String sql = "INSERT INTO alerts (alert_key,pilot_username,source_key,rule,severity,message,created_at,resolved) VALUES (?,?,?,?,?,?,?,0)"
                    + " ON CONFLICT(alert_key) DO UPDATE SET severity=excluded.severity,message=excluded.message,resolved=0,"
                    + " acknowledged_by=CASE WHEN alerts.resolved=1 OR (alerts.source_key<>'readiness' AND alerts.message<>excluded.message) OR alerts.severity<>excluded.severity THEN NULL ELSE alerts.acknowledged_by END,"
                    + " acknowledgement=CASE WHEN alerts.resolved=1 OR (alerts.source_key<>'readiness' AND alerts.message<>excluded.message) OR alerts.severity<>excluded.severity THEN NULL ELSE alerts.acknowledgement END"
                    + " WHERE alerts.resolved<>0 OR alerts.message<>excluded.message OR alerts.severity<>excluded.severity";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Alert alert : findings) {
                        statement.setString(1, alert.key());
                        statement.setString(2, alert.pilotUsername());
                        statement.setString(3, alert.sourceKey());
                        statement.setString(4, alert.rule());
                        statement.setString(5, alert.severity().name());
                        statement.setString(6, alert.message());
                        statement.setString(7, alert.createdAt().toString());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<Alert> loadAlerts() throws SQLException {
        List<Alert> alerts = new ArrayList<>();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM alerts WHERE resolved=0 ORDER BY created_at DESC")) {
            while (result.next()) {
                alerts.add(new Alert(result.getString("alert_key"), result.getString("pilot_username"),
                    result.getString("source_key"), result.getString("rule"), ComplianceReport.Status.valueOf(result.getString("severity")),
                    result.getString("message"), Instant.parse(result.getString("created_at")), result.getString("acknowledged_by"),
                    result.getString("acknowledgement"), result.getBoolean("resolved")));
            }
        }
        return alerts;
    }

    public void acknowledgeAlert(String actorUsername, String key, String note) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!requireUser(connection, actorUsername).role().managesFleet()) {
                    throw new RecordConflictException("Only dispatchers and administrators acknowledge alerts.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE alerts SET acknowledged_by=?,acknowledgement=? WHERE alert_key=? AND resolved=0")) {
                    statement.setString(1, actorUsername);
                    statement.setString(2, note);
                    statement.setString(3, key);
                    requireChanged(statement.executeUpdate());
                }
                audit(connection, actorUsername, "ACKNOWLEDGE_ALERT", key, note);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void audit(Connection connection, String actor, String action, String target, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO audit_events (timestamp,actor,action,target,detail) VALUES (?,?,?,?,?)")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, actor);
            statement.setString(3, action);
            statement.setString(4, target);
            statement.setString(5, detail);
            statement.executeUpdate();
        }
    }

    public List<AuditEvent> loadAudit(String usernameOrNull) throws SQLException {
        String sql = "SELECT * FROM audit_events" + (usernameOrNull == null ? "" : " WHERE actor=? OR target=?")
            + " ORDER BY id DESC LIMIT 250";
        List<AuditEvent> events = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (usernameOrNull != null) {
                statement.setString(1, usernameOrNull);
                statement.setString(2, usernameOrNull);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new AuditEvent(Instant.parse(result.getString("timestamp")), result.getString("actor"),
                        result.getString("action"), result.getString("target"), result.getString("detail")));
                }
            }
        }
        return events;
    }

    public void seedDemoDataIfEmpty() throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM pilots")) {
                    if (result.next() && result.getInt(1) > 0) {
                        connection.commit();
                        return;
                    }
                }
                String adminHash = User.hashPassword("admin");
                String dispatchHash = User.hashPassword("dispatch-demo");
                String pilotHash = User.hashPassword("pilot-demo");
                List<Pilot> users = List.of(
                    new Pilot("admin", adminHash, "Administrator", 0, "N/A", "Operations", "Harley Driscoll", "America/Denver"),
                    new Pilot("dispatcher", dispatchHash, "Dispatcher", 0, "N/A", "Operations", "Flight Dispatch", "America/Denver"),
                    new Pilot("alex.morgan", pilotHash, "Pilot", 1840, "ATP", "DEN", "Alex Morgan", "America/Denver"),
                    new Pilot("jamie.chen", pilotHash, "Pilot", 965, "Commercial", "SEA", "Jamie Chen", "America/Los_Angeles"),
                    new Pilot("riley.patel", pilotHash, "Pilot", 2310, "ATP", "DFW", "Riley Patel", "America/Chicago"),
                    new Pilot("casey.brooks", pilotHash, "Pilot", 720, "Commercial", "PHX", "Casey Brooks", "America/Phoenix"),
                    new Pilot("taylor.reed", pilotHash, "Pilot", 1465, "ATP", "ORD", "Taylor Reed", "America/Chicago"),
                    new Pilot("morgan.diaz", pilotHash, "Pilot", 1180, "ATP", "ATL", "Morgan Diaz", "America/New_York"));
                for (Pilot user : users) {
                    persistPilot(connection, user, false);
                }
                Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
                for (int hoursAgo = 160; hoursAgo >= 20; hoursAgo -= 20) {
                    insertDuty(connection, "alex.morgan", demoDuty(now.minusSeconds(hoursAgo * 3600L), 8, 5, "America/Denver", false));
                }
                insertDuty(connection, "jamie.chen", demoDuty(now.minusSeconds(64 * 3600), 8, 4, "America/Los_Angeles", false));
                insertDuty(connection, "jamie.chen", demoDuty(now.minusSeconds(44 * 3600), 8, 4, "America/Los_Angeles", false));
                insertDuty(connection, "riley.patel", demoDuty(now.minusSeconds(40 * 3600), 8, 4, "America/Chicago", true));
                LocalDateTime caseyToday = Times.local(now, "America/Phoenix").toLocalDate().atTime(7, 0);
                for (int daysAgo = 5; daysAgo >= 1; daysAgo--) {
                    insertDuty(connection, "casey.brooks", demoDuty(Times.instant(caseyToday.minusDays(daysAgo), "America/Phoenix"),
                        11, 5, "America/Phoenix", false));
                }
                insertDuty(connection, "taylor.reed", demoDuty(now.minusSeconds(96 * 3600), 8, 4, "America/Chicago", false));
                insertDuty(connection, "taylor.reed", demoDuty(now.minusSeconds(58 * 3600), 8, 4, "America/Chicago", false));
                insertDuty(connection, "morgan.diaz", demoDuty(now.minusSeconds(16 * 3600), 8, 4, "America/New_York", false));
                insertRest(connection, "jamie.chen", new RestPeriod(Times.local(now.minusSeconds(56 * 3600), "America/Los_Angeles"),
                    Times.local(now.minusSeconds(44 * 3600), "America/Los_Angeles"), "America/Los_Angeles", 480, "Demo sleep opportunity"));
                audit(connection, "system", "DEMO_INITIALIZED", "fleet", "Sample records created for the capstone demonstration");
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private DutyPeriod demoDuty(Instant start, int dutyHours, int flightHours, String zone, boolean special) {
        Instant end = start.plusSeconds(dutyHours * 3600L);
        return new DutyPeriod(Times.local(start, zone), Times.local(end, zone), Times.local(end, zone), zone,
            List.of(new FlightLeg(start.plusSeconds(30 * 60), start.plusSeconds(30 * 60 + flightHours * 3600L))),
            true, true, true, special, 0, false, false, false, "Sample record for the capstone demonstration");
    }

    public static boolean isAvailabilityFailure(SQLException exception) {
        if (exception instanceof RecordConflictException) {
            return false;
        }
        int code = exception.getErrorCode() & 0xff;
        return code == 5 || code == 6 || code == 8 || code == 10 || code == 11 || code == 14
            || exception instanceof java.sql.SQLTransientException;
    }

    private static Path resolveDatabasePath() {
        String configured = System.getProperty("frms.database");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("FRMS_DATABASE");
        }
        return configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), ".frms", "frms.sqlite") : Path.of(configured);
    }
}
