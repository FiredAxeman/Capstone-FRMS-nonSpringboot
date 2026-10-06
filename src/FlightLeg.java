import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Actual block-out/block-in times, stored as instants for precise rolling flight totals.
 */
public record FlightLeg(Instant start, Instant end) {
    public FlightLeg {
        Objects.requireNonNull(start, "Flight start");
        Objects.requireNonNull(end, "Flight end");
        if (!end.isAfter(start) || start.getEpochSecond() % 60 != 0 || end.getEpochSecond() % 60 != 0
            || start.getNano() != 0 || end.getNano() != 0) {
            throw new IllegalArgumentException("Flight legs must have positive, whole-minute durations.");
        }
    }

    public FlightLeg(LocalDateTime start, LocalDateTime end, String zone) {
        this(Times.instant(start, zone), Times.instant(end, zone));
    }

    public long minutes() {
        return Duration.between(start, end).toMinutes();
    }
}
