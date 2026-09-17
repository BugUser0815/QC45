package pt.efacec.es.evcsd.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import pt.efacec.es.evcsd.ui.info.EpoInfo;

/** Connector selection using bundled, technically recognisable plug images. */
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
                      : "AC und ein DC-Anschluss können gleichzeitig laden");

        connectorKey(g, KEY_TOP_LEFT, "CCS", "CCS2",
            connectorState(showCcs, ccsInUse, ccsOut),
            showCcs && !ccsInUse && !ccsOut && !emergency, ccsOut, ccsInUse);
        connectorKey(g, KEY_TOP_RIGHT, "CHAdeMO", "CHADEMO",
            connectorState(showChademo, chaInUse, chaOut),
            showChademo && !chaInUse && !chaOut && !emergency, chaOut, chaInUse);
        connectorKey(g, KEY_BOTTOM_LEFT, "AC", "TYPE 2",
            connectorState(showAc, acInUse, acOut),
            showAc && !acInUse && !acOut && !emergency, acOut, acInUse);
        languageKey(g, KEY_BOTTOM_RIGHT, !emergency);

        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, 14));
        centered(g, emergency ? "Laden gesperrt"
                              : "DYNAMISCHES LOAD BALANCING", 320, 246);
        if (!emergency) {
            g.setColor(YELLOW);
            g.setFont(font(java.awt.Font.PLAIN, 12));
            centered(g, "AC + DC · gleichberechtigt und netzsicher", 320, 268);
        }
    }

    private void connectorKey(Graphics2D g, int position, String label, String kind,
                              String detail, boolean enabled, boolean outOfService, boolean inUse) {
        boolean left = position == KEY_TOP_LEFT || position == KEY_BOTTOM_LEFT;
        boolean top = position == KEY_TOP_LEFT || position == KEY_TOP_RIGHT;
        int x = left ? 18 : 432;
        int y = top ? 116 : 332;
        int width = 190;
        int height = 88;
        Color border = outOfService ? RED : inUse ? SECONDARY : enabled ? YELLOW : DIVIDER;

        g.setColor(enabled || inUse || outOfService ? PANEL : BG);
        g.fillRoundRect(x, y, width, height, 10, 10);
        g.setColor(border);
        g.setStroke(new BasicStroke(enabled ? 3.0f : 1.0f));
        g.drawRoundRect(x, y, width, height, 10, 10);
        drawButtonChevron(g, left, y + height / 2, border);

        int iconX = left ? x + 45 : x + width - 45;
        int textX = left ? x + 125 : x + 65;
        ConnectorImages.draw(g, kind, iconX, y + 44, 74, 74,
            enabled || inUse || outOfService);

        g.setColor(enabled || inUse || outOfService ? PRIMARY : SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, "CHAdeMO".equals(label) ? 14 : 17));
        centered(g, label, textX, y + 35);
        g.setColor(enabled ? SECONDARY : DIVIDER);
        g.setFont(font(java.awt.Font.PLAIN, 10));
        centered(g, detail, textX, y + 59);
    }

    private void languageKey(Graphics2D g, int position, boolean enabled) {
        boolean left = position == KEY_TOP_LEFT || position == KEY_BOTTOM_LEFT;
        boolean top = position == KEY_TOP_LEFT || position == KEY_TOP_RIGHT;
        int x = left ? 18 : 432;
        int y = top ? 116 : 332;
        int width = 190;
        int height = 88;
        Color border = enabled ? SECONDARY : DIVIDER;

        g.setColor(enabled ? PANEL : BG);
        g.fillRoundRect(x, y, width, height, 10, 10);
        g.setColor(border);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(x, y, width, height, 10, 10);
        drawButtonChevron(g, left, y + height / 2, border);

        int iconX = left ? x + 45 : x + width - 45;
        int textX = left ? x + 125 : x + 65;
        drawLanguageSymbol(g, iconX, y + 44, border);
        g.setColor(enabled ? PRIMARY : SECONDARY);
        g.setFont(font(java.awt.Font.BOLD, 15));
        centered(g, "SPRACHE", textX, y + 35);
        g.setColor(enabled ? SECONDARY : DIVIDER);
        g.setFont(font(java.awt.Font.PLAIN, 10));
        centered(g, "MENÜ", textX, y + 59);
    }

    private void drawButtonChevron(Graphics2D g, boolean left, int centerY, Color color) {
        int outerX = left ? 5 : 635;
        int innerX = left ? 14 : 626;
        g.setColor(color);
        g.setStroke(new BasicStroke(4.0f));
        g.drawLine(innerX, centerY - 10, outerX, centerY);
        g.drawLine(outerX, centerY, innerX, centerY + 10);
    }

    private void drawLanguageSymbol(Graphics2D g, int cx, int cy, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2.4f));
        g.drawOval(cx - 20, cy - 20, 40, 40);
        g.drawOval(cx - 10, cy - 20, 20, 40);
        g.drawLine(cx - 19, cy, cx + 19, cy);
        g.drawArc(cx - 17, cy - 13, 34, 26, 0, 180);
        g.drawArc(cx - 17, cy - 13, 34, 26, 180, 180);
    }

    private String connectorState(boolean visible, boolean inUse, boolean outOfService) {
        if (!visible) return "NICHT VERBAUT";
        if (outOfService) return "AUSSER BETRIEB";
        if (inUse) return "BELEGT";
        return "VERFÜGBAR";
    }
}
