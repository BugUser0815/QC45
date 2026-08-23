package pt.efacec.es.evcsd.ui;

import pt.efacec.es.evcsd.ui.info.ChargeInfo;

public class InACChargingPanel extends AlpitronicChargingPanel {
    public InACChargingPanel(int type, int modus, int startType, boolean showKwh, boolean showInfoDown) {
        super(modus, false);
    }

    public InACChargingPanel(ChargeInfo info, int type, int modus, int startType,
                             boolean showKwh, boolean usesCreditCard, boolean showInfoDown) {
        super(modus, usesCreditCard);
        setInfo(info);
    }
}
