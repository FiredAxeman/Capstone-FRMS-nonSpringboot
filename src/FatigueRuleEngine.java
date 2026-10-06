import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Part 117 Tables A/B, 117.13(b), 117.19(a), 117.23, and ordinary 117.25(b/e).
 * Flight totals use actual block-time intervals; FDP totals clip intervals at rolling boundaries.
 * Special operations are explicitly referred for review rather than treated as compliant.
 */
public class FatigueRuleEngine implements RegulationEvaluator {
    public static final int REQUIRED_REST_HOURS = 30;
    public static final int LOOKBACK_WINDOW_HOURS = 168;
    public static final long WEEKLY_DUTY_LIMIT = 60 * 60L;
    public static final long MONTHLY_DUTY_LIMIT = 190 * 60L;
    public static final long MONTHLY_FLIGHT_LIMIT = 100 * 60L;
    public static final long ANNUAL_FLIGHT_LIMIT = 1000 * 60L;
    private static final int[][] TABLE_B = {
        {540, 540, 540, 540, 540, 540, 540},
        {600, 600, 600, 600, 540, 540, 540},
        {720, 720, 720, 720, 690, 660, 630},
        {780, 780, 720, 720, 690, 660, 630},
        {840, 840, 780, 780, 750, 720, 690},
        {780, 780, 780, 780, 750, 720, 690},
        {720, 720, 720, 720, 690, 660, 630},
        {720, 720, 660, 660, 600, 540, 540},
        {660, 660, 600, 600, 540, 540, 540},
        {600, 600, 600, 540, 540, 540, 540}
    };

    @Override
    public boolean evaluateCompliance(Pilot pilot, DutyPeriod proposedFDP) {
        return evaluate(pilot, proposedFDP, List.of()).passesModeledRules();
    }

