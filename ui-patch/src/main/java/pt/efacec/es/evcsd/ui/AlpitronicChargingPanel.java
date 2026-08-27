package pt.efacec.es.evcsd.ui;

import pt.efacec.es.evcsd.ui.info.ChargeInfo;

/** Active sessions all use the approved Modbus-backed charging monitor. */
class AlpitronicChargingPanel extends WaitingForCardChargingTimer {
    AlpitronicChargingPanel(int modus, boolean usesCreditCard) {
        this(modus, usesCreditCard, 0);
    }

    AlpitronicChargingPanel(int modus, boolean usesCreditCard, int connector) {
        super("de", modus, usesCreditCard, true, connector);
        AlpitronicSessionState.markCharging();
    }

    public void setInfo(ChargeInfo info) {
        super.setInfo(info);
    }
}
