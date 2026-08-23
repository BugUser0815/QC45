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
        softKey(g, KEY_TOP_LEFT, "ABBRECHEN", null, true, false);
        if (manualStart) softKey(g, KEY_TOP_RIGHT, "START", null, true, true);
    }
}
