package de.rothner.qc45;

import java.io.IOException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises complete polling cycles through the coordinator and hardware boundary. */
public final class LoadManagerControlTest {
    @Test
    public void slowVehicleCannotAccumulateUnreachedLimits() throws Exception {
        Fixture f = new Fixture(5);
        f.station.active[2] = true;
        for (int i = 0; i < 15; i++) {
            f.tick(5.0d, 0);
            assertEquals(5, f.command());
        }
        f.tick(10.0d, 5);
        assertEquals(5, f.command());
        f.tick(10.0d, 5);
        assertEquals(10, f.command());
        for (int i = 0; i < 10; i++) {
            f.tick(10.0d, 5);
            assertEquals(10, f.command());
        }
    }

    @Test
    public void singleHighPowerReadingCannotUnlockNextRampStep() throws Exception {
        Fixture f = new Fixture(5);
        f.station.active[2] = true;
        f.tick(5.0d, 0);
        f.tick(8.0d, 5);
        f.tick(8.0d, 0);
        f.tick(8.0d, 5);
        assertEquals(5, f.command());
        f.tick(8.0d, 5);
        assertEquals(10, f.command());
    }

    @Test
    public void reducedCommandDoesNotReboundBeforeStationFollowsIt() throws Exception {
        Fixture f = new Fixture(5);
        f.station.active[2] = true;
        f.tick(5.0d, 5);
        f.tick(10.0d, 5);
        assertEquals(10, f.command());
        f.limits.requestDcBudget(5);
        f.tick(5.0d, 10, false);
        f.limits.requestDcBudget(35);
        f.tick(5.0d, 10);
        f.tick(5.0d, 10);
        assertEquals(5, f.command());
        f.tick(5.0d, 5);
        f.tick(5.0d, 5);
        assertEquals(10, f.command());
    }

    @Test
    public void smallCeilingExcessIsHeldButPermitWithdrawalStillStops() throws Exception {
        Fixture f = new Fixture(5);
        f.station.active[2] = true;
        f.tick(5.0d, 5);
        f.tick(10.0d, 5);
        assertEquals(10, f.command());
        f.tick(15.0d, 10);
        f.tick(35.1d, 10);
        assertEquals(10, f.command());
        f.limits.requestDcBudget(0);
        f.tick(5.0d, 5, false);
        assertEquals(0, f.limits.effectiveDcKw());
        assertEquals(5, f.command());
    }

    @Test
    public void meterFailureDropsToNotladenAndRequalifiesBeforeRamping() throws Exception {
        Fixture f = new Fixture(5);
        f.station.active[2] = true;
        f.tick(5.0d, 5);
        f.tick(10.0d, 5);
        assertEquals(10, f.command());
        f.meter.fail = true;
        f.tick(10.0d, 10);
        assertEquals(5, f.command());
        assertTrue(f.limits.snapshot().loadMeterBlocked);
        f.meter.fail = false;
        for (int i = 0; i < 4; i++) {
            f.tick(5.0d, 5);
            assertEquals(5, f.command());
            assertTrue(f.limits.snapshot().loadMeterBlocked);
        }
        f.tick(5.0d, 5);
        assertEquals(5, f.command());
        assertFalse(f.limits.snapshot().loadMeterBlocked);
        f.tick(5.0d, 5);
        assertEquals(10, f.command());
    }

    @Test
    public void powerReadFailureCannotReleaseHigherBudget() throws Exception {
        Fixture f = new Fixture(5);
        f.station.active[2] = true;
        f.tick(5.0d, 5);
        f.tick(10.0d, 5);
        f.station.failPower = true;
        f.tick(5.0d, 5);
        assertEquals(5, f.command());
        assertTrue(f.limits.snapshot().loadMeterBlocked);
    }

    @Test
    public void connectorChangeDiscardsPreviousVehicleCatchupEvidence() throws Exception {
        Fixture f = new Fixture(5);
        f.station.active[2] = true;
        f.tick(5.0d, 5);
        f.tick(10.0d, 5);
        assertEquals(10, f.command());
        f.station.active[2] = false;
        f.station.active[1] = true;
        f.station.power[1] = 0;
        f.tick(5.0d, 10);
        assertEquals(5, f.station.limit[1]);
        assertEquals(5, f.station.limit[2]);
    }

