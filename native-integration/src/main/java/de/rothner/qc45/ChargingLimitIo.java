package de.rothner.qc45;

/** Minimal hardware boundary used by the safety coordinator. */
interface ChargingLimitIo {
    int limitKw(int connector) throws Exception;
    void setConnectorLimitKw(int connector, int kw) throws Exception;
    /**
     * Prepare the satellite's next-session start value without authorizing or
     * starting a charge and without changing the station-wide configuration.
     */
    void preArmConnectorLimitKw(int connector, int kw) throws Exception;
}
