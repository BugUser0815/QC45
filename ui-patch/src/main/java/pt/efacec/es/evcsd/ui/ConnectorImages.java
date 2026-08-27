package pt.efacec.es.evcsd.ui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import javax.imageio.ImageIO;

/** Bundled product renders for the three physical QC45 connector types. */
final class ConnectorImages {
    private static final String RESOURCE_ROOT =
        "/pt/efacec/es/evcsd/ui/connectors/";

    private static final BufferedImage CCS2 = load("ccs2.png");
    private static final BufferedImage CHADEMO = load("chademo.png");
    private static final BufferedImage TYPE2 = load("type2.png");

    private ConnectorImages() {}

    static void draw(Graphics2D graphics, String kind, int centerX, int centerY,
                     int maxWidth, int maxHeight, boolean active) {
        BufferedImage image = imageFor(kind);
        double scale = Math.min((double)maxWidth / image.getWidth(),
                                (double)maxHeight / image.getHeight());
        int width = Math.max(1, (int)Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int)Math.round(image.getHeight() * scale));

        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            if (!active) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
            }
            g.drawImage(image, centerX - width / 2, centerY - height / 2,
                width, height, null);
        } finally {
            g.dispose();
        }
    }

    static boolean resourcesAvailable() {
        return CCS2 != null && CHADEMO != null && TYPE2 != null;
    }

    private static BufferedImage imageFor(String kind) {
        if ("CCS".equals(kind) || "CCS2".equals(kind)) return CCS2;
        if ("CHADEMO".equals(kind)) return CHADEMO;
        return TYPE2;
    }

    private static BufferedImage load(String name) {
        URL resource = ConnectorImages.class.getResource(RESOURCE_ROOT + name);
        if (resource == null) {
            throw new IllegalStateException("missing connector image: " + name);
        }
        try {
            BufferedImage image = ImageIO.read(resource);
            if (image == null) {
                throw new IllegalStateException("invalid connector image: " + name);
            }
            return image;
        } catch (IOException error) {
            throw new IllegalStateException("cannot load connector image: " + name, error);
        }
    }
}
