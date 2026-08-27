package de.rothner.qc45;

/** Minimal hardware boundary used by the safety coordinator. */
interface ChargingLimitIo {
    int limitKw(int connector) throws Exception;
    void setConnectorLimitKw(int connector, int kw) throws Exception;
}
