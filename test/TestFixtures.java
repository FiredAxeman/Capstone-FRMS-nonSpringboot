import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

final class TestFixtures {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T18:00:00Z"), ZoneOffset.UTC);
    static final String PASSWORD = "test-password-2026";
    static final String HASH = User.hashPassword(PASSWORD);

    private TestFixtures() {
    }

    static Pilot user(String name, Role role) {
        return new Pilot(name, HASH, role.label(), 100, "ATP", "DEN", name, "UTC");
    }

    static DutyPeriod duty(String start, int dutyMinutes, int flightMinutes) {
        LocalDateTime first = LocalDateTime.parse(start);
        LocalDateTime last = first.plusMinutes(dutyMinutes);
        List<FlightLeg> legs = flightMinutes == 0 ? List.of()
            : List.of(new FlightLeg(first.plusMinutes(15), first.plusMinutes(15 + flightMinutes), "UTC"));
        return new DutyPeriod(first, last, last, "UTC", legs, true, true, true, false,
            0, false, false, false, "Test actual record");
    }

    static DutyPeriod extended(String start, int duration, int extension, boolean approved) {
        LocalDateTime first = LocalDateTime.parse(start);
        LocalDateTime last = first.plusMinutes(duration);
        return new DutyPeriod(first, last, last, "UTC",
            List.of(new FlightLeg(first.plusHours(1), first.plusHours(3), "UTC")),
            true, true, true, false, extension, approved, approved, approved, "Weather diversion before takeoff");
    }

    static ComplianceReport.Check check(ComplianceReport report, String code) {
        return report.checks().stream().filter(check -> check.code().equals(code)).findFirst().orElseThrow();
    }
}
