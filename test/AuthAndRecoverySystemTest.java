import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full application workflows use an isolated real SQLite database and durable local journal.
 */
class AuthAndRecoverySystemTest {
    @TempDir
    Path directory;
    private FaultyDatabase database;

    private static final class FaultyDatabase extends DatabaseManager {
        boolean unavailable;
        int failures;

        FaultyDatabase(Path path) throws Exception {
            super(path);
        }

        @Override
        protected Connection openConnection() throws SQLException {
            if (unavailable) {
                failures++;
                throw new SQLException("Simulated database I/O failure", "08001", 14);
            }
            return super.openConnection();
        }
    }

    @BeforeEach
    void initialize() throws Exception {
        database = new FaultyDatabase(directory.resolve("frms.sqlite"));
        database.savePilot(TestFixtures.user("admin", Role.ADMINISTRATOR));
        database.savePilot(TestFixtures.user("dispatcher", Role.DISPATCHER));
        database.savePilot(TestFixtures.user("pilot", Role.PILOT));
        database.savePilot(TestFixtures.user("other", Role.PILOT));
    }

    private FrmsService session(String name) throws Exception {
        FrmsService service = new FrmsService(database, directory.resolve("outbox"), TestFixtures.CLOCK);
        service.signIn(name, TestFixtures.PASSWORD.toCharArray());
        return service;
    }

    @Test
    void pilotsSeeAndModifyOnlyTheirOwnRecordsAcrossEveryServiceEntryPoint() throws Exception {
        FrmsService admin = session("admin");
        admin.saveDuty("other", TestFixtures.duty("2026-10-01T08:00", 480, 240), false);
        admin.snapshot();
        FrmsService pilot = session("pilot");
        DashboardSnapshot view = pilot.snapshot();
        assertEquals(List.of("pilot"), view.crew().stream().map(row -> row.pilot().getUsername()).toList());
        assertEquals(1, view.data().users().size());
        assertTrue(view.dutyReports().isEmpty());
        assertThrows(SecurityException.class, () -> pilot.preview("other", TestFixtures.duty("2026-10-02T08:00", 480, 240)));
        assertThrows(SecurityException.class, () -> pilot.saveDuty("other", TestFixtures.duty("2026-10-02T08:00", 480, 240), false));
        assertThrows(SecurityException.class, () -> pilot.saveAccount(TestFixtures.user("unauthorized", Role.ADMINISTRATOR), false));
        assertThrows(SecurityException.class, () -> pilot.deleteAccount("other"));
        assertThrows(SecurityException.class, () -> pilot.acknowledge("any-alert", "Reviewed"));
        DutyPeriod own = TestFixtures.duty("2026-10-02T08:00", 480, 240);
        pilot.saveDuty("pilot", own, false);
        assertEquals(1, pilot.snapshot().dutyReports().size());
    }

    @Test
    void dispatcherMonitorsFleetWithoutAccountAdministration() throws Exception {
        FrmsService dispatcher = session("dispatcher");
        DashboardSnapshot view = dispatcher.snapshot();
        assertEquals(2, view.crew().size());
        assertFalse(view.data().users().containsKey("admin"));
        assertThrows(SecurityException.class, () -> dispatcher.saveAccount(TestFixtures.user("another", Role.PILOT), false));
        assertFalse(dispatcher.saveDuty("pilot", TestFixtures.duty("2026-10-02T08:00", 480, 240), false).queued());
    }

    @Test
    void databaseAlsoRejectsForgedQueuedPermissions() throws Exception {
        DutyPeriod duty = TestFixtures.duty("2026-10-02T08:00", 480, 240);
        assertThrows(RecordConflictException.class, () -> database.applyWrite(PendingWrite.duty("pilot", "other", duty, false)));
        assertTrue(database.loadDutyHistory("other").isEmpty());
    }

    @Test
    void writesSurviveAnOutageAndRestartAndReplayExactlyOnce() throws Exception {
        FrmsService service = session("pilot");
        service.snapshot();
        database.unavailable = true;
        DutyPeriod duty = TestFixtures.duty("2026-10-02T08:00", 480, 240);
        assertTrue(service.saveDuty("pilot", duty, false).queued());
        DashboardSnapshot offline = service.snapshot();
        assertFalse(offline.online());
        assertEquals(1, offline.pending().size());
        assertEquals(ComplianceReport.Status.REVIEW, offline.crew().get(0).status());
        assertEquals(1, offline.data().users().get("pilot").getDutyHistory().size());
        assertTrue(database.failures >= 3);
        String journal = Files.readString(offline.pending().get(0).entry().path());
        assertFalse(journal.contains(TestFixtures.PASSWORD));
        assertFalse(journal.contains(TestFixtures.HASH));

        database.unavailable = false;
        // Simulate a crash after SQL commit but before removing the journal file.
        database.applyWrite(offline.pending().get(0).entry().write());
        FrmsService restarted = session("pilot");
        DashboardSnapshot recovered = restarted.snapshot();
        assertTrue(recovered.online());
        assertTrue(recovered.pending().isEmpty());
        assertEquals(1, database.loadDutyHistory("pilot").size());
        assertEquals(1, database.loadAudit("pilot").stream().filter(event -> event.action().equals("ADD_DUTY")).count());
    }

