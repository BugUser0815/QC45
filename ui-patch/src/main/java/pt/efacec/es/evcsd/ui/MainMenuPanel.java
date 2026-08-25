package pt.efacec.es.evcsd.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import pt.efacec.es.evcsd.ui.info.EpoInfo;

/** Connector selection using SGS-styled, technically recognisable plug faces. */
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

        connectorKey(g, KEY_TOP_LEFT, "CCS", "CCS2",
            connectorState(showCcs, ccsInUse, ccsOut),
            showCcs && !ccsInUse && !ccsOut && !emergency, ccsOut, ccsInUse);
        connectorKey(g, KEY_TOP_RIGHT, "CHAdeMO", "CHADEMO",
            connectorState(showChademo, chaInUse, chaOut),
            showChademo && !chaInUse && !chaOut && !emergency, chaOut, chaInUse);
        connectorKey(g, KEY_BOTTOM_LEFT, "AC 11", "TYPE2",
            connectorState(showAc, acInUse, acOut),
            showAc && !acInUse && !acOut && !emergency, acOut, acInUse);
        languageKey(g, KEY_BOTTOM_RIGHT, !emergency);

        g.setColor(SECONDARY);
        g.setFont(font(java.awt.Font.PLAIN, 15));
        centered(g, emergency ? "Laden gesperrt"
                              : "Taste neben dem gewünschten Anschluss drücken", 320, 254);
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
        drawConnectorSymbol(g, kind, iconX, y + 44, border, enabled || inUse || outOfService);

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

    /**
     * Front-facing connector drawings based on the physical plug geometry used by
     * European CCS2, CHAdeMO and IEC 62196-2 Type 2 cables. The drawings are kept
     * deliberately vector-only so they remain sharp on the native 640x480 panel.
     */
    private void drawConnectorSymbol(Graphics2D g, String kind, int cx, int cy,
                                     Color stateColor, boolean visible) {
        Color shell = visible ? new Color(61, 64, 64) : new Color(34, 36, 36);
        Color face = visible ? new Color(12, 14, 14) : new Color(22, 24, 24);
        Color metal = visible ? new Color(224, 226, 226) : DIVIDER;
        Color accent = visible ? stateColor : DIVIDER;

        if ("CCS2".equals(kind)) {
            drawCcs2Plug(g, cx, cy, shell, face, metal, accent);
        } else if ("CHADEMO".equals(kind)) {
            drawChademoPlug(g, cx, cy, shell, face, metal, accent);
        } else {
            drawType2Plug(g, cx, cy, shell, face, metal, accent);
        }
    }

    /** CCS Combo 2 DC cable plug: PE + CP/PP above, two large DC contacts below. */
    private void drawCcs2Plug(Graphics2D g, int cx, int cy, Color shell, Color face,
                              Color metal, Color accent) {
        drawCableTail(g, cx - 17, cy + 22, cx - 27, cy + 33, shell);

        g.setColor(shell);
        g.fillRoundRect(cx - 25, cy - 29, 50, 39, 17, 17);
        g.fillRoundRect(cx - 22, cy + 5, 44, 25, 12, 12);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.4f));
        g.drawRoundRect(cx - 25, cy - 29, 50, 39, 17, 17);
        g.drawRoundRect(cx - 22, cy + 5, 44, 25, 12, 12);

        g.setColor(face);
        g.fillRoundRect(cx - 20, cy - 24, 40, 30, 13, 13);
        g.fillRoundRect(cx - 17, cy + 9, 34, 17, 8, 8);

        // The real DC cable plug only populates the signalling/earth contacts
        // in the Type-2 half: two small pilots and the central PE contact.
        drawContact(g, cx - 8, cy - 17, 4, metal, face);
        drawContact(g, cx + 8, cy - 17, 4, metal, face);
        drawContact(g, cx, cy - 7, 6, metal, face);

        drawContact(g, cx - 9, cy + 17, 9, metal, face);
        drawContact(g, cx + 9, cy + 17, 9, metal, face);

        g.setColor(YELLOW);
        g.fillRect(cx - 24, cy - 2, 3, 7);
    }

    /** CHAdeMO plug face: circular shell, two large DC contacts plus control contacts. */
    private void drawChademoPlug(Graphics2D g, int cx, int cy, Color shell, Color face,
                                 Color metal, Color accent) {
        drawCableTail(g, cx - 16, cy + 20, cx - 25, cy + 32, shell);

        g.setColor(shell);
        g.fillOval(cx - 28, cy - 28, 56, 56);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.4f));
        g.drawOval(cx - 28, cy - 28, 56, 56);
        g.setColor(face);
        g.fillOval(cx - 23, cy - 23, 46, 46);

        drawContact(g, cx - 10, cy, 9, metal, face);
        drawContact(g, cx + 10, cy, 9, metal, face);

        drawContact(g, cx - 10, cy - 14, 4, metal, face);
        drawContact(g, cx, cy - 17, 4, metal, face);
        drawContact(g, cx + 10, cy - 14, 4, metal, face);
        drawContact(g, cx - 15, cy + 12, 4, metal, face);
        drawContact(g, cx - 5, cy + 16, 4, metal, face);
        drawContact(g, cx + 5, cy + 16, 4, metal, face);
        drawContact(g, cx + 15, cy + 12, 4, metal, face);
        drawContact(g, cx, cy + 10, 3, metal, face);

        g.setColor(YELLOW);
        g.fillRect(cx - 3, cy - 28, 6, 3);
        g.fillRect(cx - 28, cy - 3, 3, 6);
        g.fillRect(cx + 25, cy - 3, 3, 6);
    }

    /** IEC 62196-2 Type 2 AC plug with the recognisable seven-contact layout. */
    private void drawType2Plug(Graphics2D g, int cx, int cy, Color shell, Color face,
                               Color metal, Color accent) {
        drawCableTail(g, cx - 15, cy + 21, cx - 24, cy + 33, shell);

        GeneralPath body = new GeneralPath();
        body.moveTo(cx - 21, cy - 24);
        body.quadTo(cx - 25, cy - 20, cx - 25, cy - 12);
        body.lineTo(cx - 25, cy + 13);
        body.quadTo(cx - 25, cy + 25, cx - 13, cy + 27);
        body.lineTo(cx + 13, cy + 27);
        body.quadTo(cx + 25, cy + 25, cx + 25, cy + 13);
        body.lineTo(cx + 25, cy - 12);
        body.quadTo(cx + 25, cy - 20, cx + 21, cy - 24);
        body.closePath();
        g.setColor(shell);
        g.fill(body);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.4f));
        g.draw(body);

        g.setColor(face);
        g.fillRoundRect(cx - 20, cy - 19, 40, 40, 14, 14);

        drawContact(g, cx - 8, cy - 12, 4, metal, face);
        drawContact(g, cx + 8, cy - 12, 4, metal, face);
        drawContact(g, cx - 12, cy, 6, metal, face);
        drawContact(g, cx, cy, 6, metal, face);
        drawContact(g, cx + 12, cy, 6, metal, face);
        drawContact(g, cx - 7, cy + 12, 6, metal, face);
        drawContact(g, cx + 7, cy + 12, 6, metal, face);

        g.setColor(YELLOW);
        g.fillRect(cx - 4, cy - 24, 8, 3);
    }

    private void drawCableTail(Graphics2D g, int x1, int y1, int x2, int y2, Color shell) {
        g.setColor(new Color(20, 22, 22));
        g.setStroke(new BasicStroke(11.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x1, y1, x2, y2);
        g.setColor(shell);
        g.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x1 + 1, y1, x2 + 1, y2);
    }

    private void drawContact(Graphics2D g, int cx, int cy, int diameter,
                             Color metal, Color face) {
        int outer = diameter + 4;
        g.setColor(metal);
        g.fillOval(cx - outer / 2, cy - outer / 2, outer, outer);
        g.setColor(face);
        g.fillOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);
        if (diameter >= 6) {
            g.setColor(new Color(132, 134, 134));
            int core = Math.max(2, diameter / 3);
            g.fillOval(cx - core / 2, cy - core / 2, core, core);
        }
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
