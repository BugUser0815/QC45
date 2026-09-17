package pt.efacec.es.evcsd.ui;

import pt.efacec.es.evcsd.ui.info.ChargeInfo;

public class InChademoChargingPanel extends AlpitronicChargingPanel {
    public InChademoChargingPanel(int modus, int startType, boolean showKwh, boolean showInfoDown) {
        super(modus, false, 1);
    }

    public InChademoChargingPanel(ChargeInfo info, int modus, int startType,
                                  boolean showKwh, boolean usesCreditCard, boolean showInfoDown) {
        super(modus, usesCreditCard, 1);
        setInfo(info);
    }
}
