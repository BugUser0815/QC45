package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.ChargeStoppedInfo;

class AlpitronicStoppedPanel extends AlpitronicPanel implements ActionPanel<ChargeStoppedInfo> {
    private final String connector;
    private volatile ChargeStoppedInfo info;

    AlpitronicStoppedPanel(String connector) {
        super("BEENDET", SECONDARY);
        this.connector = connector;
        AlpitronicSessionState.markIdle();
    }

    public void start() {}
    public void stop() {}
    public void setInfo(ChargeStoppedInfo next) {
        this.info = next;
        repaint();
    }

    protected void paintScreen(Graphics2D g) {
        title(g, "LADEVORGANG BEENDET", connector);
        drawCheck(g, 320, 176, GREEN);
        ChargeStoppedInfo current = info;
        metric(g, 25, 185, "ENERGIE", current == null ? "-- kWh" : safe(current.getEnergy(), "-- kWh"));
        metric(g, 227, 185, "LADEZEIT", current == null ? "--:--" : safe(current.getTime(), "--:--"));
        metric(g, 429, 185, "FAHRZEUG", current == null ? "-- %" : safe(current.getBattery(), "-- %"));
        String reason = current == null ? "" : safe(current.getFinishReason(), "");
        footer(g, reason.length() == 0 ? "Ladekabel abziehen." : reason);
    }
}
