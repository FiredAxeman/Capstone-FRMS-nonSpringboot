import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FatigueRuleEngineTest {
    private final FatigueRuleEngine engine = new FatigueRuleEngine();
    private final LocalDateTime proposedStart = LocalDateTime.parse("2026-10-08T00:00:00");

    @Test
    void acceptsExactlyThirtyHoursOfRest() {
        DutyPeriod lastDuty = period("2026-10-01T08:00:00", "2026-10-06T18:00:00");

        assertTrue(engine.validateRestWindow(List.of(lastDuty), proposedStart));
    }

    @Test
    void rejectsLessThanThirtyHoursOfRest() {
        DutyPeriod lastDuty = period("2026-10-01T08:00:00", "2026-10-06T18:01:00");

        assertFalse(engine.validateRestWindow(List.of(lastDuty), proposedStart));
    }

    @Test
    void evaluatesDutyThatBeganBeforeTheRollingWindow() {
        DutyPeriod crossingWindow = period("2026-09-30T23:00:00", "2026-10-06T18:01:00");

        assertFalse(engine.validateRestWindow(List.of(crossingWindow), proposedStart));
    }

    @Test
    void considersGapsBetweenHistoricalDuties() {
        List<DutyPeriod> history = List.of(
            period("2026-10-01T08:00:00", "2026-10-01T09:00:00"),
            period("2026-10-02T16:00:00", "2026-10-02T17:00:00"));

        assertTrue(engine.validateRestWindow(history, proposedStart));
    }

    @Test
    void allowsAnEmptyDutyHistory() {
        assertTrue(engine.validateRestWindow(List.of(), proposedStart));
    }

    @Test
    void rejectsOverlappingProposedDuty() {
        Pilot pilot = new Pilot("pilot", "", "Pilot", 0, "ATP", "DEN");
        pilot.logDutyPeriod(period("2026-10-07T08:00:00", "2026-10-07T16:00:00"));
        DutyPeriod proposed = period("2026-10-07T15:00:00", "2026-10-07T20:00:00");

        assertFalse(engine.evaluateCompliance(pilot, proposed));
    }

    private static DutyPeriod period(String start, String end) {
        return new DutyPeriod(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }
}
