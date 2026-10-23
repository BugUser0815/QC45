package de.rothner.qc45;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Complete protection cycles, including the hardware writer and stop retries. */
public final class GridFailbackControlTest {
    @Test public void aSmallCrossingOfRatedCurrentDoesNotResetChargingToFiveKw() throws Exception {
        Fixture f = new Fixture(35, 36, 38, 175);
        for (int i = 0; i < 50; i++) f.tick(35.1d, 35);
        assertEquals(35, f.limits.effectiveDcKw());
        assertFalse(f.guard.isChargingBlocked());
        assertEquals(0, f.station.stops);
    }

    @Test public void shortThirtyEightAmpPeakIsReducedProportionallyAndRecoversInPlace()
            throws Exception {
        Fixture f = new Fixture(35, 36, 38, 175);
        for (int i = 0; i < 8; i++) f.tick(38.5d, 35);
        assertEquals(32, f.limits.effectiveDcKw());
        assertFalse(f.guard.isTripped());
        assertFalse(f.guard.isChargingBlocked());
        for (int i = 0; i < 6; i++) f.tick(33.0d, 32);
        assertFalse(f.limits.snapshot().stageLimited);
        assertEquals(32, f.limits.effectiveDcKw());
        assertEquals(0, f.station.stops);
        assertEquals(0, f.station.acWrites);
    }

    @Test public void delayedPowerReductionIsNotSubtractedAgain() throws Exception {
        Fixture f = new Fixture(35, 36, 38, 175);
        for (int i = 0; i < 18; i++) f.tick(38.5d, 35);
        assertEquals(32, f.limits.effectiveDcKw());
        for (int i = 0; i < 10; i++) f.tick(38.5d, 32);
        assertEquals(29, f.limits.effectiveDcKw());
    }

    @Test public void operatorThresholdBelowRatingStillStartsReduction() throws Exception {
        Fixture f = new Fixture(35, 30, 38, 175);
        for (int i = 0; i < 5; i++) f.tick(31.0d, 35);
        assertEquals(35, f.limits.effectiveDcKw());
        f.tick(31.0d, 35);
        assertEquals(34, f.limits.effectiveDcKw());
    }

    @Test public void fiftyAmpBreakerHoldsAboveThirtyFiveAndUsesItsOwnReductionThreshold()
            throws Exception {
        Fixture f = new Fixture(50, 51, 53, 250);
        for (int i = 0; i < 20; i++) f.tick(50.5d, 35);
        assertEquals(35, f.limits.effectiveDcKw());
        for (int i = 0; i < 8; i++) f.tick(54.0d, 35);
        assertEquals(32, f.limits.effectiveDcKw());
        assertFalse(f.guard.isTripped());
    }

    @Test public void operatorInstantThresholdIsRespectedWithoutWaitingForThermalModel()
            throws Exception {
        Fixture f = new Fixture(35, 36, 38, 70);
        f.tick(70.0d, 35);
        assertTrue(f.guard.isTripped());
        assertEquals(1, f.station.stops);
        assertEquals(5, f.station.limit[2]);
    }

    @Test public void magneticThresholdScalesWithInstalledRating() throws Exception {
        Fixture f = new Fixture(50, 51, 53, 250);
        f.tick(249.0d, 35);
        assertFalse(f.guard.isTripped());
        f.tick(250.0d, 35);
        assertTrue(f.guard.isTripped());
        assertEquals(1, f.station.stops);
    }

    @Test public void persistentOverloadEventuallyTripsAndKeepsRetryingFailedStops()
            throws Exception {
        Fixture f = new Fixture(35, 36, 38, 175);
        f.station.stopFails = true;
        for (int i = 0; i < 120; i++) f.tick(42.0d, 35);
        assertTrue(f.guard.isTripped());
        int attempts = f.station.stops;
        for (int i = 0; i < 30; i++) f.tick(20.0d, 5);
        assertTrue(f.station.stops > attempts);
        assertTrue(f.guard.isChargingBlocked());
    }

    @Test public void missingKsemBlocksImmediatelyAndNeverCountsAsCooling() throws Exception {
        Fixture f = new Fixture(35, 36, 38, 175);
        for (int i = 0; i < 35; i++) f.tick(42.0d, 35);
        double before = f.guard.thermalExposure();
        f.meter.fail = true;
        for (int i = 0; i < 40; i++) f.tick(0.0d, 0);
        assertTrue(f.guard.isMeterPaused());
        assertEquals(5, f.station.limit[2]);
        assertEquals(before, f.guard.thermalExposure(), 0.000001d);
        f.meter.fail = false;
        f.tick(5.0d, 5);
        assertEquals(before, f.guard.thermalExposure(), 0.000001d);
        for (int i = 0; i < 4; i++) f.tick(5.0d, 5);
        assertFalse(f.guard.isMeterPaused());
    }