    @Test
    void queuedEditsDoNotOverwriteAConcurrentCorrection() throws Exception {
        FrmsService service = session("admin");
        DutyPeriod duty = TestFixtures.duty("2026-10-02T08:00", 480, 240);
        service.saveDuty("pilot", duty, false);
        service.snapshot();
        database.unavailable = true;
        DutyPeriod local = TestFixtures.duty("2026-10-02T08:00", 480, 300).withIdentity(duty.getRecordKey(), 0);
        assertTrue(service.saveDuty("pilot", local, true).queued());
        database.unavailable = false;
        DutyPeriod correction = TestFixtures.duty("2026-10-02T08:00", 480, 360).withIdentity(duty.getRecordKey(), 0);
        database.applyWrite(PendingWrite.duty("dispatcher", "pilot", correction, true));
        DashboardSnapshot view = service.snapshot();
        assertEquals(1, view.pending().size());
        assertTrue(view.pending().get(0).conflict().contains("changed"));
        assertEquals(360, database.loadDutyHistory("pilot").get(0).getFlightMinutes());
        assertEquals(360, view.data().users().get("pilot").getDutyHistory().get(0).getFlightMinutes());
        service.discardQueuedChange(view.pending().get(0).entry());
        assertTrue(service.snapshot().pending().isEmpty());
    }

    @Test
    void roleChangesAndCredentialResetsInvalidateExistingSessions() throws Exception {
        FrmsService pilot = session("pilot");
        database.changePassword("admin", "pilot", User.hashPassword("replacement-password"));
        assertThrows(SecurityException.class, pilot::snapshot);
        assertThrows(SecurityException.class, pilot::currentUser);
        assertThrows(IllegalArgumentException.class, () -> pilot.signIn("pilot", TestFixtures.PASSWORD.toCharArray()));
    }

    @Test
    void saltedCredentialsAndPasswordChangesNeverKeepThePlaintext() throws Exception {
        String first = User.hashPassword(TestFixtures.PASSWORD);
        String second = User.hashPassword(TestFixtures.PASSWORD);
        assertFalse(first.equals(second));
        Pilot user = new Pilot("secure", first, "Pilot", 0, "ATP", "DEN");
        assertTrue(user.authenticate(TestFixtures.PASSWORD));
        assertFalse(user.authenticate("wrong"));
        assertFalse(new Pilot("empty", "", "Pilot", 0, "ATP", "DEN").authenticate(""));
        FrmsService pilot = session("pilot");
        pilot.changePassword(TestFixtures.PASSWORD.toCharArray(), "new-password-2026".toCharArray());
        assertTrue(database.findUser("pilot").authenticate("new-password-2026"));
        assertTrue(pilot.snapshot().online());
        assertFalse(database.loadAudit("pilot").stream().anyMatch(event -> event.detail().contains("password-2026")));
    }

    @Test
    void loginThrottleUsesADeterministicClock() throws Exception {
        FrmsService service = new FrmsService(database, directory.resolve("outbox"), TestFixtures.CLOCK);
        for (int index = 0; index < 5; index++) {
            assertThrows(IllegalArgumentException.class, () -> service.signIn("pilot", "wrong".toCharArray()));
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.signIn("pilot", TestFixtures.PASSWORD.toCharArray()));
        assertTrue(error.getMessage().contains("one minute"));
    }

    @Test
    void queuesWhenARealSqliteWriteLockPreventsTheTransactionAndRecoversAfterUnlock() throws Exception {
        FrmsService service = session("pilot");
        try (Connection lock = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.getDatabasePath());
             java.sql.Statement statement = lock.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                assertTrue(service.saveDuty("pilot", TestFixtures.duty("2026-10-02T08:00", 480, 240), false).queued());
                assertTrue(database.loadDutyHistory("pilot").isEmpty());
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertTrue(service.snapshot().pending().isEmpty());
        assertEquals(1, database.loadDutyHistory("pilot").size());
    }

    @Test
    void upgradesOriginalSha256CredentialsOnSuccessfulSignIn() throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(TestFixtures.PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        database.savePilot(new Pilot("legacy", java.util.HexFormat.of().formatHex(digest), "Pilot", 0, "ATP", "DEN"));
        FrmsService service = session("legacy");
        assertTrue(database.findUser("legacy").getPasswordHash().startsWith("pbkdf2$"));
        assertTrue(service.snapshot().online());
    }
}
