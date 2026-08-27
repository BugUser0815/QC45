package pt.efacec.es.evcsd.ui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JPanel;

/** Headless schema and 640x480 rendering check, executable without JUnit. */
public final class UiPatchTest {
    private UiPatchTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("six preview paths are required");
        require(ConnectorImages.resourcesAvailable(), "connector image resources");
        verifyDecoder();
        render(args[0], 0);
        render(args[1], 1);
        render(args[2], 2);
        render(args[3], 3);
        renderPanel(new MainMenuPanel("", false, false, false,
            false, false, false, true, true, true, false), args[4]);
        renderPanel(new MultipleChargingPanel(true, true, true,
            false, false, true, false, false, false, 22), args[5]);
        System.out.println("UI tests passed; six 640x480 previews rendered");
    }

    private static void verifyDecoder() {
        int[] raw = telemetry(0);
        LoadBalancingTelemetry data = LoadBalancingTelemetry.decode(raw);
        require(data.activeDcConnector == 2, "active DC connector");
        require(data.dcActualKw == 17 && data.acActualKw == 11, "actual power");
        require(data.dcEffectiveKw == 17 && data.acEffectiveKw == 13, "effective power");
        require(data.totalActualKw() == 28, "total power");
        require(data.totalEnergyWh() == 17600L, "total energy");
        require(data.demandTransfer(), "demand transfer flag");

        raw = telemetry(0);
        raw[1] = LoadBalancingTelemetry.FLAG_BLOCKED
            | LoadBalancingTelemetry.FLAG_STARTUP
            | LoadBalancingTelemetry.FLAG_CONFIGURATION;
        data = LoadBalancingTelemetry.decode(raw);
        require(data.blocked(), "configuration block");
        require(data.has(LoadBalancingTelemetry.FLAG_CONFIGURATION),
            "configuration flag");

        raw[0] = 99;
        try {
            LoadBalancingTelemetry.decode(raw);
            throw new AssertionError("unknown schema version accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void render(String path, int safetyState) throws Exception {
        InCCSChargingPanel panel = new InCCSChargingPanel(0, 0, false, false);
        try {
            set(panel, "lastBalancingData", LoadBalancingTelemetry.decode(telemetry(safetyState)));
            setLong(panel, "lastBalancingDataFetch", System.currentTimeMillis());
            set(panel, "lastBatterySoc", Integer.valueOf(64));

            Method render = WaitingForCardChargingTimer.class.getDeclaredMethod("renderChargePage");
            render.setAccessible(true);
            ImageIcon icon = (ImageIcon)render.invoke(panel);
            Image source = icon.getImage();
            BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            graphics.drawImage(source, 0, 0, null);
            graphics.dispose();
            writeAndVerify(image, path);
        } finally {
            panel.stop();
        }
    }

    private static void renderPanel(JPanel panel, String path) throws Exception {
        panel.setSize(640, 480);
        BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        panel.paint(graphics);
        graphics.dispose();
        writeAndVerify(image, path);
    }

    private static void writeAndVerify(BufferedImage image, String path) throws Exception {
        require(image.getWidth() == 640 && image.getHeight() == 480, "preview dimensions");
        require(nonBackgroundPixels(image) > 30000, "preview is unexpectedly blank");
        File output = new File(path);
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(image, "png", output);
    }

    private static int[] telemetry(int safetyState) {
        int flags = LoadBalancingTelemetry.FLAG_DC_SESSION
            | LoadBalancingTelemetry.FLAG_AC_SESSION
            | LoadBalancingTelemetry.FLAG_DC_FLOW
            | LoadBalancingTelemetry.FLAG_AC_FLOW
            | LoadBalancingTelemetry.FLAG_DEMAND_TRANSFER;
        if (safetyState == 1) {
            flags = LoadBalancingTelemetry.FLAG_DC_SESSION
                | LoadBalancingTelemetry.FLAG_AC_SESSION
                | LoadBalancingTelemetry.FLAG_BLOCKED
                | LoadBalancingTelemetry.FLAG_FAILBACK;
        } else if (safetyState == 2) {
            flags = LoadBalancingTelemetry.FLAG_DC_SESSION
                | LoadBalancingTelemetry.FLAG_AC_SESSION
                | LoadBalancingTelemetry.FLAG_BLOCKED
                | LoadBalancingTelemetry.FLAG_STARTUP
                | LoadBalancingTelemetry.FLAG_CONFIGURATION;
        } else if (safetyState == 3) {
            flags = LoadBalancingTelemetry.FLAG_DC_SESSION
                | LoadBalancingTelemetry.FLAG_BLOCKED
                | LoadBalancingTelemetry.FLAG_LIMIT_MISMATCH;
        }
        boolean blocked = safetyState != 0;
        return new int[] {
            LoadBalancingTelemetry.VERSION, flags, 2,
            blocked ? 0 : 17, 50, 17, blocked ? 0 : 50, blocked ? 0 : 17,
            78, 754, 0, 12400,
            blocked ? 0 : 11, 22, 13, blocked ? 0 : 22, blocked ? 0 : 13,
            302, 0, 5200
        };
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = WaitingForCardChargingTimer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setLong(Object target, String name, long value) throws Exception {
        Field field = WaitingForCardChargingTimer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static int nonBackgroundPixels(BufferedImage image) {
        int background = image.getRGB(0, 479);
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                if (image.getRGB(x, y) != background) count++;
            }
        }
        return count * 4;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
