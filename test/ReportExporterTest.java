import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportExporterTest {
    @TempDir
    Path directory;

    @Test
    void exportsOperationalRecordsWithConsistentHeadersAndFreshnessMetadata() throws Exception {
        DatabaseManager database = new DatabaseManager(directory.resolve("frms.sqlite"));
        database.savePilot(TestFixtures.user("admin", Role.ADMINISTRATOR));
        database.savePilot(TestFixtures.user("pilot", Role.PILOT));
        FrmsService service = new FrmsService(database, directory.resolve("outbox"), TestFixtures.CLOCK);
        service.signIn("admin", TestFixtures.PASSWORD.toCharArray());
        service.saveDuty("pilot", TestFixtures.duty("2026-10-01T08:00", 480, 240), false);
        DashboardSnapshot snapshot = service.snapshot();
        for (ReportExporter.Type type : ReportExporter.Type.values()) {
            Path report = directory.resolve(type + ".csv");
            ReportExporter.export(snapshot, type, report);
            List<String> lines = Files.readAllLines(report);
            assertTrue(lines.get(0).endsWith("\"Exported_UTC\",\"Last_synchronized_UTC\",\"Data_state\""));
            for (String row : lines.subList(1, lines.size())) {
                assertEquals(lines.get(0).split(",").length, row.split(",").length);
                assertTrue(row.endsWith("\"Synchronized\""));
            }
        }
        assertTrue(Files.readString(directory.resolve("DUTY.csv")).contains("2026-10-01T08:00:00Z"));
    }

    @Test
    void protectsAgainstSpreadsheetFormulasAndEscapesQuotesAndNewlines() {
        assertEquals("\"'=2+3\"", ReportExporter.escape("=2+3"));
        assertEquals("\"'   @SUM(1)\"", ReportExporter.escape("   @SUM(1)"));
        assertEquals("\"O'Neil, \"\"Sky\"\"\nCrew\"", ReportExporter.escape("O'Neil, \"Sky\"\nCrew"));
    }
}
