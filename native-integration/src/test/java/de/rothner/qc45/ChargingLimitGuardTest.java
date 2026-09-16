package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ChargingLimitGuardTest {
    @Test
    public void stopsActiveSessionThatDrawsPowerAboveNotladen() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, false, 5, 0);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, true);
        station.session[2] = true;
        station.power[2] = 35;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(0, station.stopCount);
        guard.runCycle(2100L);

        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
        assertEquals(5, station.limit[1]);
        assertEquals(5, station.limit[2]);
        assertEquals(5, station.limit[3]);
    }

    @Test
    public void keepsNotladenSessionOpenAtFiveKw() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        station.session[2] = true;
        station.power[2] = 5;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(5000L);

        assertEquals(0, station.stopCount);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
        assertEquals(5, station.limit[2]);
    }

    @Test
    public void allowsInitialQualificationToPublishSafeCcsTarget() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        station.session[2] = true;
        station.power[2] = 6;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(6000L);
        assertEquals(0, station.stopCount);

        limits.setGridTargets(2, false, 5, 0);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        guard.runCycle(6250L);
        assertEquals(5, limits.effectiveConnectorKw(2));
        assertEquals(0, station.stopCount);
    }

    @Test
    public void startupMismatchHardStopsPowerFarAboveNotladen() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        station.session[2] = true;
        station.power[2] = 35;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(2100L);

        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void hardStopsFirmwarePowerAboveIdlePrearm() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[2] = true;
        station.power[2] = 50;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(0, station.stopCount);
        assertEquals(5, station.limit[2]);
        guard.runCycle(2100L);

        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
        assertEquals(5, station.limit[2]);
    }

    @Test
    public void toleratesSmallTelemetryDifferenceAboveNotladen() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[2] = true;
        station.power[2] = 8;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(5000L);

        assertEquals(0, station.stopCount);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
    }

    private ChargingLimitCoordinator running(FakeStation station) throws Exception {
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, false, 30, 0);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[2] = true;
        station.power[2] = 30;
        return limits;
    }

    @Test
    public void recordedThirtyToFiveKwReductionDoesNotLatchWhileFalling() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = running(station);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, true);
        guard.runCycle(1250L);
        int[] powers = {29, 26, 20, 12, 5};
        long[] times = {1750, 2250, 2750, 3750, 4750};
        for (int i = 0; i < powers.length; i++) {
            station.power[2] = powers[i];
            guard.runCycle(times[i]);
        }
        assertEquals(0, station.stopCount);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
        assertEquals(5, station.limit[2]);
    }

    @Test
    public void stalledReductionStillStopsAndDeadlineCannotBeExtended() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = running(station);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, true);
        guard.runCycle(1250L);
        guard.runCycle(2750L);
        assertEquals(1, station.stopCount);

        station = new FakeStation();
        limits = running(station);
        guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, true);
        guard.runCycle(1250L);
        for (int i = 1; i <= 5; i++) {
            station.power[2] = 30 - i;
            guard.runCycle(1250L + i * 1000L);
        }
        assertEquals(1, station.stopCount);
    }

    @Test
    public void stopRetriesAtFiveKwAndAlsoStopsOtherChannels() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = running(station);
        station.stopWorks = false;
        station.power[2] = 40;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(2100L);
        station.power[2] = 5;
        station.session[3] = true;
        station.power[3] = 5;
        guard.runCycle(2350L);
        assertEquals(2, station.stopCount);
        guard.runCycle(4100L);
        assertEquals(3, station.stopCount);
        assertTrue(station.hardStop);
    }

    @Test
    public void mismatchResetsOnlyAfterIdleAndFreshSafeGridForSixtySeconds() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = running(station);
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);
        station.session[2] = false;
        station.power[2] = 0;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        for (long now = 1000; now <= 60000; now += 1000) {
            limits.recordRecoveryGrid(now, true);
            guard.runCycle(now);
        }
        assertTrue(limits.snapshot().limitMismatchBlocked);
        limits.recordRecoveryGrid(61000L, true);
        guard.runCycle(61000L);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
        assertTrue(!station.hardStop);
        assertEquals(0, limits.effectiveConnectorKw(2));
        assertEquals(5, station.limit[2]);
    }

    @Test
    public void staleMeterOrFailedReadCannotResetMismatch() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = running(station);
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);
        station.session[2] = false;
        station.power[2] = 0;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        limits.recordRecoveryGrid(1000L, true);
        guard.runCycle(1000L);
        guard.runCycle(62000L);
        assertTrue(limits.snapshot().limitMismatchBlocked);
        for (long now = 63000; now <= 122000; now += 1000) {
            limits.recordRecoveryGrid(now, true);
            guard.runCycle(now);
        }
        limits.recordRecoveryGrid(123000L, false);
        guard.runCycle(123000L);
        assertTrue(limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void activeSessionAndOtherBlockersPreventReset() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = running(station);
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);
        station.stopWorks = false;
        station.power[2] = 0;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        for (long now = 1000; now <= 62000; now += 1000) {
            limits.recordRecoveryGrid(now, true);
            guard.runCycle(now);
        }
        assertTrue(limits.snapshot().limitMismatchBlocked);
        station.session[2] = false;
        limits.setBlocked(ChargingLimitCoordinator.CONFIGURATION, true);
        for (long now = 63000; now <= 124000; now += 1000) {
            limits.recordRecoveryGrid(now, true);
            guard.runCycle(now);
        }
        assertTrue(limits.snapshot().limitMismatchBlocked);
        assertTrue(limits.snapshot().configurationBlocked);
    }

    @Test
    public void pressedEmergencyStopPreventsRecovery() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = running(station);
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);
        station.session[2] = false;
        station.power[2] = 0;
        station.epo = true;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        for (long now = 1000; now <= 62000; now += 1000) {
            limits.recordRecoveryGrid(now, true);
            guard.runCycle(now);
        }
        assertTrue(limits.snapshot().limitMismatchBlocked);
    }

    private static final class FakeStation implements ChargingLimitIo, ChargingSessionIo, ChargingSafetyIo {
        final int[] limit = new int[] { 0, 0, 0, 0 };
        final int[] power = new int[] { 0, 0, 0, 0 };
        final boolean[] session = new boolean[] { false, false, false, false };
        int stopCount;
        boolean stopWorks = true;
        boolean hardStop;
        boolean epo;
        public boolean emergencyStopPressed() { return epo; }
        public void setHardStopRequired(boolean value) { hardStop = value; }

        public int limitKw(int connector) { return limit[connector]; }

        public void setConnectorLimitKw(int connector, int kw) {
            limit[connector] = kw;
        }

        public void preArmConnectorLimitKw(int connector, int kw) {
            limit[connector] = kw;
        }

        public boolean sessionActive(int connector) {
            return session[connector] || power[connector] > 0;
        }

        public int powerKw(int connector) { return power[connector]; }

        public void remoteStop(int connector) {
            stopCount++;
            if (stopWorks) {
                session[connector] = false;
                power[connector] = 0;
            }
        }
    }
}
