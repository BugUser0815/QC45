package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.DtcInfo;

/** Reduced diagnostic page preserving the original data contract. */
public class DtcPanel extends AlpitronicPanel implements ActionPanel<DtcInfo> {
    private volatile DtcInfo info;

    public DtcPanel(Main main) {
        super("DIAGNOSE", SECONDARY);
    }

    public void start() {}
    public void stop() {}

    public void setInfo(DtcInfo next) {
        info = next;
        repaint();
    }

    protected void paintScreen(Graphics2D g) {
        title(g, "DIAGNOSE", "Aktueller Gerätestatus");
        DtcInfo current = info;
        diagnostic(g, 139, "DC", current == null ? "--" : safe(current.getDtc(), "--"));
        diagnostic(g, 207, "AC", current == null ? "--" : safe(current.getAcDTC(), "--"));
        diagnostic(g, 275, "ENERGIEZÄHLER", current == null ? "--" : Integer.toString(current.getEnergy()));
        softKey(g, KEY_BOTTOM_LEFT, "ZURÜCK", null, true, false);
    }

    private void diagnostic(Graphics2D g, int y, String label, String value) {
        g.setColor(PANEL);
        g.fillRect(218, y, 204, 52);
        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, 11));
        centered(g, label, 320, y + 18);
        g.setColor(PRIMARY);
        g.setFont(font(java.awt.Font.BOLD, 18));
        centered(g, value, 320, y + 41);
    }
}
