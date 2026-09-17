package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.EpoInfo;

/** Additional-connector selection using the same four-button geometry as the main menu. */
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
        super(multipleStatus(acinUse, chainUse, ccsinUse), YELLOW);
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
        setStatus(emergency ? "NOT-HALT"
            : multipleStatus(acInUse, chaInUse, ccsInUse), emergency ? RED : YELLOW);
    }

    protected void paintScreen(Graphics2D g) {
        title(g, emergency ? "NOT-HALT ENTRIEGELN" : "WEITEREN ANSCHLUSS WÄHLEN",
            emergency ? "Der Not-Halt ist betätigt."
                      : "Ein DC-Anschluss und Type 2 dürfen parallel laden");

        connectorKey(g, KEY_TOP_LEFT, "CCS", "CCS",
            connectorState(isCcs, ccsInUse, ccsOut, chaInUse),
            isCcs && !ccsInUse && !ccsOut && !chaInUse && !emergency,
            ccsOut, ccsInUse);
        connectorKey(g, KEY_TOP_RIGHT, "CHAdeMO", "CHADEMO",
            connectorState(isChademo, chaInUse, chaOut, ccsInUse),
            isChademo && !chaInUse && !chaOut && !ccsInUse && !emergency,
            chaOut, chaInUse);
        connectorKey(g, KEY_BOTTOM_LEFT, acLabel(), "AC",
            connectorState(isAc, acInUse, acOut, false),
            isAc && !acInUse && !acOut && !emergency, acOut, acInUse);
        languageKey(g, KEY_BOTTOM_RIGHT, !emergency);

        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, 14));
        centered(g, emergency ? "Laden gesperrt"
                              : "AC + DC · GLEICHE PRIORITÄT", 320, 248);
        if (!emergency) {
            g.setColor(YELLOW);
            g.setFont(font(java.awt.Font.PLAIN, 12));
            centered(g, "Freie Leistung wird bedarfsgerecht umverteilt", 320, 270);
        }
    }

    private String acLabel() {
        if (acType == 11) return "AC 11";
        if (acType == 22) return "AC 22";
        if (acType == 43) return "AC 43";
        return "AC";
    }

    private void connectorKey(Graphics2D g, int position, String label, String kind,
                              String detail, boolean enabled, boolean outOfService, boolean inUse) {
        boolean left = position == KEY_TOP_LEFT || position == KEY_BOTTOM_LEFT;
        boolean top = position == KEY_TOP_LEFT || position == KEY_TOP_RIGHT;
        int x = left ? 18 : 432;
        int y = top ? 128 : 344;
        int width = 190;
        int height = 62;
        java.awt.Color border = outOfService ? RED : inUse ? SECONDARY : enabled ? YELLOW : DIVIDER;

        g.setColor(enabled || inUse || outOfService ? PANEL : BG);
        g.fillRect(x, y, width, height);
        g.setColor(border);
        g.setStroke(new java.awt.BasicStroke(enabled ? 3.0f : 1.0f));
        g.drawRect(x, y, width, height);
        drawButtonChevron(g, left, y + height / 2, border);

        int iconX = left ? x + 31 : x + width - 31;
        int textX = left ? x + 113 : x + 77;
        ConnectorImages.draw(g, kind, iconX, y + 31, 52, 52,
            enabled || inUse || outOfService);

        g.setColor(enabled || inUse || outOfService ? PRIMARY : SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, 16));
        centered(g, label, textX, y + 25);
        g.setColor(enabled ? SECONDARY : DIVIDER);
        g.setFont(font(java.awt.Font.PLAIN, 10));
        centered(g, detail, textX, y + 46);
    }

    private void languageKey(Graphics2D g, int position, boolean enabled) {
        boolean left = position == KEY_TOP_LEFT || position == KEY_BOTTOM_LEFT;
        boolean top = position == KEY_TOP_LEFT || position == KEY_TOP_RIGHT;
        int x = left ? 18 : 432;
        int y = top ? 128 : 344;
        int width = 190;
        int height = 62;
        java.awt.Color border = enabled ? SECONDARY : DIVIDER;

        g.setColor(enabled ? PANEL : BG);
        g.fillRect(x, y, width, height);
        g.setColor(border);
        g.setStroke(new java.awt.BasicStroke(1.0f));
        g.drawRect(x, y, width, height);
        drawButtonChevron(g, left, y + height / 2, border);

        int iconX = left ? x + 31 : x + width - 31;
        int textX = left ? x + 113 : x + 77;
        drawLanguageSymbol(g, iconX, y + 31, border);
        g.setColor(enabled ? PRIMARY : SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, 15));
        centered(g, "SPRACHE", textX, y + 25);
        g.setColor(enabled ? SECONDARY : DIVIDER);
        g.setFont(font(java.awt.Font.PLAIN, 10));
        centered(g, "MENÜ", textX, y + 46);
    }

    private void drawButtonChevron(Graphics2D g, boolean left, int centerY, java.awt.Color color) {
        int outerX = left ? 5 : 635;
        int innerX = left ? 14 : 626;
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke(4.0f));
        g.drawLine(innerX, centerY - 10, outerX, centerY);
        g.drawLine(outerX, centerY, innerX, centerY + 10);
    }

    private void drawLanguageSymbol(Graphics2D g, int cx, int cy, java.awt.Color color) {
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke(2.0f));
        g.drawOval(cx - 14, cy - 14, 28, 28);
        g.drawOval(cx - 7, cy - 14, 14, 28);
        g.drawLine(cx - 13, cy, cx + 13, cy);
        g.drawArc(cx - 12, cy - 9, 24, 18, 0, 180);
        g.drawArc(cx - 12, cy - 9, 24, 18, 180, 180);
    }

    private String connectorState(boolean visible, boolean inUse,
                                  boolean outOfService, boolean otherDcInUse) {
        if (!visible) return "NICHT VERBAUT";
        if (outOfService) return "AUSSER BETRIEB";
        if (inUse) return "BELEGT";
        if (otherDcInUse) return "DC-PFAD BELEGT";
        return "VERFÜGBAR";
    }

    private static String multipleStatus(boolean ac, boolean chademo, boolean ccs) {
        boolean dc = chademo || ccs;
        if (ac && dc) return "AC + DC";
        if (ac) return "AC AKTIV";
        if (dc) return "DC AKTIV";
        return "PARALLELLADEN";
    }
}
