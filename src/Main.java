import java.time.LocalDateTime;
import java.util.ArrayList;

public class Main {
    public static void main(String[] args) {
        DatabaseManager db = new DatabaseManager();

        // 1. Initialize Pilot
        Pilot pilot = new Pilot("CaptSmith", "hash123", "Line Pilot", 1450, "ATP", "DEN");

        // 2. Load historical logs from SQL persistence into the pilot object
        ArrayList<DutyPeriod> historicalLogs = db.loadDutyHistory(pilot.username);
        for (DutyPeriod dp : historicalLogs) {
            pilot.logDutyPeriod(dp);
        }

        // 3. Propose a new duty period
        LocalDateTime proposedStart = LocalDateTime.now().plusHours(12);
        LocalDateTime proposedEnd = proposedStart.plusHours(8);
        DutyPeriod proposedFDP = new DutyPeriod(proposedStart, proposedEnd);

        // 4. Validate using the FatigueRuleEngine
        FatiugeRuleEngine engine = new FatiugeRuleEngine();
        boolean isLegal = engine.evaluateCompliance(pilot, proposedFDP);

        if (isLegal) {
            System.out.println("FDP is legally compliant under 14 CFR Part 117. Saving to database...");
            db.saveDutyPeriod(pilot.username, proposedFDP);
        } else {
            System.out.println("Warning: Pilot violates 168-hour rolling rest window.");
        }
    }
}