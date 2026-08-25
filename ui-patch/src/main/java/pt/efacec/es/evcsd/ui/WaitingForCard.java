package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import pt.efacec.es.evcsd.ui.info.NoInfo;

/** Quiet idle/authentication page branded with the original SGS Elektrotechnik logo. */
public class WaitingForCard extends AlpitronicPanel implements ActionPanel<NoInfo> {
    private static final BufferedImage SGS_LOGO = loadLogo();

    public WaitingForCard(Main main) {
        super("BEREIT", GREEN);
        AlpitronicSessionState.markIdle();
    }

    public void start() {
        setVisible(true);
    }

    public void stop() {}

    public void setInfo(NoInfo info) {}

    protected void paintScreen(Graphics2D g) {
        title(g, "LADEN STARTEN", "Fahrzeug verbinden und anschließend authentifizieren");
        drawSgsLogo(g);
        instruction(g, "Karte vorhalten oder App benutzen.", "Der verfügbare Anschluss wird automatisch erkannt.");
    }

    private void drawSgsLogo(Graphics2D g) {
        if (SGS_LOGO != null) {
            g.drawImage(SGS_LOGO, 110, 141, null);
            return;
        }

        g.setColor(PRIMARY);
        g.setFont(font(java.awt.Font.BOLD, 26));
        centered(g, "SGS Elektrotechnik GbR", 320, 190);
        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.PLAIN, 17));
        centered(g, "Alexander & Marion Rothner", 320, 218);
    }

    private static BufferedImage loadLogo() {
        String[] paths = new String[] {
            "/pt/efacec/es/evcsd/ui/sgs-logo.png",
            "sgs-logo.png"
        };

        for (int i = 0; i < paths.length; i++) {
            InputStream in = null;
            try {
                in = WaitingForCard.class.getResourceAsStream(paths[i]);
                if (in != null) {
                    BufferedImage image = ImageIO.read(in);
                    if (image != null) return image;
                }
            } catch (Exception ignored) {
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }
}
