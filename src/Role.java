/**
 * Roles used by both service authorization and queued write replay.
 */
public enum Role {
    ADMINISTRATOR("Administrator"), DISPATCHER("Dispatcher"), PILOT("Pilot");
    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean managesFleet() {
        return this != PILOT;
    }

    public static Role from(String value) {
        for (Role role : values()) {
            if (role.label.equalsIgnoreCase(value) || role.name().equalsIgnoreCase(value)) {
                return role;
            }
        }
        if ("Chief Flight Instructor".equalsIgnoreCase(value)) {
            return DISPATCHER;
        }
        throw new IllegalArgumentException("Choose Administrator, Dispatcher, or Pilot.");
    }

    @Override
    public String toString() {
        return label;
    }
}
