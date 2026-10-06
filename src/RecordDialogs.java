import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Focused editors keep validation errors beside the user's input.
 */
public final class RecordDialogs {
    private RecordDialogs() {
    }

    public interface DutyActions {
        void evaluate(DutyEditor editor, String username, DutyPeriod duty);

        void save(DutyEditor editor, String username, DutyPeriod duty, boolean update);
    }

    public static final class DutyEditor extends JDialog {
        private final JComboBox<Pilot> pilot;
        private final JTextField start = new JTextField();
        private final JTextField end = new JTextField();
        private final JTextField release = new JTextField();
        private final JComboBox<String> zone = zones();
        private final DefaultTableModel legs = new DefaultTableModel(new Object[]{"Block out", "Block in"}, 0);
        private final JTable legTable = new JTable(legs);
        private final JCheckBox complete = new JCheckBox("All operated flight legs are recorded", true);
        private final JCheckBox acclimated = new JCheckBox("Acclimated in the reporting theater", true);
        private final JCheckBox sleep = new JCheckBox("At least 8 uninterrupted hours of sleep opportunity");
        private final JCheckBox fit = new JCheckBox("Pilot affirmed fitness for duty");
        private final JCheckBox special = new JCheckBox("Additional conditions: augmented / reserve / split duty / deadhead / travel / nighttime / emergency");
        private final JSpinner extension = new JSpinner(new SpinnerNumberModel(0, 0, 120, 15));
        private final JCheckBox unforeseen = new JCheckBox("Unforeseen operational circumstances before takeoff");
        private final JCheckBox pic = new JCheckBox("Pilot in command concurrence recorded");
        private final JCheckBox carrier = new JCheckBox("Certificate holder concurrence recorded");
        private final JTextArea notes = notes();
        private final JTextArea report = readOnlyText();
        private final JLabel result = UiTheme.label("Evaluate this record to see each rule check.", 14, true, UiTheme.MUTED);
        private final JLabel error = UiTheme.label(" ", 12, false, UiTheme.RED);
        private final JButton evaluate = UiTheme.button("Evaluate", false);
        private final JButton save = UiTheme.button("Save actual record", true);
        private final JButton cancel = UiTheme.button("Cancel", false);
        private final JTabbedPane tabs = new JTabbedPane();
        private final DutyPeriod original;

