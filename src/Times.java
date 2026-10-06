import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

/**
 * Minute-precision inputs; ambiguous or missing DST times must be entered in UTC instead.
 */
public final class Times {
    public static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        .withResolverStyle(ResolverStyle.STRICT);
    public static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    private Times() {
    }

    public static Instant instant(LocalDateTime local, String zoneId) {
        if (local.getSecond() != 0 || local.getNano() != 0) {
            throw new IllegalArgumentException("Use whole minutes for operational times.");
        }
        return legacyInstant(local, zoneId);
    }

    static Instant legacyInstant(LocalDateTime local, String zoneId) {
        List<ZoneOffset> offsets = ZoneId.of(zoneId).getRules().getValidOffsets(local);
        if (offsets.size() != 1) {
            throw new IllegalArgumentException("This local time is ambiguous or absent during a daylight-saving change. Use UTC.");
        }
        return local.toInstant(offsets.get(0));
    }

    public static long overlapMinutes(Instant start, Instant end, Instant windowStart, Instant windowEnd) {
        Instant lower = start.isAfter(windowStart) ? start : windowStart;
        Instant upper = end.isBefore(windowEnd) ? end : windowEnd;
        return upper.isAfter(lower) ? Duration.between(lower, upper).toMinutes() : 0;
    }

    public static String hours(long minutes) {
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, Math.abs(minutes % 60));
    }

    public static LocalDateTime local(Instant instant, String zone) {
        return LocalDateTime.ofInstant(instant, ZoneId.of(zone));
    }

    public static LocalDateTime parse(String value) {
        try {
            return LocalDateTime.parse(value.trim().replace('T', ' '), INPUT);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Enter a valid date and time as YYYY-MM-DD HH:MM.", exception);
        }
    }
}
