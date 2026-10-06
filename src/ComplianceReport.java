import java.util.Comparator;
import java.util.List;

/**
 * Explainable assessment of modeled rules, never an operational dispatch approval.
 */
public record ComplianceReport(Status status, List<Check> checks, long flightLimitMinutes,
                               long dutyLimitMinutes, long remainingFlightMinutes,
                               long remainingDutyMinutes, long availableExtensionMinutes) {
    public ComplianceReport {
        checks = List.copyOf(checks);
    }

    public enum Status {
        CLEAR("Within modeled limits", 0), WARNING("Approaching limit", 1),
        REVIEW("Review required", 2), VIOLATION("Limit exceeded", 3);
        private final String label;
        private final int priority;

        Status(String label, int priority) {
            this.label = label;
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Check(String code, String title, Status status, String explanation) {
    }

    public boolean passesModeledRules() {
        return status == Status.CLEAR || status == Status.WARNING;
    }

    public static Status highest(List<Check> checks) {
        return checks.stream().map(Check::status).max(Comparator.comparingInt(Status::priority)).orElse(Status.CLEAR);
    }
}
