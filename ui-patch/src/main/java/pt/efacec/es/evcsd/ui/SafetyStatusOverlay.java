package pt.efacec.es.evcsd.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Read-only footer overlay backed by the native safety diagnostic Modbus. */
final class SafetyStatusOverlay extends JPanel {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 64;
    private static final int FIRST_REGISTER = 186;
    private static final int REGISTER_COUNT = 5;
    private static final int VERSION = 1;
    private static final int PORT = 1503;
    private static final AtomicInteger TRANSACTION = new AtomicInteger(1);

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
        g.drawString(title(status.state), 38, 23);
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

    private static String title(int state) {
        switch (state) {
            case STATE_STARTUP: return "SICHERER START";
            case STATE_KSEM_RECOVERY: return "KSEM-WIEDERANLAUF";
            case STATE_GRID_OVER_LIMIT: return "NETZSCHUTZ · WIEDERANLAUF";
            case STATE_GRID_HARD_TRIP:
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
                return "Auto-Reset: " + value.progress + "/" + total
                    + " s stabil · noch " + Math.max(0, total - value.progress) + " s";
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
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            socket.setSoTimeout(500);
            int tx = TRANSACTION.getAndIncrement() & 0xffff;
            byte[] request = new byte[12];
            putU16(request, 0, tx);
            putU16(request, 2, 0);
            putU16(request, 4, 6);
            request[6] = 1;
            request[7] = 3;
            putU16(request, 8, FIRST_REGISTER);
            putU16(request, 10, REGISTER_COUNT);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] header = new byte[7];
            readFully(in, header, 0, header.length);
            int length = u16(header, 4);
            if (u16(header, 0) != tx || u16(header, 2) != 0 || length != 13)
                return Status.normal();
            byte[] pdu = new byte[length - 1];
            readFully(in, pdu, 0, pdu.length);
            if ((pdu[0] & 0xff) != 3 || (pdu[1] & 0xff) != REGISTER_COUNT * 2)
                return Status.normal();
            int[] value = new int[REGISTER_COUNT];
            for (int i = 0; i < REGISTER_COUNT; i++) value[i] = u16(pdu, 2 + i * 2);
            if (value[0] != VERSION) return Status.normal();
            return new Status(value[1], value[2], value[3], value[4]);
        } catch (Throwable ignored) {
            return Status.normal();
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static void readFully(InputStream in, byte[] data, int offset, int length)
            throws Exception {
        int done = 0;
        while (done < length) {
            int read = in.read(data, offset + done, length - done);
            if (read < 0) throw new IllegalStateException("Unexpected Modbus EOF");
            done += read;
        }
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte)((value >>> 8) & 0xff);
        data[offset + 1] = (byte)(value & 0xff);
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