    public ComplianceReport evaluate(Pilot pilot, DutyPeriod proposed, List<RestPeriod> rest) {
        Objects.requireNonNull(pilot, "pilot");
        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(rest, "rest");
        List<DutyPeriod> history = pilot.getDutyHistory().stream()
            .filter(period -> !period.getRecordKey().equals(proposed.getRecordKey())).toList();
        List<ComplianceReport.Check> checks = new ArrayList<>();
        check(checks, "overlap", "Duty overlap", hasOverlappingDuty(history, proposed)
                ? ComplianceReport.Status.VIOLATION : ComplianceReport.Status.CLEAR,
            hasOverlappingDuty(history, proposed) ? "This record overlaps another duty or postflight duty."
                : "Duty intervals are distinct.");
        long longestRest = longestRestMinutes(history, proposed.getStartInstant());
        check(checks, "rest30", "30 hours free from duty / 168 hours",
            longestRest >= 1800 ? ComplianceReport.Status.CLEAR : ComplianceReport.Status.VIOLATION,
            "Longest continuous rest in the lookback: " + Times.hours(longestRest) + " / 30:00.");
        long immediateRest = immediateRestMinutes(history, proposed.getStartInstant());
        check(checks, "rest10", "10 hours immediately before report",
            immediateRest >= 600 ? ComplianceReport.Status.CLEAR : ComplianceReport.Status.VIOLATION,
            "Time since release from all duty: " + Times.hours(immediateRest) + " / 10:00.");
        boolean sleep = proposed.isSleepOpportunityConfirmed() || rest.stream().anyMatch(period ->
            period.endInstant().equals(proposed.getStartInstant())
                && period.durationMinutes() >= 600 && period.sleepMinutes() >= 480);
        check(checks, "sleep", "8-hour uninterrupted sleep opportunity",
            sleep ? ComplianceReport.Status.CLEAR : ComplianceReport.Status.REVIEW,
            sleep ? "Sleep opportunity attestation recorded." : "Record a qualifying rest period or confirm the sleep opportunity.");
        check(checks, "fitness", "Fitness for duty (§117.5)",
            proposed.isFitForDuty() ? ComplianceReport.Status.CLEAR
                : proposed.hasFlightData() ? ComplianceReport.Status.VIOLATION : ComplianceReport.Status.REVIEW,
            proposed.isFitForDuty() ? "Fit-for-duty attestation recorded."
                : proposed.hasFlightData() ? "The pilot has not affirmed fitness for duty." : "Legacy fitness attestation is missing.");

        long dailyFlightLimit = flightLimitMinutes(proposed.getStartTime().toLocalTime());
        long baseDutyLimit = dutyLimitMinutes(proposed.getStartTime().toLocalTime(),
            proposed.getSegments(), proposed.isAcclimated());
        long extensionCapacity = extensionCapacity(history, proposed, baseDutyLimit);
        boolean approvals = proposed.isUnforeseen() && proposed.isPicApproved() && proposed.isCarrierApproved()
            && !proposed.getNotes().isBlank();
        long authorizedExtension = approvals ? Math.min(proposed.getExtensionMinutes(), extensionCapacity) : 0;
        if (proposed.getExtensionMinutes() > 0) {
            boolean valid = approvals && proposed.getExtensionMinutes() <= extensionCapacity;
            check(checks, "extension", "Pre-takeoff FDP extension (§117.19(a))",
                valid ? ComplianceReport.Status.WARNING : ComplianceReport.Status.VIOLATION,
                valid ? "Unforeseen circumstance and PIC/carrier concurrence recorded. Extensions over 0:30 require an FAA report within 10 days."
                    : "Extension needs unforeseen circumstances, PIC and carrier concurrence, notes, available cumulative hours, and no repeat extension over 0:30 since qualifying rest.");
        }
        limit(checks, "dailyFlight", "Daily flight time (Table A)", proposed.getFlightMinutes(), dailyFlightLimit);
        limit(checks, "dailyDuty", "FDP (Table B)", proposed.calculateDuration(), baseDutyLimit + authorizedExtension);

        List<DutyPeriod> withCandidate = new ArrayList<>(history);
        withCandidate.add(proposed);
        long duty7 = peakRollingDuty(withCandidate, proposed, 168);
        long duty28 = peakRollingDuty(withCandidate, proposed, 672);
        long flight28 = peakRollingFlight(withCandidate, proposed, 672);
        long flight365 = peakCalendarFlight(withCandidate, proposed);
        limit(checks, "duty7", "FDP / 168 hours", duty7, WEEKLY_DUTY_LIMIT);
        limit(checks, "duty28", "FDP / 672 hours", duty28, MONTHLY_DUTY_LIMIT);
        limit(checks, "flight28", "Flight / 672 hours", flight28, MONTHLY_FLIGHT_LIMIT);
        limit(checks, "flight365", "Flight / 365 calendar days", flight365, ANNUAL_FLIGHT_LIMIT);

        Instant yearStart = calendarWindowStart(proposed.getStartInstant(), proposed.getZoneId());
        if (!proposed.hasFlightData() || history.stream().anyMatch(period ->
            !period.hasFlightData() && period.getStartInstant().isBefore(proposed.getEndInstant())
                && period.getEndInstant().isAfter(yearStart))) {
            check(checks, "missingFlight", "Complete flight history", ComplianceReport.Status.REVIEW,
                "Legacy flight-leg times are missing. Flight totals and availability are incomplete until these records are corrected.");
        }
        if (proposed.isSpecialOperation()) {
            check(checks, "scope", "Operational scope", ComplianceReport.Status.REVIEW,
                "Augmented, reserve, split duty, deadhead, extended-theater travel, consecutive nighttime, and emergency provisions require specialist review.");
        }
        if (!proposed.isAcclimated()) {
            check(checks, "acclimation", "Non-acclimated report time", ComplianceReport.Status.WARNING,
                "Table B reduced by 0:30. Times must be entered in the last acclimated theater's time zone.");
        }
        long remainingFlight = Math.max(0, Math.min(dailyFlightLimit - proposed.getFlightMinutes(),
            Math.min(MONTHLY_FLIGHT_LIMIT - flight28, ANNUAL_FLIGHT_LIMIT - flight365)));
        long remainingDuty = Math.max(0, Math.min(baseDutyLimit + authorizedExtension - proposed.calculateDuration(),
            Math.min(WEEKLY_DUTY_LIMIT - duty7, MONTHLY_DUTY_LIMIT - duty28)));
        if (checks.stream().anyMatch(item -> item.code().equals("missingFlight"))) {
            remainingFlight = -1; // Unknown is never displayed as positive availability.
        }
        if (proposed.isSpecialOperation()) {
            // Baseline ceilings cannot establish a violation in an operational context outside this model.
            checks.replaceAll(item -> item.status() == ComplianceReport.Status.VIOLATION
                && !item.code().equals("overlap") && !item.code().equals("fitness")
                ? new ComplianceReport.Check(item.code(), item.title(), ComplianceReport.Status.REVIEW,
                "Unaugmented baseline: " + item.explanation() + " Additional provisions require review.") : item);
            remainingFlight = -1;
            remainingDuty = -1;
        }
        return new ComplianceReport(ComplianceReport.highest(checks), checks, dailyFlightLimit,
            baseDutyLimit + authorizedExtension, remainingFlight, remainingDuty,
            approvals && !proposed.isSpecialOperation() ? extensionCapacity : 0);
    }

