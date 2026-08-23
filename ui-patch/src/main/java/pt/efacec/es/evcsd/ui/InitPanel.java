package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;

public class InitPanel extends AlpitronicPanel {
    public InitPanel() {
        super("STARTET", YELLOW);
    }

    protected void paintScreen(Graphics2D g) {
        g.setColor(PRIMARY);
        g.setFont(font(java.awt.Font.BOLD, 48));
        centered(g, "QC45", 320, 205);
        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.PLAIN, 18));
        centered(g, "Ladesystem wird gestartet", 320, 244);
        g.setColor(PANEL_LIGHT);
        g.fillRect(170, 285, 300, 6);
        g.setColor(YELLOW);
        g.fillRect(170, 285, 190, 6);
        footer(g, "Bitte einen Moment warten.");
    }
}
