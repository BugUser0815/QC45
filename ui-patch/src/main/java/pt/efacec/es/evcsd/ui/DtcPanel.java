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
        diagnostic(g, 129, "DC", current == null ? "--" : safe(current.getDtc(), "--"));
        diagnostic(g, 213, "AC", current == null ? "--" : safe(current.getAcDTC(), "--"));
        diagnostic(g, 297, "ENERGIEZÄHLER", current == null ? "--" : Integer.toString(current.getEnergy()));
        action(g, 18, 426, 190, "ZURÜCK", 0);
    }

    private void diagnostic(Graphics2D g, int y, String label, String value) {
        g.setColor(PANEL);
        g.fillRect(90, y, 460, 64);
        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, 13));
        g.drawString(label, 112, y + 25);
        g.setColor(PRIMARY);
        g.setFont(font(java.awt.Font.BOLD, 20));
        right(g, value, 528, y + 39);
    }
}