        public DutyEditor(JFrame owner, List<Pilot> pilots, String selected, DutyPeriod original,
                          boolean scenario, DutyActions actions) {
            super(owner, scenario ? "Evaluate assignment" : original == null ? "Log actual duty" : "Edit duty record", true);
            this.original = original;
            pilot = new JComboBox<>(pilots.toArray(Pilot[]::new));
            pilots.stream().filter(user -> user.getUsername().equals(selected)).findFirst().ifPresent(pilot::setSelectedItem);
            pilot.setEnabled(original == null);
            Pilot selectedPilot = (Pilot) pilot.getSelectedItem();
            String initialZone = original == null ? selectedPilot.getZoneId() : original.getZoneId();
            zone.setSelectedItem(initialZone);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            Instant first = scenario ? now.plusSeconds(36 * 3600) : now.minusSeconds(8 * 3600);
            start.setText(Times.INPUT.format(original == null ? Times.local(first, initialZone) : original.getStartTime()));
            end.setText(Times.INPUT.format(original == null ? Times.local(first.plusSeconds(8 * 3600), initialZone) : original.getEndTime()));
            release.setText(Times.INPUT.format(original == null ? Times.local(first.plusSeconds(8 * 3600), initialZone) : original.getReleaseTime()));
            if (original != null) {
                original.getFlights().forEach(flight -> legs.addRow(new Object[]{
                    Times.INPUT.format(Times.local(flight.start(), initialZone)), Times.INPUT.format(Times.local(flight.end(), initialZone))}));
                complete.setSelected(original.hasFlightData());
                acclimated.setSelected(original.isAcclimated());
                sleep.setSelected(original.isSleepOpportunityConfirmed());
                fit.setSelected(original.isFitForDuty());
                special.setSelected(original.isSpecialOperation());
                extension.setValue(original.getExtensionMinutes());
                unforeseen.setSelected(original.isUnforeseen());
                pic.setSelected(original.isPicApproved());
                carrier.setSelected(original.isCarrierApproved());
                notes.setText(original.getNotes());
            }
            JPanel input = new JPanel(new BorderLayout(0, 16));
            input.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
            input.add(form("Pilot", pilot, "Report time", start, "FDP end", end, "Released from all duty", release,
                "Report time zone", zone), BorderLayout.NORTH);
            JPanel flights = new JPanel(new BorderLayout(0, 10));
            flights.add(UiTheme.label("Flight legs • block-out / block-in in the report time zone", 12, true, UiTheme.TEXT), BorderLayout.NORTH);
            UiTheme.table(legTable);
            legTable.setAutoCreateRowSorter(false);
            legTable.setRowHeight(36);
            legTable.getTableHeader().setPreferredSize(new Dimension(0, 34));
            JScrollPane scroll = new JScrollPane(legTable);
            scroll.setPreferredSize(new Dimension(650, 150));
            flights.add(scroll, BorderLayout.CENTER);
            JPanel flightActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            JButton add = UiTheme.button("Add leg", false);
            JButton remove = UiTheme.button("Remove leg", false);
            add.addActionListener(event -> {
                try {
                    LocalDateTime local = Times.parse(start.getText()).plusMinutes(30);
                    legs.addRow(new Object[]{Times.INPUT.format(local), Times.INPUT.format(local.plusHours(1))});
                } catch (IllegalArgumentException exception) {
                    showError(exception.getMessage());
                }
            });
            remove.addActionListener(event -> {
                if (legTable.getSelectedRow() >= 0) {
                    legs.removeRow(legTable.getSelectedRow());
                }
            });
            flightActions.add(add);
            flightActions.add(remove);
            flightActions.add(complete);
            flights.add(flightActions, BorderLayout.SOUTH);
            input.add(flights, BorderLayout.CENTER);
            input.add(UiTheme.label("Date format: YYYY-MM-DD HH:MM. For a DST overlap or gap, enter the times in UTC.", 11, false, UiTheme.MUTED),
                BorderLayout.SOUTH);
            tabs.addTab("Duty & flight times", input);

            JPanel conditions = new JPanel(new BorderLayout(0, 16));
            conditions.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
            JPanel attestations = new JPanel(new GridLayout(0, 1, 0, 8));
            for (JCheckBox box : List.of(acclimated, sleep, fit, special)) {
                attestations.add(box);
            }
            attestations.add(UiTheme.label("When not acclimated, enter times in the last acclimated theater's time zone.", 11, false, UiTheme.MUTED));
            attestations.add(form("Actual FDP extension (minutes)", extension));
            for (JCheckBox box : List.of(unforeseen, pic, carrier)) {
                attestations.add(box);
            }
            conditions.add(attestations, BorderLayout.NORTH);
            JPanel notePanel = new JPanel(new BorderLayout(0, 8));
            notePanel.add(UiTheme.label("Notes / extension circumstances", 12, true, UiTheme.TEXT), BorderLayout.NORTH);
            notePanel.add(new JScrollPane(notes), BorderLayout.CENTER);
            conditions.add(notePanel, BorderLayout.CENTER);
            conditions.add(UiTheme.label("Extensions are conditional; this application does not grant operational approval.", 11, false, UiTheme.MUTED),
                BorderLayout.SOUTH);
            tabs.addTab("Rest & conditions", conditions);
            JPanel assessment = new JPanel(new BorderLayout(0, 15));
            assessment.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
            assessment.add(result, BorderLayout.NORTH);
            assessment.add(new JScrollPane(report), BorderLayout.CENTER);
            tabs.addTab("Assessment", assessment);
            add(tabs, BorderLayout.CENTER);
            JPanel bottom = new JPanel(new BorderLayout(12, 0));
            bottom.setBorder(BorderFactory.createEmptyBorder(10, 22, 16, 22));
            bottom.add(error, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(cancel);
            buttons.add(evaluate);
            if (!scenario) {
                buttons.add(save);
            }
            bottom.add(buttons, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);
            cancel.addActionListener(event -> dispose());
            evaluate.addActionListener(event -> withDuty(duty -> actions.evaluate(this, selectedUsername(), duty)));
            save.addActionListener(event -> withDuty(duty -> actions.save(this, selectedUsername(), duty, original != null)));
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(850, 670);
            setMinimumSize(new Dimension(790, 610));
            setLocationRelativeTo(owner);
        }

        private String selectedUsername() {
            return ((Pilot) pilot.getSelectedItem()).getUsername();
        }

        private void withDuty(java.util.function.Consumer<DutyPeriod> action) {
            try {
                if (legTable.isEditing() && !legTable.getCellEditor().stopCellEditing()) {
                    return;
                }
                String selectedZone = zone.getSelectedItem().toString().trim();
                List<FlightLeg> flights = new ArrayList<>();
                for (int row = 0; row < legs.getRowCount(); row++) {
                    flights.add(new FlightLeg(Times.parse(String.valueOf(legs.getValueAt(row, 0))),
                        Times.parse(String.valueOf(legs.getValueAt(row, 1))), selectedZone));
                }
                DutyPeriod duty = new DutyPeriod(Times.parse(start.getText()), Times.parse(end.getText()),
                    Times.parse(release.getText()), selectedZone, complete.isSelected() ? flights : null,
                    acclimated.isSelected(), sleep.isSelected(), fit.isSelected(), special.isSelected(), (Integer) extension.getValue(),
                    unforeseen.isSelected(), pic.isSelected(), carrier.isSelected(), notes.getText());
                if (original != null) {
                    duty = duty.withIdentity(original.getRecordKey(), original.getRevision());
                }
                error.setText(" ");
                action.accept(duty);
            } catch (IllegalArgumentException exception) {
                showError(exception.getMessage());
            }
        }

        public void setBusy(boolean busy) {
            evaluate.setEnabled(!busy);
            save.setEnabled(!busy);
            cancel.setEnabled(!busy);
            setDefaultCloseOperation(busy ? DO_NOTHING_ON_CLOSE : DISPOSE_ON_CLOSE);
            error.setForeground(UiTheme.MUTED);
            error.setText(busy ? "Processing…" : " ");
        }

        public void showError(String message) {
            setBusy(false);
            error.setForeground(UiTheme.RED);
            error.setText(message);
            error.setToolTipText(message);
        }

        public void showReport(ComplianceReport assessment) {
            setBusy(false);
            result.setText(assessment.status() + "   •   Flight remaining: "
                + (assessment.remainingFlightMinutes() < 0 ? "Unknown" : Times.hours(assessment.remainingFlightMinutes()))
                + "   •   FDP remaining: " + (assessment.remainingDutyMinutes() < 0 ? "Unknown" : Times.hours(assessment.remainingDutyMinutes())));
            result.setForeground(UiTheme.statusColor(assessment.status()));
            report.setText(formatReport(assessment));
            report.setCaretPosition(0);
            tabs.setSelectedIndex(2);
        }
    }

