import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Shared, accessible desktop styling without a framework or bitmap assets.
 */
public final class UiTheme {
    public static final Color NAVY = new Color(18, 34, 53);
    public static final Color BACKGROUND = new Color(243, 246, 250);
    public static final Color TEXT = new Color(29, 45, 65);
    public static final Color MUTED = new Color(109, 123, 142);
    public static final Color LINE = new Color(225, 231, 238);
    public static final Color TEAL = new Color(13, 128, 121);
    public static final Color BLUE = new Color(54, 105, 176);
    public static final Color GREEN = new Color(28, 125, 81);
    public static final Color AMBER = new Color(163, 101, 13);
    public static final Color RED = new Color(184, 56, 65);

    private UiTheme() {
    }

    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception exception) {
            System.err.println("Using the default desktop theme.");
        }
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        for (Object key : UIManager.getDefaults().keySet().toArray()) {
            if (key.toString().endsWith(".font")) {
                UIManager.put(key, font);
            }
        }
        UIManager.put("Panel.background", Color.WHITE);
        UIManager.put("Table.background", Color.WHITE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", new Color(230, 243, 243));
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("TextField.background", Color.WHITE);
        UIManager.put("TextField.border", BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(LINE), new EmptyBorder(8, 10, 8, 10)));
        UIManager.put("PasswordField.border", UIManager.get("TextField.border"));
        UIManager.put("ComboBox.background", Color.WHITE);
        UIManager.put("TabbedPane.background", BACKGROUND);
        UIManager.put("ScrollPane.border", BorderFactory.createLineBorder(LINE));
        UIManager.put("ToolTip.background", new Color(245, 249, 252));
    }

    public static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    public static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    public static JButton button(String text, boolean primary) {
        return new ActionButton(text, primary);
    }

    public static javax.swing.JTextField searchField(int columns, String placeholder) {
        return new javax.swing.JTextField(columns) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getText().isEmpty() && !hasFocus()) {
                    Graphics2D draw = (Graphics2D) graphics.create();
                    draw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    draw.setColor(MUTED);
                    draw.setFont(getFont().deriveFont(11f));
                    draw.drawString(placeholder, getInsets().left, (getHeight() + draw.getFontMetrics().getAscent()
                        - draw.getFontMetrics().getDescent()) / 2);
                    draw.dispose();
                }
            }
        };
    }

    public static Color statusColor(ComplianceReport.Status status) {
        return switch (status) {
            case CLEAR -> GREEN;
            case WARNING -> AMBER;
            case REVIEW -> BLUE;
            case VIOLATION -> RED;
        };
    }

    public static Color statusBackground(ComplianceReport.Status status) {
        return switch (status) {
            case CLEAR -> new Color(228, 245, 235);
            case WARNING -> new Color(255, 245, 224);
            case REVIEW -> new Color(232, 240, 252);
            case VIOLATION -> new Color(253, 234, 237);
        };
    }

    public static void table(JTable table) {
        table.setRowHeight(49);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, 39));
        table.getTableHeader().setBackground(new Color(248, 250, 252));
        table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.setDefaultRenderer(ComplianceReport.Status.class, new CellRenderer());
    }

    public static final class CellRenderer extends DefaultTableCellRenderer {
        public CellRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (value instanceof java.time.Instant instant) {
                setText(Times.INPUT.format(java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)));
            } else if (value instanceof java.time.Duration duration) {
                setText(Times.hours(duration.toMinutes()));
            } else if (value == null) {
                setText("Unknown");
            }
            putClientProperty("html.disable", Boolean.TRUE);
            setBorder(new EmptyBorder(0, 12, 0, 12));
            setHorizontalAlignment(value instanceof ComplianceReport.Status ? SwingConstants.CENTER : SwingConstants.LEFT);
            setFont(new Font(Font.SANS_SERIF, value instanceof ComplianceReport.Status ? Font.BOLD : Font.PLAIN,
                value instanceof ComplianceReport.Status ? 11 : 13));
            if (value instanceof java.time.Instant) {
                setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            }
            setBackground(selected ? table.getSelectionBackground() : row % 2 == 0 ? Color.WHITE : new Color(251, 252, 254));
            setForeground(TEXT);
            if (value instanceof ComplianceReport.Status status) {
                setForeground(statusColor(status));
                setBackground(statusBackground(status));
                setToolTipText(status.toString());
            } else {
                setToolTipText(value == null ? null : value.toString());
            }
            return this;
        }
    }

    public static final class Surface extends JPanel implements javax.swing.Scrollable {
        public Surface(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
            setBorder(new EmptyBorder(18, 20, 18, 20));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 14;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(14, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 14);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof javax.swing.JViewport viewport
                && viewport.getHeight() >= getPreferredSize().height;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D draw = (Graphics2D) graphics.create();
            draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            draw.setColor(Color.WHITE);
            draw.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            draw.setColor(LINE);
            draw.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            draw.dispose();
            super.paintComponent(graphics);
        }
    }

    public static final class UtilizationBar extends JComponent {
        private long used;
        private final long limit;

        public UtilizationBar(long used, long limit) {
            this.used = used;
            this.limit = limit;
            setPreferredSize(new Dimension(200, 7));
            getAccessibleContext().setAccessibleName("Utilization");
        }

        public void setUsed(long value) {
            used = value;
            getAccessibleContext().setAccessibleDescription(Times.hours(value) + " of " + Times.hours(limit));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D draw = (Graphics2D) graphics.create();
            draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            draw.setColor(LINE);
            draw.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            draw.setColor(used > limit ? RED : used >= limit * 0.9 ? AMBER : TEAL);
            draw.fillRoundRect(0, 0, (int) (getWidth() * Math.min(1, used / (double) limit)), getHeight(), 8, 8);
            draw.dispose();
        }

        @Override
        public javax.accessibility.AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJComponent() {
                };
            }
            return accessibleContext;
        }
    }

    private static final class ActionButton extends JButton {
        private final boolean primary;

        private ActionButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setContentAreaFilled(false);
            setOpaque(false);
            setFocusPainted(false);
            setBorder(new EmptyBorder(10, 15, 10, 15));
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            setForeground(primary ? Color.WHITE : TEXT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D draw = (Graphics2D) graphics.create();
            draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = primary ? TEAL : Color.WHITE;
            if (getModel().isPressed()) {
                fill = primary ? TEAL.darker() : BACKGROUND;
            } else if (getModel().isRollover()) {
                fill = primary ? new Color(10, 111, 106) : BACKGROUND;
            }
            if (!isEnabled()) {
                fill = primary ? new Color(145, 184, 181) : BACKGROUND;
            }
            draw.setColor(fill);
            draw.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            if (!primary) {
                draw.setColor(LINE);
                draw.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
            if (hasFocus()) {
                draw.setColor(BLUE);
                draw.setStroke(new BasicStroke(2));
                draw.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 9, 9);
            }
            draw.dispose();
            super.paintComponent(graphics);
        }
    }
}
