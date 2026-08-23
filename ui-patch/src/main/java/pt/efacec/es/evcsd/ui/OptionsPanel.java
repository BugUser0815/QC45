package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.MenuInfo;

public class OptionsPanel extends AlpitronicPanel implements ActionPanel<MenuInfo> {
    private volatile int selected = 1;

    public OptionsPanel() {
        super("EINSTELLUNGEN", SECONDARY);
    }

    public void start() {}
    public void stop() {}
    public void setInfo(MenuInfo info) {
        if (info != null) selected = Math.max(1, Math.min(3, info.getCommand()));
        repaint();
    }

    protected void paintScreen(Graphics2D g) {
        title(g, "EINSTELLUNGEN", "Auswahl mit den rechten Gerätetasten ändern");
        option(g, 139, "DIAGNOSE", 1);
        option(g, 207, "SPRACHE", 2);
        option(g, 275, "SITZUNG BEENDEN", 3);
        softKey(g, KEY_TOP_LEFT, "BESTÄTIGEN", null, true, true);
        softKey(g, KEY_TOP_RIGHT, "NACH OBEN", "▲", true, false);
        softKey(g, KEY_BOTTOM_LEFT, "ZURÜCK", null, true, false);
        softKey(g, KEY_BOTTOM_RIGHT, "NACH UNTEN", "▼", true, false);
    }

    private void option(Graphics2D g, int y, String name, int index) {
        g.setColor(selected == index ? YELLOW : PANEL);
        g.fillRect(218, y, 204, 52);
        g.setColor(selected == index ? BG : PRIMARY);
        g.setFont(font(java.awt.Font.BOLD, 17));
        centered(g, name, 320, y + 32);
    }
}
