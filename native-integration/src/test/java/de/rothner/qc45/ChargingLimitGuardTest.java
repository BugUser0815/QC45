package de.rothner.qc45;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.fail;

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

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(0, station.stopCount);
        guard.runCycle(6100L);

        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
        assertEquals(5, station.limit[1]);
        assertEquals(5, station.limit[2]);
        assertEquals(5, station.limit[3]);
    }

    @Test
    public void guardsPhysicalFiveKwAcNotladenWithoutStoppingIt() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        station.session[3] = true;
        station.power[3] = 5;

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(6100L);

        assertEquals(0, station.stopCount);
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

        limits.requestDcBudget(50);
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

        limits.requestDcBudget(50);
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

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(6100L);

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

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(0, station.stopCount);
        assertEquals(5, station.limit[2]);
        guard.runCycle(6100L);

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

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(5000L);

        assertEquals(0, station.stopCount);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void allowsQc45ToRampDownWhilePowerKeepsMakingProgress() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, false, 5, 0);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[2] = true;

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        station.power[2] = 35;
        guard.runCycle(1000L);
        station.power[2] = 31;
        guard.runCycle(3000L);
        station.power[2] = 26;
        guard.runCycle(6500L);
        station.power[2] = 20;
        guard.runCycle(10000L);
        station.power[2] = 14;
        guard.runCycle(14000L);
        station.power[2] = 9;
        guard.runCycle(18000L);
        station.power[2] = 8;
        guard.runCycle(22000L);

        assertEquals(0, station.stopCount);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void hardStopsOnlyAfterPowerStallsAboveLimit() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, false, 5, 0);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[2] = true;

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        station.power[2] = 35;
        guard.runCycle(1000L);
        station.power[2] = 30;
        guard.runCycle(3000L);
        station.power[2] = 30;
        guard.runCycle(7900L);
        assertEquals(0, station.stopCount);
        guard.runCycle(8101L);

        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void clearsMismatchBlockAfterHardStoppedConnectorIsInactive() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, false, 5, 0);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[2] = true;
        station.power[2] = 35;

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(6100L);
        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);

        guard.runCycle(6350L);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void retriesHardStopAtNotladenUntilSessionActuallyEnds() throws Exception {
        FakeStation station = new FakeStation();
        station.stopEndsSession = false;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, false, 5, 0);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[2] = true;
        station.power[2] = 35;

        limits.requestDcBudget(50);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        guard.runCycle(6100L);
        assertEquals(1, station.stopCount);

        station.power[2] = 5;
        guard.runCycle(7000L);
        assertEquals(1, station.stopCount);
        guard.runCycle(8200L);
        assertEquals(2, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);

        station.session[2] = false;
        station.power[2] = 0;
        guard.runCycle(8450L);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void immediatelyStopsUnauthorizedChademoEvenAtFiveKw() throws Exception {
        FakeStation station = new FakeStation();
        station.stopEndsSession = false;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        station.session[1] = true;
        station.power[1] = 5;

        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
        guard.runCycle(3100L);
        assertEquals(2, station.stopCount);
        station.session[1] = false;
        station.power[1] = 0;
        guard.runCycle(3400L);
        assertTrue(!limits.snapshot().limitMismatchBlocked);
    }

    @Test
    public void immediatelyStopsUnauthorizedCcsAtAnyPower() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.initializeNotladen();
        station.session[2] = true;
        station.power[2] = 35;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(1, station.stopCount);
        assertEquals(5, station.limit[2]);
    }

    @Test public void brokenChademoStatusDoesNotSkipUnauthorizedCcs() throws Exception {
        FakeStation station = new FakeStation();
        station.failSessionConnector = 1; station.session[2] = true;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43, false);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        try { guard.runCycle(1000L); fail("status error must be reported"); }
        catch (IOException expected) {}
        assertEquals(1, station.stops[2]);
    }

    @Test public void missingPowerDoesNotBypassPermitOrLatchedStop() throws Exception {
        FakeStation station = new FakeStation();
        station.session[2] = true; station.stopEndsSession = false; station.failPowerConnector = 2;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43, false);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L);
        assertEquals(1, station.stops[2]);
        station.failSessionConnector = 2;
        try { guard.runCycle(3100L); fail("unknown session must be reported"); }
        catch (IOException expected) {}
        assertEquals(2, station.stops[2]);
        assertTrue(limits.snapshot().limitMismatchBlocked);
    }

    @Test public void failedSafetyLimitWriteStillAttemptsImmediateStop() throws Exception {
        FakeStation station = new FakeStation(); station.session[2] = true;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43, false);
        limits.initializeNotladen(); station.failWriteConnector = 2;
        try { new ChargingLimitGuard(station, limits, 250).runCycle(1000L); fail("write error must be reported"); }
        catch (IOException expected) {}
        assertEquals(1, station.stops[2]);
    }

    @Test public void failedChademoStopDoesNotSkipCcsStop() throws Exception {
        FakeStation station = new FakeStation(); station.session[1] = true; station.session[2] = true;
        station.failStopConnector = 1;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43, false);
        try { new ChargingLimitGuard(station, limits, 250).runCycle(1000L); fail("stop error must be reported"); }
        catch (IOException expected) {}
        assertEquals(1, station.stops[2]);
    }

    @Test public void networkTimeoutKeepsTheSamePermittedDcTransactionAtFiveKw() throws Exception {
        for (int connector = 1; connector <= 2; connector++) {
            FakeStation station = new FakeStation();
            ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
            limits.initializeNotladen(); limits.setCcsAvailable(true);
            limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
            limits.requestDcBudget(35); limits.setGridTargets(connector, false, 20, 0);
            station.session[connector] = true; station.power[connector] = 20;
            ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
            guard.runCycle(1000L);
            expire(limits);
            limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, true);
            guard.runCycle(2000L);
            station.power[connector] = 5;
            guard.runCycle(4000L); guard.runCycle(120000L);
            assertEquals(0, station.stopCount);
            assertEquals(5, station.limit[connector]);
            assertTrue(!limits.snapshot().limitMismatchBlocked);
        }
    }

    @Test public void explicitZeroStillStopsAPreviouslyPermittedFallbackSession() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.requestDcBudget(35); station.session[2] = true; station.power[2] = 5;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L); expire(limits); guard.runCycle(2000L);
        assertEquals(0, station.stopCount);
        limits.requestDcBudget(0); guard.runCycle(3000L);
        assertEquals(1, station.stopCount);
    }

    @Test public void networkFallbackCannotAuthorizeANewlyObservedSession() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.requestDcBudget(35); expire(limits);
        station.session[2] = true; station.power[2] = 5;
        new ChargingLimitGuard(station, limits, 250).runCycle(1000L);
        assertEquals(1, station.stopCount);
    }

    @Test public void missingTransactionIdentityCannotCreateContinuationPermission() throws Exception {
        FakeStation station = new FakeStation(); station.identity[2] = null;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.requestDcBudget(35); station.session[2] = true; station.power[2] = 5;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L); expire(limits); guard.runCycle(2000L);
        assertEquals(1, station.stopCount);
    }

    @Test public void replacementTransactionCannotInheritFallbackEvenWithoutAnIdleSample() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.requestDcBudget(35); station.session[2] = true; station.power[2] = 5;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L); expire(limits);
        station.identity[2] = "replacement-transaction";
        guard.runCycle(2000L);
        assertEquals(1, station.stopCount);
    }

    @Test public void unpluggingClearsFallbackEvenIfTheNextTransactionReusesItsIdentifier() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.requestDcBudget(35); station.session[2] = true; station.power[2] = 5;
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L); expire(limits);
        station.session[2] = false; station.power[2] = 0;
        guard.runCycle(2000L);
        station.session[2] = true; station.power[2] = 5;
        guard.runCycle(3000L);
        assertEquals(1, station.stopCount);
    }

    @Test public void aRealLimitMismatchStillStopsDuringNetworkFallback() throws Exception {
        FakeStation station = new FakeStation();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(station, 5, 50, 5, 43);
        limits.requestDcBudget(35); station.session[2] = true; station.power[2] = 20;
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(2, false, 20, 0); limits.setCcsAvailable(true);
        ChargingLimitGuard guard = new ChargingLimitGuard(station, limits, 250);
        guard.runCycle(1000L); expire(limits);
        guard.runCycle(2000L); guard.runCycle(7100L);
        assertEquals(1, station.stopCount);
        assertTrue(limits.snapshot().limitMismatchBlocked);
    }

    private static void expire(ChargingLimitCoordinator limits) throws Exception {
        limits.expireDcRequestIfStale(System.currentTimeMillis()
            + ChargingLimitCoordinator.DC_REQUEST_TIMEOUT_MS + 1L);
    }

    private static final class FakeStation implements ChargingLimitIo, ChargingSessionIdentityIo {
        final int[] limit = new int[] { 0, 0, 0, 0 };
        final int[] power = new int[] { 0, 0, 0, 0 };
        final boolean[] session = new boolean[] { false, false, false, false };
        int stopCount;
        boolean stopEndsSession = true;
        int failSessionConnector, failPowerConnector, failWriteConnector, failStopConnector;
        final int[] stops = new int[4];
        final String[] identity = { null, "transaction-1", "transaction-2", "transaction-3" };

        public String sessionIdentity(int connector) { return identity[connector]; }

        public int limitKw(int connector) { return limit[connector]; }

        public void setConnectorLimitKw(int connector, int kw) throws IOException {
            if (connector == failWriteConnector) throw new IOException("write failed");
            limit[connector] = kw;
        }

        public void preArmConnectorLimitKw(int connector, int kw) {
            limit[connector] = kw;
        }

        public boolean sessionActive(int connector) throws IOException {
            if (connector == failSessionConnector) throw new IOException("session failed");
            return session[connector] || power[connector] > 0;
        }

        public int powerKw(int connector) throws IOException {
            if (connector == failPowerConnector) throw new IOException("power failed");
            return power[connector];
        }

        public void remoteStop(int connector) throws IOException {
            if (connector == failStopConnector) throw new IOException("stop failed");
            stopCount++;
            stops[connector]++;
            if (stopEndsSession) {
                session[connector] = false;
                power[connector] = 0;
            }
        }
    }
}
