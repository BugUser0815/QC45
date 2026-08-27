package pt.efacec.es.evcsd.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import pt.efacec.es.evcsd.ui.info.ChargeInfo;

/**
 * 640x480 charging monitor for the QC45.
 *
 * The active-session page uses the same reduced dark design language as the
 * other patched operator pages. Charging values are read exclusively from the
 * native integration's versioned AC/DC Modbus block 126-145, with a compatible
 * fallback to the legacy DC block 120-125. The buffer-battery SoC remains an
 * evcc value because it is not part of the QC45/EVCSD state.
 */
public class WaitingForCardChargingTimer extends JPanel implements ActionPanel<ChargeInfo> {
    private static final Logger LOGGER = Logger.getLogger(WaitingForCardChargingTimer.class.getName());
    private static final AtomicInteger TRANSACTION = new AtomicInteger(1);
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int MODBUS_FIRST_REGISTER = 120;
    private static final int MODBUS_REGISTER_COUNT = 6;

    private static final Color BACKGROUND = new Color(13, 15, 15);
    private static final Color PANEL = new Color(29, 31, 31);
    private static final Color PANEL_LIGHT = new Color(43, 46, 46);
    private static final Color PRIMARY = new Color(245, 245, 245);
    private static final Color SECONDARY = new Color(176, 179, 179);
    private static final Color DIVIDER = new Color(77, 81, 81);
    private static final Color TRACK = new Color(52, 54, 54);
    private static final Color YELLOW = new Color(255, 214, 0);
    private static final Color READY_GREEN = new Color(72, 184, 106);
    private static final Color STOP_RED = new Color(166, 30, 30);
    private static final String SGS_LOGO_PATH = "/pt/efacec/es/evcsd/ui/sgs-logo.png";
    private static final BufferedImage SGS_LOGO = loadLogo();

    private static String languagef = "";
    private static int modusf;
    private static ArrayList listOfImagesToDisplay = new ArrayList();

    private boolean usescreditcardLocal;
    private ImageIcon icone;
    int barValue;
    String imageID;
    private Timer timer;
    boolean efacecScreensON;
    private JLabel jLabel1;
    private JLabel jLabelText;

    private Integer lastBatterySoc;
    private long lastBatterySocFetch;
    private int[] lastChargingData;
    private long lastChargingDataFetch;
    private LoadBalancingTelemetry lastBalancingData;
    private long lastBalancingDataFetch;
    private final int displayConnector;

    public WaitingForCardChargingTimer() {
        this.displayConnector = 0;
        this.usescreditcardLocal = false;
        this.barValue = 0;
        this.imageID = "1";
        this.efacecScreensON = true;
        initComponents();
        setSize(WIDTH, HEIGHT);
        renderAndSet(false);
        ensureScreenTimer();
        setVisible(true);
    }

    public WaitingForCardChargingTimer(String language, int modus,
                                       boolean usesCreditCard, boolean efacecScreensVisible) {
        this(language, modus, usesCreditCard, efacecScreensVisible, 0);
    }

    protected WaitingForCardChargingTimer(String language, int modus,
                                           boolean usesCreditCard, boolean efacecScreensVisible,
                                           int connector) {
        this.displayConnector = connector;
        this.usescreditcardLocal = usesCreditCard;
        this.efacecScreensON = efacecScreensVisible;
        languagef = language == null ? "" : language;
        modusf = modus;
        this.barValue = 0;
        this.imageID = "1";
        initComponents();
        setSize(WIDTH, HEIGHT);
        ensureScreenTimer();
        setVisible(true);
    }

    public void start() {
        setVisible(true);
        ensureScreenTimer();
    }

    public void stop() {
        stopScreenTimer();
    }

    public void setInfo(ChargeInfo info) {
        // Values deliberately come from the native integration's Modbus UI block.
    }

