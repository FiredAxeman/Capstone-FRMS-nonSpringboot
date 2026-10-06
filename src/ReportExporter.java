import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * UTF-8 CSV reports with explicit freshness metadata and spreadsheet-formula protection.
 */
public final class ReportExporter {
    public enum Type {FLEET, DUTY, REST, ALERTS, AUDIT}

    private ReportExporter() {
    }

    public static void export(DashboardSnapshot snapshot, Type type, Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), ".frms-report-", ".csv");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                List<List<String>> rows = rows(snapshot, type);
                for (int index = 0; index < rows.size(); index++) {
                    List<String> row = rows.get(index);
                    List<String> values = new ArrayList<>(row);
                    values.add(index == 0 ? "Exported_UTC" : snapshot.evaluatedAt().toString());
                    values.add(index == 0 ? "Last_synchronized_UTC" : snapshot.lastSynchronized() == null ? "" : snapshot.lastSynchronized().toString());
                    values.add(index == 0 ? "Data_state" : snapshot.online() && snapshot.pending().isEmpty() ? "Synchronized" : "Cached / pending changes");
                    writer.write(values.stream().map(ReportExporter::escape).collect(java.util.stream.Collectors.joining(",")));
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<List<String>> rows(DashboardSnapshot snapshot, Type type) {
        List<List<String>> result = new ArrayList<>();
        List<String> header = switch (type) {
            case FLEET -> List.of("Username", "Pilot", "Base", "Status", "FDP_168h", "FDP_672h", "Flight_672h",
                "Flight_365days", "Longest_rest_168h", "Rest_since_release", "Unacknowledged_alerts");
            case DUTY -> List.of("Username", "Record_ID", "Report_UTC", "FDP_end_UTC", "Release_UTC", "Report_zone",
                "FDP_duration", "Flight_duration", "Segments", "Status", "Extension_minutes", "Notes");
            case REST ->
                List.of("Username", "Record_ID", "Start_UTC", "End_UTC", "Zone", "Duration", "Sleep_opportunity", "Notes");
            case ALERTS ->
                List.of("Username", "Rule", "Severity", "Finding", "Created_UTC", "Acknowledged_by", "Acknowledgement");
            case AUDIT -> List.of("Timestamp_UTC", "Actor", "Action", "Target", "Detail");
        };
        result.add(header);
        switch (type) {
            case FLEET ->
                snapshot.crew().forEach(row -> result.add(List.of(row.pilot().getUsername(), row.pilot().getDisplayName(),
                    row.pilot().getBaseAssignment(), row.status().toString(), Times.hours(row.duty7Minutes()), Times.hours(row.duty28Minutes()),
                    row.completeFlightHistory() ? Times.hours(row.flight28Minutes()) : "Unknown",
                    row.completeFlightHistory() ? Times.hours(row.flight365Minutes()) : "Unknown",
                    Times.hours(row.longestRestMinutes()), Times.hours(row.immediateRestMinutes()), Integer.toString(row.unacknowledgedAlerts()))));
            case DUTY ->
                snapshot.data().users().values().stream().sorted(java.util.Comparator.comparing(Pilot::getUsername)).forEach(user ->
                    user.getDutyHistory().forEach(duty -> result.add(List.of(user.getUsername(), duty.getRecordKey(),
                        duty.getStartInstant().toString(), duty.getEndInstant().toString(), duty.getReleaseInstant().toString(), duty.getZoneId(),
                        Times.hours(duty.calculateDuration()), duty.hasFlightData() ? Times.hours(duty.getFlightMinutes()) : "Unknown",
                        Integer.toString(duty.getSegments()), snapshot.dutyReports().get(duty.getRecordKey()).status().toString(),
                        Integer.toString(duty.getExtensionMinutes()), duty.getNotes()))));
            case REST ->
                snapshot.data().rest().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry ->
                    entry.getValue().forEach(rest -> result.add(List.of(entry.getKey(), rest.recordKey(), rest.startInstant().toString(),
                        rest.endInstant().toString(), rest.zoneId(), Times.hours(rest.durationMinutes()), Times.hours(rest.sleepMinutes()), rest.notes()))));
            case ALERTS ->
                snapshot.alerts().forEach(alert -> result.add(List.of(alert.pilotUsername(), alert.rule(), alert.severity().toString(),
                    alert.message(), alert.createdAt().atOffset(ZoneOffset.UTC).toString(), alert.acknowledgedBy() == null ? "" : alert.acknowledgedBy(),
                    alert.acknowledgement() == null ? "" : alert.acknowledgement())));
            case AUDIT ->
                snapshot.audit().forEach(event -> result.add(List.of(event.timestamp().toString(), event.actor(),
                    event.action(), event.target(), event.detail())));
        }
        return result;
    }

    static String escape(String value) {
        String safe = value == null ? "" : value;
        String leading = safe.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
