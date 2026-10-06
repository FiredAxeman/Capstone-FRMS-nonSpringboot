import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * Recorded time free from all duty and its uninterrupted sleep opportunity.
 */
public record RestPeriod(String recordKey, int revision, LocalDateTime startTime, LocalDateTime endTime,
                         String zoneId, int sleepMinutes, String notes) {
    public RestPeriod {
        recordKey = UUID.fromString(recordKey).toString();
        Objects.requireNonNull(startTime, "Rest start");
        Objects.requireNonNull(endTime, "Rest end");
        zoneId = ZoneId.of(zoneId).getId();
        long duration = Duration.between(Times.instant(startTime, zoneId), Times.instant(endTime, zoneId)).toMinutes();
        if (duration <= 0 || revision < 0 || sleepMinutes < 0 || sleepMinutes > duration) {
            throw new IllegalArgumentException("Rest must have positive duration and a sleep opportunity within that duration.");
        }
        notes = notes == null ? "" : notes.trim();
        if (notes.length() > 2000) {
            throw new IllegalArgumentException("Notes must not exceed 2,000 characters.");
        }
    }

    public RestPeriod(LocalDateTime start, LocalDateTime end, String zone, int sleepMinutes, String notes) {
        this(UUID.randomUUID().toString(), 0, start, end, zone, sleepMinutes, notes);
    }

    public Instant startInstant() {
        return Times.instant(startTime, zoneId);
    }

    public Instant endInstant() {
        return Times.instant(endTime, zoneId);
    }

    public long durationMinutes() {
        return Duration.between(startInstant(), endInstant()).toMinutes();
    }

    public RestPeriod withIdentity(String key, int version) {
        return new RestPeriod(key, version, startTime, endTime, zoneId, sleepMinutes, notes);
    }
}
