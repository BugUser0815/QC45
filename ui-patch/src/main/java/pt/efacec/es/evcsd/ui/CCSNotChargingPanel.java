package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import java.awt.Image;
import pt.efacec.es.evcsd.ui.info.ChargeStoppedInfo;

/** CCS connection state used while the vehicle has not started drawing power. */
public class CCSNotChargingPanel extends AlpitronicPanel implements ActionPanel<ChargeStoppedInfo> {
    private volatile ChargeStoppedInfo info;

    public CCSNotChargingPanel(Image ignored) {
        super("VERBUNDEN", YELLOW);
    }

    public void start() {}
    public void stop() {}

    public void setInfo(ChargeStoppedInfo next) {
        info = next;
        repaint();
    }

    protected void paintScreen(Graphics2D g) {
        title(g, "FAHRZEUG VERBUNDEN", "CCS");
        drawPlug(g, 320, 191, YELLOW);
        String reason = info == null ? "" : safe(info.getFinishReason(), "");
        instruction(g, "Warte auf Fahrzeug …",
            reason.length() == 0 ? "Der Ladevorgang startet, sobald das Fahrzeug bereit ist." : reason);
        action(g, 18, 426, 190, "ABBRECHEN", 0);
    }
}