    @Test
    public void delayedVehicleAndOneCycleDelayedMeterConvergeWithoutGuardSawtooth()
            throws Exception {
        // Rectifier reaction is five cycles late; KSEM is one sample late.
        // 14.7 kW of site support makes a full 35 kW exceed the 35 A ceiling,
        // as in the live session. The old controller accumulated increases.
        Fixture f = new Fixture(1);
        f.station.active[2] = true;
        int[] delayedCommands = { 5, 5, 5, 5, 5 };
        int actual = 0;
        double measuredA = 4.0d;
        int minSettledCommand = 35;
        int maxSettledCommand = 0;
        for (int tick = 0; tick < 300; tick++) {
            int wanted = delayedCommands[tick % delayedCommands.length];
            actual += Math.max(-8, Math.min(2, wanted - actual));
            double realA = 4.0d + Math.max(0.0d, actual - 14.7d) / 0.65d;
            f.tick(measuredA, actual);
            assertTrue("guard reached at tick " + tick, realA < 35.0d);
            delayedCommands[tick % delayedCommands.length] = f.command();
            measuredA = realA;
            if (tick > 200) {
                minSettledCommand = Math.min(minSettledCommand, f.command());
                maxSettledCommand = Math.max(maxSettledCommand, f.command());
            }
        }
        assertTrue("charging must make progress", minSettledCommand >= 25);
        assertTrue("steady limit must stop oscillating",
            maxSettledCommand - minSettledCommand <= 1);
        assertEquals("normal DC control must not write AC", 0, f.station.acWrites);
    }

    private static final class Fixture {
        final FakeStation station = new FakeStation();
        final FakeMeter meter = new FakeMeter();
        final ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, 5, 50, 5, 43, false);
        final LoadManager manager;

        Fixture(int ramp) throws Exception {
            limits.initializeNotladen();
            limits.setCcsAvailable(true);
            manager = new LoadManager(station, meter, limits,
                34.0d, 35.0d, 1.5d, 5, 50, 5, 43,
                ramp, 1000, 5000L, 2, 34.0d);
            for (int i = 0; i < 5; i++) tick(4.0d, 0);
        }

        void tick(double criticalA, int actualKw) throws Exception {
            tick(criticalA, actualKw, true);
        }

        void tick(double criticalA, int actualKw, boolean renewPermit) throws Exception {
            if (renewPermit) limits.requestDcBudget(35);
            meter.criticalA = criticalA;
            station.power[2] = actualKw;
            manager.runCycle(System.currentTimeMillis());
        }

        int command() { return station.limit[2]; }
    }

    private static final class FakeMeter implements GridCurrentIo {
        double criticalA;
        boolean fail;
        public KsemClient.Currents readCurrents() throws Exception {
            if (fail) throw new IOException("simulated KSEM failure");
            // L2 is deliberately the highest phase.
            return new KsemClient.Currents(Math.max(0.0d, criticalA - 3.0d), criticalA,
                Math.max(0.0d, criticalA - 1.0d));
        }
    }

    private static final class FakeStation implements ChargingSessionIo, ChargingLimitIo {
        final int[] limit = { 0, 0, 0, 0 };
        final int[] power = { 0, 0, 0, 0 };
        final boolean[] active = { false, false, false, false };
        boolean failPower;
        int acWrites;
        public boolean sessionActive(int connector) { return active[connector]; }
        public int powerKw(int connector) throws Exception {
            if (failPower) throw new IOException("simulated DC telemetry failure");
            return power[connector];
        }
        public void remoteStop(int connector) { active[connector] = false; }
        public int limitKw(int connector) { return limit[connector]; }
        public void setConnectorLimitKw(int connector, int kw) {
            if (connector == 3) acWrites++;
            limit[connector] = kw;
        }
        public void preArmConnectorLimitKw(int connector, int kw) {
            setConnectorLimitKw(connector, kw);
        }
    }
}
