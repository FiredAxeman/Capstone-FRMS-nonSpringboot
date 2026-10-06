import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FDP, release from all duty, flight legs, and operational attestations.
 */
public class DutyPeriod {
    private final String recordKey;
    private final int revision;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final LocalDateTime releaseTime;
    private final String zoneId;
    private final Instant startInstant;
    private final Instant endInstant;
    private final Instant releaseInstant;
    private final List<FlightLeg> flights;
    private final boolean acclimated;
    private final boolean sleepOpportunityConfirmed;
    private final boolean fitForDuty;
    private final boolean specialOperation;
    private final int extensionMinutes;
    private final boolean unforeseen;
    private final boolean picApproved;
    private final boolean carrierApproved;
    private final String notes;

    /**
     * Legacy inputs have unknown flight time and require review in a full assessment.
     */
    public DutyPeriod(LocalDateTime startTime, LocalDateTime endTime) {
        this(UUID.randomUUID().toString(), 0, startTime, endTime, endTime, ZoneId.systemDefault().getId(),
            null, true, false, false, false, 0, false, false, false, "Legacy duty record");
    }

    public DutyPeriod(LocalDateTime start, LocalDateTime end, LocalDateTime release, String zone,
                      List<FlightLeg> flights, boolean acclimated, boolean sleepConfirmed,
                      boolean fitForDuty, boolean specialOperation, int extensionMinutes,
                      boolean unforeseen, boolean picApproved, boolean carrierApproved, String notes) {
        this(UUID.randomUUID().toString(), 0, start, end, release, zone, flights, acclimated, sleepConfirmed,
            fitForDuty, specialOperation, extensionMinutes, unforeseen, picApproved, carrierApproved, notes);
    }

    public DutyPeriod(String recordKey, int revision, LocalDateTime start, LocalDateTime end,
                      LocalDateTime release, String zone, List<FlightLeg> flights, boolean acclimated,
                      boolean sleepConfirmed, boolean fitForDuty, boolean specialOperation, int extensionMinutes,
                      boolean unforeseen, boolean picApproved, boolean carrierApproved, String notes) {
        this.recordKey = UUID.fromString(recordKey).toString();
        this.revision = revision;
        this.startTime = Objects.requireNonNull(start, "Start time");
        this.endTime = Objects.requireNonNull(end, "FDP end time");
        this.releaseTime = Objects.requireNonNull(release, "Release time");
        this.zoneId = ZoneId.of(zone).getId();
        Instant first = flights == null ? Times.legacyInstant(start, zone) : Times.instant(start, zone);
        Instant last = flights == null ? Times.legacyInstant(end, zone) : Times.instant(end, zone);
        Instant released = flights == null ? Times.legacyInstant(release, zone) : Times.instant(release, zone);
        this.startInstant = first;
        this.endInstant = last;
        this.releaseInstant = released;
        if (!last.isAfter(first) || released.isBefore(last)) {
            throw new IllegalArgumentException("FDP end must follow report time; release must be at or after FDP end.");
        }
        if (Duration.between(first, released).toMinutes() > 48 * 60L && flights != null) {
            throw new IllegalArgumentException("A duty record may not exceed 48 hours.");
        }
        if (revision < 0 || extensionMinutes < 0 || extensionMinutes > 120) {
            throw new IllegalArgumentException("Extension must be between 0 and 120 minutes.");
        }
        if (flights == null) {
            this.flights = null;
        } else {
            List<FlightLeg> sorted = new ArrayList<>(flights);
            sorted.sort(Comparator.comparing(FlightLeg::start));
            Instant occupied = first;
            for (FlightLeg flight : sorted) {
                if (flight.start().isBefore(occupied) || flight.end().isAfter(last)) {
                    throw new IllegalArgumentException("Flight legs must fit inside the FDP and must not overlap.");
                }
                occupied = flight.end();
            }
            this.flights = List.copyOf(sorted);
        }
        this.acclimated = acclimated;
        this.sleepOpportunityConfirmed = sleepConfirmed;
        this.fitForDuty = fitForDuty;
        this.specialOperation = specialOperation;
        this.extensionMinutes = extensionMinutes;
        this.unforeseen = unforeseen;
        this.picApproved = picApproved;
        this.carrierApproved = carrierApproved;
        this.notes = notes == null ? "" : notes.trim();
        if (this.notes.length() > 2000) {
            throw new IllegalArgumentException("Notes must not exceed 2,000 characters.");
        }
    }

    public String getRecordKey() {
        return recordKey;
    }

    public int getRevision() {
        return revision;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public LocalDateTime getReleaseTime() {
        return releaseTime;
    }

    public String getZoneId() {
        return zoneId;
    }

    public Instant getStartInstant() {
        return startInstant;
    }

    public Instant getEndInstant() {
        return endInstant;
    }

    public Instant getReleaseInstant() {
        return releaseInstant;
    }

    public boolean hasFlightData() {
        return flights != null;
    }

    public List<FlightLeg> getFlights() {
        return flights == null ? List.of() : flights;
    }

    public long getFlightMinutes() {
        return getFlights().stream().mapToLong(FlightLeg::minutes).sum();
    }

    public int getSegments() {
        return Math.max(1, getFlights().size());
    }

    public boolean isAcclimated() {
        return acclimated;
    }

    public boolean isSleepOpportunityConfirmed() {
        return sleepOpportunityConfirmed;
    }

    public boolean isFitForDuty() {
        return fitForDuty;
    }

    public boolean isSpecialOperation() {
        return specialOperation;
    }

    public int getExtensionMinutes() {
        return extensionMinutes;
    }

    public boolean isUnforeseen() {
        return unforeseen;
    }

    public boolean isPicApproved() {
        return picApproved;
    }

    public boolean isCarrierApproved() {
        return carrierApproved;
    }

    public String getNotes() {
        return notes;
    }

    public long calculateDuration() {
        return Duration.between(getStartInstant(), getEndInstant()).toMinutes();
    }

    public DutyPeriod withIdentity(String key, int version) {
        return new DutyPeriod(key, version, startTime, endTime, releaseTime, zoneId, flights, acclimated,
            sleepOpportunityConfirmed, fitForDuty, specialOperation, extensionMinutes, unforeseen,
            picApproved, carrierApproved, notes);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DutyPeriod duty)) {
            return false;
        }
        return recordKey.equals(duty.recordKey) && revision == duty.revision && startTime.equals(duty.startTime)
            && endTime.equals(duty.endTime) && releaseTime.equals(duty.releaseTime) && zoneId.equals(duty.zoneId)
            && Objects.equals(flights, duty.flights) && acclimated == duty.acclimated
            && sleepOpportunityConfirmed == duty.sleepOpportunityConfirmed && fitForDuty == duty.fitForDuty
            && specialOperation == duty.specialOperation && extensionMinutes == duty.extensionMinutes
            && unforeseen == duty.unforeseen && picApproved == duty.picApproved
            && carrierApproved == duty.carrierApproved && notes.equals(duty.notes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordKey, revision, startTime, endTime, releaseTime, zoneId, flights, acclimated,
            sleepOpportunityConfirmed, fitForDuty, specialOperation, extensionMinutes, unforeseen, picApproved, carrierApproved, notes);
    }
}
