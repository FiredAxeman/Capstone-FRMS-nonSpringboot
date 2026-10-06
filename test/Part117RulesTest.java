import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent regulatory examples sourced from eCFR Part 117 Tables A/B and §§117.19/23/25.
 */
class Part117RulesTest {
    private final FatigueRuleEngine engine = new FatigueRuleEngine();

    @ParameterizedTest
    @CsvSource({"00:00,480", "04:59,480", "05:00,540", "19:59,540", "20:00,480", "23:59,480"})
    void tableAChangesAtExactReportTimeBoundaries(String time, int minutes) {
        assertEquals(minutes, engine.flightLimitMinutes(LocalTime.parse(time)));
    }

    @ParameterizedTest
    @CsvSource({
        "00:00,1,540", "03:59,7,540", "04:00,4,600", "04:59,5,540",
        "05:00,4,720", "05:00,5,690", "05:59,6,660", "05:59,7,630",
        "06:00,2,780", "06:00,3,720", "06:59,5,690", "06:59,7,630",
        "07:00,1,840", "07:00,3,780", "11:59,5,750", "11:59,6,720", "11:59,7,690",
        "12:00,1,780", "12:59,4,780", "12:59,7,690",
        "13:00,4,720", "16:59,5,690", "16:59,6,660", "16:59,7,630",
        "17:00,2,720", "17:00,3,660", "21:59,5,600", "21:59,6,540",
        "22:00,2,660", "22:00,3,600", "22:59,7,540",
        "23:00,3,600", "23:00,4,540", "23:59,10,540"})
    void tableBUsesReportTimeAndFlightSegments(String time, int segments, int minutes) {
        assertEquals(minutes, engine.dutyLimitMinutes(LocalTime.parse(time), segments, true));
        assertEquals(minutes - 30, engine.dutyLimitMinutes(LocalTime.parse(time), segments, false));
    }

