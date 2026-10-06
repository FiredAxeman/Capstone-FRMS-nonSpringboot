import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DutyPeriodTest {
    @Test
    void calculatesDurationInMinutes() {
        DutyPeriod period = new DutyPeriod(
            LocalDateTime.parse("2026-10-01T08:00:00"),
            LocalDateTime.parse("2026-10-01T16:30:00"));

        assertEquals(510, period.calculateDuration());
    }

    @Test
    void rejectsNonPositiveDuration() {
        LocalDateTime start = LocalDateTime.parse("2026-10-01T08:00:00");

        assertThrows(IllegalArgumentException.class, () -> new DutyPeriod(start, start));
        assertThrows(IllegalArgumentException.class,
            () -> new DutyPeriod(start, start.minusMinutes(1)));
    }
}
