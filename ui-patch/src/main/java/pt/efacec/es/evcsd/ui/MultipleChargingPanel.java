package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.EpoInfo;

public class MultipleChargingPanel extends AlpitronicPanel implements ActionPanel<EpoInfo> {
    private final boolean isChademo;
    private final boolean isCcs;
    private final boolean isAc;
    private final boolean acInUse;
    private final boolean chaInUse;
    private final boolean ccsInUse;
    private final boolean acOut;
    private final boolean chaOut;
    private final boolean ccsOut;
    private final int acType;
    private volatile boolean emergency;

    public MultipleChargingPanel(boolean isCHADEMO, boolean isCCS, boolean isAC,
            boolean acinUse, boolean chainUse, boolean ccsinUse,
            boolean acOut, boolean chaOut, boolean ccsOut, int acType) {
        super("MEHRFACHLADUNG", YELLOW);
        this.isChademo = isCHADEMO;
        this.isCcs = isCCS;
        this.isAc = isAC;
        this.acInUse = acinUse;
        this.chaInUse = chainUse;
        this.ccsInUse = ccsinUse;
        this.acOut = acOut;
        this.chaOut = chaOut;
        this.ccsOut = ccsOut;
        this.acType = acType;
    }

    public void start() {}
    public void stop() {}
    public void setInfo(EpoInfo info) {
        emergency = info != null && info.isShowEpoPressed();
        setStatus(emergency ? "NOT-HALT" : "MEHRFACHLADUNG", emergency ? RED : YELLOW);
    }

    protected void paintScreen(Graphics2D g) {
        title(g, emergency ? "NOT-HALT ENTRIEGELN" : "WEITEREN ANSCHLUSS WÄHLEN",
            "Bereits belegte Anschlüsse sind gekennzeichnet");
        connectorCard(g, 18, "CCS", "DC-Schnellladen", isCcs, ccsInUse, ccsOut);
        connectorCard(g, 228, "CHAdeMO", "DC-Schnellladen", isChademo, chaInUse, chaOut);
        connectorCard(g, 438, "AC", acType > 0 ? Integer.toString(acType) + " kW" : "Typ 2", isAc, acInUse, acOut);
        action(g, 432, 426, 190, "EINSTELLUNGEN", 0);
    }
}
