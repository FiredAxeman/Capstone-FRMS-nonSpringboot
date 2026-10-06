import java.util.List;

public record CrewAssessment(Pilot pilot, ComplianceReport.Status status, List<ComplianceReport.Check> checks,
                             long duty7Minutes, long duty28Minutes, long flight28Minutes, long flight365Minutes,
                             long longestRestMinutes, long immediateRestMinutes, boolean completeFlightHistory,
                             int unacknowledgedAlerts) {
    public CrewAssessment {
        checks = List.copyOf(checks);
    }
}
