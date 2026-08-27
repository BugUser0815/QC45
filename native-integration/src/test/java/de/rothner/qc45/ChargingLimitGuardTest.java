package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ChargingLimitGuardTest {
    @Test
    public void stopsActiveSessionThatDrawsPowerAgainstZeroTarget() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeSafeZero();
        station.session[2] = true;
        station.power[2] = 35;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(0, station.stopCount);
        guard.runCycle(1800L);

        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
        assertEquals(0, limits.effectiveConnectorKw(1));
        assertEquals(0, limits.effectiveConnectorKw(2));
        assertEquals(0, limits.effectiveConnectorKw(3));
    }

    @Test
    public void keepsSuspendedZeroPowerSessionOpen() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeSafeZero();
        station.session[2] = true;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(2000L);

        assertEquals(0, station.stopCount);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
    }

    private static final class FakeStation implements ChargingLimitIo, ChargingSessionIo {
        final int[] limit = new int[] { 0, 0, 0, 0 };
        final int[] power = new int[] { 0, 0, 0, 0 };
        final boolean[] session = new boolean[] { false, false, false, false };
        int stopCount;

        public int limitKw(int connector) { return limit[connector]; }

        public void setConnectorLimitKw(int connector, int kw) {
            limit[connector] = kw;
        }

        public boolean sessionActive(int connector) {
            return session[connector] || power[connector] > 0;
        }

        public int powerKw(int connector) { return power[connector]; }

        public void remoteStop(int connector) {
            stopCount++;
            session[connector] = false;
            power[connector] = 0;
        }
    }
}
