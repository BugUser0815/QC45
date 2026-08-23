package pt.efacec.es.evcsd.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.JPanel;
import javax.swing.Timer;

/** Shared, code-rendered 640x480 design system for all QC45 operator pages. */
public abstract class AlpitronicPanel extends JPanel {
    protected static final int WIDTH = 640;
    protected static final int HEIGHT = 480;
    protected static final Color BG = new Color(13, 15, 15);
    protected static final Color PANEL = new Color(29, 31, 31);
    protected static final Color PANEL_LIGHT = new Color(43, 46, 46);
    protected static final Color PRIMARY = new Color(245, 245, 245);
    protected static final Color SECONDARY = new Color(176, 179, 179);
    protected static final Color DIVIDER = new Color(77, 81, 81);
    protected static final Color YELLOW = new Color(255, 214, 0);
    protected static final Color RED = new Color(166, 30, 30);
    protected static final Color GREEN = new Color(72, 184, 106);

    private String status;
    private Color statusColor;
    private final Timer clockTimer;

    protected AlpitronicPanel(String status, Color statusColor) {
        this.status = status;
        this.statusColor = statusColor;
        setOpaque(true);
        setBackground(BG);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setMinimumSize(new Dimension(WIDTH, HEIGHT));
        setSize(WIDTH, HEIGHT);
        this.clockTimer = new Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                repaint(540, 0, 100, 43);
            }
        });
    }

    protected final void setStatus(String nextStatus, Color nextColor) {
        this.status = nextStatus;
        this.statusColor = nextColor;
        repaint();
    }

    public void addNotify() {
        super.addNotify();
        clockTimer.start();
    }

    public void removeNotify() {
        clockTimer.stop();
        super.removeNotify();
    }

    protected final void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D)graphics.create();
        antialias(g);
        g.setColor(BG);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        drawHeader(g);
        paintScreen(g);
        g.dispose();
    }

    protected abstract void paintScreen(Graphics2D g);

    private void drawHeader(Graphics2D g) {
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 18));
        g.drawString("QC45", 18, 28);

        g.setColor(statusColor == null ? SECONDARY : statusColor);
        g.fillOval(157, 17, 10, 10);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 16));
        g.drawString(status == null ? "" : status, 176, 29);

        g.setFont(font(Font.BOLD, 18));
        right(g, new SimpleDateFormat("HH:mm").format(new Date()), 622, 28);
        g.setColor(DIVIDER);
        g.drawLine(0, 42, WIDTH, 42);
    }

    protected final void title(Graphics2D g, String text, String detail) {
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 31));
        centered(g, text, 320, 88);
        if (detail != null && detail.length() > 0) {
            g.setColor(SECONDARY);
            g.setFont(font(Font.PLAIN, 15));
            centered(g, detail, 320, 113);
        }
    }

    protected final void action(Graphics2D g, int x, int y, int width, String text, int kind) {
        Color fill = kind == 1 ? YELLOW : kind == 2 ? RED : PANEL_LIGHT;
        Color foreground = kind == 1 ? BG : PRIMARY;
        g.setColor(fill);
        g.fillRect(x, y, width, 42);
        g.setColor(foreground);
        g.setFont(font(Font.BOLD, 14));
        centered(g, text, x + width / 2, y + 27);
    }

    protected final void connectorCard(Graphics2D g, int x, String name, String detail,
                                       boolean enabled, boolean inUse, boolean outOfService) {
        Color border = outOfService ? RED : inUse ? SECONDARY : enabled ? YELLOW : DIVIDER;
        g.setColor(PANEL);
        g.fillRect(x, 126, 184, 210);
        g.setColor(border);
        g.setStroke(new BasicStroke(enabled && !inUse && !outOfService ? 3.0f : 1.0f));
        g.drawRect(x, 126, 184, 210);
        drawPlug(g, x + 92, 188, border);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 25));
        centered(g, name, x + 92, 250);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 14));
        centered(g, detail, x + 92, 276);
        String state = outOfService ? "AUSSER BETRIEB" : inUse ? "BELEGT" : enabled ? "VERFÜGBAR" : "DEAKTIVIERT";
        g.setColor(border);
        g.setFont(font(Font.BOLD, 12));
        centered(g, state, x + 92, 313);
    }

    protected final void metric(Graphics2D g, int x, int width, String label, String value) {
        g.setColor(PANEL);
        g.fillRect(x, 274, width, 98);
        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 12));
        centered(g, label, x + width / 2, 302);
        g.setColor(PRIMARY);
        g.setFont(font(Font.PLAIN, 28));
        centered(g, safe(value, "--"), x + width / 2, 346);
    }

    protected final void instruction(Graphics2D g, String text, String detail) {
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 27));
        wrappedCentered(g, text, 320, 282, 500, 34);
        if (detail != null && detail.length() > 0) {
            g.setColor(SECONDARY);
            g.setFont(font(Font.PLAIN, 15));
            wrappedCentered(g, detail, 320, 345, 520, 22);
        }
    }

    protected final void footer(Graphics2D g, String text) {
        g.setColor(DIVIDER);
        g.drawLine(0, 416, WIDTH, 416);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 12));
        centered(g, text, 320, 452);
    }

    protected final void drawPlug(Graphics2D g, int centerX, int centerY, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(4.0f));
        g.drawOval(centerX - 28, centerY - 22, 44, 44);
        g.drawLine(centerX + 16, centerY, centerX + 42, centerY);
        g.drawLine(centerX + 42, centerY, centerX + 51, centerY - 9);
        g.drawLine(centerX + 42, centerY, centerX + 51, centerY + 9);
        g.fillOval(centerX - 16, centerY - 8, 6, 6);
        g.fillOval(centerX - 16, centerY + 3, 6, 6);
    }

    protected final void drawCard(Graphics2D g, int centerX, int centerY) {
        g.setColor(YELLOW);
        g.setStroke(new BasicStroke(4.0f));
        g.drawRoundRect(centerX - 58, centerY - 38, 78, 76, 8, 8);
        g.drawArc(centerX + 3, centerY - 28, 42, 56, -55, 110);
        g.drawArc(centerX + 13, centerY - 19, 28, 38, -55, 110);
        g.drawArc(centerX + 23, centerY - 10, 14, 20, -55, 110);
    }

    protected final void drawCheck(Graphics2D g, int centerX, int centerY, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(7.0f));
        g.drawOval(centerX - 42, centerY - 42, 84, 84);
        g.drawLine(centerX - 21, centerY, centerX - 5, centerY + 17);
        g.drawLine(centerX - 5, centerY + 17, centerX + 26, centerY - 18);
    }

    protected static Font font(int style, int size) {
        return new Font("Roboto", style, size);
    }

    protected static String safe(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    protected static void centered(Graphics2D g, String text, int centerX, int baseline) {
        String value = safe(text, "");
        g.drawString(value, centerX - g.getFontMetrics().stringWidth(value) / 2, baseline);
    }

    protected static void right(Graphics2D g, String text, int rightX, int baseline) {
        g.drawString(text, rightX - g.getFontMetrics().stringWidth(text), baseline);
    }

    protected static void wrappedCentered(Graphics2D g, String text, int centerX, int firstBaseline,
                                          int maxWidth, int lineHeight) {
        String[] words = safe(text, "").replaceAll("<[^>]+>", " ").trim().split("\\s+");
        String line = "";
        int y = firstBaseline;
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i < words.length; i++) {
            String candidate = line.length() == 0 ? words[i] : line + " " + words[i];
            if (line.length() > 0 && metrics.stringWidth(candidate) > maxWidth) {
                centered(g, line, centerX, y);
                y += lineHeight;
                line = words[i];
            } else {
                line = candidate;
            }
        }
        if (line.length() > 0) centered(g, line, centerX, y);
    }

    protected static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
