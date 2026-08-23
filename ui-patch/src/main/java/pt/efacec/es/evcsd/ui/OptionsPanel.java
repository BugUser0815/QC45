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
        title(g, "EINSTELLUNGEN", "Auswahl mit den Navigationstasten ändern");
        option(g, 145, "DIAGNOSE", "Fehler- und Statusinformationen", 1);
        option(g, 215, "SPRACHE", "Anzeigesprache auswählen", 2);
        option(g, 285, "SITZUNG BEENDEN", "Aktive Bedienersitzung schließen", 3);
        action(g, 18, 426, 190, "ZURÜCK", 0);
        action(g, 432, 426, 190, "BESTÄTIGEN", 1);
    }

    private void option(Graphics2D g, int y, String name, String detail, int index) {
        g.setColor(selected == index ? YELLOW : PANEL);
        g.fillRect(110, y, 420, 56);
        g.setColor(selected == index ? BG : PRIMARY);
        g.setFont(font(java.awt.Font.BOLD, 17));
        g.drawString(name, 132, y + 23);
        g.setFont(font(java.awt.Font.PLAIN, 12));
        g.drawString(detail, 132, y + 43);
    }
}
