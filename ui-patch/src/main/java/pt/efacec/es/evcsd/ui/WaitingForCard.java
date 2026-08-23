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
        footer(g, "Die Ladeleistung wird vom Fahrzeug bestimmt.");
    }
}
