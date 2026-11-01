package de.rothner.qc45;

/** A local transaction identity which cannot be inherited by the next vehicle. */
interface ChargingSessionIdentityIo extends ChargingSessionIo {
    String sessionIdentity(int connector) throws Exception;
}
