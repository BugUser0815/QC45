package pt.efacec.es.evcsd.ui;

import pt.efacec.es.evcsd.ui.info.ChargeInfo;

public class InCCSChargingPanel extends AlpitronicChargingPanel {
    public InCCSChargingPanel(int modus, int startType, boolean showKwh, boolean showInfoDown) {
        super(modus, false, 2);
    }

    public InCCSChargingPanel(ChargeInfo info, int modus, int startType,
                              boolean showKwh, boolean usesCreditCard, boolean showInfoDown) {
        super(modus, usesCreditCard, 2);
        setInfo(info);
    }
}
