import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DashboardSnapshot(Instant evaluatedAt, Instant lastSynchronized, FleetData data,
                                List<CrewAssessment> crew, List<Alert> alerts, List<AuditEvent> audit,
                                List<QueuedChange> pending, Map<String, ComplianceReport> dutyReports,
                                boolean online, String syncMessage) {
    public DashboardSnapshot {
        crew = List.copyOf(crew);
        alerts = List.copyOf(alerts);
        audit = List.copyOf(audit);
        pending = List.copyOf(pending);
        dutyReports = Map.copyOf(dutyReports);
    }

    public record QueuedChange(DurableOutbox.Entry entry, String conflict) {
    }
}
