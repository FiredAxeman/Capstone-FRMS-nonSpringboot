import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceWorkflowTest {
    @TempDir
    Path directory;
    private DatabaseManager database;
    private FrmsService service;

    @BeforeEach
    void initialize() throws Exception {
        database = new DatabaseManager(directory.resolve("frms.sqlite"));
        database.savePilot(TestFixtures.user("admin", Role.ADMINISTRATOR));
        database.savePilot(TestFixtures.user("dispatcher", Role.DISPATCHER));
        database.savePilot(TestFixtures.user("pilot", Role.PILOT));
        service = new FrmsService(database, directory.resolve("outbox"), TestFixtures.CLOCK);
        service.signIn("admin", TestFixtures.PASSWORD.toCharArray());
    }

    @Test
    void completesDutyAndRestCrudWithAuditsAndFlightLegCascade() throws Exception {
        DutyPeriod original = TestFixtures.duty("2026-10-01T08:00", 480, 240);
        assertFalse(service.saveDuty("pilot", original, false).queued());
        DutyPeriod changed = TestFixtures.duty("2026-10-01T08:00", 480, 300).withIdentity(original.getRecordKey(), 0);
        service.saveDuty("pilot", changed, true);
        DutyPeriod persisted = database.loadDutyHistory("pilot").get(0);
        assertEquals(1, persisted.getRevision());
        assertEquals(300, persisted.getFlightMinutes());

        RestPeriod rest = new RestPeriod(LocalDateTime.parse("2026-10-01T16:00"), LocalDateTime.parse("2026-10-02T03:00"), "UTC", 480, "Rest after duty");
        service.saveRest("pilot", rest, false);
        RestPeriod updated = new RestPeriod(rest.recordKey(), 0, rest.startTime(), rest.endTime(), rest.zoneId(), 510, "Corrected opportunity");
        service.saveRest("pilot", updated, true);
        assertEquals(1, database.loadRestHistory("pilot").get(0).revision());
        assertEquals(510, database.loadRestHistory("pilot").get(0).sleepMinutes());
        service.deleteRecord("pilot", rest.recordKey(), 1, false);
        service.deleteRecord("pilot", original.getRecordKey(), 1, true);
        assertTrue(database.loadDutyHistory("pilot").isEmpty());
        assertTrue(database.loadRestHistory("pilot").isEmpty());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getDatabasePath());
             Statement statement = connection.createStatement(); ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM flight_legs")) {
            assertTrue(count.next());
            assertEquals(0, count.getInt(1));
        }
        assertEquals(6, database.loadAudit(null).size());
        assertTrue(service.snapshot().pending().isEmpty());
    }

    @Test
    void rejectsOverlappingRestAndDutyWithoutLosingTheExistingRecord() throws Exception {
        DutyPeriod original = TestFixtures.duty("2026-10-01T08:00", 480, 240);
        service.saveDuty("pilot", original, false);
        assertThrows(RecordConflictException.class, () -> service.saveDuty("pilot",
            TestFixtures.duty("2026-10-01T15:00", 180, 60), false));
        assertThrows(RecordConflictException.class, () -> service.saveRest("pilot",
            new RestPeriod(LocalDateTime.parse("2026-10-01T15:00"), LocalDateTime.parse("2026-10-02T03:00"), "UTC", 480, ""), false));
        assertEquals(1, database.loadDutyHistory("pilot").size());
        assertTrue(service.snapshot().pending().isEmpty());
    }

    @Test
    void preservesActualViolationsAndCreatesPersistentReviewableAlerts() throws Exception {
        DutyPeriod violated = TestFixtures.duty("2026-10-07T07:00", 600, 541);
        FrmsService.WriteResult saved = service.saveDuty("pilot", violated, false);
        assertEquals(ComplianceReport.Status.VIOLATION, saved.report().status());
        DashboardSnapshot snapshot = service.snapshot();
        Alert alert = snapshot.alerts().stream().filter(item -> item.rule().contains("Daily flight")).findFirst().orElseThrow();
        service.acknowledge(alert.key(), "Review completed; no further assignment pending correction.");
        Alert acknowledged = service.snapshot().alerts().stream().filter(item -> item.key().equals(alert.key())).findFirst().orElseThrow();
        assertTrue(acknowledged.acknowledged());
        assertEquals(ComplianceReport.Status.VIOLATION, acknowledged.severity());
        assertEquals(1, database.loadDutyHistory("pilot").size());
        FrmsService restarted = new FrmsService(new DatabaseManager(database.getDatabasePath()), directory.resolve("outbox"), TestFixtures.CLOCK);
        restarted.signIn("admin", TestFixtures.PASSWORD.toCharArray());
        assertTrue(restarted.snapshot().alerts().stream().anyMatch(item -> item.key().equals(alert.key()) && item.acknowledged()));
    }

    @Test
    void editingAFlaggedRecordRecalculatesAndResolvesItsOldFindings() throws Exception {
        DutyPeriod original = TestFixtures.duty("2026-10-07T07:00", 600, 541);
        service.saveDuty("pilot", original, false);
        assertTrue(service.snapshot().alerts().stream().anyMatch(alert -> alert.rule().contains("Daily flight")));
        service.saveDuty("pilot", TestFixtures.duty("2026-10-07T07:00", 600, 240).withIdentity(original.getRecordKey(), 0), true);
        assertFalse(service.snapshot().alerts().stream().anyMatch(alert -> alert.rule().contains("Daily flight")));
    }

    @Test
    void accountCreationDoesNotUpsertAndPasswordsSurviveProfileEdits() throws Exception {
        Pilot newUser = TestFixtures.user("new.pilot", Role.PILOT);
        service.saveAccount(newUser, false);
        assertThrows(RecordConflictException.class, () -> service.saveAccount(newUser, false));
        Pilot changed = new Pilot(newUser.getUsername(), newUser.getPasswordHash(), "Pilot", 250, "ATP", "SEA", "New Pilot", "America/Los_Angeles");
        service.saveAccount(changed, true);
        Pilot loaded = database.findUser("new.pilot");
        assertEquals("SEA", loaded.getBaseAssignment());
        assertTrue(loaded.authenticate(TestFixtures.PASSWORD));
        service.saveDuty("new.pilot", TestFixtures.duty("2026-10-01T08:00", 480, 240), false);
        assertThrows(RecordConflictException.class, () -> service.saveAccount(
            new Pilot("new.pilot", newUser.getPasswordHash(), "Dispatcher", 250, "ATP", "SEA", "New Pilot", "UTC"), true));
        service.deleteAccount("new.pilot");
        assertEquals(null, database.findUser("new.pilot"));
        assertTrue(database.loadDutyHistory("new.pilot").isEmpty());
        assertThrows(RecordConflictException.class, () -> service.deleteAccount("admin"));
        assertTrue(database.findUser("admin").authenticate(TestFixtures.PASSWORD));
    }

    @Test
    void rejectsFutureActualRecordsButAllowsEvaluationWithoutSaving() throws Exception {
        DutyPeriod future = TestFixtures.duty("2026-10-10T08:00", 480, 240);
        assertThrows(IllegalArgumentException.class, () -> service.saveDuty("pilot", future, false));
        assertTrue(service.preview("pilot", future).passesModeledRules());
        assertTrue(database.loadDutyHistory("pilot").isEmpty());
    }

    @Test
    void handlesQuotedProfileAndNotesAsDataRatherThanSql() throws Exception {
        Pilot user = new Pilot("quoted", TestFixtures.HASH, "Pilot", 0, "ATP", "O'Hare", "O'Neil", "UTC");
        service.saveAccount(user, false);
        assertEquals("O'Hare", database.findUser("quoted").getBaseAssignment());
        assertEquals(null, database.findUser("' OR 1=1 --"));
        assertThrows(IllegalArgumentException.class, () -> TestFixtures.user("' OR 1=1 --", Role.PILOT));
    }

    @Test
    void migratesLegacySchemaAndPreservesDutyIdentityAcrossRestarts() throws Exception {
        Path legacy = directory.resolve("legacy.sqlite");
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + legacy); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pilots (username TEXT PRIMARY KEY,password_hash TEXT NOT NULL,role TEXT NOT NULL,"
                + "cumulative_flight_hours INTEGER NOT NULL,certification_status TEXT NOT NULL,base_assignment TEXT NOT NULL)");
            statement.execute("CREATE TABLE duty_periods (id INTEGER PRIMARY KEY AUTOINCREMENT,pilot_username TEXT NOT NULL REFERENCES pilots(username),"
                + "start_time TEXT NOT NULL,end_time TEXT NOT NULL,duration_minutes INTEGER NOT NULL)");
            statement.execute("INSERT INTO pilots VALUES ('Captain','','Pilot',1234,'ATP','DEN')");
            statement.execute("INSERT INTO duty_periods (pilot_username,start_time,end_time,duration_minutes)"
                + " VALUES ('Captain','2026-10-01T08:00:12','2026-10-01T16:00:12',480)");
        }
        DatabaseManager migrated = new DatabaseManager(legacy);
        DutyPeriod record = migrated.loadDutyHistory("captain").get(0);
        assertFalse(record.hasFlightData());
        assertEquals(480, record.calculateDuration());
        assertEquals(1234, migrated.findUser("captain").getCumulativeFlightHours());
        DatabaseManager reopened = new DatabaseManager(legacy);
        assertEquals(record.getRecordKey(), reopened.loadDutyHistory("captain").get(0).getRecordKey());
        assertNotEquals("", record.getRecordKey());
        assertEquals(12, record.getStartTime().getSecond());
    }
}