    @Test public void hardTripNeedsCoolingStableReadingsAndInactiveSessions() throws Exception {
        Fixture f = new Fixture(35, 36, 38, 175);
        f.tick(175, 35);
        assertTrue(f.guard.isTripped());
        // A successful RemoteStop in this fixture confirms inactivity.
        for (int i = 0; i < 1800; i++) f.tick(5, 0);
        assertFalse(f.guard.isTripped());
        assertFalse(f.guard.isMeterPaused());
        assertEquals(0, f.limits.effectiveDcKw());
    }

    @Test public void bothControllersHandleDelayedVehicleAndRepeatedSiteLoadPeaks()
            throws Exception {
        for (double nominal : new double[] { 35, 50 }) {
            Fixture f = new Fixture(nominal, nominal + 1, nominal + 3, nominal * 5);
            double target = nominal - 1;
            LoadManager manager = new LoadManager(f.station, f.meter, f.limits,
                target, nominal, 1.5d, 5, 50, 5, 43,
                5, 1000, 5000L, 2, target);
            // Start with a fresh allocation, then a three-second vehicle lag.
            f.limits.setGridTargets(2, false, 5, 0);
            int[] delayed = { 5, 5, 5 };
            int actual = 5;
            double measured = 5;
            int minimum = 50;
            int maximum = 0;
            for (int tick = 0; tick < 2400; tick++) {
                if (tick % 10 == 0) {
                    int slot = (tick / 10) % delayed.length;
                    actual += Math.max(-4, Math.min(2, delayed[slot] - actual));
                    f.station.power[2] = actual;
                    f.meter.amps = measured;
                    f.limits.requestDcBudget(35);
                    manager.runCycle(System.currentTimeMillis());
                    delayed[slot] = f.station.limit[2];
                }
                double baseA = nominal == 35 ? 7.0d : 18.0d;
                double sitePeakA = tick > 600 && tick % 200 < 15 ? 2.5d : 0.0d;
                measured = baseA + actual / 0.65d + sitePeakA;
                f.tick(measured, actual);
                assertFalse("thermal stop at E" + nominal + " cycle " + tick,
                    f.guard.isTripped());
                if (tick > 600) {
                    minimum = Math.min(minimum, f.limits.effectiveDcKw());
                    maximum = Math.max(maximum, f.limits.effectiveDcKw());
                }
            }
            assertTrue("no recurring collapse to Notladen at E" + nominal, minimum >= 12);
            assertTrue("recovery stays near the operating point", maximum - minimum <= 5);
            assertEquals(0, f.station.stops);
            assertEquals(0, f.station.acWrites);
        }
    }

    private static final class Fixture {
        final FakeStation station = new FakeStation();
        final FakeMeter meter = new FakeMeter();
        final ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43, false);
        final GridFailback guard;
        long now = 1000L;

        Fixture(double nominal, double reduce, double trip, double instant) throws Exception {
            limits.initializeNotladen();
            limits.setCcsAvailable(true);
            limits.requestDcBudget(50);
            limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
            limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);
            guard = new GridFailback(station, meter, limits,
                reduce, 500L, trip, 250L, instant, 5, 5, 100,
                60000L, nominal, 30000L);
            for (int i = 0; i < 5; i++) tick(5, 0);
            station.active[2] = true;
            station.power[2] = 35;
            limits.setGridTargets(2, false, 35, 0);
        }

        void tick(double criticalA, int actualKw) {
            meter.amps = criticalA;
            station.power[2] = actualKw;
            guard.runCycle(now);
            now += 100L;
        }
    }

    private static final class FakeMeter implements GridCurrentIo {
        double amps;
        boolean fail;
        public KsemClient.Currents readCurrents() throws Exception {
            if (fail) throw new IOException("KSEM unavailable");
            return new KsemClient.Currents(Math.max(0, amps - 4), amps, Math.max(0, amps - 2));
        }
    }

    private static final class FakeStation implements ChargingSessionIo, ChargingLimitIo {
        final int[] limit = new int[4];
        final int[] power = new int[4];
        final boolean[] active = new boolean[4];
        int stops;
        int acWrites;
        boolean stopFails;
        public boolean sessionActive(int connector) { return active[connector]; }
        public int powerKw(int connector) { return power[connector]; }
        public int limitKw(int connector) { return limit[connector]; }
        public void remoteStop(int connector) throws Exception {
            stops++;
            if (stopFails) throw new IOException("stop still pending");
            active[connector] = false;
        }
        public void setConnectorLimitKw(int connector, int kw) {
            if (connector == 3) acWrites++;
            limit[connector] = kw;
        }
        public void preArmConnectorLimitKw(int connector, int kw) { setConnectorLimitKw(connector, kw); }
    }
}
