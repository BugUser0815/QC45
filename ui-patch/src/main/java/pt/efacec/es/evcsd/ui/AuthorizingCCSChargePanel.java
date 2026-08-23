package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;

public class AuthorizingCCSChargePanel extends AlpitronicPanel {
    private final String message;

    public AuthorizingCCSChargePanel(int modus, boolean usesApp) {
        super("AUTORISIERUNG", YELLOW);
        if (modus == 2 || modus == 4) {
            message = "Ladevorgang in der App starten.";
        } else if (usesApp) {
            message = "Karte vorhalten oder App benutzen.";
        } else {
            message = "Karte am Leser vorhalten.";
        }
    }

    protected void paintScreen(Graphics2D g) {
        title(g, "LADEVORGANG STARTEN", "CCS");
        drawCard(g, 320, 196);
        instruction(g, message, "Autorisierung wird geprüft.");
        action(g, 18, 426, 190, "ABBRECHEN", 0);
    }
}
