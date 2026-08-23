package pt.efacec.es.evcsd.ui;

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
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import pt.efacec.es.evcsd.ui.info.ChargeInfo;

/**
 * Minimal 640x480 charging monitor for the QC45 screen saver.
 *
 * The charging values are read exclusively from the native integration's
 * documented Modbus block 120-125. The buffer-battery SoC remains an evcc
 * value because it is not part of the QC45/EVCSD state.
 */
public class WaitingForCardChargingTimer extends JPanel implements ActionPanel<ChargeInfo> {
    private static final Logger LOGGER = Logger.getLogger(WaitingForCardChargingTimer.class.getName());
    private static final AtomicInteger TRANSACTION = new AtomicInteger(1);
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int MODBUS_FIRST_REGISTER = 120;
    private static final int MODBUS_REGISTER_COUNT = 6;

    private static final Color BACKGROUND = new Color(13, 15, 15);
    private static final Color PRIMARY = new Color(245, 245, 245);
    private static final Color SECONDARY = new Color(180, 180, 180);
    private static final Color DIVIDER = new Color(82, 86, 86);
    private static final Color TRACK = new Color(52, 54, 54);
    private static final Color YELLOW = new Color(255, 214, 0);
    private static final Color STOP_RED = new Color(166, 30, 30);

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

