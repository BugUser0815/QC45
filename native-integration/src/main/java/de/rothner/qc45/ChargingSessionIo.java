package de.rothner.qc45;

/** Minimal live-session surface used by the independent limit guard. */
interface ChargingSessionIo {
    boolean sessionActive(int connector) throws Exception;
    int powerKw(int connector) throws Exception;
    void remoteStop(int connector) throws Exception;
}
