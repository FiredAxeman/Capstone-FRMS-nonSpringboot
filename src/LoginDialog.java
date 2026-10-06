import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * A real sign-in boundary; hashing and SQL work run outside the event-dispatch thread.
 */
public final class LoginDialog extends JDialog {
    private final JTextField username = new JTextField("admin", 22);
    private final JPasswordField password = new JPasswordField(22);
    private final JLabel error = UiTheme.label(" ", 12, false, UiTheme.RED);
    private final JButton signIn = UiTheme.button("Sign in", true);
    private boolean authenticated;

    private LoginDialog(FrmsService service) {
        super((java.awt.Frame) null, "FRMS • Sign in", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        JPanel left = new JPanel(new BorderLayout(0, 36));
        left.setBackground(UiTheme.NAVY);
        left.setBorder(BorderFactory.createEmptyBorder(44, 36, 38, 36));
        left.setPreferredSize(new Dimension(325, 500));
        JPanel brand = UiTheme.transparent(new GridLayout(0, 1, 0, 9));
        brand.add(UiTheme.label("FRMS", 30, true, Color.WHITE));
        brand.add(UiTheme.label("FLIGHT OPERATIONS", 11, true, new Color(151, 183, 202)));
        left.add(brand, BorderLayout.NORTH);
        JPanel message = UiTheme.transparent(new GridLayout(0, 1, 0, 14));
        message.add(UiTheme.label("Flight duty & fatigue monitoring", 13, false, new Color(187, 204, 219)));
        message.add(UiTheme.label("Harley Driscoll • CSC480 Capstone", 12, false, new Color(151, 183, 202)));
        left.add(message, BorderLayout.CENTER);
        left.add(UiTheme.label("JAVA DESKTOP   /   LOCAL SQL", 10, true, new Color(151, 183, 202)), BorderLayout.SOUTH);

        JPanel right = new JPanel(new GridBagLayout());
        right.setBorder(BorderFactory.createEmptyBorder(38, 42, 32, 42));
        GridBagConstraints layout = new GridBagConstraints();
        layout.gridx = 0;
        layout.gridy = 0;
        layout.weightx = 1;
        layout.fill = GridBagConstraints.HORIZONTAL;
        layout.insets = new Insets(0, 0, 10, 0);
        addField(right, layout, UiTheme.label("Welcome to flight operations", 22, true, UiTheme.TEXT));
        addField(right, layout, UiTheme.label("Sign in with your assigned account.", 13, false, UiTheme.MUTED));
        layout.insets = new Insets(14, 0, 7, 0);
        JLabel usernameLabel = UiTheme.label("Username", 12, true, UiTheme.TEXT);
        usernameLabel.setLabelFor(username);
        addField(right, layout, usernameLabel);
        layout.insets = new Insets(0, 0, 8, 0);
        addField(right, layout, username);
        JLabel passwordLabel = UiTheme.label("Password", 12, true, UiTheme.TEXT);
        passwordLabel.setLabelFor(password);
        addField(right, layout, passwordLabel);
        addField(right, layout, password);
        addField(right, layout, error);
        addField(right, layout, signIn);
        layout.insets = new Insets(12, 0, 7, 0);
        addField(right, layout, UiTheme.label("CAPSTONE DEMO ACCESS", 10, true, UiTheme.TEAL));
        layout.insets = new Insets(0, 0, 6, 0);
        addField(right, layout, UiTheme.label("Administrator    admin / admin", 12, false, UiTheme.MUTED));
        addField(right, layout, UiTheme.label("Dispatcher        dispatcher / dispatch-demo", 12, false, UiTheme.MUTED));
        addField(right, layout, UiTheme.label("Pilot                   alex.morgan / pilot-demo", 12, false, UiTheme.MUTED));
        addField(right, layout, UiTheme.label("Demo accounts exist only in a newly seeded database.", 10, false, UiTheme.MUTED));
        signIn.addActionListener(event -> {
            String enteredUsername = username.getText();
            char[] enteredPassword = password.getPassword();
            signIn.setEnabled(false);
            username.setEnabled(false);
            password.setEnabled(false);
            error.setText("Verifying account…");
            error.setForeground(UiTheme.MUTED);
            new SwingWorker<Pilot, Void>() {
                @Override
                protected Pilot doInBackground() throws Exception {
                    try {
                        return service.signIn(enteredUsername, enteredPassword);
                    } finally {
                        Arrays.fill(enteredPassword, '\0');
                    }
                }

                @Override
                protected void done() {
                    try {
                        get();
                        authenticated = true;
                        dispose();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        showFailure("Sign-in was interrupted.");
                    } catch (ExecutionException exception) {
                        showFailure(exception.getCause().getMessage());
                    }
                }
            }.execute();
        });
        getRootPane().setDefaultButton(signIn);
        password.addActionListener(event -> signIn.doClick());
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);
    }

    private void showFailure(String message) {
        error.setForeground(UiTheme.RED);
        error.setText(message);
        error.setToolTipText(message);
        signIn.setEnabled(true);
        username.setEnabled(true);
        password.setEnabled(true);
        password.setText("");
        password.requestFocusInWindow();
    }

    private static void addField(JPanel panel, GridBagConstraints layout, java.awt.Component component) {
        panel.add(component, layout);
        layout.gridy++;
    }

    public static boolean show(FrmsService service) {
        LoginDialog dialog = new LoginDialog(service);
        dialog.setVisible(true);
        return dialog.authenticated;
    }
}
