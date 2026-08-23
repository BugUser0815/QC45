package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;

class AlpitronicPreparationPanel extends AlpitronicPanel {
    private final String connector;
    private final String message;
    private final boolean manualStart;

    AlpitronicPreparationPanel(String connector, String message, boolean manualStart) {
        super("VORBEREITUNG", YELLOW);
        this.connector = connector;
        this.message = message;
        this.manualStart = manualStart;
    }

    protected void paintScreen(Graphics2D g) {
        title(g, connector + " VERBINDEN", "Schritt 1 von 2");
        drawPlug(g, 320, 190, YELLOW);
        instruction(g, message, manualStart ? "Danach START wählen." : "Der Ladevorgang wird anschließend automatisch vorbereitet.");
        action(g, 18, 426, 190, "ABBRECHEN", 0);
        if (manualStart) action(g, 432, 426, 190, "START", 1);
    }
}