    public static final class RestEditor extends JDialog {
        private final JComboBox<Pilot> pilot;
        private final JTextField start = new JTextField();
        private final JTextField end = new JTextField();
        private final JComboBox<String> zone = zones();
        private final JTextField sleep = new JTextField("8:00");
        private final JTextArea notes = notes();
        private final JLabel error = UiTheme.label(" ", 12, false, UiTheme.RED);
        private final JButton save = UiTheme.button("Save rest period", true);
        private final JButton cancel = UiTheme.button("Cancel", false);

        public RestEditor(JFrame owner, List<Pilot> pilots, String selected, RestPeriod original,
                          BiConsumer<RestEditor, RestPeriod> action) {
            super(owner, original == null ? "Log completed rest" : "Edit rest period", true);
            pilot = new JComboBox<>(pilots.toArray(Pilot[]::new));
            pilots.stream().filter(user -> user.getUsername().equals(selected)).findFirst().ifPresent(pilot::setSelectedItem);
            pilot.setEnabled(original == null);
            Pilot user = (Pilot) pilot.getSelectedItem();
            String initialZone = original == null ? user.getZoneId() : original.zoneId();
            zone.setSelectedItem(initialZone);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            Instant lower = now.minusSeconds(10 * 3600);
            Instant lastRelease = user.getDutyHistory().stream().map(DutyPeriod::getReleaseInstant)
                .filter(instant -> !instant.isAfter(now)).max(java.util.Comparator.naturalOrder()).orElse(lower);
            if (lastRelease.isAfter(lower)) {
                lower = lastRelease;
            }
            start.setText(Times.INPUT.format(original == null ? Times.local(lower, initialZone) : original.startTime()));
            end.setText(Times.INPUT.format(original == null ? Times.local(now, initialZone) : original.endTime()));
            if (original != null) {
                sleep.setText(Times.hours(original.sleepMinutes()));
                notes.setText(original.notes());
            }
            JPanel content = new JPanel(new BorderLayout(0, 18));
            content.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));
            content.add(form("Pilot", pilot, "Start", start, "End", end, "Time zone", zone,
                "Uninterrupted sleep opportunity (H:MM)", sleep), BorderLayout.NORTH);
            JPanel notePanel = new JPanel(new BorderLayout(0, 8));
            notePanel.add(UiTheme.label("Notes", 12, true, UiTheme.TEXT), BorderLayout.NORTH);
            notePanel.add(new JScrollPane(notes), BorderLayout.CENTER);
            content.add(notePanel, BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(0, 8));
            footer.add(error, BorderLayout.NORTH);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(cancel);
            buttons.add(save);
            footer.add(buttons, BorderLayout.SOUTH);
            content.add(footer, BorderLayout.SOUTH);
            add(content);
            cancel.addActionListener(event -> dispose());
            save.addActionListener(event -> {
                try {
                    RestPeriod rest = new RestPeriod(Times.parse(start.getText()), Times.parse(end.getText()),
                        zone.getSelectedItem().toString().trim(), duration(sleep.getText()), notes.getText());
                    if (original != null) {
                        rest = rest.withIdentity(original.recordKey(), original.revision());
                    }
                    action.accept(this, rest);
                } catch (IllegalArgumentException exception) {
                    showError(exception.getMessage());
                }
            });
            setSize(660, 550);
            setResizable(false);
            setLocationRelativeTo(owner);
        }