    public long flightLimitMinutes(LocalTime reportTime) {
        return reportTime.getHour() >= 5 && reportTime.getHour() < 20 ? 540 : 480;
    }

    public long dutyLimitMinutes(LocalTime reportTime, int segments, boolean acclimated) {
        if (segments < 1) {
            throw new IllegalArgumentException("At least one flight segment is required.");
        }
        int hour = reportTime.getHour();
        int row = hour < 4 ? 0 : hour < 5 ? 1 : hour < 6 ? 2 : hour < 7 ? 3 : hour < 12 ? 4
            : hour < 13 ? 5 : hour < 17 ? 6 : hour < 22 ? 7 : hour < 23 ? 8 : 9;
        return TABLE_B[row][Math.min(segments, 7) - 1] - (acclimated ? 0 : 30);
    }

    public boolean hasOverlappingDuty(List<DutyPeriod> history, DutyPeriod proposed) {
        return history.stream().anyMatch(period ->
            period.getStartInstant().isBefore(proposed.getReleaseInstant())
                && period.getReleaseInstant().isAfter(proposed.getStartInstant()));
    }

    public boolean validateRestWindow(List<DutyPeriod> history, LocalDateTime report) {
        return longestRestMinutes(history, report.atZone(ZoneId.systemDefault()).toInstant()) >= 1800;
    }

    public long longestRestMinutes(List<DutyPeriod> history, Instant report) {
        Instant windowStart = report.minus(Duration.ofHours(168));
        List<DutyPeriod> relevant = history.stream()
            .filter(period -> period.getStartInstant().isBefore(report)
                && period.getReleaseInstant().isAfter(windowStart))
            .sorted(Comparator.comparing(DutyPeriod::getStartInstant)).toList();
        Instant occupied = windowStart;
        long longest = 0;
        for (DutyPeriod period : relevant) {
            Instant start = period.getStartInstant().isBefore(windowStart) ? windowStart : period.getStartInstant();
            Instant end = period.getReleaseInstant().isAfter(report) ? report : period.getReleaseInstant();
            longest = Math.max(longest, Duration.between(occupied, start).toMinutes());
            if (end.isAfter(occupied)) {
                occupied = end;
            }
        }
        return Math.max(longest, Duration.between(occupied, report).toMinutes());
    }

    public long immediateRestMinutes(List<DutyPeriod> history, Instant report) {
        Instant release = history.stream().filter(period -> period.getStartInstant().isBefore(report))
            .map(DutyPeriod::getReleaseInstant).max(Comparator.naturalOrder())
            .orElse(report.minus(Duration.ofHours(168)));
        return Math.max(0, Duration.between(release, report).toMinutes());
    }

    public long rollingDutyMinutes(List<DutyPeriod> history, Instant at, int hours) {
        Instant start = at.minus(Duration.ofHours(hours));
        return history.stream().mapToLong(period ->
            Times.overlapMinutes(period.getStartInstant(), period.getEndInstant(), start, at)).sum();
    }

    public long rollingFlightMinutes(List<DutyPeriod> history, Instant at, int hours) {
        return flightMinutesBetween(history, at.minus(Duration.ofHours(hours)), at);
    }

    public long calendarFlightMinutes(List<DutyPeriod> history, Instant at, String zone) {
        return flightMinutesBetween(history, calendarWindowStart(at, zone), at);
    }

    private long flightMinutesBetween(List<DutyPeriod> history, Instant from, Instant to) {
        return history.stream().flatMap(period -> period.getFlights().stream())
            .mapToLong(leg -> Times.overlapMinutes(leg.start(), leg.end(), from, to)).sum();
    }

    private Instant calendarWindowStart(Instant at, String zone) {
        return at.atZone(ZoneId.of(zone)).toLocalDate().minusDays(364).atStartOfDay(ZoneId.of(zone)).toInstant();
    }

