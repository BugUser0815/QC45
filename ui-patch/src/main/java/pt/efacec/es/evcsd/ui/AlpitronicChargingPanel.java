package pt.efacec.es.evcsd.ui;

import pt.efacec.es.evcsd.ui.info.ChargeInfo;

/** Active sessions all use the approved Modbus-backed charging monitor. */
class AlpitronicChargingPanel extends WaitingForCardChargingTimer {
    AlpitronicChargingPanel(int modus, boolean usesCreditCard) {
        super("de", modus, usesCreditCard, true);
        AlpitronicSessionState.markCharging();
    }

    public void setInfo(ChargeInfo info) {
        super.setInfo(info);
    }
}
