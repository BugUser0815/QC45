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
            emergency ? "Der Not-Halt ist betätigt." : "Verfügbaren Ladeanschluss auswählen");
        connectorCard(g, 18, "CCS", "DC-Schnellladen", showCcs, ccsInUse, ccsOut);
        connectorCard(g, 228, "CHAdeMO", "DC-Schnellladen", showChademo, chaInUse, chaOut);
        connectorCard(g, 438, "AC", "Typ 2", showAc, acInUse, acOut);
        action(g, 432, 426, 190, "EINSTELLUNGEN", 0);
    }
}