    private TreeSet<Instant> criticalTimes(List<DutyPeriod> history, DutyPeriod proposed, int hours) {
        TreeSet<Instant> times = new TreeSet<>();
        times.add(proposed.getStartInstant());
        times.add(proposed.getEndInstant());
        Duration window = Duration.ofHours(hours);
        for (DutyPeriod period : history) {
            List<Instant> endpoints = new ArrayList<>(List.of(period.getStartInstant(), period.getEndInstant()));
            period.getFlights().forEach(flight -> {
                endpoints.add(flight.start());
                endpoints.add(flight.end());
            });
            for (Instant endpoint : endpoints) {
                for (Instant at : List.of(endpoint, endpoint.plus(window))) {
                    if (!at.isBefore(proposed.getStartInstant()) && !at.isAfter(proposed.getEndInstant())) {
                        times.add(at);
                    }
                }
            }
        }
        return times;
    }

    private long peakRollingDuty(List<DutyPeriod> history, DutyPeriod proposed, int hours) {
        return criticalTimes(history, proposed, hours).stream()
            .mapToLong(at -> rollingDutyMinutes(history, at, hours)).max().orElse(0);
    }

    private long peakRollingFlight(List<DutyPeriod> history, DutyPeriod proposed, int hours) {
        return criticalTimes(history, proposed, hours).stream()
            .mapToLong(at -> rollingFlightMinutes(history, at, hours)).max().orElse(0);
    }

    private long peakCalendarFlight(List<DutyPeriod> history, DutyPeriod proposed) {
        TreeSet<Instant> times = criticalTimes(history, proposed, 24 * 365);
        ZoneId zone = ZoneId.of(proposed.getZoneId());
        LocalDate first = proposed.getStartInstant().atZone(zone).toLocalDate();
        LocalDate last = proposed.getEndInstant().atZone(zone).toLocalDate();
        for (LocalDate day = first.plusDays(1); !day.isAfter(last); day = day.plusDays(1)) {
            // Include the minute before the window advances at midnight.
            times.add(day.atStartOfDay(zone).toInstant().minusSeconds(60));
            times.add(day.atStartOfDay(zone).toInstant());
        }
        return times.stream().mapToLong(at -> calendarFlightMinutes(history, at, proposed.getZoneId())).max().orElse(0);
    }

    private long extensionCapacity(List<DutyPeriod> history, DutyPeriod proposed, long baseLimit) {
        long used7 = rollingDutyMinutes(history, proposed.getEndInstant(), 168);
        long used28 = rollingDutyMinutes(history, proposed.getEndInstant(), 672);
        long capacity = Math.max(0, Math.min(120, Math.min(WEEKLY_DUTY_LIMIT - used7 - baseLimit,
            MONTHLY_DUTY_LIMIT - used28 - baseLimit)));
        List<DutyPeriod> previous = history.stream()
            .filter(period -> !period.getReleaseInstant().isAfter(proposed.getStartInstant()))
            .sorted(Comparator.comparing(DutyPeriod::getStartInstant).reversed()).toList();
        Instant nextStart = proposed.getStartInstant();
        for (DutyPeriod period : previous) {
            if (Duration.between(period.getReleaseInstant(), nextStart).toMinutes() >= 1800) {
                break;
            }
            long actualExtension = period.calculateDuration() - dutyLimitMinutes(
                period.getStartTime().toLocalTime(), period.getSegments(), period.isAcclimated());
            if (actualExtension > 30) {
                capacity = Math.min(capacity, 30);
                break;
            }
            nextStart = period.getStartInstant();
        }
        return capacity;
    }

    private void limit(List<ComplianceReport.Check> checks, String code, String title, long used, long maximum) {
        ComplianceReport.Status status = used > maximum ? ComplianceReport.Status.VIOLATION
            : used >= maximum * 0.9 ? ComplianceReport.Status.WARNING : ComplianceReport.Status.CLEAR;
        check(checks, code, title, status, Times.hours(used) + " used / " + Times.hours(maximum) + " maximum.");
    }

    private void check(List<ComplianceReport.Check> checks, String code, String title,
                       ComplianceReport.Status status, String explanation) {
        checks.add(new ComplianceReport.Check(code, title, status, explanation));
    }
}
