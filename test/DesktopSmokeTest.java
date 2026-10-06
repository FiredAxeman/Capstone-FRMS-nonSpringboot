import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Optional real Swing workflow; captures only this application's components, never the desktop.
 */
@EnabledIfEnvironmentVariable(named = "FRMS_UI_TEST", matches = "true")
class DesktopSmokeTest {
    @TempDir
    Path directory;

    @Test
    void rendersAndExercisesTheFleetSearchAndActualDutyEditor() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "FRMS_UI_TEST requires a graphical environment.");
        DatabaseManager database = new DatabaseManager(directory.resolve("demo.sqlite"));
        database.seedDemoDataIfEmpty();
        FrmsService service = new FrmsService(database, directory.resolve("outbox"));
        service.signIn("admin", "admin".toCharArray());
        AtomicReference<ComplianceDashboard> frame = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                UiTheme.install();
                ComplianceDashboard dashboard = new ComplianceDashboard(service, () -> {
                });
                dashboard.addNotify();
                dashboard.validate();
                frame.set(dashboard);
            });
            await(() -> roster(frame.get()).getRowCount() == 6);
            Path previews = Path.of("target", "ui-preview");
            Files.createDirectories(previews);
            SwingUtilities.invokeAndWait(() -> capture(frame.get().getContentPane(), previews.resolve("overview.png")));
            SwingUtilities.invokeAndWait(() -> {
                JTextField search = components(frame.get(), JTextField.class).stream().filter(input ->
                    "Search pilot roster".equals(input.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                search.setText("jamie");
                assertEquals(1, roster(frame.get()).getRowCount());
                search.setText("");
                JComboBox<?> filter = components(frame.get(), JComboBox.class).stream()
                    .filter(combo -> combo.getItemCount() > 0 && "All statuses".equals(combo.getItemAt(0))).findFirst().orElseThrow();
                filter.setSelectedItem("Within modeled limits");
                assertEquals(2, roster(frame.get()).getRowCount());
                filter.setSelectedItem("All statuses");
                assertEquals(6, roster(frame.get()).getRowCount());
                button(frame.get(), "Duty records").doClick();
                capture(frame.get().getContentPane(), previews.resolve("duty-records.png"));
            });
            DashboardSnapshot before = service.snapshot();
            AtomicReference<DutyPeriod> input = new AtomicReference<>();
            AtomicReference<String> pilotName = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                List<Pilot> pilots = before.crew().stream().map(CrewAssessment::pilot).toList();
                RecordDialogs.DutyEditor editor = new RecordDialogs.DutyEditor(frame.get(), pilots, "jamie.chen", null, false,
                    new RecordDialogs.DutyActions() {
                        @Override
                        public void evaluate(RecordDialogs.DutyEditor dialog, String username, DutyPeriod duty) {
                        }

                        @Override
                        public void save(RecordDialogs.DutyEditor dialog, String username, DutyPeriod duty, boolean update) {
                            pilotName.set(username);
                            input.set(duty);
                            dialog.dispose();
                        }
                    });
                editor.addNotify();
                editor.validate();
                components(editor, JCheckBox.class).stream().filter(box -> box.getText().equals("Pilot affirmed fitness for duty")
                    || box.getText().equals("At least 8 uninterrupted hours of sleep opportunity")).forEach(box -> box.setSelected(true));
                button(editor, "Add leg").doClick();
                capture(editor.getContentPane(), previews.resolve("duty-editor.png"));
                button(editor, "Save actual record").doClick();
            });
            assertNotNull(input.get());
            assertEquals("jamie.chen", pilotName.get());
            assertEquals(60, input.get().getFlightMinutes());
            assertFalse(service.saveDuty(pilotName.get(), input.get(), false).queued());
            SwingUtilities.invokeAndWait(() -> frame.get().aggregateData());
            int expected = before.data().users().values().stream().mapToInt(user -> user.getDutyHistory().size()).sum() + 1;
            await(() -> dutyTable(frame.get()).getRowCount() == expected);
            SwingUtilities.invokeAndWait(() -> assertFalse(dutyTable(frame.get()).getModel().isCellEditable(0, 0)));
            // Login preview uses a fresh dialog with the same production layout.
            java.lang.reflect.Constructor<LoginDialog> constructor = LoginDialog.class.getDeclaredConstructor(FrmsService.class);
            constructor.setAccessible(true);
            AtomicReference<JDialog> login = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JDialog dialog = constructor.newInstance(service);
                    dialog.addNotify();
                    dialog.validate();
                    login.set(dialog);
                    capture(dialog.getContentPane(), previews.resolve("sign-in.png"));
                    dialog.dispose();
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } finally {
            if (frame.get() != null) {
                SwingUtilities.invokeAndWait(() -> frame.get().dispose());
            }
        }
    }

    private static JTable roster(Container root) {
        return components(root, JTable.class).stream().filter(table -> table.getColumnCount() == 6).findFirst().orElseThrow();
    }

    private static JTable dutyTable(Container root) {
        return components(root, JTable.class).stream().filter(table -> table.getColumnCount() == 9).findFirst().orElseThrow();
    }

    private static AbstractButton button(Container root, String text) {
        return components(root, AbstractButton.class).stream().filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
    }

    private static <T> List<T> components(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                result.add(type.cast(component));
            }
            if (component instanceof Container child) {
                result.addAll(components(child, type));
            }
        }
        return result;
    }

    private static void capture(Container panel, Path target) {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        panel.printAll(graphics);
        graphics.dispose();
        try {
            ImageIO.write(image, "png", target.toFile());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> done = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Dashboard update did not complete.");
    }
}
