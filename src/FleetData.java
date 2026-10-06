import java.util.List;
import java.util.Map;

/**
 * One consistent, bulk-loaded database snapshot; histories are indexed by username.
 */
public record FleetData(Map<String, Pilot> users, Map<String, List<RestPeriod>> rest) {
    public FleetData {
        users = Map.copyOf(users);
        rest = Map.copyOf(rest);
    }
}
