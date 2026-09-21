package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AcPowerLimitTransportTest {
    @Test
    public void doesNotRaceNativeStartHandshake() {
        long started = 1000L;
        assertFalse(AcPowerLimitTransport.shouldSendEnergy(
            started, started, 5, 5));
        assertFalse(AcPowerLimitTransport.shouldSendEnergy(
            started + 4999L, started, 6, 5));
    }

    @Test
    public void sendsChangedLimitAfterStartGrace() {
        long started = 1000L;
        assertTrue(AcPowerLimitTransport.shouldSendEnergy(
            started + 5000L, started, 6, 5));
        assertFalse(AcPowerLimitTransport.shouldSendEnergy(
            started + 5000L, started, 5, 5));
    }
}
