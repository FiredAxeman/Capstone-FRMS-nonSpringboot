import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Management and pilot workspaces backed by the same authenticated application service.
 */
public class ComplianceDashboard extends JFrame {
    private record DutyRow(String username, DutyPeriod duty, ComplianceReport report) {
    }

    private record RestRow(String username, RestPeriod rest) {
    }

    private final FrmsService service;
    private final Pilot signedIn;
    private final Runnable signInAgain;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "frms-data-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = UiTheme.transparent(cards);
    private final JLabel pageTitle = UiTheme.label("Fleet overview", 25, true, UiTheme.TEXT);
    private final JLabel pageSubtitle = UiTheme.label("Duty, flight time, and rest in one operational view.", 13, false, UiTheme.MUTED);
    private final JLabel connection = UiTheme.label("Connecting…", 11, true, UiTheme.MUTED);
    private final JLabel notice = UiTheme.label("Modeled unaugmented rules • complete duty history is assumed • all table times are UTC", 10, false, UiTheme.MUTED);
    private final JLabel updated = UiTheme.label(" ", 10, false, UiTheme.MUTED);
    private final JLabel banner = UiTheme.label(" ", 12, true, UiTheme.BLUE);
    private final JPanel detail = UiTheme.transparent(new BorderLayout());
    private final Map<String, JButton> navigation = new LinkedHashMap<>();
    private final List<JButton> dataButtons = new ArrayList<>();
    private final DefaultTableModel rosterModel = model("PILOT", "BASE", "FDP / 168H", "FLIGHT / 672H", "REST / 30H", "STATUS");
    private final JTable roster = new JTable(rosterModel);
    private final JComboBox<String> statusFilter = new JComboBox<>(new String[]{"All statuses", "Within modeled limits",
        "Approaching limit", "Review required", "Limit exceeded"});
    private final JTextField rosterSearch = UiTheme.searchField(14, "Search pilot or base…");
    private final DefaultTableModel dutyModel = model("PILOT", "REPORT UTC", "FDP END UTC", "RELEASE UTC", "REPORT ZONE", "FDP", "FLIGHT", "LEGS", "STATUS");
    private final JTable duties = new JTable(dutyModel);
    private final JTextField dutySearch = UiTheme.searchField(20, "Search duty records…");
    private final DefaultTableModel restModel = model("PILOT", "START UTC", "END UTC", "ZONE", "REST", "SLEEP OPPORTUNITY", "NOTES");
    private final JTable rests = new JTable(restModel);
    private final JTextField restSearch = UiTheme.searchField(20, "Search rest records…");
    private final DefaultTableModel alertModel = model("SEVERITY", "PILOT", "RULE", "FINDING", "ACKNOWLEDGEMENT");
    private final JTable alerts = new JTable(alertModel);
    private final JTextField alertSearch = UiTheme.searchField(20, "Search findings…");
    private final DefaultTableModel accountModel = model("USERNAME", "NAME", "ROLE", "BASE", "CERTIFICATION", "HOME ZONE", "LIFETIME FLIGHT");
    private final JTable accounts = new JTable(accountModel);
    private final DefaultTableModel auditModel = model("TIMESTAMP UTC", "ACTOR", "ACTION", "TARGET", "DETAIL");
    private final JTable audit = new JTable(auditModel);
    private final DefaultTableModel queueModel = model("CREATED UTC", "ACTOR", "PILOT", "CHANGE", "STATE");
    private final JTable queue = new JTable(queueModel);
    private final JLabel pilotsMetric = UiTheme.label("—", 31, true, UiTheme.TEXT);
    private final JLabel clearMetric = UiTheme.label("—", 31, true, UiTheme.TEXT);
    private final JLabel attentionMetric = UiTheme.label("—", 31, true, UiTheme.TEXT);
    private final JLabel alertsMetric = UiTheme.label("—", 31, true, UiTheme.TEXT);
    private final JButton export = UiTheme.button("Export report", false);
    private final Timer refreshTimer;
    private DashboardSnapshot snapshot;
    private List<DutyRow> dutyRows = List.of();
    private List<RestRow> restRows = List.of();
    private List<Pilot> accountRows = List.of();
    private String page = "Overview";
    private int pendingTasks;
    private boolean refreshFailed;

