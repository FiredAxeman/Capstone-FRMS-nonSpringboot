import java.util.ArrayList;

public class Pilot extends User {
    private int cumulativeFlightHours;
    private String certificationStatus;
    private String baseAssignment;
    private ArrayList<DutyPeriod> dutyHistory;

    public Pilot(String username, String passwordHash, String role,
                 int cumulativeFlightHours, String certificationStatus, String baseAssignment) {
        // Inherited from User superclass
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;

        // Pilot specific variables
        this.cumulativeFlightHours = cumulativeFlightHours;
        this.certificationStatus = certificationStatus;
        this.baseAssignment = baseAssignment;
        this.dutyHistory = new ArrayList<>();
    }

    // Getter for the FatigueRuleEngine history array
    public ArrayList<DutyPeriod> getDutyHistory() {
        return this.dutyHistory;
    }

    // Getters for specific pilot attributes
    public int getCumulativeFlightHours() {
        return this.cumulativeFlightHours;
    }

    public String getCertificationStatus() {
        return this.certificationStatus;
    }

    public String getBaseAssignment() {
        return this.baseAssignment;
    }

    // Method to ingest new operational data
    public void logDutyPeriod(DutyPeriod dutyPeriod) {
        this.dutyHistory.add(dutyPeriod);
    }

    // Abstracted data retrieval method defined in the UML
    public String getPilotData() {
        return "Base: " + this.baseAssignment + " | Cert: " + this.certificationStatus;
    }
}