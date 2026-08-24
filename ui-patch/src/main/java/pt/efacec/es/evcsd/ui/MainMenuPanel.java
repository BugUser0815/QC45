package pt.efacec.es.evcsd.ui;

import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.EpoInfo;

/** Connector selection in the same reduced visual language as the new start page. */
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
            emergency ? "Der Not-Halt ist betätigt."
                      : "Die vier Positionen entsprechen direkt den vier Gerätetasten");

        connectorKey(g, KEY_TOP_LEFT, "CCS", "CCS",
            connectorState(showCcs, ccsInUse, ccsOut),
            showCcs && !ccsInUse && !ccsOut && !emergency, ccsOut, ccsInUse);
        connectorKey(g, KEY_TOP_RIGHT, "CHAdeMO", "CHADEMO",
            connectorState(showChademo, chaInUse, chaOut),
            showChademo && !chaInUse && !chaOut && !emergency, chaOut, chaInUse);
        connectorKey(g, KEY_BOTTOM_LEFT, "AC", "AC",
            connectorState(showAc, acInUse, acOut),
            showAc && !acInUse && !acOut && !emergency, acOut, acInUse);
        languageKey(g, KEY_BOTTOM_RIGHT, !emergency);

        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.PLAIN, 15));
        centered(g, emergency ? "Laden gesperrt"
                              : "Taste neben dem gewünschten Anschluss drücken", 320, 254);
    }

    /**
     * Keeps the legacy four-button geometry, but renders the choices entirely in
     * the new code-drawn start-page style. No old bitmap connector graphics are used.
     */
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
        drawConnectorSymbol(g, kind, iconX, y + 31, border);

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

    private void drawConnectorSymbol(Graphics2D g, String kind, int cx, int cy, java.awt.Color color) {
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke(2.0f));
        g.drawOval(cx - 14, cy - 14, 28, 28);

        if ("CCS".equals(kind)) {
            g.fillOval(cx - 8, cy - 8, 4, 4);
            g.fillOval(cx + 4, cy - 8, 4, 4);
            g.fillOval(cx - 8, cy + 1, 4, 4);
            g.fillOval(cx + 4, cy + 1, 4, 4);
            g.fillOval(cx - 9, cy + 16, 7, 7);
            g.fillOval(cx + 2, cy + 16, 7, 7);
            g.drawLine(cx - 12, cy + 12, cx - 12, cy + 18);
            g.drawLine(cx + 12, cy + 12, cx + 12, cy + 18);
        } else if ("CHADEMO".equals(kind)) {
            g.fillOval(cx - 7, cy - 7, 5, 5);
            g.fillOval(cx + 2, cy - 7, 5, 5);
            g.fillOval(cx - 7, cy + 2, 5, 5);
            g.fillOval(cx + 2, cy + 2, 5, 5);
            g.drawArc(cx - 9, cy - 10, 18, 20, 205, 130);
        } else {
            g.fillOval(cx - 7, cy - 8, 4, 4);
            g.fillOval(cx + 3, cy - 8, 4, 4);
            g.fillOval(cx - 9, cy + 1, 4, 4);
            g.fillOval(cx + 5, cy + 1, 4, 4);
            g.fillOval(cx - 2, cy + 5, 4, 4);
        }
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

    private String connectorState(boolean visible, boolean inUse, boolean outOfService) {
        if (!visible) return "NICHT VERBAUT";
        if (outOfService) return "AUSSER BETRIEB";
        if (inUse) return "BELEGT";
        return "VERFÜGBAR";
    }
}
