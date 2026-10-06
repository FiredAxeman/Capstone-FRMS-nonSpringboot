import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * A credential-free, versioned command for crash-safe and idempotent database replay.
 */
public record PendingWrite(String id, Instant createdAt, String actor, String pilotUsername, Operation operation,
                           String targetKey, int expectedRevision, DutyPeriod duty, RestPeriod rest) {
    public enum Operation {ADD_DUTY, EDIT_DUTY, DELETE_DUTY, ADD_REST, EDIT_REST, DELETE_REST}

    public PendingWrite {
        UUID.fromString(id);
        UUID.fromString(targetKey);
        if (actor == null || pilotUsername == null || expectedRevision < 0 || operation == null) {
            throw new IllegalArgumentException("Invalid pending write.");
        }
    }

    public static PendingWrite duty(String actor, String pilot, DutyPeriod duty, boolean update) {
        return new PendingWrite(UUID.randomUUID().toString(), Instant.now(), actor, pilot,
            update ? Operation.EDIT_DUTY : Operation.ADD_DUTY, duty.getRecordKey(), duty.getRevision(), duty, null);
    }

    public static PendingWrite rest(String actor, String pilot, RestPeriod rest, boolean update) {
        return new PendingWrite(UUID.randomUUID().toString(), Instant.now(), actor, pilot,
            update ? Operation.EDIT_REST : Operation.ADD_REST, rest.recordKey(), rest.revision(), null, rest);
    }

    public static PendingWrite delete(String actor, String pilot, String key, int revision, boolean isDuty) {
        return new PendingWrite(UUID.randomUUID().toString(), Instant.now(), actor, pilot,
            isDuty ? Operation.DELETE_DUTY : Operation.DELETE_REST, key, revision, null, null);
    }

    public Properties toProperties() {
        Properties data = new Properties();
        put(data, "format", 1);
        put(data, "id", id);
        put(data, "created", createdAt);
        put(data, "actor", actor);
        put(data, "pilot", pilotUsername);
        put(data, "operation", operation);
        put(data, "key", targetKey);
        put(data, "revision", expectedRevision);
        if (duty != null) {
            put(data, "start", duty.getStartTime());
            put(data, "end", duty.getEndTime());
            put(data, "release", duty.getReleaseTime());
            put(data, "zone", duty.getZoneId());
            put(data, "known", duty.hasFlightData());
            put(data, "acclimated", duty.isAcclimated());
            put(data, "sleep", duty.isSleepOpportunityConfirmed());
            put(data, "fit", duty.isFitForDuty());
            put(data, "special", duty.isSpecialOperation());
            put(data, "extension", duty.getExtensionMinutes());
            put(data, "unforeseen", duty.isUnforeseen());
            put(data, "pic", duty.isPicApproved());
            put(data, "carrier", duty.isCarrierApproved());
            put(data, "notes", duty.getNotes());
            put(data, "legs", duty.getFlights().size());
            for (int index = 0; index < duty.getFlights().size(); index++) {
                put(data, "leg." + index + ".start", duty.getFlights().get(index).start());
                put(data, "leg." + index + ".end", duty.getFlights().get(index).end());
            }
        }
        if (rest != null) {
            put(data, "start", rest.startTime());
            put(data, "end", rest.endTime());
            put(data, "zone", rest.zoneId());
            put(data, "sleepMinutes", rest.sleepMinutes());
            put(data, "notes", rest.notes());
        }
        return data;
    }

    public static PendingWrite fromProperties(Properties data) {
        if (!"1".equals(data.getProperty("format"))) {
            throw new IllegalArgumentException("Unsupported queue format.");
        }
        Operation operation = Operation.valueOf(required(data, "operation"));
        String key = required(data, "key");
        int revision = number(data, "revision");
        DutyPeriod duty = null;
        RestPeriod rest = null;
        if (operation == Operation.ADD_DUTY || operation == Operation.EDIT_DUTY) {
            List<FlightLeg> flights = new ArrayList<>();
            int count = number(data, "legs");
            if (count < 0 || count > 100) {
                throw new IllegalArgumentException("Invalid flight leg count.");
            }
            for (int index = 0; index < count; index++) {
                flights.add(new FlightLeg(Instant.parse(required(data, "leg." + index + ".start")),
                    Instant.parse(required(data, "leg." + index + ".end"))));
            }
            duty = new DutyPeriod(key, revision, local(data, "start"), local(data, "end"), local(data, "release"),
                required(data, "zone"), flag(data, "known") ? flights : null, flag(data, "acclimated"),
                flag(data, "sleep"), flag(data, "fit"), flag(data, "special"), number(data, "extension"),
                flag(data, "unforeseen"), flag(data, "pic"), flag(data, "carrier"), data.getProperty("notes", ""));
        } else if (operation == Operation.ADD_REST || operation == Operation.EDIT_REST) {
            rest = new RestPeriod(key, revision, local(data, "start"), local(data, "end"), required(data, "zone"),
                number(data, "sleepMinutes"), data.getProperty("notes", ""));
        }
        return new PendingWrite(required(data, "id"), Instant.parse(required(data, "created")),
            required(data, "actor"), required(data, "pilot"), operation, key, revision, duty, rest);
    }

    private static void put(Properties data, String key, Object value) {
        data.setProperty(key, value.toString());
    }

    private static String required(Properties data, String key) {
        String value = data.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Queue field missing: " + key);
        }
        return value;
    }

    private static int number(Properties data, String key) {
        return Integer.parseInt(required(data, key));
    }

    private static boolean flag(Properties data, String key) {
        return Boolean.parseBoolean(required(data, key));
    }

    private static LocalDateTime local(Properties data, String key) {
        return LocalDateTime.parse(required(data, key));
    }
}
