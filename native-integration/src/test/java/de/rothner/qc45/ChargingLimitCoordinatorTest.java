package de.rothner.qc45;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ChargingLimitCoordinatorTest {
    @Test
    public void startsWithFailClosedEvccRequests() {
        ChargingLimitCoordinator limits = coordinator(new FakeIo());
        assertEquals(0, limits.requestedDcKw());
        assertEquals(0, limits.requestedAcKw());
    }

    @Test
    public void safetyBlockCannotBeOverwrittenByEvcc() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.setGridTargets(1, true, 30, 20);
        limits.requestBudgets(50, 22);
        assertLimits(io, 0, 0, 0);

        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        assertLimits(io, 30, 0, 20);
        limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);
        limits.requestBudgets(50, 22);
        assertLimits(io, 0, 0, 0);
    }

    @Test
    public void evccDecreaseDiscardsStaleGridRelease() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.requestBudgets(50, 22);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, false, 30, 0);
        assertEquals(30, io.value[1]);

        limits.requestDcBudget(5);
        assertEquals(5, io.value[1]);
        limits.requestDcBudget(30);
        assertEquals("increase waits for a new LoadManager grid target", 5, io.value[1]);
    }

    @Test
    public void subMinimumRequestMeansPause() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, true, 20, 20);
        limits.requestBudgets(4, 1);
        assertEquals(0, limits.requestedDcKw());
        assertEquals(0, limits.requestedAcKw());
        assertLimits(io, 0, 0, 0);
    }

    @Test
    public void connectorSwitchReducesBeforeItIncreases() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.requestBudgets(50, 22);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, true, 20, 20);
        io.operations.clear();

        limits.setGridTargets(2, true, 20, 20);
        assertLimits(io, 0, 20, 20);
        assertTrue(io.operations.indexOf("1=0") >= 0);
        assertTrue(io.operations.indexOf("2=20") > io.operations.indexOf("1=0"));
    }

    @Test
    public void ccsRemainsZeroUntilV3IsAvailable() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.requestBudgets(50, 22);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(2, false, 20, 0);
        assertEquals(0, io.value[2]);
        limits.setCcsAvailable(true);
        assertEquals(20, io.value[2]);
    }

    @Test
    public void getterFailureFailsClosedOnEveryConnector() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.requestBudgets(50, 22);
        limits.setCcsAvailable(true);
        limits.setGridTargets(1, true, 20, 20);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        io.failReadConnector = 2;

        try {
            limits.reconcile();
            fail("read failure expected");
        } catch (Exception expected) {
            assertEquals("read failed", expected.getMessage());
        }
        assertLimits(io, 0, 0, 0);
    }

    @Test
    public void snapshotSeparatesEvccGridSafetyAndEffectiveLimits() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.requestBudgets(50, 22);
        limits.setGridTargets(2, true, 17, 13, true);

        ChargingLimitCoordinator.Snapshot blocked = limits.snapshot();
        assertTrue(blocked.blocked);
        assertTrue(blocked.startupBlocked);
        assertEquals(50, blocked.requestedDcKw);
        assertEquals(17, blocked.gridDcKw);
        assertEquals(0, blocked.effectiveDcKw);

        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setStageCaps(15, 12);
        ChargingLimitCoordinator.Snapshot active = limits.snapshot();
        assertEquals(2, active.activeDcConnector);
        assertTrue(active.acActive);
        assertTrue(active.demandTransfer);
        assertTrue(active.stageLimited);
        assertEquals(15, active.effectiveDcKw);
        assertEquals(12, active.effectiveAcKw);
    }

    private static ChargingLimitCoordinator coordinator(FakeIo io) {
        return new ChargingLimitCoordinator(io, 5, 50, 5, 22);
    }

    private static void assertLimits(FakeIo io, int c1, int c2, int c3) {
        assertEquals(c1, io.value[1]);
        assertEquals(c2, io.value[2]);
        assertEquals(c3, io.value[3]);
    }

    private static final class FakeIo implements ChargingLimitIo {
        final int[] value = new int[] { 0, 50, 50, 22 };
        final List<String> operations = new ArrayList<String>();
        int failReadConnector;

        public int limitKw(int connector) throws Exception {
            if (connector == failReadConnector) throw new Exception("read failed");
            return value[connector];
        }

        public void setConnectorLimitKw(int connector, int kw) {
            value[connector] = kw;
            operations.add(connector + "=" + kw);
        }
    }
}
