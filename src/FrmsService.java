import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Application boundary: authenticated access, evaluation, offline journaling, and fleet snapshots.
 */
public final class FrmsService {
    public enum State {USER_AUTHENTICATION, DATA_INGESTION, ACTIVE_EVALUATION, VISUAL_RENDERING, OFFLINE}

    public record WriteResult(boolean queued, ComplianceReport report, String message) {
    }

    private record Attempt(int count, Instant since) {
    }

    private record EvaluationCache(List<DutyPeriod> history, List<RestPeriod> rest,
                                   Map<String, ComplianceReport> reports) {
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private final DatabaseManager database;
    private final DurableOutbox outbox;
    private final FatigueRuleEngine engine = new FatigueRuleEngine();
    private final Clock clock;
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Map<String, String> conflicts = new HashMap<>();
    private final Map<String, EvaluationCache> evaluations = new HashMap<>();
    private Pilot session;
    private FleetData cached;
    private List<Alert> cachedAlerts = List.of();
    private List<AuditEvent> cachedAudit = List.of();
    private Instant lastSynchronized;
    private boolean online = true;
    private String syncMessage = "All changes saved";
    private volatile State state = State.USER_AUTHENTICATION;

    public FrmsService(DatabaseManager database, Path outboxDirectory) throws IOException {
        this(database, outboxDirectory, Clock.systemUTC());
    }

    public FrmsService(DatabaseManager database, Path outboxDirectory, Clock clock) throws IOException {
        this.database = database;
        this.outbox = new DurableOutbox(outboxDirectory);
        this.clock = clock;
    }

    public State state() {
        return state;
    }

    public synchronized Pilot currentUser() {
        requireSession();
        return session;
    }

    public Path databasePath() {
        return database.getDatabasePath();
    }

    public synchronized Pilot signIn(String username, char[] password) throws SQLException {
        state = State.USER_AUTHENTICATION;
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        Attempt attempt = attempts.get(normalized);
        if (attempt != null && Duration.between(attempt.since(), clock.instant()).toSeconds() >= 60) {
            attempts.remove(normalized);
            attempt = null;
        }
        if (attempt != null && attempt.count() >= 5) {
            throw new IllegalArgumentException("Too many sign-in attempts. Wait one minute before trying again.");
        }
        Pilot user = retry(() -> database.findUser(normalized));
        if (user == null || !user.authenticate(password)) {
            attempts.put(normalized, new Attempt(attempt == null ? 1 : attempt.count() + 1,
                attempt == null ? clock.instant() : attempt.since()));
            throw new IllegalArgumentException("Username or password is incorrect.");
        }
        if (!user.getPasswordHash().startsWith("pbkdf2$")) {
            String hash = User.hashPassword(password);
            String accountName = user.getUsername();
            retry(() -> {
                database.changePassword(accountName, accountName, hash);
                return null;
            });
            user = user.withPasswordHash(hash);
        }
        cached = retry(database::loadFleet);
        session = user;
        online = true;
        attempts.remove(normalized);
        lastSynchronized = clock.instant();
        state = State.DATA_INGESTION;
        return user;
    }

    public synchronized void signOut() {
        session = null;
        cached = null;
        cachedAlerts = List.of();
        cachedAudit = List.of();
        evaluations.clear();
        state = State.USER_AUTHENTICATION;
    }

    public synchronized DashboardSnapshot snapshot() throws SQLException, IOException {
        requireSession();
        state = State.ACTIVE_EVALUATION;
        try {
            flushPending();
            try {
                FleetData fresh = retry(database::loadFleet);
                verifySession(fresh);
                cached = fresh;
                online = true;
                lastSynchronized = clock.instant();
            } catch (SQLException exception) {
                if (!DatabaseManager.isAvailabilityFailure(exception) || cached == null) {
                    throw exception;
                }
                online = false;
                syncMessage = "Database unavailable • showing cached data and local drafts";
            }
            List<DurableOutbox.Entry> pending = outbox.entries();
            FleetData effective = overlay(cached, pending);
            Map<String, ComplianceReport> reports = new HashMap<>();
            evaluations.keySet().retainAll(effective.users().keySet());
            for (Pilot user : effective.users().values()) {
                List<DutyPeriod> history = user.getDutyHistory();
                List<RestPeriod> rest = effective.rest().getOrDefault(user.getUsername(), List.of());
                EvaluationCache previous = evaluations.get(user.getUsername());
                if (previous != null && previous.history().equals(history) && previous.rest().equals(rest)) {
                    reports.putAll(previous.reports());
                } else {
                    Map<String, ComplianceReport> changed = new HashMap<>();
                    for (DutyPeriod duty : history) {
                        changed.put(duty.getRecordKey(), engine.evaluate(user, duty, rest));
                    }
                    evaluations.put(user.getUsername(), new EvaluationCache(history, rest, Map.copyOf(changed)));
                    reports.putAll(changed);
                }
            }
            List<Alert> findings = findings(effective, clock.instant(), reports);
            if (online && pending.isEmpty()) {
                try {
                    retry(() -> {
                        database.synchronizeAlerts(findings);
                        return null;
                    });
                    cachedAlerts = retry(database::loadAlerts);
                    cachedAudit = retry(() -> database.loadAudit(session.role().managesFleet() ? null : session.getUsername()));
                } catch (SQLException exception) {
                    if (!DatabaseManager.isAvailabilityFailure(exception)) {
                        throw exception;
                    }
                    online = false;
                    syncMessage = "Database unavailable • changes will be queued locally";
                }
            } else {
                // Cached acknowledgement metadata is retained, but unsynchronized findings remain explicit.
                Map<String, Alert> previous = new HashMap<>();
                cachedAlerts.forEach(alert -> previous.put(alert.key(), alert));
                cachedAlerts = findings.stream().map(alert -> {
                    Alert saved = previous.get(alert.key());
                    return saved != null && saved.message().equals(alert.message()) ? saved : alert;
                }).toList();
            }
            List<Alert> visibleAlerts = cachedAlerts.stream().filter(alert -> canSee(alert.pilotUsername())).toList();
            List<CrewAssessment> crew = effective.users().values().stream().filter(user -> user.role() == Role.PILOT)
                .filter(user -> canSee(user.getUsername())).sorted(Comparator.comparing(Pilot::getDisplayName))
                .map(user -> assess(user, visibleAlerts, pending)).toList();
            List<DashboardSnapshot.QueuedChange> visiblePending = pending.stream()
                .filter(entry -> session.role().managesFleet() || entry.write().actor().equals(session.getUsername()))
                .map(entry -> new DashboardSnapshot.QueuedChange(entry, conflicts.getOrDefault(entry.write().id(), ""))).toList();
            Map<String, Pilot> users = new HashMap<>();
            Map<String, List<RestPeriod>> rest = new HashMap<>();
            effective.users().forEach((key, user) -> {
                if (session.role() == Role.ADMINISTRATOR || (user.role() == Role.PILOT && canSee(key))
                    || key.equals(session.getUsername())) {
                    users.put(key, user);
                    rest.put(key, effective.rest().getOrDefault(key, List.of()));
                }
            });
            if (online) {
                syncMessage = pending.isEmpty() ? "All changes saved" : pending.size() + " local change(s) awaiting synchronization";
            }
            state = online ? State.VISUAL_RENDERING : State.OFFLINE;
            Map<String, ComplianceReport> visibleReports = new HashMap<>();
            for (Pilot user : users.values()) {
                for (DutyPeriod duty : user.getDutyHistory()) {
                    visibleReports.put(duty.getRecordKey(), reports.get(duty.getRecordKey()));
                }
            }
            return new DashboardSnapshot(clock.instant(), lastSynchronized, new FleetData(users, rest), crew,
                visibleAlerts, cachedAudit, visiblePending, visibleReports, online, syncMessage);
        } catch (SecurityException exception) {
            signOut();
            throw exception;
        }
    }

    public synchronized ComplianceReport preview(String username, DutyPeriod duty) throws SQLException, IOException {
        requireSession();
        state = State.ACTIVE_EVALUATION;
        FleetData data = loadForMutation();
        Pilot pilot = requirePilot(data, username);
        ComplianceReport report = engine.evaluate(pilot, duty, data.rest().getOrDefault(username, List.of()));
        if (!online || !outbox.entries().isEmpty()) {
            List<ComplianceReport.Check> checks = new ArrayList<>(report.checks());
            checks.add(new ComplianceReport.Check("unsynchronized", "Synchronized records", ComplianceReport.Status.REVIEW,
                "The assessment includes cached data or local drafts. Synchronize before relying on availability."));
            report = new ComplianceReport(ComplianceReport.highest(checks), checks, report.flightLimitMinutes(),
                report.dutyLimitMinutes(), -1, -1, 0);
        }
        state = State.DATA_INGESTION;
        return report;
    }

    public synchronized WriteResult saveDuty(String username, DutyPeriod duty, boolean update) throws SQLException, IOException {
        requireSession();
        state = State.DATA_INGESTION;
        FleetData data = loadForMutation();
        Pilot pilot = requirePilot(data, username);
        if (duty.getReleaseInstant().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Actual duty records must finish in the past. Use Evaluate assignment for future scenarios.");
        }
        DatabaseManager.validateIntervals(data, username, duty, null);
        validateRevision(pilot.getDutyHistory().stream().filter(item -> item.getRecordKey().equals(duty.getRecordKey()))
            .map(DutyPeriod::getRevision).findFirst().orElse(null), duty.getRevision(), update);
        ComplianceReport report = engine.evaluate(pilot, duty, data.rest().getOrDefault(username, List.of()));
        return submit(PendingWrite.duty(session.getUsername(), username, duty, update), report);
    }

    public synchronized WriteResult saveRest(String username, RestPeriod rest, boolean update) throws SQLException, IOException {
        requireSession();
        state = State.DATA_INGESTION;
        FleetData data = loadForMutation();
        requirePilot(data, username);
        if (rest.endInstant().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Only completed rest periods can be logged.");
        }
        DatabaseManager.validateIntervals(data, username, null, rest);
        validateRevision(data.rest().getOrDefault(username, List.of()).stream().filter(item -> item.recordKey().equals(rest.recordKey()))
            .map(RestPeriod::revision).findFirst().orElse(null), rest.revision(), update);
        return submit(PendingWrite.rest(session.getUsername(), username, rest, update), null);
    }

    public synchronized WriteResult deleteRecord(String username, String key, int revision, boolean duty) throws SQLException, IOException {
        FleetData data = loadForMutation();
        Pilot user = requirePilot(data, username);
        Integer current = duty ? user.getDutyHistory().stream().filter(item -> item.getRecordKey().equals(key))
            .map(DutyPeriod::getRevision).findFirst().orElse(null)
            : data.rest().getOrDefault(username, List.of()).stream().filter(item -> item.recordKey().equals(key))
            .map(RestPeriod::revision).findFirst().orElse(null);
        validateRevision(current, revision, true);
        return submit(PendingWrite.delete(session.getUsername(), username, key, revision, duty), null);
    }

    private void validateRevision(Integer current, int expected, boolean update) throws RecordConflictException {
        if (update && (current == null || current != expected)) {
            throw new RecordConflictException("This record changed. Refresh before editing.");
        }
        if (!update && current != null) {
            throw new RecordConflictException("This record already exists.");
        }
    }

    private WriteResult submit(PendingWrite write, ComplianceReport report) throws SQLException, IOException {
        outbox.enqueue(write); // Durability is established before attempting the backend.
        flushPending();
        boolean queued = outbox.entries().stream().anyMatch(entry -> entry.write().id().equals(write.id()));
        return new WriteResult(queued, report, queued ? "Saved locally. " + conflicts.getOrDefault(write.id(), "Will synchronize automatically.")
            : "Record saved. Compliance findings are available in Alerts.");
    }

    private void flushPending() throws SQLException, IOException {
        Set<String> blockedPilots = new HashSet<>();
        for (DurableOutbox.Entry entry : outbox.entries()) {
            PendingWrite write = entry.write();
            if (blockedPilots.contains(write.pilotUsername())) {
                continue;
            }
            try {
                retry(() -> {
                    database.applyWrite(write);
                    return null;
                });
                outbox.remove(entry);
                conflicts.remove(write.id());
            } catch (SQLException exception) {
                if (DatabaseManager.isAvailabilityFailure(exception)) {
                    online = false;
                    syncMessage = "Database unavailable • pending writes are safely stored locally";
                    return;
                }
                conflicts.put(write.id(), exception.getMessage());
                blockedPilots.add(write.pilotUsername());
            }
        }
    }

    private FleetData loadForMutation() throws SQLException, IOException {
        requireSession();
        try {
            FleetData data = retry(database::loadFleet);
            verifySession(data);
            cached = data;
            online = true;
        } catch (SQLException exception) {
            if (!DatabaseManager.isAvailabilityFailure(exception) || cached == null) {
                throw exception;
            }
            online = false;
        }
        return overlay(cached, outbox.entries());
    }

    private FleetData overlay(FleetData original, List<DurableOutbox.Entry> entries) {
        Map<String, Pilot> users = new HashMap<>();
        Map<String, List<RestPeriod>> rests = new HashMap<>();
        for (Pilot user : original.users().values()) {
            List<DutyPeriod> duties = new ArrayList<>(user.getDutyHistory());
            List<RestPeriod> rest = new ArrayList<>(original.rest().getOrDefault(user.getUsername(), List.of()));
            for (DurableOutbox.Entry entry : entries) {
                PendingWrite write = entry.write();
                if (!write.pilotUsername().equals(user.getUsername()) || conflicts.containsKey(write.id())) {
                    continue;
                }
                switch (write.operation()) {
                    case ADD_DUTY -> {
                        if (duties.stream().noneMatch(item -> item.getRecordKey().equals(write.targetKey()))) {
                            duties.add(write.duty());
                        }
                    }
                    case EDIT_DUTY -> {
                        duties.removeIf(item -> item.getRecordKey().equals(write.targetKey()));
                        duties.add(write.duty().withIdentity(write.targetKey(), write.expectedRevision() + 1));
                    }
                    case DELETE_DUTY -> duties.removeIf(item -> item.getRecordKey().equals(write.targetKey()));
                    case ADD_REST -> {
                        if (rest.stream().noneMatch(item -> item.recordKey().equals(write.targetKey()))) {
                            rest.add(write.rest());
                        }
                    }
                    case EDIT_REST -> {
                        rest.removeIf(item -> item.recordKey().equals(write.targetKey()));
                        rest.add(write.rest().withIdentity(write.targetKey(), write.expectedRevision() + 1));
                    }
                    case DELETE_REST -> rest.removeIf(item -> item.recordKey().equals(write.targetKey()));
                }
            }
            Pilot copy = new Pilot(user.getUsername(), user.getPasswordHash(), user.getRole(), user.getCumulativeFlightHours(),
                user.getCertificationStatus(), user.getBaseAssignment(), user.getDisplayName(), user.getZoneId());
            duties.stream().sorted(Comparator.comparing(DutyPeriod::getStartInstant)).forEach(copy::logDutyPeriod);
            users.put(copy.getUsername(), copy);
            rests.put(copy.getUsername(), List.copyOf(rest));
        }
        return new FleetData(users, rests);
    }

    private List<ComplianceReport.Check> readiness(Pilot user, Instant at) {
        List<DutyPeriod> history = user.getDutyHistory();
        List<ComplianceReport.Check> checks = new ArrayList<>();
        long rest30 = engine.longestRestMinutes(history, at);
        long rest10 = engine.immediateRestMinutes(history, at);
        checks.add(new ComplianceReport.Check("rest30", "Rolling rest",
            rest30 >= 1800 ? ComplianceReport.Status.CLEAR : ComplianceReport.Status.VIOLATION,
            "Longest rest: " + Times.hours(rest30) + " / 30:00 in the past 168 hours."));
        checks.add(new ComplianceReport.Check("rest10", "Rest before a new assignment",
            rest10 >= 600 ? ComplianceReport.Status.CLEAR : ComplianceReport.Status.VIOLATION,
            "Time since release: " + Times.hours(rest10) + " / 10:00."));
        addUtilization(checks, "duty7", "FDP / 168 hours", engine.rollingDutyMinutes(history, at, 168), FatigueRuleEngine.WEEKLY_DUTY_LIMIT);
        addUtilization(checks, "duty28", "FDP / 672 hours", engine.rollingDutyMinutes(history, at, 672), FatigueRuleEngine.MONTHLY_DUTY_LIMIT);
        addUtilization(checks, "flight28", "Flight / 672 hours", engine.rollingFlightMinutes(history, at, 672), FatigueRuleEngine.MONTHLY_FLIGHT_LIMIT);
        addUtilization(checks, "flight365", "Flight / 365 calendar days", engine.calendarFlightMinutes(history, at, user.getZoneId()),
            FatigueRuleEngine.ANNUAL_FLIGHT_LIMIT);
        if (history.stream().anyMatch(duty -> !duty.hasFlightData()
            && duty.getEndInstant().isAfter(at.minus(Duration.ofDays(366))) && duty.getStartInstant().isBefore(at))) {
            checks.add(new ComplianceReport.Check("missingFlight", "Flight history", ComplianceReport.Status.REVIEW,
                "Legacy flight-leg times are missing; availability is unknown."));
        }
        return checks;
    }

    private void addUtilization(List<ComplianceReport.Check> checks, String code, String title, long used, long limit) {
        ComplianceReport.Status status = used > limit ? ComplianceReport.Status.VIOLATION
            : used >= limit * 0.9 ? ComplianceReport.Status.WARNING : ComplianceReport.Status.CLEAR;
        checks.add(new ComplianceReport.Check(code, title, status, Times.hours(used) + " / " + Times.hours(limit) + "."));
    }

    private List<Alert> findings(FleetData data, Instant now, Map<String, ComplianceReport> reports) {
        List<Alert> alerts = new ArrayList<>();
        for (Pilot user : data.users().values()) {
            if (user.role() != Role.PILOT) {
                continue;
            }
            for (ComplianceReport.Check check : readiness(user, now)) {
                addFinding(alerts, user, "readiness", check, now);
            }
            for (DutyPeriod duty : user.getDutyHistory()) {
                if (duty.getEndInstant().isBefore(now.minus(Duration.ofHours(672)))) {
                    continue;
                }
                ComplianceReport report = reports.get(duty.getRecordKey());
                for (ComplianceReport.Check check : report.checks()) {
                    addFinding(alerts, user, duty.getRecordKey(), check, now);
                }
            }
        }
        return alerts;
    }

    private void addFinding(List<Alert> alerts, Pilot user, String source, ComplianceReport.Check check, Instant now) {
        if (check.status() != ComplianceReport.Status.CLEAR) {
            alerts.add(new Alert(user.getUsername() + "|" + source + "|" + check.code(), user.getUsername(), source,
                check.title(), check.status(), check.explanation(), now, null, null, false));
        }
    }

    private CrewAssessment assess(Pilot user, List<Alert> alerts, List<DurableOutbox.Entry> pending) {
        Instant at = clock.instant();
        List<ComplianceReport.Check> checks = readiness(user, at);
        List<Alert> ownAlerts = alerts.stream().filter(alert -> alert.pilotUsername().equals(user.getUsername())).toList();
        ComplianceReport.Status status = ComplianceReport.highest(checks);
        for (Alert alert : ownAlerts) {
            if (alert.severity().priority() > status.priority()) {
                status = alert.severity();
            }
        }
        boolean queued = pending.stream().anyMatch(entry -> entry.write().pilotUsername().equals(user.getUsername()));
        if ((!online || queued) && status.priority() < ComplianceReport.Status.REVIEW.priority()) {
            status = ComplianceReport.Status.REVIEW;
        }
        if (!online || queued) {
            checks.add(new ComplianceReport.Check("sync", "Data freshness", ComplianceReport.Status.REVIEW,
                online ? "Local changes await synchronization." : "Database is unavailable; the assessment uses cached data."));
        }
        List<DutyPeriod> history = user.getDutyHistory();
        return new CrewAssessment(user, status, checks, engine.rollingDutyMinutes(history, at, 168),
            engine.rollingDutyMinutes(history, at, 672), engine.rollingFlightMinutes(history, at, 672),
            engine.calendarFlightMinutes(history, at, user.getZoneId()), engine.longestRestMinutes(history, at),
            engine.immediateRestMinutes(history, at), checks.stream().noneMatch(check -> check.code().equals("missingFlight")),
            (int) ownAlerts.stream().filter(alert -> !alert.acknowledged()).count());
    }

    public synchronized void saveAccount(Pilot user, boolean update) throws SQLException, IOException {
        requireAdmin();
        if (!outbox.entries().isEmpty()) {
            throw new IllegalArgumentException("Synchronize pending operational changes before editing accounts.");
        }
        retry(() -> {
            database.saveAccount(session.getUsername(), user, update);
            return null;
        });
        if (session.getUsername().equals(user.getUsername())) {
            session = user;
        }
    }

    public synchronized void deleteAccount(String username) throws SQLException, IOException {
        requireAdmin();
        if (outbox.entries().stream().anyMatch(entry -> entry.write().pilotUsername().equals(username)
            || entry.write().actor().equals(username))) {
            throw new IllegalArgumentException("Resolve this account's queued changes before deleting it.");
        }
        retry(() -> {
            database.deleteAccount(session.getUsername(), username);
            return null;
        });
    }

    public synchronized void changePassword(char[] oldPassword, char[] newPassword) throws SQLException {
        requireSession();
        User.validateNewPassword(newPassword);
        Pilot fresh = retry(() -> database.findUser(session.getUsername()));
        if (fresh == null || !fresh.authenticate(oldPassword)) {
            throw new IllegalArgumentException("The current password is incorrect.");
        }
        String hash = User.hashPassword(newPassword);
        retry(() -> {
            database.changePassword(session.getUsername(), session.getUsername(), hash);
            return null;
        });
        session = session.withPasswordHash(hash);
    }

    public synchronized void acknowledge(String key, String note) throws SQLException {
        requireSession();
        if (!session.role().managesFleet()) {
            throw new SecurityException("Only dispatchers and administrators acknowledge alerts.");
        }
        String validated = Pilot.requireValue(note, "Acknowledgement note", 500);
        retry(() -> {
            database.acknowledgeAlert(session.getUsername(), key, validated);
            return null;
        });
    }

    public synchronized void discardQueuedChange(DurableOutbox.Entry entry) throws IOException {
        requireSession();
        if (!session.role().managesFleet() && !entry.write().actor().equals(session.getUsername())) {
            throw new SecurityException("You cannot discard another user's queued change.");
        }
        outbox.remove(entry);
        conflicts.remove(entry.write().id());
    }

    private Pilot requirePilot(FleetData data, String username) {
        if (!canSee(username)) {
            throw new SecurityException("Pilot accounts can access only their own records.");
        }
        Pilot user = data.users().get(username);
        if (user == null || user.role() != Role.PILOT) {
            throw new IllegalArgumentException("Select an available pilot account.");
        }
        return user;
    }

    private boolean canSee(String username) {
        return session.role().managesFleet() || session.getUsername().equals(username);
    }

    private void requireSession() {
        if (session == null) {
            throw new SecurityException("Sign in to continue.");
        }
    }

    private void requireAdmin() {
        requireSession();
        if (session.role() != Role.ADMINISTRATOR) {
            throw new SecurityException("Only administrators manage accounts.");
        }
    }

    private void verifySession(FleetData data) {
        Pilot fresh = data.users().get(session.getUsername());
        if (fresh == null || fresh.role() != session.role() || !fresh.getPasswordHash().equals(session.getPasswordHash())) {
            throw new SecurityException("Your account changed. Sign in again.");
        }
    }

    private <T> T retry(SqlWork<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return work.run();
            } catch (SQLException exception) {
                if (!DatabaseManager.isAvailabilityFailure(exception)) {
                    throw exception;
                }
                last = exception;
                if (attempt < 2) {
                    try {
                        Thread.sleep(100L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Operation interrupted.", interrupted);
                    }
                }
            }
        }
        throw last;
    }
}