    public ComplianceDashboard(FrmsService service, Runnable signInAgain) {
        super("FRMS • Flight Operations");
        this.service = service;
        this.signedIn = service.currentUser();
        this.signInAgain = signInAgain;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1120, 740));
        setSize(1390, 870);
        setLocationRelativeTo(null);
        getContentPane().setBackground(UiTheme.BACKGROUND);
        add(buildSidebar(), BorderLayout.WEST);
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setBackground(UiTheme.BACKGROUND);
        workspace.add(buildHeader(), BorderLayout.NORTH);
        JPanel body = UiTheme.transparent(new BorderLayout(0, 12));
        body.setBorder(BorderFactory.createEmptyBorder(20, 26, 18, 26));
        banner.setOpaque(true);
        banner.setBackground(new Color(232, 240, 252));
        banner.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        banner.setVisible(false);
        body.add(banner, BorderLayout.NORTH);
        body.add(pages, BorderLayout.CENTER);
        workspace.add(body, BorderLayout.CENTER);
        workspace.add(buildFooter(), BorderLayout.SOUTH);
        add(workspace, BorderLayout.CENTER);
        buildPages();
        refreshTimer = new Timer(30_000, event -> {
            if (pendingTasks == 0) {
                refreshDashboard(false);
            }
        });
        refreshTimer.start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (pendingTasks == 0) {
                    dispose();
                }
            }

            @Override
            public void windowClosed(WindowEvent event) {
                refreshTimer.stop();
                worker.shutdown();
            }
        });
        refreshDashboard(false);
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 28));
        sidebar.setPreferredSize(new Dimension(208, 0));
        sidebar.setBackground(UiTheme.NAVY);
        sidebar.setBorder(BorderFactory.createEmptyBorder(30, 18, 22, 18));
        JPanel brand = UiTheme.transparent(new GridLayout(0, 1, 0, 8));
        brand.add(UiTheme.label("FRMS", 28, true, Color.WHITE));
        brand.add(UiTheme.label("FLIGHT OPERATIONS", 10, true, new Color(148, 178, 198)));
        brand.add(UiTheme.label("CSC480  /  FINAL CAPSTONE", 9, false, new Color(148, 178, 198)));
        sidebar.add(brand, BorderLayout.NORTH);
        JPanel links = UiTheme.transparent(new GridLayout(0, 1, 0, 8));
        List<String> names = new ArrayList<>(List.of("Overview", "Duty records", "Rest records", "Alerts"));
        if (signedIn.role() == Role.ADMINISTRATOR) {
            names.add("Accounts");
        }
        names.addAll(List.of("Audit trail", "Sync queue"));
        for (String name : names) {
            JButton button = new JButton(name);
            button.setHorizontalAlignment(JButton.LEFT);
            button.setFocusPainted(false);
            button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            button.setBorder(BorderFactory.createEmptyBorder(13, 13, 13, 8));
            button.setForeground(new Color(193, 211, 226));
            button.setBackground(UiTheme.NAVY);
            button.setOpaque(true);
            button.setBorderPainted(false);
            button.addActionListener(event -> selectPage(name));
            navigation.put(name, button);
            links.add(button);
        }
        JPanel linkPosition = UiTheme.transparent(new BorderLayout());
        linkPosition.add(links, BorderLayout.NORTH);
        sidebar.add(linkPosition, BorderLayout.CENTER);
        JPanel account = UiTheme.transparent(new GridLayout(0, 1, 0, 8));
        account.add(UiTheme.label(signedIn.getDisplayName(), 12, true, Color.WHITE));
        account.add(UiTheme.label(signedIn.getRole(), 11, false, new Color(148, 178, 198)));
        JButton password = new JButton("Change password");
        password.setForeground(new Color(193, 211, 226));
        password.setBackground(UiTheme.NAVY);
        password.setFocusPainted(false);
        password.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        password.setHorizontalAlignment(JButton.LEFT);
        password.addActionListener(event -> changePassword());
        dataButtons.add(password);
        JButton signOut = new JButton("Sign out");
        signOut.setForeground(new Color(193, 211, 226));
        signOut.setBackground(UiTheme.NAVY);
        signOut.setFocusPainted(false);
        signOut.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        signOut.setHorizontalAlignment(JButton.LEFT);
        signOut.addActionListener(event -> {
            if (pendingTasks == 0) {
                runTask(() -> {
                    service.signOut();
                    return null;
                }, ignored -> {
                    dispose();
                    signInAgain.run();
                }, this::showFailure);
            }
        });
        account.add(password);
        account.add(signOut);
        sidebar.add(account, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.LINE),
            BorderFactory.createEmptyBorder(22, 26, 20, 26)));
        JPanel title = UiTheme.transparent(new GridLayout(0, 1, 0, 6));
        title.add(pageTitle);
        title.add(pageSubtitle);
        JPanel actions = UiTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 13, 0));
        connection.setBorder(BorderFactory.createEmptyBorder(11, 0, 0, 0));
        actions.add(connection);
        actions.add(export);
        export.addActionListener(event -> exportReport());
        header.add(title, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(UiTheme.BACKGROUND);
        footer.setBorder(BorderFactory.createEmptyBorder(0, 27, 14, 27));
        footer.add(notice, BorderLayout.WEST);
        footer.add(updated, BorderLayout.EAST);
        return footer;
    }

    private void buildPages() {
        pages.add(buildOverview(), "Overview");
        pages.add(recordPage(duties, dutySearch, "Duty records", List.of(
            action("Log actual duty", true, () -> editDuty(null, false)),
            action("Evaluate assignment", false, () -> editDuty(null, true)),
            action("View assessment", false, this::viewDuty),
            action("Edit", false, () -> editDuty(selectedDuty(), false)),
            action("Delete", false, () -> deleteOperational(true)))), "Duty records");
        pages.add(recordPage(rests, restSearch, "Rest records", List.of(
            action("Log rest", true, () -> editRest(null)),
            action("Edit", false, () -> editRest(selectedRest())),
            action("Delete", false, () -> deleteOperational(false)))), "Rest records");
        List<JButton> alertActions = new ArrayList<>();
        if (signedIn.role().managesFleet()) {
            alertActions.add(action("Acknowledge", true, this::acknowledge));
        }
        alertActions.add(action("View finding", false, this::viewAlert));
        pages.add(recordPage(alerts, alertSearch, "Current findings", alertActions), "Alerts");
        if (signedIn.role() == Role.ADMINISTRATOR) {
            pages.add(recordPage(accounts, new JTextField(20), "Crew & operations accounts", List.of(
                action("Add account", true, () -> editAccount(null)), action("Edit", false, () -> editAccount(selectedAccount())),
                action("Delete", false, this::deleteAccount))), "Accounts");
        }
        pages.add(recordPage(audit, new JTextField(20), "Audit trail • latest 250 events", List.of()), "Audit trail");
        pages.add(recordPage(queue, new JTextField(20), "Local change queue", List.of(
            action("Retry sync", true, () -> refreshDashboard(true)), action("Inspect", false, this::inspectQueued),
            action("Discard selected", false, this::discardQueued))), "Sync queue");
        duties.getColumnModel().getColumn(0).setPreferredWidth(150);
        for (int index : List.of(1, 2, 3)) {
            duties.getColumnModel().getColumn(index).setPreferredWidth(145);
        }
        duties.getColumnModel().getColumn(4).setPreferredWidth(155);
        duties.getColumnModel().getColumn(8).setPreferredWidth(165);
        alerts.getColumnModel().getColumn(3).setPreferredWidth(400);
        rests.getColumnModel().getColumn(6).setPreferredWidth(250);
        selectPage("Overview");
    }

    private JPanel buildOverview() {
        JPanel overview = UiTheme.transparent(new BorderLayout(0, 19));
        JPanel metrics = UiTheme.transparent(new GridLayout(1, 4, 14, 0));
        metrics.add(metric("PILOTS MONITORED", pilotsMetric, "Your flight operations roster", UiTheme.TEAL));
        metrics.add(metric("WITHIN MODELED LIMITS", clearMetric, "Recorded data and current rest", UiTheme.GREEN));
        metrics.add(metric("NEEDS ATTENTION", attentionMetric, "Warnings, missing data, or limits", UiTheme.AMBER));
        metrics.add(metric("UNACKNOWLEDGED ALERTS", alertsMetric, "Open findings for review", UiTheme.RED));
        overview.add(metrics, BorderLayout.NORTH);
        UiTheme.Surface rosterCard = new UiTheme.Surface(new BorderLayout(0, 14));
        JPanel toolbar = UiTheme.transparent(new BorderLayout(10, 0));
        JPanel heading = UiTheme.transparent(new GridLayout(0, 1, 0, 5));
        heading.add(UiTheme.label("Pilot roster", 16, true, UiTheme.TEXT));
        heading.add(UiTheme.label("Select a pilot to review limits and availability.", 11, false, UiTheme.MUTED));
        JPanel filters = UiTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rosterSearch.setToolTipText("Search name, username, base, certification, or status");
        rosterSearch.getAccessibleContext().setAccessibleName("Search pilot roster");
        filters.add(rosterSearch);
        filters.add(statusFilter);
        filters.add(action("Refresh", false, () -> refreshDashboard(true)));
        toolbar.add(heading, BorderLayout.WEST);
        toolbar.add(filters, BorderLayout.EAST);
        rosterCard.add(toolbar, BorderLayout.NORTH);
        UiTheme.table(roster);
        roster.getColumnModel().getColumn(0).setPreferredWidth(180);
        roster.getColumnModel().getColumn(5).setPreferredWidth(175);
        JScrollPane scroll = new JScrollPane(roster);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.WHITE);
        rosterCard.add(scroll, BorderLayout.CENTER);
        JLabel legend = UiTheme.label("Green  within modeled limits    •    Amber  approaching    •    Blue  review    •    Red  exceeded", 10, false, UiTheme.MUTED);
        rosterCard.add(legend, BorderLayout.SOUTH);
        listen(rosterSearch, this::filterRoster);
        statusFilter.addActionListener(event -> filterRoster());
        roster.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateDetail();
            }
        });
        detail.setPreferredSize(new Dimension(274, 450));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rosterCard, detail);
        split.setResizeWeight(0.76);
        split.setDividerSize(14);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        overview.add(split, BorderLayout.CENTER);
        return overview;
    }

    private JPanel metric(String title, JLabel value, String caption, Color accent) {
        UiTheme.Surface surface = new UiTheme.Surface(new BorderLayout(0, 10));
        JLabel heading = UiTheme.label(title, 10, true, UiTheme.MUTED);
        surface.add(heading, BorderLayout.NORTH);
        surface.add(value, BorderLayout.CENTER);
        surface.add(UiTheme.label(caption, 10, false, accent), BorderLayout.SOUTH);
        return surface;
    }

    private JPanel recordPage(JTable table, JTextField search, String heading, List<JButton> actions) {
        UiTheme.Surface surface = new UiTheme.Surface(new BorderLayout(0, 16));
        JPanel top = UiTheme.transparent(new BorderLayout(10, 12));
        top.add(UiTheme.label(heading, 16, true, UiTheme.TEXT), BorderLayout.NORTH);
        JPanel tools = UiTheme.transparent(new BorderLayout(10, 0));
        search.setToolTipText("Filter these records");
        search.getAccessibleContext().setAccessibleName("Search " + heading);
        JPanel searchPosition = UiTheme.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        searchPosition.add(search);
        tools.add(searchPosition, BorderLayout.WEST);
        JPanel buttons = UiTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.forEach(buttons::add);
        tools.add(buttons, BorderLayout.EAST);
        top.add(tools, BorderLayout.CENTER);
        UiTheme.table(table);
        listen(search, () -> filter(table, search.getText(), null));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.WHITE);
        surface.add(top, BorderLayout.NORTH);
        surface.add(scroll, BorderLayout.CENTER);
        surface.add(UiTheme.label(table == queue ? "Queued data survives restarts. Conflicts require review; they never overwrite newer records."
            : table == alerts ? "Acknowledging records a review decision; it does not clear a regulatory finding."
            : table == duties ? "Actual records remain loggable when limits are exceeded. Future assignments are evaluated without saving."
            : "Records persist in the local SQL database. Exports include data freshness.", 11, false, UiTheme.MUTED), BorderLayout.SOUTH);
        return surface;
    }

    private JButton action(String text, boolean primary, Runnable handler) {
        JButton button = UiTheme.button(text, primary);
        button.addActionListener(event -> {
            try {
                handler.run();
            } catch (RuntimeException exception) {
                showFailure(exception);
            }
        });
        dataButtons.add(button);
        return button;
    }

    private void selectPage(String selected) {
        page = selected;
        cards.show(pages, selected);
        navigation.forEach((name, button) -> {
            button.setBackground(name.equals(selected) ? new Color(37, 66, 83) : UiTheme.NAVY);
            button.setForeground(name.equals(selected) ? Color.WHITE : new Color(193, 211, 226));
            button.setFont(new Font(Font.SANS_SERIF, name.equals(selected) ? Font.BOLD : Font.PLAIN, 13));
        });
        pageTitle.setText(switch (selected) {
            case "Overview" -> signedIn.role().managesFleet() ? "Fleet overview" : "My flight operations";
            case "Accounts" -> "Crew & accounts";
            default -> selected;
        });
        pageSubtitle.setText(switch (selected) {
            case "Overview" -> "Duty, flight time, and rest in one operational view.";
            case "Duty records" -> "Accurate flight-leg times, duty intervals, and explainable assessments.";
            case "Rest records" -> "Time free from all duty and uninterrupted sleep opportunity.";
            case "Alerts" -> "Review approaching limits, exceeded limits, and incomplete records.";
            case "Accounts" -> "Manage crew profiles, credentials, and access.";
            case "Audit trail" -> "A persistent record of operational changes and review decisions.";
            case "Sync queue" -> "Inspect local drafts and resolve synchronization conflicts.";
            default -> "";
        });
        updateButtons();
    }

    public void aggregateData() {
        refreshDashboard(true);
    }

    public void renderVisualIndicators() {
        roster.repaint();
        duties.repaint();
        alerts.repaint();
    }

    private void refreshDashboard(boolean manual) {
        if (pendingTasks > 0) {
            return;
        }
        runTask(service::snapshot, data -> {
            String selected = selectedRosterUsername();
            snapshot = data;
            refreshFailed = false;
            renderSnapshot(selected);
        }, error -> {
            refreshFailed = true;
            banner.setText("Update failed • " + error.getMessage());
            banner.setVisible(true);
            banner.setForeground(UiTheme.RED);
            connection.setText("Update failed • cached view");
            connection.setForeground(UiTheme.RED);
            export.setEnabled(false);
            if (manual || snapshot == null) {
                showFailure(error);
            }
        });
    }

    private void renderSnapshot(String selected) {
        pilotsMetric.setText(Integer.toString(snapshot.crew().size()));
        long clear = snapshot.crew().stream().filter(row -> row.status() == ComplianceReport.Status.CLEAR).count();
        clearMetric.setText(Long.toString(clear));
        attentionMetric.setText(Long.toString(snapshot.crew().size() - clear));
        alertsMetric.setText(Long.toString(snapshot.alerts().stream().filter(alert -> !alert.acknowledged()).count()));
        rosterModel.setRowCount(0);
        for (CrewAssessment row : snapshot.crew()) {
            rosterModel.addRow(new Object[]{row.pilot().getDisplayName(), row.pilot().getBaseAssignment(),
                Duration.ofMinutes(row.duty7Minutes()), row.completeFlightHistory() ? Duration.ofMinutes(row.flight28Minutes()) : null,
                Duration.ofMinutes(row.longestRestMinutes()), row.status()});
        }
        dutyRows = snapshot.data().users().values().stream().filter(user -> user.role() == Role.PILOT)
            .flatMap(user -> user.getDutyHistory().stream().map(duty -> new DutyRow(user.getUsername(), duty,
                snapshot.dutyReports().get(duty.getRecordKey()))))
            .sorted(Comparator.comparing((DutyRow row) -> row.duty().getStartInstant()).reversed()).toList();
        dutyModel.setRowCount(0);
        dutyRows.forEach(row -> dutyModel.addRow(new Object[]{row.username(), row.duty().getStartInstant(), row.duty().getEndInstant(),
            row.duty().getReleaseInstant(), row.duty().getZoneId(), Duration.ofMinutes(row.duty().calculateDuration()),
            row.duty().hasFlightData() ? Duration.ofMinutes(row.duty().getFlightMinutes()) : null, row.duty().getSegments(), row.report().status()}));
        restRows = snapshot.data().rest().entrySet().stream().flatMap(entry -> entry.getValue().stream()
            .map(rest -> new RestRow(entry.getKey(), rest))).sorted(Comparator.comparing((RestRow row) -> row.rest().startInstant()).reversed()).toList();
        restModel.setRowCount(0);
        restRows.forEach(row -> restModel.addRow(new Object[]{row.username(), row.rest().startInstant(), row.rest().endInstant(),
            row.rest().zoneId(), Duration.ofMinutes(row.rest().durationMinutes()), Duration.ofMinutes(row.rest().sleepMinutes()), row.rest().notes()}));
        alertModel.setRowCount(0);
        snapshot.alerts().forEach(alert -> alertModel.addRow(new Object[]{alert.severity(), alert.pilotUsername(), alert.rule(), alert.message(),
            alert.acknowledged() ? "Reviewed by " + alert.acknowledgedBy() : "Needs review"}));
        accountRows = snapshot.data().users().values().stream().sorted(Comparator.comparing(Pilot::getUsername)).toList();
        accountModel.setRowCount(0);
        accountRows.forEach(user -> accountModel.addRow(new Object[]{user.getUsername(), user.getDisplayName(), user.getRole(),
            user.getBaseAssignment(), user.getCertificationStatus(), user.getZoneId(), Times.hours(user.getLifetimeFlightMinutes())}));
        auditModel.setRowCount(0);
        snapshot.audit().forEach(event -> auditModel.addRow(new Object[]{event.timestamp(), event.actor(), event.action(), event.target(), event.detail()}));
        queueModel.setRowCount(0);
        snapshot.pending().forEach(change -> queueModel.addRow(new Object[]{change.entry().write().createdAt(), change.entry().write().actor(),
            change.entry().write().pilotUsername(), change.entry().write().operation(), change.conflict().isBlank() ? "Pending" : "Conflict • " + change.conflict()}));
        filterRoster();
        for (int row = 0; row < snapshot.crew().size(); row++) {
            if (snapshot.crew().get(row).pilot().getUsername().equals(selected)) {
                int view = roster.convertRowIndexToView(row);
                if (view >= 0) {
                    roster.setRowSelectionInterval(view, view);
                }
            }
        }
        if (roster.getSelectedRow() < 0 && roster.getRowCount() > 0) {
            roster.setRowSelectionInterval(0, 0);
        }
        updateDetail();
        boolean pending = !snapshot.pending().isEmpty();
        connection.setText(snapshot.online() ? pending ? "Local changes pending" : "●  Synchronized" : "●  Offline");
        connection.setForeground(snapshot.online() && !pending ? UiTheme.GREEN : UiTheme.AMBER);
        banner.setText(snapshot.syncMessage());
        banner.setForeground(snapshot.online() ? UiTheme.BLUE : UiTheme.AMBER);
        banner.setBackground(snapshot.online() ? new Color(232, 240, 252) : new Color(255, 245, 224));
        banner.setVisible(!snapshot.online() || pending);
        updated.setText("Updated " + snapshot.evaluatedAt().atOffset(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " UTC");
        updateButtons();
    }

    private void updateDetail() {
        detail.removeAll();
        if (snapshot == null || roster.getSelectedRow() < 0) {
            UiTheme.Surface empty = new UiTheme.Surface(new BorderLayout());
            empty.add(UiTheme.label("Select a pilot to see details.", 12, false, UiTheme.MUTED), BorderLayout.NORTH);
            detail.add(empty);
            detail.revalidate();
            detail.repaint();
            return;
        }
        int index = roster.convertRowIndexToModel(roster.getSelectedRow());
        if (index >= snapshot.crew().size()) {
            return;
        }
        CrewAssessment row = snapshot.crew().get(index);
        UiTheme.Surface card = new UiTheme.Surface(new BorderLayout(0, 14));
        JPanel identity = UiTheme.transparent(new BorderLayout());
        identity.setLayout(new javax.swing.BoxLayout(identity, javax.swing.BoxLayout.Y_AXIS));
        List<JLabel> identityLabels = List.of(
            UiTheme.label("PILOT DETAIL", 10, true, UiTheme.MUTED),
            UiTheme.label(row.pilot().getDisplayName(), 19, true, UiTheme.TEXT),
            UiTheme.label(row.pilot().getUsername(), 11, false, UiTheme.MUTED),
            UiTheme.label(row.pilot().getBaseAssignment() + "  •  " + row.pilot().getCertificationStatus(), 12, false, UiTheme.MUTED),
            UiTheme.label(row.status().toString(), 12, true, UiTheme.statusColor(row.status())));
        for (int labelIndex = 0; labelIndex < identityLabels.size(); labelIndex++) {
            JLabel label = identityLabels.get(labelIndex);
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            identity.add(label);
            if (labelIndex < identityLabels.size() - 1) {
                identity.add(javax.swing.Box.createVerticalStrut(7));
            }
        }
        card.add(identity, BorderLayout.NORTH);
        JPanel values = UiTheme.transparent(new BorderLayout());
        values.setLayout(new javax.swing.BoxLayout(values, javax.swing.BoxLayout.Y_AXIS));
        for (JPanel gauge : List.of(
            utilization("Duty / 168 hours", row.duty7Minutes(), FatigueRuleEngine.WEEKLY_DUTY_LIMIT, true),
            utilization("Flight / 672 hours", row.flight28Minutes(), FatigueRuleEngine.MONTHLY_FLIGHT_LIMIT, row.completeFlightHistory()),
            utilization("Duty / 672 hours", row.duty28Minutes(), FatigueRuleEngine.MONTHLY_DUTY_LIMIT, true),
            utilization("Flight / 365 days", row.flight365Minutes(), FatigueRuleEngine.ANNUAL_FLIGHT_LIMIT, row.completeFlightHistory()))) {
            gauge.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            values.add(gauge);
            values.add(javax.swing.Box.createVerticalStrut(10));
        }
        JPanel rest = UiTheme.transparent(new GridLayout(0, 1, 0, 6));
        rest.add(UiTheme.label("REST SINCE RELEASE", 10, true, UiTheme.MUTED));
        rest.add(UiTheme.label(Times.hours(row.immediateRestMinutes()) + " / 10:00 minimum", 13, true, UiTheme.TEXT));
        rest.add(UiTheme.label("Longest rest: " + Times.hours(row.longestRestMinutes()), 12, false, UiTheme.MUTED));
        values.add(rest);
        rest.setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
        values.add(javax.swing.Box.createVerticalGlue());
        card.add(values, BorderLayout.CENTER);
        JPanel bottom = UiTheme.transparent(new BorderLayout(0, 12));
        JTextArea summary = RecordDialogs.readOnlyText();
        summary.setOpaque(false);
        summary.setBorder(null);
        summary.setRows(3);
        summary.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        List<String> findings = snapshot.alerts().stream().filter(alert -> alert.pilotUsername().equals(row.pilot().getUsername()))
            .map(Alert::rule).distinct().limit(2).toList();
        summary.setText(findings.isEmpty() ? "No current findings in the modeled rules." : "Review: " + String.join("; ", findings) + ".");
        bottom.add(summary, BorderLayout.CENTER);
        JButton records = UiTheme.button("View duty records", false);
        records.addActionListener(event -> {
            dutySearch.setText(row.pilot().getUsername());
            selectPage("Duty records");
        });
        bottom.add(records, BorderLayout.SOUTH);
        card.add(bottom, BorderLayout.SOUTH);
        JScrollPane detailScroll = new JScrollPane(card);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.getVerticalScrollBar().setUnitIncrement(14);
        detail.add(detailScroll);
        detail.revalidate();
        detail.repaint();
    }

    private JPanel utilization(String title, long used, long limit, boolean known) {
        JPanel panel = UiTheme.transparent(new BorderLayout(0, 7));
        JPanel labels = UiTheme.transparent(new BorderLayout(4, 0));
        labels.add(UiTheme.label(title, 11, false, UiTheme.MUTED), BorderLayout.WEST);
        labels.add(UiTheme.label(known ? Times.hours(used) + " / " + Times.hours(limit) : "Unknown", 11, true, UiTheme.TEXT), BorderLayout.EAST);
        panel.add(labels, BorderLayout.NORTH);
        panel.add(new UiTheme.UtilizationBar(known ? used : 0, limit), BorderLayout.SOUTH);
        return panel;
    }

    private String selectedRosterUsername() {
        if (snapshot == null || roster.getSelectedRow() < 0) {
            return "";
        }
        int index = roster.convertRowIndexToModel(roster.getSelectedRow());
        return index < snapshot.crew().size() ? snapshot.crew().get(index).pilot().getUsername() : "";
    }

    private void filterRoster() {
        if (snapshot == null) {
            return;
        }
        String query = rosterSearch.getText().trim().toLowerCase(Locale.ROOT);
        String status = statusFilter.getSelectedItem().toString();
        @SuppressWarnings("unchecked") TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) roster.getRowSorter();
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                int index = entry.getIdentifier();
                if (index >= snapshot.crew().size()) {
                    return false;
                }
                CrewAssessment row = snapshot.crew().get(index);
                Pilot pilot = row.pilot();
                String text = pilot.getDisplayName() + " " + pilot.getUsername() + " " + pilot.getBaseAssignment()
                    + " " + pilot.getCertificationStatus() + " " + row.status();
                return text.toLowerCase(Locale.ROOT).contains(query) && (status.equals("All statuses") || row.status().toString().equals(status));
            }
        });
    }

    private static void filter(JTable table, String text, String ignored) {
        @SuppressWarnings("unchecked") TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) table.getRowSorter();
        String query = text.trim().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                for (int column = 0; column < entry.getValueCount(); column++) {
                    if (entry.getStringValue(column).toLowerCase(Locale.ROOT).contains(query)) {
                        return true;
                    }
                }
                return query.isEmpty();
            }
        });
    }

    private List<Pilot> selectablePilots() {
        return snapshot == null ? List.of() : snapshot.data().users().values().stream().filter(user -> user.role() == Role.PILOT)
            .sorted(Comparator.comparing(Pilot::getDisplayName)).toList();
    }

    private void editDuty(DutyRow existing, boolean scenario) {
        if (selectablePilots().isEmpty()) {
            toast("Add a pilot account before logging records.");
            return;
        }
        String selected = existing == null ? selectedRosterUsername() : existing.username();
        RecordDialogs.DutyEditor editor = new RecordDialogs.DutyEditor(this, selectablePilots(), selected,
            existing == null ? null : existing.duty(), scenario, new RecordDialogs.DutyActions() {
            @Override
            public void evaluate(RecordDialogs.DutyEditor dialog, String username, DutyPeriod duty) {
                dialog.setBusy(true);
                runTask(() -> service.preview(username, duty), dialog::showReport,
                    error -> dialog.showError(error.getMessage()));
            }

            @Override
            public void save(RecordDialogs.DutyEditor dialog, String username, DutyPeriod duty, boolean update) {
                dialog.setBusy(true);
                runTask(() -> service.preview(username, duty), assessment -> {
                    dialog.showReport(assessment);
                    if (!assessment.passesModeledRules() && JOptionPane.showConfirmDialog(dialog,
                        "This actual record has findings. Save it to preserve the operational history and create alerts?",
                        "Save flagged actual record", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                        return;
                    }
                    dialog.setBusy(true);
                    runTask(() -> service.saveDuty(username, duty, update), result -> {
                        dialog.dispose();
                        toast(result.message());
                        refreshDashboard(false);
                    }, error -> dialog.showError(error.getMessage()));
                }, error -> dialog.showError(error.getMessage()));
            }
        });
        editor.setVisible(true);
    }

    private void editRest(RestRow existing) {
        if (selectablePilots().isEmpty()) {
            toast("Add a pilot account before logging records.");
            return;
        }
        String selected = existing == null ? selectedRosterUsername() : existing.username();
        RecordDialogs.RestEditor editor = new RecordDialogs.RestEditor(this, selectablePilots(), selected, existing == null ? null : existing.rest(),
            (dialog, rest) -> {
                dialog.setBusy(true);
                runTask(() -> service.saveRest(dialog.username(), rest, existing != null), result -> {
                    dialog.dispose();
                    toast(result.message());
                    refreshDashboard(false);
                }, error -> dialog.showError(error.getMessage()));
            });
        editor.setVisible(true);
    }

    private void editAccount(Pilot existing) {
        RecordDialogs.AccountEditor editor = new RecordDialogs.AccountEditor(this, existing, (dialog, user) -> {
            dialog.setBusy(true);
            runTask(() -> {
                service.saveAccount(user, existing != null);
                return null;
            }, ignored -> {
                dialog.clearPassword();
                dialog.dispose();
                toast("Account saved.");
                refreshDashboard(false);
            }, error -> dialog.showError(error.getMessage()));
        });
        editor.setVisible(true);
    }

    private DutyRow selectedDuty() {
        if (duties.getSelectedRow() < 0) {
            throw new IllegalArgumentException("Select a duty record first.");
        }
        return dutyRows.get(duties.convertRowIndexToModel(duties.getSelectedRow()));
    }

    private RestRow selectedRest() {
        if (rests.getSelectedRow() < 0) {
            throw new IllegalArgumentException("Select a rest record first.");
        }
        return restRows.get(rests.convertRowIndexToModel(rests.getSelectedRow()));
    }

    private Pilot selectedAccount() {
        if (accounts.getSelectedRow() < 0) {
            throw new IllegalArgumentException("Select an account first.");
        }
        return accountRows.get(accounts.convertRowIndexToModel(accounts.getSelectedRow()));
    }

    private void viewDuty() {
        DutyRow row = selectedDuty();
        showText("Duty assessment • " + row.username(), RecordDialogs.formatReport(row.report())
            + "\n\nNotes: " + row.duty().getNotes() + "\nRecord ID: " + row.duty().getRecordKey());
    }

    private void deleteOperational(boolean duty) {
        String username;
        String key;
        int revision;
        if (duty) {
            DutyRow row = selectedDuty();
            username = row.username();
            key = row.duty().getRecordKey();
            revision = row.duty().getRevision();
        } else {
            RestRow row = selectedRest();
            username = row.username();
            key = row.rest().recordKey();
            revision = row.rest().revision();
        }
        if (JOptionPane.showConfirmDialog(this, "Delete the selected record? Related assessments will be recalculated.",
            "Delete operational record", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        runTask(() -> service.deleteRecord(username, key, revision, duty), result -> {
            toast(result.message());
            refreshDashboard(false);
        }, this::showFailure);
    }

    private void deleteAccount() {
        Pilot user = selectedAccount();
        if (JOptionPane.showConfirmDialog(this, "Delete " + user.getUsername() + " and all of this account's duty, flight, rest, and alert records?",
            "Delete account", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        runTask(() -> {
            service.deleteAccount(user.getUsername());
            return null;
        }, ignored -> {
            toast("Account deleted.");
            refreshDashboard(false);
        }, this::showFailure);
    }

    private Alert selectedAlert() {
        if (alerts.getSelectedRow() < 0) {
            throw new IllegalArgumentException("Select an alert first.");
        }
        return snapshot.alerts().get(alerts.convertRowIndexToModel(alerts.getSelectedRow()));
    }

    private void acknowledge() {
        Alert alert = selectedAlert();
        JTextArea note = RecordDialogs.notes();
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(note), "Review note • " + alert.pilotUsername(),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String entered = note.getText();
        runTask(() -> {
            service.acknowledge(alert.key(), entered);
            return null;
        }, ignored -> {
            toast("Review recorded. The finding remains visible.");
            refreshDashboard(false);
        }, this::showFailure);
    }

    private void viewAlert() {
        Alert alert = selectedAlert();
        showText(alert.rule(), alert.severity() + "\n\n" + alert.message() + "\n\nPilot: " + alert.pilotUsername()
            + (alert.acknowledged() ? "\nReviewed by: " + alert.acknowledgedBy() + "\nNote: " + alert.acknowledgement() : "\nAwaiting review"));
    }

    private DashboardSnapshot.QueuedChange selectedQueued() {
        if (queue.getSelectedRow() < 0) {
            throw new IllegalArgumentException("Select a queued change first.");
        }
        return snapshot.pending().get(queue.convertRowIndexToModel(queue.getSelectedRow()));
    }

    private void inspectQueued() {
        DashboardSnapshot.QueuedChange change = selectedQueued();
        PendingWrite write = change.entry().write();
        String detail = write.toProperties().entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
            .map(entry -> entry.getKey() + ": " + entry.getValue()).collect(java.util.stream.Collectors.joining("\n"));
        showText("Queued change", (change.conflict().isBlank() ? "Awaiting synchronization" : change.conflict()) + "\n\n" + detail);
    }

    private void discardQueued() {
        DashboardSnapshot.QueuedChange change = selectedQueued();
        if (JOptionPane.showConfirmDialog(this, "Discard this local change? It has not been saved to the database. Later dependent changes may need review.",
            "Discard queued change", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        runTask(() -> {
            service.discardQueuedChange(change.entry());
            return null;
        }, ignored -> {
            toast("Local change discarded.");
            refreshDashboard(false);
        }, this::showFailure);
    }

    private void changePassword() {
        JPasswordField current = new JPasswordField();
        JPasswordField next = new JPasswordField();
        JPasswordField confirm = new JPasswordField();
        if (JOptionPane.showConfirmDialog(this, RecordDialogs.form("Current password", current, "New password", next, "Confirm password", confirm),
            "Change password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        char[] previous = current.getPassword();
        char[] replacement = next.getPassword();
        char[] confirmation = confirm.getPassword();
        boolean matches = Arrays.equals(replacement, confirmation);
        Arrays.fill(confirmation, '\0');
        current.setText("");
        next.setText("");
        confirm.setText("");
        if (!matches) {
            Arrays.fill(previous, '\0');
            Arrays.fill(replacement, '\0');
            toast("New passwords do not match.");
            return;
        }
        runTask(() -> {
            try {
                service.changePassword(previous, replacement);
                return null;
            } finally {
                Arrays.fill(previous, '\0');
                Arrays.fill(replacement, '\0');
            }
        }, ignored -> {
            toast("Password changed.");
            refreshDashboard(false);
        }, this::showFailure);
    }

    private void exportReport() {
        if (snapshot == null || refreshFailed) {
            return;
        }
        ReportExporter.Type type = switch (page) {
            case "Duty records" -> ReportExporter.Type.DUTY;
            case "Rest records" -> ReportExporter.Type.REST;
            case "Alerts" -> ReportExporter.Type.ALERTS;
            case "Audit trail" -> ReportExporter.Type.AUDIT;
            default -> ReportExporter.Type.FLEET;
        };
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV report", "csv"));
        chooser.setSelectedFile(new java.io.File("frms-" + type.name().toLowerCase(Locale.ROOT) + "-" + LocalDate.now() + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path destination = chooser.getSelectedFile().toPath();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            destination = destination.resolveSibling(destination.getFileName() + ".csv");
        }
        if (java.nio.file.Files.exists(destination) && JOptionPane.showConfirmDialog(this, "Replace the existing report?",
            "Export report", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        java.nio.file.Path selected = destination;
        DashboardSnapshot exported = snapshot;
        runTask(() -> {
                ReportExporter.export(exported, type, selected);
                return null;
            },
            ignored -> toast("Report exported to " + selected.toAbsolutePath()), this::showFailure);
    }

    private <T> void runTask(Callable<T> operation, Consumer<T> success, Consumer<Throwable> failure) {
        pendingTasks++;
        updateButtons();
        connection.setText("Updating…");
        connection.setForeground(UiTheme.MUTED);
        worker.submit(() -> {
            try {
                T result = operation.call();
                SwingUtilities.invokeLater(() -> {
                    pendingTasks--;
                    updateButtons();
                    if (isDisplayable()) {
                        success.accept(result);
                    }
                });
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> {
                    pendingTasks--;
                    updateButtons();
                    if (isDisplayable()) {
                        failure.accept(exception);
                    }
                });
            }
        });
    }

    private void updateButtons() {
        boolean available = pendingTasks == 0 && snapshot != null;
        dataButtons.forEach(button -> button.setEnabled(available
            || (pendingTasks == 0 && (button.getText().equals("Refresh") || button.getText().equals("Retry sync")))));
        export.setEnabled(available && !refreshFailed && !page.equals("Accounts") && !page.equals("Sync queue"));
        if (available && snapshot != null) {
            connection.setText(snapshot.online() ? snapshot.pending().isEmpty() ? "●  Synchronized" : "Local changes pending" : "●  Offline");
            connection.setForeground(snapshot.online() && snapshot.pending().isEmpty() ? UiTheme.GREEN : UiTheme.AMBER);
        }
    }

    private void showFailure(Throwable error) {
        if (error instanceof SecurityException) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "Sign in again", JOptionPane.WARNING_MESSAGE);
            dispose();
            signInAgain.run();
            return;
        }
        JOptionPane.showMessageDialog(this, error.getMessage(), "FRMS • Action could not complete", JOptionPane.ERROR_MESSAGE);
    }

    private void showText(String title, String text) {
        JTextArea area = RecordDialogs.readOnlyText();
        area.setText(text);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(660, 470));
        JOptionPane.showMessageDialog(this, scroll, title, JOptionPane.PLAIN_MESSAGE);
    }

    private void toast(String text) {
        notice.setText(text);
        notice.setForeground(UiTheme.TEAL);
        notice.setToolTipText(text);
        Timer timer = new Timer(6500, event -> {
            notice.setText("Modeled unaugmented rules • complete duty history is assumed • all table times are UTC");
            notice.setForeground(UiTheme.MUTED);
        });
        timer.setRepeats(false);
        timer.start();
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                for (int row = 0; row < getRowCount(); row++) {
                    Object value = getValueAt(row, column);
                    if (value != null) {
                        return value.getClass();
                    }
                }
                return Object.class;
            }
        };
    }

    private static void listen(JTextField input, Runnable callback) {
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                callback.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                callback.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                callback.run();
            }
        });
    }

    public static void showStartupError(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(message);
        } else {
            JOptionPane.showMessageDialog(null, message, "FRMS • Startup error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
