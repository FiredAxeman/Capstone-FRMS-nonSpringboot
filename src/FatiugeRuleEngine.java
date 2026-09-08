import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
public class FatiugeRuleEngine implements RegulationEvaluator {
    private static final int REQUIRED_REST_HOURS = 30;
    private static final int LOOKBACK_WINDOW_HOURS = 168;

    @Override
    public boolean evaluateCompliance(Pilot pilot, DutyPeriod proposedFDP) {
        // Assume pilot.getDutyHistory() returns an ArrayList of DutyPeriod objects
        ArrayList<DutyPeriod> history = pilot.getDutyHistory();
        return validateRestWindow(history, proposedFDP.getStartTime());
    }

    public boolean validateRestWindow(ArrayList<DutyPeriod> dutyHistory, LocalDateTime proposedStartTime) {
        LocalDateTime windowStart = proposedStartTime.minusHours(LOOKBACK_WINDOW_HOURS);

        // Filter history to only include duty periods that fall within the 168-hour window
        ArrayList<DutyPeriod> relevantHistory = new ArrayList<>();
        for (DutyPeriod dp : dutyHistory) {
            if (dp.getEndTime().isAfter(windowStart) || dp.getStartTime().isAfter(windowStart)) {
                relevantHistory.add(dp);
            }
        }

        // Sort chronologically by start time to ensure accurate gap calculation
        relevantHistory.sort(Comparator.comparing(DutyPeriod::getStartTime));

        // If no prior duty in the last 168 hours, the rest requirement is automatically met
        if (relevantHistory.isEmpty()) {
            return true;
        }

        // Check the gap between the start of the 168-hour window and the first duty period
        DutyPeriod firstDuty = relevantHistory.get(0);
        if (Duration.between(windowStart, firstDuty.getStartTime()).toHours() >= REQUIRED_REST_HOURS) {
            return true;
        }

        // Check gaps between consecutive duty periods
        for (int i = 0; i < relevantHistory.size() - 1; i++) {
            LocalDateTime endOfCurrent = relevantHistory.get(i).getEndTime();
            LocalDateTime startOfNext = relevantHistory.get(i + 1).getStartTime();

            if (Duration.between(endOfCurrent, startOfNext).toHours() >= REQUIRED_REST_HOURS) {
                return true;
            }
        }

        // Check the gap between the last recorded duty period and the proposed start time
        DutyPeriod lastDuty = relevantHistory.get(relevantHistory.size() - 1);
        if (Duration.between(lastDuty.getEndTime(), proposedStartTime).toHours() >= REQUIRED_REST_HOURS) {
            return true;
        }

        // If no 30-hour gap is found, compliance fails
        return false;
    }
}
