import java.time.Instant;

/**
 * A persisted warning with acknowledgement kept separately from regulatory findings.
 */
public record Alert(String key, String pilotUsername, String sourceKey, String rule,
                    ComplianceReport.Status severity, String message, Instant createdAt,
                    String acknowledgedBy, String acknowledgement, boolean resolved) {
    public boolean acknowledged() {
        return acknowledgedBy != null && !acknowledgedBy.isBlank();
    }
}
