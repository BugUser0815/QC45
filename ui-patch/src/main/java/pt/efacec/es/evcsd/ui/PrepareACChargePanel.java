package pt.efacec.es.evcsd.ui;

public class PrepareACChargePanel extends AlpitronicPreparationPanel {
    public PrepareACChargePanel(int type) {
        super("AC" + (type > 0 ? " " + type + " kW" : ""), "Ladekabel mit Fahrzeug und Säule verbinden.", true);
    }
}
