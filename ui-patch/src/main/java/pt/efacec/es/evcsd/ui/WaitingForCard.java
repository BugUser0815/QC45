package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.NoInfo;

/** Quiet idle/authentication page without the legacy advertising animation. */
public class WaitingForCard extends AlpitronicPanel implements ActionPanel<NoInfo> {
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
        drawCard(g, 320, 203);
        instruction(g, "Karte vorhalten oder App benutzen.", "Der verfügbare Anschluss wird automatisch erkannt.");
        keyIndicator(g, KEY_TOP_LEFT);
        keyIndicator(g, KEY_TOP_RIGHT);
        keyIndicator(g, KEY_BOTTOM_LEFT);
        keyIndicator(g, KEY_BOTTOM_RIGHT);
        footer(g, "Beliebige Gerätetaste öffnet die Anschlussauswahl.");
    }
}
