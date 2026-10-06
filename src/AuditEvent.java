import java.time.Instant;

public record AuditEvent(Instant timestamp, String actor, String action, String target, String detail) {
}
