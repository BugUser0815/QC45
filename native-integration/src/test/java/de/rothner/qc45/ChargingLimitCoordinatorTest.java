package de.rothner.qc45;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ChargingLimitCoordinatorTest {
    @Test
    public void startsAutonomousUntilEvccWrites() {
        ChargingLimitCoordinator limits = coordinator(new FakeIo());
        assertEquals(50, limits.requestedDcKw());
        assertEquals(43, limits.requestedAcKw());
        assertTrue(!limits.evccControlsDc());
        assertTrue(!limits.evccControlsAc());
        assertTrue(limits.snapshot().startupBlocked);
    }

    @Test
    public void firstEvccWriteTakesOverOnlyItsOwnChannel() throws Exception {
        ChargingLimitCoordinator limits = coordinator(new FakeIo());
        limits.requestDcBudget(0);
        assertEquals(0, limits.requestedDcKw());
        assertEquals(43, limits.requestedAcKw());
        assertTrue(limits.evccControlsDc());
        assertTrue(!limits.evccControlsAc());
    }

    @Test
    public void autonomousAcAndDcReleaseOnlyAfterSafeGridTarget() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, true, 15, 15);
        assertLimits(io, 0, 0, 0);

        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        assertLimits(io, 0, 15, 15);

        limits.requestDcBudget(0);
        assertLimits(io, 0, 0, 15);
        assertTrue(limits.evccControlsDc());
        assertTrue(!limits.evccControlsAc());
    }

    @Test
    public void safetyBlockCannotBeOverwrittenByEvcc() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.setGridTargets(1, true, 30, 20);
        limits.requestBudgets(50, 43);
        assertLimits(io, 0, 0, 0);

        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        assertLimits(io, 30, 0, 20);
        limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);
        limits.requestBudgets(50, 43);
        assertLimits(io, 0, 0, 0);
    }

    @Test
    public void evccDecreaseDiscardsStaleGridRelease() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.requestBudgets(50, 43);
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
        limits.requestBudgets(50, 43);
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
        limits.requestBudgets(50, 43);
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
        limits.requestBudgets(50, 43);
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
        limits.requestBudgets(50, 43);
        limits.setGridTargets(2, true, 17, 13, true);

        ChargingLimitCoordinator.Snapshot blocked = limits.snapshot();
        assertTrue(blocked.blocked);
        assertTrue(blocked.startupBlocked);
        assertTrue(!blocked.configurationBlocked);
        assertTrue(!blocked.limitMismatchBlocked);
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

        limits.setBlocked(ChargingLimitCoordinator.CONFIGURATION, true);
        ChargingLimitCoordinator.Snapshot configurationBlocked = limits.snapshot();
        assertTrue(configurationBlocked.configurationBlocked);
        assertEquals(0, configurationBlocked.effectiveDcKw);
        assertEquals(0, configurationBlocked.effectiveAcKw);
    }

    @Test
    public void gridApprovedIdlePrearmUsesNonAuthorizingWriter() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);

        assertLimits(io, 5, 5, 0);
        assertTrue(io.operations.contains("prearm1=5"));
        assertTrue(io.operations.contains("prearm2=5"));
        assertEquals("pre-arm is not an active allocation", 0, limits.effectiveDcKw());
    }

    @Test
    public void safetyBlockClearsIdlePrearmWithAuthoritativeZero() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        io.operations.clear();

        limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);

        assertLimits(io, 0, 0, 0);
        assertTrue(io.operations.contains("set1=0"));
        assertTrue(io.operations.contains("set2=0"));
    }

    @Test
    public void firstActiveCcsTargetReassertsPrearmedValueThroughFullWriter() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeSafeZero();
        limits.setCcsAvailable(true);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        io.operations.clear();

        limits.setGridTargetsAndPrearm(2, false, 5, 0, 0, 0, false);

        assertLimits(io, 0, 5, 0);
        assertTrue(io.operations.contains("set2=5"));
    }

    private static ChargingLimitCoordinator coordinator(FakeIo io) {
        return new ChargingLimitCoordinator(io, 5, 50, 5, 43);
    }

    private static void assertLimits(FakeIo io, int c1, int c2, int c3) {
        assertEquals(c1, io.value[1]);
        assertEquals(c2, io.value[2]);
        assertEquals(c3, io.value[3]);
    }

    private static final class FakeIo implements ChargingLimitIo {
        final int[] value = new int[] { 0, 50, 50, 43 };
        final List<String> operations = new ArrayList<String>();
        int failReadConnector;

        public int limitKw(int connector) throws Exception {
            if (connector == failReadConnector) throw new Exception("read failed");
            return value[connector];
        }

        public void setConnectorLimitKw(int connector, int kw) {
            value[connector] = kw;
            operations.add("set" + connector + "=" + kw);
            operations.add(connector + "=" + kw);
        }

        public void preArmConnectorLimitKw(int connector, int kw) {
            value[connector] = kw;
            operations.add("prearm" + connector + "=" + kw);
        }
    }
}
