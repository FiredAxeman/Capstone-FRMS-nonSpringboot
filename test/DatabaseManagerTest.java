import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesSchemaAndPersistsPilotDutyHistory() throws Exception {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("data/frms.sqlite"));
        Pilot pilot = new Pilot("captain", "", "Pilot", 1200, "ATP", "DEN");
        database.savePilot(pilot);
        database.saveDutyPeriod("captain", new DutyPeriod(
            LocalDateTime.parse("2026-10-01T08:00:00"),
            LocalDateTime.parse("2026-10-01T16:00:00")));

        List<Pilot> savedPilots = database.loadPilots();
        List<DutyPeriod> savedHistory = database.loadDutyHistory("captain");

        assertEquals(1, savedPilots.size());
        assertEquals("captain", savedPilots.get(0).getUsername());
        assertEquals(1, savedHistory.size());
        assertEquals(480, savedHistory.get(0).calculateDuration());
    }

    @Test
    void rejectsDutyForUnknownPilot() throws Exception {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("frms.sqlite"));
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
            () -> database.saveDutyPeriod("missing", new DutyPeriod(
                LocalDateTime.parse("2026-10-01T08:00:00"),
                LocalDateTime.parse("2026-10-01T09:00:00"))));
    }

    @Test
    void seedsDemoPilotsAndAdminOnlyOnceForAnEmptyDatabase() throws Exception {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("demo.sqlite"));

        database.seedDemoDataIfEmpty();
        database.seedDemoDataIfEmpty();

        List<Pilot> pilots = database.loadPilots();
        Pilot admin = pilots.stream()
            .filter(pilot -> pilot.getUsername().equals("admin"))
            .findFirst().orElseThrow();
        assertEquals(8, pilots.size());
        assertTrue(admin.authenticate("admin"));
        assertTrue(!admin.authenticate("wrong"));
        List<DutyPeriod> alexHistory = database.loadDutyHistory("alex.morgan");
        assertTrue(alexHistory.size() > 1);
        FatigueRuleEngine engine = new FatigueRuleEngine();
        assertTrue(!engine.validateRestWindow(alexHistory, LocalDateTime.now()));
        assertTrue(engine.validateRestWindow(
            database.loadDutyHistory("jamie.chen"), LocalDateTime.now()));
    }
}