    public WaitingForCardChargingTimer() {
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
        // Values deliberately come from Modbus registers 120-125.
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
            refreshBufferSoc(now);

            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            textAA(g);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            drawGrid(g);
            drawHeader(g);
            drawMainValues(g);
            drawSessionValues(g);
            drawFooter(g);

            g.dispose();
            return new ImageIcon(image);
        } catch (Throwable error) {
            LOGGER.warning("charge page: " + error);
            return null;
        }
    }

    private boolean isChargingSession() {
        int state = AlpitronicSessionState.get();
        if (state != AlpitronicSessionState.UNKNOWN) return state == AlpitronicSessionState.CHARGING;
        ChargeInfo latest = ChargeInfo.getLatest();
        if (latest != null) return latest.isCharging();
        return value(0, 0) > 0 || (value(1, 0) > 0 && value(3, 0) > 0);
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
        g.setColor(new Color(72, 184, 106));
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

        drawCardSymbol(g, 320, 217);

        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 24));
        centered(g, "Karte vorhalten oder App benutzen.", 320, 332);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 15));
        centered(g, "Der verfügbare Anschluss wird automatisch erkannt.", 320, 364);
        g.setColor(DIVIDER);
        g.drawLine(0, 416, WIDTH, 416);
        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 12));
        centered(g, "Die Ladeleistung wird vom Fahrzeug bestimmt.", 320, 452);
        g.dispose();
        return new ImageIcon(image);
    }

    private void drawCardSymbol(Graphics2D g, int centerX, int centerY) {
        g.setColor(YELLOW);
        g.setStroke(new java.awt.BasicStroke(4.0f));
        g.drawRoundRect(centerX - 58, centerY - 38, 78, 76, 8, 8);
        g.drawArc(centerX + 3, centerY - 28, 42, 56, -55, 110);
        g.drawArc(centerX + 13, centerY - 19, 28, 38, -55, 110);
        g.drawArc(centerX + 23, centerY - 10, 14, 20, -55, 110);
    }

    private void drawGrid(Graphics2D g) {
        g.setColor(DIVIDER);
        g.drawLine(0, 42, WIDTH, 42);
        g.drawLine(320, 42, 320, 308);
        g.drawLine(0, 308, WIDTH, 308);
        g.drawLine(210, 308, 210, 416);
        g.drawLine(430, 308, 430, 416);
        g.drawLine(0, 416, WIDTH, 416);
    }

    private void drawHeader(Graphics2D g) {
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 18));
        g.drawString("QC45", 18, 28);

        g.setColor(YELLOW);
        g.fillOval(157, 17, 10, 10);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 17));
        g.drawString("LÄDT", 176, 29);

        g.setFont(font(Font.BOLD, 18));
        rightAligned(g, new SimpleDateFormat("HH:mm").format(new Date()), 622, 28);
    }

    private void drawMainValues(Graphics2D g) {
        int actualKw = value(0, -1);
        int targetKw = value(1, -1);
        int vehicleSoc = value(2, -1);

        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 14));
        g.drawString("LADELEISTUNG", 30, 87);
        g.drawString("FAHRZEUG", 355, 87);

        drawLargeValue(g, actualKw < 0 ? "--" : Integer.toString(actualKw), "kW", 160, 207, 78, 43);
        drawLargeValue(g, vehicleSoc < 0 ? "--" : Integer.toString(vehicleSoc), "%", 480, 207, 82, 47);

        drawPowerBar(g, actualKw, targetKw);
        drawSocBar(g, vehicleSoc);

        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 17));
        g.drawString(targetKw < 0 ? "SOLL -- kW" : "SOLL " + targetKw + " kW", 30, 282);
    }

    private void drawPowerBar(Graphics2D g, int actualKw, int targetKw) {
        int x = 30;
        int y = 245;
        int width = 260;
        int height = 7;
        g.setColor(TRACK);
        g.fillRect(x, y, width, height);
        if (actualKw < 0 || targetKw <= 0) return;
        int filled = (int)Math.round(width * Math.min(1.0d, actualKw / (double)targetKw));
        g.setColor(YELLOW);
        g.fillRect(x, y, filled, height);
    }

    private void drawSocBar(Graphics2D g, int soc) {
        int x = 355;
        int y = 245;
        int width = 252;
        int height = 7;
        int segments = 7;
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

        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 13));
        centered(g, "ENERGIE", 105, 341);
        centered(g, "LADEZEIT", 320, 341);
        centered(g, "PUFFERBATTERIE", 535, 341);

        g.setColor(PRIMARY);
        g.setFont(font(Font.PLAIN, 31));
        centered(g, energyWh < 0L ? "-- kWh" : formatEnergy(energyWh), 105, 386);
        centered(g, seconds < 0 ? "--:--:--" : formatDuration(seconds), 320, 386);

        g.setColor(SECONDARY);
        g.setFont(font(Font.PLAIN, 38));
        centered(g, lastBatterySoc == null ? "-- %" : lastBatterySoc + " %", 535, 386);

        g.setColor(TRACK);
        g.fillRect(460, 398, 150, 6);
        if (lastBatterySoc != null) {
            g.setColor(SECONDARY);
            g.fillRect(460, 398, (int)Math.round(150 * lastBatterySoc.intValue() / 100.0d), 6);
        }
    }

    private void drawFooter(Graphics2D g) {
        g.setColor(SECONDARY);
        g.setFont(font(Font.BOLD, 11));
        centered(g, "Das Fahrzeug bestimmt die mögliche Ladeleistung.", 185, 454);

        g.setColor(STOP_RED);
        g.fillRect(370, 422, 252, 50);
        g.setColor(PRIMARY);
        g.setFont(font(Font.BOLD, 12));
        centered(g, "zum beenden Karte vorhalten", 496, 446);
        centered(g, "oder App benutzen.", 496, 463);
    }

    private void drawLargeValue(Graphics2D g, String value, String unit,
                                int centerX, int baseline, int valueSize, int unitSize) {
        Font valueFont = font(Font.BOLD, valueSize);
        Font unitFont = font(Font.BOLD, unitSize);
        g.setFont(valueFont);
        int valueWidth = g.getFontMetrics().stringWidth(value);
        g.setFont(unitFont);
        int unitWidth = g.getFontMetrics().stringWidth(unit);
        int gap = 10;
        int x = centerX - (valueWidth + gap + unitWidth) / 2;

        g.setColor(PRIMARY);
        g.setFont(valueFont);
        g.drawString(value, x, baseline);
        g.setFont(unitFont);
        g.drawString(unit, x + valueWidth + gap, baseline);
    }

    private void refreshChargingData(long now) {
        try {
            int[] values = readRegisters(MODBUS_FIRST_REGISTER, MODBUS_REGISTER_COUNT);
            lastChargingData = values;
            lastChargingDataFetch = now;
        } catch (Throwable ignored) {
            if (now - lastChargingDataFetch > 5000L) lastChargingData = null;
        }
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