    private synchronized void ensureScreenTimer() {
        if (timer != null) return;
        timer = new Timer("qc45-charge-screen", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                final ImageIcon next = renderChargePage();
                if (next == null) return;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        icone = next;
                        jLabel1.setIcon(next);
                        jLabel1.repaint();
                    }
                });
            }
        }, 0L, 1000L);
    }

    private synchronized void stopScreenTimer() {
        if (timer == null) return;
        timer.cancel();
        timer.purge();
        timer = null;
    }

    public Timer getTimer() {
        return timer;
    }

    public void setTimer(Timer timer) {
        this.timer = timer;
    }

    private void adjustTextLabel(boolean ignored, int value) {
        // Retained for binary compatibility with the original class.
    }

    private void initComponents() {
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setLayout(null);

        jLabelText = new JLabel();
        jLabelText.setVisible(false);
        add(jLabelText);

        jLabel1 = new JLabel();
        jLabel1.setHorizontalAlignment(JLabel.CENTER);
        jLabel1.setBounds(0, 0, WIDTH, HEIGHT);
        add(jLabel1);
    }

    private void renderAndSet(boolean ignored) {
        ImageIcon rendered = renderChargePage();
        if (rendered == null) return;
        icone = rendered;
        jLabel1.setIcon(rendered);
    }

    private ImageIcon renderChargePage() {
        try {
            final long now = System.currentTimeMillis();
            refreshChargingData(now);
            if (!isChargingSession()) return renderIdlePage();
            if (showStopConfirmation()) return renderStopConfirmationPage();
            refreshBufferSoc(now);

            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            textAA(g);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            drawHeader(g);
            if (freshBalancingData(now)) {
                drawLoadBalancingValues(g, lastBalancingData);
                drawLoadBalancingSessionValues(g, lastBalancingData);
                drawLoadBalancingFooter(g, lastBalancingData);
            } else {
                drawMainValues(g);
                drawSessionValues(g);
                drawFooter(g);
            }

            g.dispose();
            return new ImageIcon(image);
        } catch (Throwable error) {
            LOGGER.warning("charge page: " + error);
            return null;
        }
    }

    /**
     * EVCSD instantiates this class directly while waiting for card interaction
     * during an active session. The normal charging panels subclass it and keep
     * the full charging monitor.
     */
    private boolean showStopConfirmation() {
        return getClass() == WaitingForCardChargingTimer.class;
    }

    private boolean isChargingSession() {
        if (freshBalancingData(System.currentTimeMillis())) {
            if (lastBalancingData.dcSession() || lastBalancingData.acSession()) return true;
        }
        int state = AlpitronicSessionState.get();
        if (state != AlpitronicSessionState.UNKNOWN) return state == AlpitronicSessionState.CHARGING;
        ChargeInfo latest = ChargeInfo.getLatest();
        if (latest != null) return latest.isCharging();
        return value(0, 0) > 0 || (value(1, 0) > 0 && value(3, 0) > 0);
    }

    private ImageIcon renderStopConfirmationPage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        textAA(g);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        drawHeader(g);
        drawStopSoftKey(g);

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 30));
        centered(g, "KARTE ERKANNT", 360, 100);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 18));
        centered(g, "Ladevorgang beenden?", 360, 130);

        drawCardSymbol(g, 360, 232);

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 19));
        centered(g, "Zum Abbrechen oben links drücken.", 360, 329);

        LoadBalancingTelemetry balancing = freshBalancingData(System.currentTimeMillis())
            ? lastBalancingData : null;
        int actualKw = balancing == null ? value(0, -1) : balancing.totalActualKw();
        int vehicleSoc = balancing == null ? value(2, -1) : balancing.dcSocPct;
        int seconds = value(3, -1);
        if (balancing != null) seconds = primarySeconds(balancing);
        String power = actualKw < 0 ? "-- kW" : actualKw + " kW";
        String soc = vehicleSoc < 0 ? "-- %" : vehicleSoc + " %";
        String duration = seconds < 0 ? "--:--:--" : formatDuration(seconds);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 17));
        centered(g, power + "   ·   " + soc + "   ·   " + duration, 360, 378);

        g.setColor(DIVIDER);
        g.drawLine(0, 416, WIDTH, 416);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 12));
        centered(g, "Die normale Ladeanzeige bleibt im Hintergrund aktiv.", 320, 452);

        g.dispose();
        return new ImageIcon(image);
    }

    private void drawStopSoftKey(Graphics2D g) {
        int x = 18;
        int y = 128;
        int width = 190;
        int height = 62;
        int centerY = y + height / 2;

        g.setColor(STOP_RED);
        g.fillRect(x, y, width, height);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 13));
        centered(g, "LADEVORGANG", x + width / 2, y + 26);
        g.setFont(font(Font.BOLD, 15));
        centered(g, "ABBRECHEN", x + width / 2, y + 48);

        int outerX = 5;
        int innerX = 14;
        g.setColor(STOP_RED);
        g.setStroke(new BasicStroke(4.0f));
        g.drawLine(innerX, centerY - 10, outerX, centerY);
        g.drawLine(outerX, centerY, innerX, centerY + 10);
    }

    private ImageIcon renderIdlePage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        textAA(g);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 18));
        g.drawString("QC45", 18, 28);
        g.setColor(READY_GREEN);
        g.fillOval(157, 17, 10, 10);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 17));
        g.drawString("BEREIT", 176, 29);
        g.setFont(font(Font.BOLD, 18));
        rightAligned(g, new SimpleDateFormat("HH:mm").format(new Date()), 622, 28);
        g.setColor(DIVIDER);
        g.drawLine(0, 42, WIDTH, 42);

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 32));
        centered(g, "LADEN STARTEN", 320, 94);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 15));
        centered(g, "Fahrzeug verbinden und authentifizieren", 320, 120);

        drawSgsLogo(g);

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 24));
        centered(g, "Karte vorhalten oder App benutzen.", 320, 332);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 15));
        centered(g, "Der verfügbare Anschluss wird automatisch erkannt.", 320, 364);

        g.setColor(YELLOW);
        g.setFont(font(Font.BOLD, 14));
        centered(g, "DYNAMISCHES LOAD BALANCING  ·  AC + DC", 320, 400);

        g.dispose();
        return new ImageIcon(image);
    }

    private void drawSgsLogo(Graphics2D g) {
        if (SGS_LOGO != null) {
            g.drawImage(SGS_LOGO, 110, 141, null);
            return;
        }

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 26));
        centered(g, "SGS Elektrotechnik GbR", 320, 190);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 17));
        centered(g, "Alexander & Marion Rothner", 320, 218);
    }

    private static BufferedImage loadLogo() {
        InputStream in = null;
        try {
            in = WaitingForCardChargingTimer.class.getResourceAsStream(SGS_LOGO_PATH);
            if (in == null) {
                ClassLoader loader = WaitingForCardChargingTimer.class.getClassLoader();
                if (loader != null) {
                    in = loader.getResourceAsStream("pt/efacec/es/evcsd/ui/sgs-logo.png");
                }
            }
            return in == null ? null : ImageIO.read(in);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void drawCardSymbol(Graphics2D g, int centerX, int centerY) {
        g.setColor(YELLOW);
        g.setStroke(new BasicStroke(4.0f));
        g.drawRoundRect(centerX - 58, centerY - 38, 78, 76, 8, 8);
        g.drawArc(centerX + 3, centerY - 28, 42, 56, -55, 110);
        g.drawArc(centerX + 13, centerY - 19, 28, 38, -55, 110);
        g.drawArc(centerX + 23, centerY - 10, 14, 20, -55, 110);
    }

    private void drawHeader(Graphics2D g) {
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 18));
        g.drawString("QC45", 18, 28);

        long now = System.currentTimeMillis();
        LoadBalancingTelemetry balancing = freshBalancingData(now) ? lastBalancingData : null;
        int actualKw = value(0, -1);
        boolean hasFreshPower = balancing != null
            ? balancing.totalActualKw() > 0
            : actualKw > 0 && lastChargingData != null && now - lastChargingDataFetch <= 2500L;
        boolean blocked = balancing != null && balancing.blocked();
        g.setColor(blocked ? STOP_RED : hasFreshPower ? YELLOW : READY_GREEN);
        g.fillOval(157, 17, 10, 10);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 16));
        g.drawString(balancing == null
            ? hasFreshPower ? "LÄDT" : "LADEBEREIT"
            : headerStatus(balancing), 176, 29);

        g.setFont(font(Font.BOLD, 18));
        rightAligned(g, new SimpleDateFormat("HH:mm").format(new Date()), 622, 28);
        g.setColor(DIVIDER);
        g.drawLine(0, 42, WIDTH, 42);
    }

    private String headerStatus(LoadBalancingTelemetry data) {
        if (data.has(LoadBalancingTelemetry.FLAG_SHUTDOWN)) return "ABGESCHALTET";
        if (data.has(LoadBalancingTelemetry.FLAG_LIMIT_MISMATCH)) return "LEISTUNGSFEHLER";
        if (data.has(LoadBalancingTelemetry.FLAG_CONFIGURATION)) return "KONFIGURATION";
        if (data.has(LoadBalancingTelemetry.FLAG_FAILBACK)) return "NETZSCHUTZ";
        if (data.has(LoadBalancingTelemetry.FLAG_LOAD_METER)) return "KSEM WARTET";
        if (data.has(LoadBalancingTelemetry.FLAG_STARTUP)) return "SICHERER START";
        if (data.blocked()) return "LADEPAUSE";
        if (data.dcActualKw > 0 && data.acActualKw > 0) return "AC + DC LÄDT";
        if (data.dcActualKw > 0) return "DC LÄDT";
        if (data.acActualKw > 0) return "AC LÄDT";
        return "LADEBEREIT";
    }

    private void drawLoadBalancingValues(Graphics2D g, LoadBalancingTelemetry data) {
        drawLoadCard(g, 18, "DC", dcConnectorName(data.activeDcConnector),
            data.dcSession(), data.dcActualKw, data.dcRequestedKw,
            data.dcGridKw, data.dcStageCapKw, data.dcEffectiveKw,
            data.dcSocPct, data.dcSeconds, data);
        drawLoadCard(g, 330, "AC", "TYPE 2",
            data.acSession(), data.acActualKw, data.acRequestedKw,
            data.acGridKw, data.acStageCapKw, data.acEffectiveKw,
            -1, data.acSeconds, data);
    }

    private void drawLoadCard(Graphics2D g, int x, String channel, String connector,
                              boolean session, int actualKw, int requestedKw,
                              int gridKw, int stageCapKw, int effectiveKw,
                              int socPct, int seconds, LoadBalancingTelemetry data) {
        int y = 60;
        int width = 292;
        int height = 188;
        Color accent = data.blocked() ? STOP_RED
            : actualKw > 0 ? YELLOW : session && effectiveKw > 0 ? READY_GREEN : DIVIDER;
        drawPanel(g, x, y, width, height, accent);

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 17));
        g.drawString(channel, x + 20, y + 29);
        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 11));
        g.drawString("·  " + connector, x + 48, y + 29);

        if (socPct >= 0 && data.dcSession()) {
            g.setColor(SECONDARY);
            g.setFont(font(Font.BOLD, 11));
            rightAligned(g, "FZ-SOC  " + socPct + " %", x + width - 18, y + 29);
        }

        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 11));
        g.drawString("IST", x + 20, y + 55);
        drawLargeValue(g, Integer.toString(Math.max(0, actualKw)), "kW",
            x + width / 2, y + 115, 52, 25);

        int barX = x + 20;
        int barY = y + 128;
        int barWidth = width - 40;
        g.setColor(TRACK);
        g.fillRect(barX, barY, barWidth, 7);
        if (effectiveKw > 0 && actualKw > 0) {
            int filled = (int)Math.round(barWidth
                * Math.min(1.0d, actualKw / (double)effectiveKw));
            g.setColor(YELLOW);
            g.fillRect(barX, barY, filled, 7);
        }

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 12));
        g.drawString("FREIGABE  " + effectiveKw + " kW", x + 20, y + 155);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 11));
        rightAligned(g, "EVCC " + requestedKw + "  ·  NETZ "
            + gridKw, x + width - 18, y + 155);

        g.setColor(accent);
        g.setFont(font(Font.BOLD, 11));
        g.drawString(loadState(session, actualKw, requestedKw, gridKw,
            stageCapKw, effectiveKw, data), x + 20, y + 177);
        if (session) {
            g.setColor(SECONDARY);
            g.setFont(font(Font.PLAIN, 10));
            rightAligned(g, formatDuration(seconds), x + width - 18, y + 177);
        }
    }

    private String loadState(boolean session, int actualKw, int requestedKw,
                             int gridKw, int stageCapKw, int effectiveKw,
                             LoadBalancingTelemetry data) {
        if (data.has(LoadBalancingTelemetry.FLAG_SHUTDOWN)) return "ABGESCHALTET";
        if (data.has(LoadBalancingTelemetry.FLAG_LIMIT_MISMATCH)) return "NOTABSCHALTUNG";
        if (data.has(LoadBalancingTelemetry.FLAG_CONFIGURATION)) return "KONFIGURATION PRÜFEN";
        if (data.has(LoadBalancingTelemetry.FLAG_FAILBACK)) return "NETZSCHUTZ AKTIV";
        if (data.has(LoadBalancingTelemetry.FLAG_LOAD_METER)) return "KSEM-MESSUNG FEHLT";
        if (data.has(LoadBalancingTelemetry.FLAG_STARTUP)) return "SICHERER START";
        if (data.blocked()) return "GESPERRT";
        if (!session) return "KEINE AKTIVE SESSION";
        if (requestedKw <= 0) return "EVCC-PAUSE";
        if (effectiveKw <= 0 || gridKw <= 0) return "NETZ-PAUSE";
        if (stageCapKw < gridKw) return "SCHUTZKAPPE AKTIV";
        if (actualKw > 0 && data.demandTransfer()) return "LÄDT · BEDARFSGERECHT";
        if (actualKw > 0) return "LÄDT";
        return "FREIGEGEBEN";
    }

    private String dcConnectorName(int connector) {
        if (connector == 1) return "CHAdeMO";
        if (connector == 2) return "CCS";
        return "BEREIT";
    }

    private void drawLoadBalancingSessionValues(Graphics2D g, LoadBalancingTelemetry data) {
        String energyLabel = data.dcSession() && data.acSession() ? "GESAMTENERGIE" : "ENERGIE";
        String timeLabel = displayConnector == 3 ? "AC-LADEZEIT"
            : displayConnector == 1 || displayConnector == 2 ? "DC-LADEZEIT" : "LADEZEIT";

        drawCompactMetricPanel(g, 18, "GESAMTLEISTUNG",
            data.totalActualKw() + " kW", "AC + DC");
        drawCompactMetricPanel(g, 173, energyLabel,
            formatEnergy(data.totalEnergyWh()), "SEIT LADEBEGINN");
        drawCompactMetricPanel(g, 328, timeLabel,
            formatDuration(primarySeconds(data)), "STUNDEN : MIN : SEK");
        drawCompactMetricPanel(g, 483, "PUFFERBATTERIE",
            lastBatterySoc == null ? "-- %" : lastBatterySoc + " %", "STATION");
    }

    private void drawCompactMetricPanel(Graphics2D g, int x, String label,
                                        String value, String detail) {
        int y = 266;
        int width = 139;
        int height = 126;
        drawPanel(g, x, y, width, height, DIVIDER);
        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, label.length() > 12 ? 9 : 10));
        centered(g, label, x + width / 2, y + 27);
        g.setColor(PRIMARY);
        int size = value.length() > 8 ? 21 : 25;
        g.setFont(font(Font.PLAIN, size));
        centered(g, value, x + width / 2, y + 73);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 8));
        centered(g, detail, x + width / 2, y + 104);
    }

    private int primarySeconds(LoadBalancingTelemetry data) {
        if (displayConnector == 3) return data.acSeconds;
        if (displayConnector == 1 || displayConnector == 2) return data.dcSeconds;
        return Math.max(data.dcSeconds, data.acSeconds);
    }

    private void drawLoadBalancingFooter(Graphics2D g, LoadBalancingTelemetry data) {
        g.setColor(DIVIDER);
        g.drawLine(0, 416, WIDTH, 416);
        g.setColor(data.blocked() ? STOP_RED : SECONDARY);
        g.setFont(font(data.blocked() ? Font.BOLD : Font.PLAIN, 11));
        centered(g, loadBalancingExplanation(data), 320, 438);

        g.setColor(STOP_RED);
        g.fillOval(104, 450, 8, 8);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 12));
        g.drawString("Zum Beenden Karte vorhalten oder App benutzen.", 122, 459);
    }

    private String loadBalancingExplanation(LoadBalancingTelemetry data) {
        if (data.has(LoadBalancingTelemetry.FLAG_LIMIT_MISMATCH))
            return "0-kW-Freigabe verletzt: Transaktion gestoppt; Neustart erforderlich.";
        if (data.has(LoadBalancingTelemetry.FLAG_CONFIGURATION))
            return "Sicherheitskonfiguration ungültig: AC und DC bleiben auf 0 kW.";
        if (data.has(LoadBalancingTelemetry.FLAG_FAILBACK))
            return "Netzschutz aktiv: AC und DC sind auf 0 kW begrenzt.";
        if (data.has(LoadBalancingTelemetry.FLAG_LOAD_METER))
            return "KSEM-Messung fehlt: AC und DC bleiben sicher pausiert.";
        if (data.has(LoadBalancingTelemetry.FLAG_STARTUP))
            return "Freigabe nach fünf gültigen KSEM-Messungen.";
        if (data.has(LoadBalancingTelemetry.FLAG_SHUTDOWN))
            return "Ladesteuerung ist sicher abgeschaltet.";
        if (data.has(LoadBalancingTelemetry.FLAG_STAGE_LIMIT))
            return "Die Schutzkappe reduziert die AC/DC-Freigabe am Netzlimit.";
        if (data.demandTransfer())
            return "Ungenutzte Leistung wird bedarfsgerecht zwischen AC und DC verteilt.";
        if (data.dcSession() && data.acSession())
            return "AC und DC teilen das sichere Netzbudget gleichberechtigt.";
        return "Der LoadManager hält die zulässige Netzlast ein.";
    }

    private void drawMainValues(Graphics2D g) {
        int actualKw = value(0, -1);
        int targetKw = value(1, -1);
        int vehicleSoc = value(2, -1);

        drawPanel(g, 18, 60, 292, 186, actualKw > 0 ? YELLOW : DIVIDER);
        drawPanel(g, 330, 60, 292, 186, vehicleSoc >= 0 ? YELLOW : DIVIDER);

        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 13));
        g.drawString("LADELEISTUNG", 38, 90);
        g.drawString("FAHRZEUG", 350, 90);

        drawLargeValue(g, actualKw < 0 ? "--" : Integer.toString(actualKw), "kW", 164, 170, 66, 34);
        drawLargeValue(g, vehicleSoc < 0 ? "--" : Integer.toString(vehicleSoc), "%", 476, 170, 70, 38);

        drawPowerBar(g, actualKw, targetKw);
        drawSocBar(g, vehicleSoc);

        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 13));
        g.drawString(targetKw < 0 ? "SOLLLEISTUNG  -- kW" : "SOLLLEISTUNG  " + targetKw + " kW", 38, 231);
        g.drawString("FAHRZEUG-SOC", 350, 231);
    }

    private void drawPowerBar(Graphics2D g, int actualKw, int targetKw) {
        int x = 38;
        int y = 197;
        int width = 252;
        int height = 8;
        g.setColor(TRACK);
        g.fillRect(x, y, width, height);
        if (actualKw < 0 || targetKw <= 0) return;
        int filled = (int)Math.round(width * Math.min(1.0d, actualKw / (double)targetKw));
        g.setColor(YELLOW);
        g.fillRect(x, y, filled, height);
    }

    private void drawSocBar(Graphics2D g, int soc) {
        int x = 350;
        int y = 197;
        int width = 252;
        int height = 8;
        int segments = 10;
        int gap = 3;
        int segmentWidth = (width - gap * (segments - 1)) / segments;
        int filled = soc < 0 ? 0 : (int)Math.ceil(clamp(soc, 0, 100) * segments / 100.0d);
        for (int i = 0; i < segments; i++) {
            g.setColor(i < filled ? YELLOW : TRACK);
            int sx = x + i * (segmentWidth + gap);
            int sw = i == segments - 1 ? width - (sx - x) : segmentWidth;
            g.fillRect(sx, y, sw, height);
        }
    }

    private void drawSessionValues(Graphics2D g) {
        int seconds = value(3, -1);
        long energyWh = energyWh();

        drawMetricPanel(g, 18, 264, 190, "ENERGIE",
            energyWh < 0L ? "-- kWh" : formatEnergy(energyWh), "SEIT LADEBEGINN");
        drawMetricPanel(g, 225, 264, 190, "LADEZEIT",
            seconds < 0 ? "--:--:--" : formatDuration(seconds), "STUNDEN : MIN : SEK");
        drawMetricPanel(g, 432, 264, 190, "PUFFERBATTERIE",
            lastBatterySoc == null ? "-- %" : lastBatterySoc + " %", "STATION");

        if (lastBatterySoc != null) {
            int x = 452;
            int y = 379;
            int width = 150;
            g.setColor(TRACK);
            g.fillRect(x, y, width, 6);
            g.setColor(SECONDARY);
            g.fillRect(x, y, (int)Math.round(width * lastBatterySoc.intValue() / 100.0d), 6);
        }
    }

    private void drawMetricPanel(Graphics2D g, int x, int y, int width,
                                 String label, String value, String detail) {
        drawPanel(g, x, y, width, 128, DIVIDER);
        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 12));
        centered(g, label, x + width / 2, y + 28);

        g.setColor(PRIMARY);
        int valueSize = value != null && value.length() > 9 ? 25 : 30;
        g.setFont(font(Font.PLAIN, valueSize));
        centered(g, value, x + width / 2, y + 76);

        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 10));
        centered(g, detail, x + width / 2, y + 105);
    }

    private void drawPanel(Graphics2D g, int x, int y, int width, int height, Color accent) {
        g.setColor(PANEL);
        g.fillRect(x, y, width, height);
        g.setColor(DIVIDER);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRect(x, y, width, height);
        g.setColor(accent == null ? DIVIDER : accent);
        g.fillRect(x, y, 4, height);
        g.setColor(PANEL_LIGHT);
        g.fillRect(x + 4, y, width - 4, 2);
    }

    private void drawFooter(Graphics2D g) {
        g.setColor(DIVIDER);
        g.drawLine(0, 416, WIDTH, 416);

        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 11));
        centered(g, "Das Fahrzeug bestimmt die mögliche Ladeleistung.", 320, 438);

        g.setColor(STOP_RED);
        g.fillOval(104, 450, 8, 8);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 12));
        g.drawString("Zum Beenden Karte vorhalten oder App benutzen.", 122, 459);
    }

    private void drawLargeValue(Graphics2D g, String value, String unit,
                                int centerX, int baseline, int valueSize, int unitSize) {
        Font valueFont = font(Font.BOLD, valueSize);
        Font unitFont = font(Font.BOLD, unitSize);
        g.setFont(valueFont);
        int valueWidth = g.getFontMetrics().stringWidth(value);
        g.setFont(unitFont);
        int unitWidth = g.getFontMetrics().stringWidth(unit);
        int gap = 9;
        int x = centerX - (valueWidth + gap + unitWidth) / 2;

        g.setColor(PRIMARY);
        g.setFont(valueFont);
        g.drawString(value, x, baseline);
        g.setFont(unitFont);
        g.drawString(unit, x + valueWidth + gap, baseline);
    }

    private void refreshChargingData(long now) {
        try {
            int[] values = readRegisters(LoadBalancingTelemetry.FIRST_REGISTER,
                LoadBalancingTelemetry.REGISTER_COUNT);
            lastBalancingData = LoadBalancingTelemetry.decode(values);
            lastBalancingDataFetch = now;
            return;
        } catch (Throwable ignored) {
            if (now - lastBalancingDataFetch > 5000L) lastBalancingData = null;
        }

        try {
            int[] values = readRegisters(MODBUS_FIRST_REGISTER, MODBUS_REGISTER_COUNT);
            lastChargingData = values;
            lastChargingDataFetch = now;
        } catch (Throwable ignored) {
            if (now - lastChargingDataFetch > 5000L) lastChargingData = null;
        }
    }

    private boolean freshBalancingData(long now) {
        return lastBalancingData != null && now - lastBalancingDataFetch <= 2500L;
    }

    private void refreshBufferSoc(long now) {
        if (lastBatterySoc != null && now - lastBatterySocFetch < 5000L) return;
        try {
            Double soc = evccNumber(".battery.soc");
            if (soc != null) {
                lastBatterySoc = Integer.valueOf(clamp((int)Math.round(soc.doubleValue()), 0, 100));
                lastBatterySocFetch = now;
            }
        } catch (Throwable ignored) {
            // Retain the last valid SoC; it is secondary information.
        }
    }

    private int value(int index, int fallback) {
        return lastChargingData == null || index < 0 || index >= lastChargingData.length
            ? fallback : lastChargingData[index];
    }

    private long energyWh() {
        if (lastChargingData == null || lastChargingData.length < 6) return -1L;
        return (((long)lastChargingData[4] & 0xffffL) << 16)
            | ((long)lastChargingData[5] & 0xffffL);
    }

    private int[] readRegisters(int address, int count) throws Exception {
        String host = System.getProperty("qc45.modbus.host", "127.0.0.1");
        int port = Integer.getInteger("qc45.modbus.port", 1502).intValue();
        int tx = TRANSACTION.getAndIncrement() & 0xffff;
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 500);
            socket.setSoTimeout(800);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] request = new byte[12];
            putU16(request, 0, tx);
            putU16(request, 2, 0);
            putU16(request, 4, 6);
            request[6] = 1;
            request[7] = 3;
            putU16(request, 8, address);
            putU16(request, 10, count);
            out.write(request);
            out.flush();

            byte[] header = new byte[7];
            readFully(in, header, 0, header.length);
            int length = u16(header, 4);
            if (u16(header, 0) != tx || u16(header, 2) != 0 || length < 3 || length > 260)
                throw new IllegalStateException("Invalid Modbus response header");

            byte[] pdu = new byte[length - 1];
            readFully(in, pdu, 0, pdu.length);
            if ((pdu[0] & 0xff) != 3 || (pdu[1] & 0xff) != count * 2 || pdu.length != 2 + count * 2)
                throw new IllegalStateException("Invalid Modbus register response");

            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = u16(pdu, 2 + i * 2);
            return values;
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private Double evccNumber(String jq) throws Exception {
        String base = System.getProperty("evcc.url", "http://10.0.0.179:7070");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + "/api/state?jq=" + URLEncoder.encode(jq, "UTF-8"));
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(1200);
        connection.setReadTimeout(1200);
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        try {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            String number = response.toString().replaceAll("[^0-9.,-]", "").replace(',', '.');
            return number.length() == 0 ? null : Double.valueOf(number);
        } finally {
            reader.close();
            connection.disconnect();
        }
    }

    private static String formatEnergy(long wh) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.GERMANY);
        DecimalFormat format = new DecimalFormat("0.0", symbols);
        format.setGroupingUsed(false);
        return format.format(wh / 1000.0d) + " kWh";
    }

    private static String formatDuration(int totalSeconds) {
        int seconds = Math.max(0, totalSeconds);
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remainder = seconds % 60;
        return twoDigits(hours) + ":" + twoDigits(minutes) + ":" + twoDigits(remainder);
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static Font font(int style, int size) {
        return new Font("Roboto", style, size);
    }

    private static void centered(Graphics2D g, String text, int centerX, int baseline) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, centerX - metrics.stringWidth(text) / 2, baseline);
    }

    private static void rightAligned(Graphics2D g, String text, int rightX, int baseline) {
        g.drawString(text, rightX - g.getFontMetrics().stringWidth(text), baseline);
    }

    private static void textAA(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static void putU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte)((value >>> 8) & 0xff);
        bytes[offset + 1] = (byte)(value & 0xff);
    }

    private static void readFully(InputStream in, byte[] bytes, int offset, int length) throws Exception {
        int done = 0;
        while (done < length) {
            int read = in.read(bytes, offset + done, length - done);
            if (read < 0) throw new EOFException();
            done += read;
        }
    }
}
