import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * User profile and operational history. Opening hours are lifetime hours, not rolling totals.
 */
public class Pilot extends User {
    private final int cumulativeFlightHours;
    private final String certificationStatus;
    private final String baseAssignment;
    private final String displayName;
    private final String zoneId;
    private final List<DutyPeriod> dutyHistory = new ArrayList<>();

    public Pilot(String username, String passwordHash, String role,
                 int cumulativeFlightHours, String certificationStatus, String baseAssignment) {
        this(username, passwordHash, role, cumulativeFlightHours, certificationStatus,
            baseAssignment, username, ZoneId.systemDefault().getId());
    }

    public Pilot(String username, String passwordHash, String role, int cumulativeFlightHours,
                 String certificationStatus, String baseAssignment, String displayName, String zoneId) {
        super(username, passwordHash, role);
        if (cumulativeFlightHours < 0 || cumulativeFlightHours > 100_000) {
            throw new IllegalArgumentException("Opening lifetime hours must be between 0 and 100,000.");
        }
        this.cumulativeFlightHours = cumulativeFlightHours;
        this.certificationStatus = requireValue(certificationStatus, "Certification", 80);
        this.baseAssignment = requireValue(baseAssignment, "Base assignment", 40);
        this.displayName = requireValue(displayName, "Display name", 100);
        this.zoneId = ZoneId.of(Objects.requireNonNull(zoneId, "zoneId")).getId();
    }

    static String requireValue(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1–" + maximum + " characters.");
        }
        return value.trim();
    }

    public List<DutyPeriod> getDutyHistory() {
        return List.copyOf(dutyHistory);
    }

    public int getCumulativeFlightHours() {
        return cumulativeFlightHours;
    }

    public long getLifetimeFlightMinutes() {
        return cumulativeFlightHours * 60L + dutyHistory.stream().mapToLong(DutyPeriod::getFlightMinutes).sum();
    }

    public String getCertificationStatus() {
        return certificationStatus;
    }

    public String getBaseAssignment() {
        return baseAssignment;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void logDutyPeriod(DutyPeriod dutyPeriod) {
        dutyHistory.add(Objects.requireNonNull(dutyPeriod, "dutyPeriod"));
    }

    public String getPilotData() {
        return "Base: " + baseAssignment + " | Cert: " + certificationStatus;
    }

    public Pilot withPasswordHash(String hash) {
        Pilot copy = new Pilot(username, hash, role, cumulativeFlightHours,
            certificationStatus, baseAssignment, displayName, zoneId);
        dutyHistory.forEach(copy::logDutyPeriod);
        return copy;
    }

    @Override
    public String toString() {
        return displayName + " (" + username + ")";
    }
}
