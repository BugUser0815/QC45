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
        softKey(g, KEY_TOP_LEFT, "CCS", connectorState(isCcs, ccsInUse, ccsOut),
            isCcs && !ccsInUse && !ccsOut && !emergency, true);
        softKey(g, KEY_TOP_RIGHT, "CHAdeMO", connectorState(isChademo, chaInUse, chaOut),
            isChademo && !chaInUse && !chaOut && !emergency, true);
        softKey(g, KEY_BOTTOM_LEFT, "AC", connectorState(isAc, acInUse, acOut),
            isAc && !acInUse && !acOut && !emergency, true);
        softKey(g, KEY_BOTTOM_RIGHT, "EINSTELLUNGEN", "SPRACHE · DIAGNOSE", !emergency, false);

        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.PLAIN, 15));
        centered(g, "Taste neben dem gewünschten Anschluss drücken", 320, 254);
    }

    private String connectorState(boolean visible, boolean inUse, boolean outOfService) {
        if (!visible) return "NICHT VERBAUT";
        if (outOfService) return "AUSSER BETRIEB";
        if (inUse) return "BELEGT";
        return "VERFÜGBAR";
    }
}
