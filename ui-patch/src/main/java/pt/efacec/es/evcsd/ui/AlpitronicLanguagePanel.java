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
        title(g, "SPRACHE WÄHLEN", "Auswahl mit den Navigationstasten ändern");
        int first = Math.max(0, selected - 2);
        int last = Math.min(languages.length, first + 5);
        first = Math.max(0, last - 5);
        for (int i = first; i < last; i++) {
            int y = 137 + (i - first) * 50;
            g.setColor(i == selected ? YELLOW : PANEL);
            g.fillRect(150, y, 340, 40);
            g.setColor(i == selected ? BG : PRIMARY);
            g.setFont(font(i == selected ? java.awt.Font.BOLD : java.awt.Font.PLAIN, 18));
            centered(g, safe(languages[i], ""), 320, y + 27);
        }
        action(g, 18, 426, 190, "ZURÜCK", 0);
        action(g, 432, 426, 190, "BESTÄTIGEN", 1);
    }
}
