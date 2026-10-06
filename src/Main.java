import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * Desktop entry point with an isolated-database option for testing and presentations.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        boolean noDemo = false;
        try {
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--database" -> {
                        if (++index >= args.length) {
                            throw new IllegalArgumentException("--database requires a file path.");
                        }
                        System.setProperty("frms.database", args[index]);
                    }
                    case "--headless" -> System.setProperty("java.awt.headless", "true");
                    case "--no-demo" -> noDemo = true;
                    case "--help" -> {
                        System.out.println("FRMS: java -jar flight-duty-frms-1.0.0.jar [--database FILE] [--headless] [--no-demo]");
                        System.out.println("A fresh database includes sample accounts unless --no-demo is specified.");
                        return;
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index] + ". Use --help.");
                }
            }
            DatabaseManager database = new DatabaseManager();
            if (!noDemo) {
                database.seedDemoDataIfEmpty();
            }
            System.out.println("FRMS database ready: " + database.getDatabasePath());
            Path queuePath = database.getDatabasePath().resolveSibling(database.getDatabasePath().getFileName() + ".outbox");
            FrmsService service = new FrmsService(database, queuePath);
            boolean bootstrap = database.loadPilots().isEmpty();
            if (GraphicsEnvironment.isHeadless()) {
                if (bootstrap) {
                    String password = System.getenv("FRMS_ADMIN_PASSWORD");
                    if (password == null) {
                        throw new IllegalArgumentException("First --no-demo headless start requires FRMS_ADMIN_PASSWORD (10–128 characters).");
                    }
                    char[] characters = password.toCharArray();
                    try {
                        User.validateNewPassword(characters);
                        database.savePilot(initialAdmin(User.hashPassword(characters)));
                    } finally {
                        Arrays.fill(characters, '\0');
                    }
                }
                System.out.println("Schema and operational data initialized. " + database.loadPilots().size() + " accounts.");
                System.out.println("A graphical environment is required to sign in and open the dashboard.");
                return;
            }
            SwingUtilities.invokeLater(() -> {
                UiTheme.install();
                if (bootstrap) {
                    createInitialAdmin(database, service);
                } else {
                    openSession(service);
                }
            });
        } catch (Exception exception) {
            ComplianceDashboard.showStartupError("FRMS could not start: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static Pilot initialAdmin(String hash) {
        return new Pilot("admin", hash, "Administrator", 0, "N/A", "Operations", "Administrator", "UTC");
    }

    private static void createInitialAdmin(DatabaseManager database, FrmsService service) {
        JPasswordField password = new JPasswordField(22);
        JPasswordField confirmation = new JPasswordField(22);
        if (JOptionPane.showConfirmDialog(null, RecordDialogs.form("Administrator password", password, "Confirm password", confirmation),
            "Create first administrator • username: admin", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        char[] entered = password.getPassword();
        char[] confirmed = confirmation.getPassword();
        boolean matches = Arrays.equals(entered, confirmed);
        Arrays.fill(confirmed, '\0');
        try {
            User.validateNewPassword(entered);
            if (!matches) {
                throw new IllegalArgumentException("Passwords do not match.");
            }
        } catch (IllegalArgumentException exception) {
            Arrays.fill(entered, '\0');
            ComplianceDashboard.showStartupError(exception.getMessage());
            createInitialAdmin(database, service);
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    database.savePilot(initialAdmin(User.hashPassword(entered)));
                    return null;
                } finally {
                    Arrays.fill(entered, '\0');
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    openSession(service);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    ComplianceDashboard.showStartupError(exception.getCause().getMessage());
                }
            }
        }.execute();
    }

    private static void openSession(FrmsService service) {
        if (LoginDialog.show(service)) {
            new ComplianceDashboard(service, () -> openSession(service)).setVisible(true);
        }
    }
}
