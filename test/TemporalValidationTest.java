import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemporalValidationTest {
    @Test
    void validatesRealDatesAndWholeMinutes() {
        assertThrows(IllegalArgumentException.class, () -> Times.parse("2026-02-30 08:00"));
        assertThrows(IllegalArgumentException.class, () -> Times.parse("2026-10-01 24:00"));
        assertThrows(IllegalArgumentException.class, () -> new FlightLeg(
            Instant.parse("2026-10-01T08:00:01Z"), Instant.parse("2026-10-01T09:00:00Z")));
    }

    @Test
    void rejectsAmbiguousAndNonexistentDaylightSavingTimes() {
        assertThrows(IllegalArgumentException.class, () -> Times.instant(LocalDateTime.parse("2026-03-08T02:30"), "America/Denver"));
        assertThrows(IllegalArgumentException.class, () -> Times.instant(LocalDateTime.parse("2026-11-01T01:30"), "America/Denver"));
        assertEquals(Instant.parse("2026-11-01T08:30:00Z"), Times.instant(LocalDateTime.parse("2026-11-01T08:30"), "UTC"));
    }

    @Test
    void computesElapsedDurationAcrossAClockChange() {
        DutyPeriod spring = new DutyPeriod(LocalDateTime.parse("2026-03-08T01:00"), LocalDateTime.parse("2026-03-08T04:00"),
            LocalDateTime.parse("2026-03-08T04:00"), "America/Denver", List.of(), true, true, true, false, 0, false, false, false, "");
        assertEquals(120, spring.calculateDuration());
    }

    @Test
    void rejectsFlightLegsOutsideDutyOrOverlappingEachOther() {
        LocalDateTime start = LocalDateTime.parse("2026-10-01T08:00");
        LocalDateTime end = start.plusHours(8);
        FlightLeg outside = new FlightLeg(start.minusMinutes(1), start.plusHours(1), "UTC");
        assertThrows(IllegalArgumentException.class, () -> new DutyPeriod(start, end, end, "UTC", List.of(outside),
            true, true, true, false, 0, false, false, false, ""));
        FlightLeg first = new FlightLeg(start.plusHours(1), start.plusHours(3), "UTC");
        FlightLeg second = new FlightLeg(start.plusHours(2), start.plusHours(4), "UTC");
        assertThrows(IllegalArgumentException.class, () -> new DutyPeriod(start, end, end, "UTC", List.of(first, second),
            true, true, true, false, 0, false, false, false, ""));
    }

    @Test
    void validatesRestDurationAndSleepOpportunity() {
        LocalDateTime start = LocalDateTime.parse("2026-10-01T08:00");
        assertThrows(IllegalArgumentException.class, () -> new RestPeriod(start, start, "UTC", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new RestPeriod(start, start.plusHours(7), "UTC", 480, ""));
        assertEquals(600, new RestPeriod(start, start.plusHours(10), "UTC", 480, "").durationMinutes());
    }
}
