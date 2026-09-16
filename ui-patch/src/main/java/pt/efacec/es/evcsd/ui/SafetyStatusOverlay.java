package pt.efacec.es.evcsd.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Read-only charging-screen overlay for the native integration's safety state.
 * Hidden when no safety blocker is active. Reflection keeps the UI patch
 * build-independent from the native integration jar.
 */
final class SafetyStatusOverlay extends JPanel {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 64;

    private static final int STATE_NORMAL = 0;
    private static final int STATE_STARTUP = 1;
    private static final int STATE_KSEM_RECOVERY = 2;
    private static final int STATE_GRID_OVER_LIMIT = 3;
    private static final int STATE_GRID_HARD_TRIP = 4;
    private static final int STATE_GRID_HARD_TRIP_WAIT_SESSION = 5;
    private static final int STATE_LIMIT_MISMATCH = 6;
    private static final int STATE_CONFIGURATION = 7;
    private static final int STATE_SHUTDOWN = 8;

    private static final Color BACKGROUND = new Color(13, 15, 15);
    private static final Color PRIMARY = new Color(245, 245, 245);
    private static final Color SECONDARY = new Color(176, 179, 179);
    private static final Color DIVIDER = new Color(77, 81, 81);
    private static final Color YELLOW = new Color(255, 214, 0);
    private static final Color RED = new Color(166, 30, 30);

    private volatile Status status = Status.normal();
    private Timer timer;

    SafetyStatusOverlay() {
        setOpaque(true);
        setBackground(BACKGROUND);
        setSize(WIDTH, HEIGHT);
        setVisible(false);
    }

    synchronized void startMonitoring() {
        if (timer != null) return;
        timer = new Timer("qc45-safety-ui", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                final Status next = readStatus();
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        status = next;
                        setVisible(next.state != STATE_NORMAL);
                        repaint();
                    }
                });
            }
        }, 0L, 1000L);
    }

    synchronized void stopMonitoring() {
        if (timer == null) return;
        timer.cancel();
        timer.purge();
        timer = null;
        setVisible(false);
    }

    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (status.state == STATE_NORMAL) return;

        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(DIVIDER);
        g.drawLine(0, 0, WIDTH, 0);

        g.setColor(accent(status.state));
        g.fillOval(18, 13, 10, 10);
        g.setFont(new Font("Roboto", Font.BOLD, 13));
        g.drawString(title(status), 38, 23);

        g.setColor(PRIMARY);
        g.setFont(new Font("Roboto", Font.BOLD, 12));
        g.drawString(detail(status), 18, 47);
        g.dispose();
    }

    private static Color accent(int state) {
        if (state == STATE_CONFIGURATION || state == STATE_SHUTDOWN
                || state == STATE_LIMIT_MISMATCH || state == STATE_GRID_HARD_TRIP
                || state == STATE_GRID_HARD_TRIP_WAIT_SESSION) return RED;
        return YELLOW;
    }

    private static String title(Status value) {
        switch (value.state) {
            case STATE_STARTUP: return "SICHERER START";
            case STATE_KSEM_RECOVERY: return "KSEM-WIEDERANLAUF";
            case STATE_GRID_OVER_LIMIT: return "NETZSCHUTZ · WIEDERANLAUF";
            case STATE_GRID_HARD_TRIP: return "NETZSCHUTZ · HARD TRIP";
            case STATE_GRID_HARD_TRIP_WAIT_SESSION: return "NETZSCHUTZ · HARD TRIP";
            case STATE_LIMIT_MISMATCH: return "LEISTUNGSFEHLER";
            case STATE_CONFIGURATION: return "SICHERHEITSSPERRE";
            case STATE_SHUTDOWN: return "LADESTEUERUNG ABGESCHALTET";
            default: return "SICHERHEITSPAUSE";
        }
    }

    private static String detail(Status value) {
        switch (value.state) {
            case STATE_STARTUP:
                return "KSEM-Freigabe: " + value.progress + "/" + safeTotal(value, 5)
                    + " gültige Messungen";
            case STATE_KSEM_RECOVERY:
                return "Gültige KSEM-Messungen: " + value.progress + "/"
                    + safeTotal(value, 5);
            case STATE_GRID_OVER_LIMIT:
                return "Freigabe nach sicheren Messungen: " + value.progress + "/"
                    + safeTotal(value, 5);
            case STATE_GRID_HARD_TRIP: {
                int total = safeTotal(value, 60);
                int remaining = Math.max(0, total - value.progress);
                return "Auto-Reset: " + value.progress + "/" + total
                    + " s stabil · noch " + remaining + " s";
            }
            case STATE_GRID_HARD_TRIP_WAIT_SESSION:
                return "Resetzeit erfüllt · warte auf Ende der aktiven Session";
            case STATE_LIMIT_MISMATCH:
                return "Transaktion wird beendet · Freigabe sobald Anschluss inaktiv";
            case STATE_CONFIGURATION:
                return "Konfiguration ungültig · Neustart nach Korrektur erforderlich";
            case STATE_SHUTDOWN:
                return "Notladen bleibt aktiv bis zum nächsten Start";
            default:
                return "Freigabebedingung wird geprüft";
        }
    }

    private static int safeTotal(Status value, int fallback) {
        return value.total > 0 ? value.total : fallback;
    }

    private static Status readStatus() {
        try {
            Class<?> diagnostics = loadDiagnosticsClass();
            if (diagnostics == null) return Status.normal();
            Method capture = diagnostics.getDeclaredMethod("capture",
                Class.forName("de.rothner.qc45.ChargingLimitCoordinator"));
            capture.setAccessible(true);

            Object limits = findCoordinator();
            if (limits == null) return Status.normal();
            Object snapshot = capture.invoke(null, limits);
            if (snapshot == null) return Status.normal();
            return new Status(
                intField(snapshot, "state"),
                intField(snapshot, "progress"),
                intField(snapshot, "total"),
                intField(snapshot, "unit"));
        } catch (Throwable ignored) {
            return Status.normal();
        }
    }

    private static Class<?> loadDiagnosticsClass() {
        String name = "de.rothner.qc45.SafetyDiagnostics";
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try { return Class.forName(name, true, context); }
            catch (Throwable ignored) {}
        }
        try { return Class.forName(name); }
        catch (Throwable ignored) { return null; }
    }

    /**
     * The coordinator is owned by the native integration. Locate it from the
     * running guard thread, avoiding any dependency on Integration internals.
     */
    private static Object findCoordinator() {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (thread == null || !"QC45-ChargingLimitGuard".equals(thread.getName())) continue;
                Field field = findField(thread.getClass(), "limits");
                if (field == null) continue;
                field.setAccessible(true);
                return field.get(thread);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int intField(Object owner, String name) throws Exception {
        Field field = findField(owner.getClass(), name);
        if (field == null) return 0;
        field.setAccessible(true);
        Object value = field.get(owner);
        return value instanceof Number ? ((Number)value).intValue() : 0;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }

    private static final class Status {
        final int state;
        final int progress;
        final int total;
        final int unit;

        Status(int state, int progress, int total, int unit) {
            this.state = state;
            this.progress = progress;
            this.total = total;
            this.unit = unit;
        }

        static Status normal() { return new Status(STATE_NORMAL, 0, 0, 0); }
    }
}
