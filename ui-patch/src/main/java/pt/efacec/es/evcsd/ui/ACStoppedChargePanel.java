package pt.efacec.es.evcsd.ui;

import java.awt.Image;

public class ACStoppedChargePanel extends AlpitronicStoppedPanel {
    public ACStoppedChargePanel(Image image, int type) {
        super("AC" + (type > 0 ? " " + type + " kW" : ""));
    }
}
