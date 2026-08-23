package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.EpoInfo;

public class MainMenuPanel extends AlpitronicPanel implements ActionPanel<EpoInfo> {
    private final boolean acInUse;
    private final boolean chaInUse;
    private final boolean ccsInUse;
    private final boolean acOut;
    private final boolean chaOut;
    private final boolean ccsOut;
    private final boolean showChademo;
    private final boolean showCcs;
    private final boolean showAc;
    private volatile boolean emergency;

    public MainMenuPanel(String image, boolean acinUse, boolean chainUse, boolean ccsinUse,
                         boolean acOut, boolean chaOut, boolean ccsOut) {
        this(image, acinUse, chainUse, ccsinUse, acOut, chaOut, ccsOut, true, true, true, false);
    }

    public MainMenuPanel(String image, boolean acinUse, boolean chainUse, boolean ccsinUse,
                         boolean acOut, boolean chaOut, boolean ccsOut,
                         boolean ischademo, boolean isccs, boolean isac, boolean emptyMenu) {
        super("BEREIT", GREEN);
        this.acInUse = acinUse;
        this.chaInUse = chainUse;
        this.ccsInUse = ccsinUse;
        this.acOut = acOut;
        this.chaOut = chaOut;
        this.ccsOut = ccsOut;
        this.showChademo = ischademo;
        this.showCcs = isccs;
        this.showAc = isac;
        AlpitronicSessionState.markIdle();
    }

    public void start() {}
    public void stop() {}
    public void setInfo(EpoInfo info) {
        emergency = info != null && info.isShowEpoPressed();
        setStatus(emergency ? "NOT-HALT" : "BEREIT", emergency ? RED : GREEN);
    }

    protected void paintScreen(Graphics2D g) {
        title(g, emergency ? "NOT-HALT ENTRIEGELN" : "ANSCHLUSS WÄHLEN",
            emergency ? "Der Not-Halt ist betätigt." : "Die vier Felder entsprechen den vier Gerätetasten");
        softKey(g, KEY_TOP_LEFT, "CCS", connectorState(showCcs, ccsInUse, ccsOut),
            showCcs && !ccsInUse && !ccsOut && !emergency, true);
        softKey(g, KEY_TOP_RIGHT, "CHAdeMO", connectorState(showChademo, chaInUse, chaOut),
            showChademo && !chaInUse && !chaOut && !emergency, true);
        softKey(g, KEY_BOTTOM_LEFT, "AC", connectorState(showAc, acInUse, acOut),
            showAc && !acInUse && !acOut && !emergency, true);
        softKey(g, KEY_BOTTOM_RIGHT, "EINSTELLUNGEN", "SPRACHE · DIAGNOSE", !emergency, false);

        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.PLAIN, 15));
        centered(g, emergency ? "Laden gesperrt" : "Taste neben dem gewünschten Anschluss drücken", 320, 254);
    }

    private String connectorState(boolean visible, boolean inUse, boolean outOfService) {
        if (!visible) return "NICHT VERBAUT";
        if (outOfService) return "AUSSER BETRIEB";
        if (inUse) return "BELEGT";
        return "VERFÜGBAR";
    }
}