    @Test
    void acceptsAnExactDailyFlightLimitAndWarnsBeforeIt() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        DutyPeriod atLimit = TestFixtures.duty("2026-10-07T07:00", 600, 540);
        assertEquals(ComplianceReport.Status.WARNING, TestFixtures.check(engine.evaluate(pilot, atLimit, List.of()), "dailyFlight").status());
        DutyPeriod over = TestFixtures.duty("2026-10-07T07:00", 600, 541);
        assertEquals(ComplianceReport.Status.VIOLATION, TestFixtures.check(engine.evaluate(pilot, over, List.of()), "dailyFlight").status());
        assertEquals(0, engine.evaluate(pilot, atLimit, List.of()).remainingFlightMinutes());
    }

    @Test
    void clipsActualFlightAndFdpIntervalsAtTheRollingBoundary() {
        DutyPeriod crossing = new DutyPeriod(LocalDateTime.parse("2026-10-01T10:00"), LocalDateTime.parse("2026-10-01T16:00"),
            LocalDateTime.parse("2026-10-01T16:30"), "UTC",
            List.of(new FlightLeg(Instant.parse("2026-10-01T11:30:00Z"), Instant.parse("2026-10-01T12:30:00Z"))),
            true, true, true, false, 0, false, false, false, "");
        Instant at = Instant.parse("2026-10-08T12:00:00Z");
        assertEquals(240, engine.rollingDutyMinutes(List.of(crossing), at, 168));
        assertEquals(30, engine.rollingFlightMinutes(List.of(crossing), at, 168));
    }

    @Test
    void checksThePeakDuringAnAssignmentEvenWhenTheOldFlightHoursExpireBeforeItsEnd() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        Instant report = Instant.parse("2026-10-08T07:00:00Z");
        Instant oldStart = report.minusSeconds(672 * 3600).plusSeconds(5 * 3600);
        pilot.logDutyPeriod(new DutyPeriod(Times.local(oldStart, "UTC"), Times.local(oldStart.plusSeconds(3 * 3600), "UTC"),
            Times.local(oldStart.plusSeconds(3 * 3600), "UTC"), "UTC",
            List.of(new FlightLeg(oldStart.plusSeconds(30 * 60), oldStart.plusSeconds(150 * 60))),
            true, true, true, false, 0, false, false, false, ""));
        for (int daysAgo = 1; daysAgo <= 19; daysAgo++) {
            pilot.logDutyPeriod(TestFixtures.duty(Times.local(report.minusSeconds(daysAgo * 24 * 3600L), "UTC").toString(), 360, 300));
        }
        DutyPeriod candidate = TestFixtures.duty("2026-10-08T07:00", 720, 240);
        ComplianceReport result = engine.evaluate(pilot, candidate, List.of());
        assertEquals(ComplianceReport.Status.VIOLATION, TestFixtures.check(result, "flight28").status());
        assertTrue(TestFixtures.check(result, "flight28").explanation().startsWith("101:00"));
        pilot.logDutyPeriod(candidate);
        assertEquals(99 * 60, engine.rollingFlightMinutes(pilot.getDutyHistory(), candidate.getEndInstant(), 672));
    }

    @Test
    void uses365CalendarDaysRatherThanLifetimeHoursOrFixedElapsedHours() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        DutyPeriod boundary = new DutyPeriod(LocalDateTime.parse("2025-10-08T22:00"), LocalDateTime.parse("2025-10-09T02:00"),
            LocalDateTime.parse("2025-10-09T02:00"), "UTC",
            List.of(new FlightLeg(Instant.parse("2025-10-08T23:00:00Z"), Instant.parse("2025-10-09T01:00:00Z"))),
            true, true, true, false, 0, false, false, false, "");
        pilot.logDutyPeriod(boundary);
        assertEquals(60, engine.calendarFlightMinutes(pilot.getDutyHistory(), TestFixtures.CLOCK.instant(), "UTC"));
        assertEquals(100 * 60 + 120, pilot.getLifetimeFlightMinutes());
    }

    @Test
    void postflightReleaseDelaysTheTenHourRestClock() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        DutyPeriod history = new DutyPeriod(LocalDateTime.parse("2026-10-06T08:00"), LocalDateTime.parse("2026-10-06T16:00"),
            LocalDateTime.parse("2026-10-06T17:00"), "UTC", List.of(), true, true, true, false, 0, false, false, false, "");
        pilot.logDutyPeriod(history);
        DutyPeriod early = TestFixtures.duty("2026-10-07T02:59", 360, 120);
        DutyPeriod exact = TestFixtures.duty("2026-10-07T03:00", 360, 120);
        assertEquals(ComplianceReport.Status.VIOLATION, TestFixtures.check(engine.evaluate(pilot, early, List.of()), "rest10").status());
        assertEquals(ComplianceReport.Status.CLEAR, TestFixtures.check(engine.evaluate(pilot, exact, List.of()), "rest10").status());
    }

    @Test
    void allowsTwoHoursOnlyWithRecordedUnforeseenCircumstancesAndBothApprovals() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        ComplianceReport authorized = engine.evaluate(pilot, TestFixtures.extended("2026-10-07T07:00", 960, 120, true), List.of());
        assertTrue(authorized.passesModeledRules());
        assertEquals(120, authorized.availableExtensionMinutes());
        ComplianceReport unauthorized = engine.evaluate(pilot, TestFixtures.extended("2026-10-07T07:00", 960, 120, false), List.of());
        assertFalse(unauthorized.passesModeledRules());
        assertEquals(ComplianceReport.Status.VIOLATION, TestFixtures.check(unauthorized, "extension").status());
    }

    @Test
    void restrictsASecondExtensionOverThirtyMinutesUntilAThirtyHourRest() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        pilot.logDutyPeriod(TestFixtures.extended("2026-10-06T07:00", 900, 60, true));
        ComplianceReport tooMuch = engine.evaluate(pilot, TestFixtures.extended("2026-10-07T09:00", 885, 45, true), List.of());
        assertEquals(ComplianceReport.Status.VIOLATION, TestFixtures.check(tooMuch, "extension").status());
        ComplianceReport thirty = engine.evaluate(pilot, TestFixtures.extended("2026-10-07T09:00", 870, 30, true), List.of());
        assertTrue(thirty.passesModeledRules());
        assertEquals(30, thirty.availableExtensionMinutes());
        ComplianceReport rested = engine.evaluate(pilot, TestFixtures.extended("2026-10-08T07:00", 960, 120, true), List.of());
        assertTrue(rested.passesModeledRules());
    }

    @Test
    void distinguishesMissingHistoricalFlightTimesFromZeroFlightTime() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        pilot.logDutyPeriod(new DutyPeriod(LocalDateTime.parse("2026-10-01T08:00"), LocalDateTime.parse("2026-10-01T16:00")));
        ComplianceReport result = engine.evaluate(pilot, TestFixtures.duty("2026-10-07T07:00", 480, 240), List.of());
        assertEquals(ComplianceReport.Status.REVIEW, result.status());
        assertEquals(-1, result.remainingFlightMinutes());
    }

    @Test
    void aRestRecordCanProvideTheSleepOpportunityEvidence() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        DutyPeriod original = TestFixtures.duty("2026-10-07T07:00", 480, 240);
        DutyPeriod withoutAttestation = new DutyPeriod(original.getStartTime(), original.getEndTime(), original.getReleaseTime(), "UTC",
            original.getFlights(), true, false, true, false, 0, false, false, false, "");
        RestPeriod rest = new RestPeriod(LocalDateTime.parse("2026-10-06T21:00"), original.getStartTime(), "UTC", 480, "");
        assertEquals(ComplianceReport.Status.REVIEW, engine.evaluate(pilot, withoutAttestation, List.of()).status());
        assertTrue(engine.evaluate(pilot, withoutAttestation, List.of(rest)).passesModeledRules());
    }

    @Test
    void refersSpecialOperationsForReviewInsteadOfDeclaringThemWithinLimits() {
        Pilot pilot = TestFixtures.user("pilot", Role.PILOT);
        DutyPeriod duty = new DutyPeriod(LocalDateTime.parse("2026-10-07T07:00"), LocalDateTime.parse("2026-10-07T15:00"),
            LocalDateTime.parse("2026-10-07T15:00"), "UTC", List.of(), true, true, true, true, 0, false, false, false, "");
        assertEquals(ComplianceReport.Status.REVIEW, engine.evaluate(pilot, duty, List.of()).status());
        assertFalse(engine.evaluateCompliance(pilot, duty));
        DutyPeriod augmented = new DutyPeriod(LocalDateTime.parse("2026-10-07T07:00"), LocalDateTime.parse("2026-10-08T01:00"),
            LocalDateTime.parse("2026-10-08T01:00"), "UTC",
            List.of(new FlightLeg(Instant.parse("2026-10-07T08:00:00Z"), Instant.parse("2026-10-07T21:00:00Z"))),
            true, true, true, true, 0, false, false, false, "Augmented crew requires specialist review");
        ComplianceReport special = engine.evaluate(pilot, augmented, List.of());
        assertEquals(ComplianceReport.Status.REVIEW, special.status());
        assertEquals(-1, special.remainingFlightMinutes());
        assertEquals(-1, special.remainingDutyMinutes());
    }
}
