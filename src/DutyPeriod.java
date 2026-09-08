import java.time.LocalDateTime;
import java.time.Duration;

public class DutyPeriod {
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public DutyPeriod(LocalDateTime startTime, LocalDateTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    // Getters for temporal data evaluation
    public LocalDateTime getStartTime() {
        return this.startTime;
    }

    public LocalDateTime getEndTime() {
        return this.endTime;
    }

    // Setters
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    // Calculates flight and duty duration as defined in the UML schema
    public long calculateDuration() {
        return Duration.between(startTime, endTime).toMinutes();
    }
}
