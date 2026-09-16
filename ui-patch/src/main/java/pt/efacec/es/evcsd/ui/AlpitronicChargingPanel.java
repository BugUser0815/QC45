package pt.efacec.es.evcsd.ui;

import pt.efacec.es.evcsd.ui.info.ChargeInfo;

/** Active sessions all use the approved Modbus-backed charging monitor. */
class AlpitronicChargingPanel extends WaitingForCardChargingTimer {
    private final SafetyStatusOverlay safetyStatus;

    AlpitronicChargingPanel(int modus, boolean usesCreditCard) {
        this(modus, usesCreditCard, 0);
    }

    AlpitronicChargingPanel(int modus, boolean usesCreditCard, int connector) {
        super("de", modus, usesCreditCard, true, connector);
        AlpitronicSessionState.markCharging();
        safetyStatus = new SafetyStatusOverlay();
        safetyStatus.setBounds(0, 416, 640, 64);
        add(safetyStatus);
        setComponentZOrder(safetyStatus, 0);
        safetyStatus.startMonitoring();
    }

    public void start() {
        super.start();
        safetyStatus.startMonitoring();
    }

    public void stop() {
        safetyStatus.stopMonitoring();
        super.stop();
    }

    public void setInfo(ChargeInfo info) {
        super.setInfo(info);
    }
}