        public String username() {
            return ((Pilot) pilot.getSelectedItem()).getUsername();
        }

        public void setBusy(boolean busy) {
            save.setEnabled(!busy);
            cancel.setEnabled(!busy);
            setDefaultCloseOperation(busy ? DO_NOTHING_ON_CLOSE : DISPOSE_ON_CLOSE);
            error.setText(busy ? "Processing…" : " ");
            error.setForeground(UiTheme.MUTED);
        }

        public void showError(String message) {
            setBusy(false);
            error.setForeground(UiTheme.RED);
            error.setText(message);
            error.setToolTipText(message);
        }
    }

    public static final class AccountEditor extends JDialog {
        private final JLabel error = UiTheme.label(" ", 12, false, UiTheme.RED);
        private final JButton save = UiTheme.button("Save account", true);
        private final JButton cancel = UiTheme.button("Cancel", false);
        private final JPasswordField password = new JPasswordField();

        public AccountEditor(JFrame owner, Pilot original, BiConsumer<AccountEditor, Pilot> action) {
            super(owner, original == null ? "Add account" : "Edit account", true);
            JTextField username = new JTextField(original == null ? "" : original.getUsername());
            username.setEnabled(original == null);
            JTextField name = new JTextField(original == null ? "" : original.getDisplayName());
            JComboBox<Role> role = new JComboBox<>(Role.values());
            role.setSelectedItem(original == null ? Role.PILOT : original.role());
            JTextField base = new JTextField(original == null ? "DEN" : original.getBaseAssignment());
            JTextField certificate = new JTextField(original == null ? "ATP" : original.getCertificationStatus());
            JSpinner hours = new JSpinner(new SpinnerNumberModel(original == null ? 0 : original.getCumulativeFlightHours(), 0, 100_000, 1));
            JComboBox<String> zone = zones();
            zone.setSelectedItem(original == null ? "America/Denver" : original.getZoneId());
            JPanel content = new JPanel(new BorderLayout(0, 18));
            content.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));
            content.add(form("Username", username, "Full name", name, "Role", role, "Base", base, "Certification", certificate,
                "Opening lifetime flight hours", hours, "Home / calendar time zone", zone,
                original == null ? "Password (10–128 characters)" : "New password (blank keeps current)", password), BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(0, 10));
            footer.add(error, BorderLayout.NORTH);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(cancel);
            buttons.add(save);
            footer.add(buttons, BorderLayout.SOUTH);
            content.add(footer, BorderLayout.SOUTH);
            add(content);
            cancel.addActionListener(event -> {
                clearPassword();
                dispose();
            });
            save.addActionListener(event -> {
                char[] entered = password.getPassword();
                try {
                    String hash = original == null ? "" : original.getPasswordHash();
                    if (original == null || entered.length > 0) {
                        User.validateNewPassword(entered);
                        hash = User.hashPassword(entered);
                    }
                    Pilot user = new Pilot(username.getText().trim(), hash, ((Role) role.getSelectedItem()).label(),
                        (Integer) hours.getValue(), certificate.getText(), base.getText(), name.getText(), zone.getSelectedItem().toString().trim());
                    action.accept(this, user);
                } catch (IllegalArgumentException exception) {
                    showError(exception.getMessage());
                } finally {
                    Arrays.fill(entered, '\0');
                }
            });
            setSize(630, 640);
            setResizable(false);
            setLocationRelativeTo(owner);
        }

        public void clearPassword() {
            password.setText("");
        }

        public void setBusy(boolean busy) {
            save.setEnabled(!busy);
            cancel.setEnabled(!busy);
            setDefaultCloseOperation(busy ? DO_NOTHING_ON_CLOSE : DISPOSE_ON_CLOSE);
            error.setText(busy ? "Saving…" : " ");
            error.setForeground(UiTheme.MUTED);
        }

        public void showError(String message) {
            setBusy(false);
            error.setForeground(UiTheme.RED);
            error.setText(message);
            error.setToolTipText(message);
        }
    }

    static JComboBox<String> zones() {
        JComboBox<String> combo = new JComboBox<>(new String[]{"UTC", "America/Denver", "America/Los_Angeles",
            "America/Chicago", "America/Phoenix", "America/New_York", "Europe/London", "Pacific/Honolulu"});
        combo.setEditable(true);
        return combo;
    }

    static JPanel form(Object... pairs) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints layout = new GridBagConstraints();
        layout.insets = new Insets(5, 0, 5, 12);
        layout.anchor = GridBagConstraints.WEST;
        layout.fill = GridBagConstraints.HORIZONTAL;
        for (int index = 0; index < pairs.length; index += 2) {
            layout.gridy = index / 2;
            layout.gridx = 0;
            layout.weightx = 0;
            JLabel label = UiTheme.label(pairs[index].toString(), 12, false, UiTheme.TEXT);
            label.setLabelFor((JComponent) pairs[index + 1]);
            panel.add(label, layout);
            layout.gridx = 1;
            layout.weightx = 1;
            panel.add((JComponent) pairs[index + 1], layout);
        }
        return panel;
    }

    static JTextArea notes() {
        JTextArea area = new JTextArea(4, 30);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
        return area;
    }

    static JTextArea readOnlyText() {
        JTextArea area = notes();
        area.setEditable(false);
        area.setForeground(UiTheme.TEXT);
        return area;
    }

    static int duration(String value) {
        if (!value.trim().matches("[0-9]{1,4}:[0-5][0-9]")) {
            throw new IllegalArgumentException("Enter sleep opportunity as H:MM, such as 8:00.");
        }
        String[] parts = value.trim().split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    static String formatReport(ComplianceReport report) {
        StringBuilder text = new StringBuilder();
        text.append("Remaining flight time: ").append(report.remainingFlightMinutes() < 0 ? "Unknown" : Times.hours(report.remainingFlightMinutes()))
            .append("\nRemaining FDP time: ").append(report.remainingDutyMinutes() < 0 ? "Unknown" : Times.hours(report.remainingDutyMinutes()))
            .append("\nConditional total FDP extension capacity: ").append(Times.hours(report.availableExtensionMinutes())).append("\n\n");
        for (ComplianceReport.Check check : report.checks()) {
            text.append(check.status()).append("  •  ").append(check.title()).append("\n")
                .append(check.explanation()).append("\n\n");
        }
        text.append("Assessment covers modeled unaugmented rules and the recorded history. It is not a dispatch approval.");
        return text.toString();
    }

    static void message(JFrame owner, String text) {
        JOptionPane.showMessageDialog(owner, text, "FRMS", JOptionPane.INFORMATION_MESSAGE);
    }
}
