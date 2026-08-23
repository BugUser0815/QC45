package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.MenuInfo;

class AlpitronicLanguagePanel extends AlpitronicPanel implements ActionPanel<MenuInfo> {
    private final String[] languages;
    private volatile int selected;

    AlpitronicLanguagePanel(String languageList, int selectedMenu) {
        super("SPRACHE", SECONDARY);
        this.languages = languageList == null || languageList.trim().length() == 0
            ? new String[] {"Deutsch", "English", "Français"} : languageList.split(",");
        this.selected = Math.max(0, Math.min(this.languages.length - 1, selectedMenu - 1));
    }

    public void start() {}
    public void stop() {}
    public void setInfo(MenuInfo info) {
        if (info != null) selected = Math.max(0, Math.min(languages.length - 1, info.getCommand() - 1));
        repaint();
    }

    protected void paintScreen(Graphics2D g) {
        title(g, "SPRACHE WÄHLEN", "Auswahl mit den rechten Gerätetasten ändern");
        int first = Math.max(0, selected - 2);
        int last = Math.min(languages.length, first + 5);
        first = Math.max(0, last - 5);
        for (int i = first; i < last; i++) {
            int y = 126 + (i - first) * 45;
            g.setColor(i == selected ? YELLOW : PANEL);
            g.fillRect(218, y, 204, 38);
            g.setColor(i == selected ? BG : PRIMARY);
            g.setFont(font(i == selected ? java.awt.Font.BOLD : java.awt.Font.PLAIN, 16));
            centered(g, safe(languages[i], ""), 320, y + 25);
        }
        softKey(g, KEY_TOP_LEFT, "BESTÄTIGEN", null, true, true);
        softKey(g, KEY_TOP_RIGHT, "NACH OBEN", "▲", true, false);
        softKey(g, KEY_BOTTOM_LEFT, "ZURÜCK", null, true, false);
        softKey(g, KEY_BOTTOM_RIGHT, "NACH UNTEN", "▼", true, false);
    }
}
