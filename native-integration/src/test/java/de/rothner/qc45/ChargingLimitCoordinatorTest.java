package de.rothner.qc45;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ChargingLimitCoordinatorTest {
    @Test
    public void startsWithoutAnyDcPermit() {
        ChargingLimitCoordinator limits = coordinator(new FakeIo());
        assertEquals(0, limits.requestedDcKw());
        assertEquals(43, limits.requestedAcKw());
        assertTrue(!limits.dcPermitValid(System.currentTimeMillis()));
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
    public void dcRequiresExplicitEvccPermitEvenAfterKsemIsHealthy() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(2, true, 15, 15);
        assertLimits(io, 5, 5, 5);

        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        assertLimits(io, 5, 5, 15);

        limits.requestDcBudget(20);
        limits.setGridTargets(2, true, 15, 15);
        assertLimits(io, 5, 15, 15);

        limits.requestDcBudget(0);
        assertLimits(io, 5, 5, 15);
        assertTrue(limits.evccControlsDc());
        assertTrue(!limits.evccControlsAc());
    }

    @Test
    public void safetyBlockCannotBeOverwrittenByEvcc() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargets(1, true, 30, 20);
        limits.requestBudgets(50, 43);
        assertLimits(io, 5, 5, 5);

        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        assertLimits(io, 30, 5, 20);
        limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);
        limits.requestBudgets(50, 43);
        assertLimits(io, 5, 5, 5);
    }

    @Test
    public void evccDecreaseDiscardsStaleGridRelease() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
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
    public void subMinimumRequestMeansNotladen() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, true, 20, 20);
        limits.requestBudgets(4, 1);
        assertEquals(0, limits.requestedDcKw());
        assertEquals(0, limits.requestedAcKw());
        assertLimits(io, 5, 5, 5);
    }

    @Test
    public void connectorSwitchReducesBeforeItIncreases() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.requestBudgets(50, 43);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, true, 20, 20);
        io.operations.clear();

        limits.setGridTargets(2, true, 20, 20);
        assertLimits(io, 5, 20, 20);
        assertTrue(io.operations.indexOf("prearm1=5") >= 0);
        assertTrue(io.operations.indexOf("set2=20") > io.operations.indexOf("prearm1=5"));
        assertTrue(!io.operations.contains("set1=5"));
    }

    @Test
    public void ccsRemainsAtNotladenUntilV3IsAvailable() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.requestBudgets(50, 43);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(2, false, 20, 0);
        assertEquals(5, io.value[2]);
        limits.setCcsAvailable(true);
        assertEquals(20, io.value[2]);
    }

    @Test
    public void getterFailureFallsBackToNotladenOnEveryConnector() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
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
        assertLimits(io, 5, 5, 5);
    }

    @Test
    public void snapshotSeparatesLogicalPauseFromPhysicalNotladen() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
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
        assertLimits(io, 5, 5, 5);

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
        assertLimits(io, 5, 5, 5);
    }

    @Test
    public void gridApprovedIdlePrearmPrimesSharedDcConfigurationWithoutCcsWriter() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);

        assertLimits(io, 5, 5, 5);
        assertTrue(io.operations.contains("set1=5"));
        assertTrue(io.operations.contains("prearm2=5"));
        assertTrue(!io.operations.contains("set2=5"));
        assertEquals("pre-arm is not an active allocation", 0, limits.effectiveDcKw());
    }

    @Test
    public void safetyBlockUsesNotladenAndNeverNativeZero() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        io.operations.clear();

        limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);

        assertLimits(io, 5, 5, 5);
        assertTrue(io.operations.contains("set1=5"));
        assertTrue(io.operations.contains("prearm2=5"));
        assertTrue(!containsNativeZero(io.operations));
    }

    @Test
    public void firstActiveCcsTargetReassertsPrearmedValueThroughFullWriter() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.requestDcBudget(5);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        io.operations.clear();

        limits.setGridTargetsAndPrearm(2, false, 5, 0, 0, 0, false);

        assertLimits(io, 5, 5, 5);
        assertTrue(io.operations.contains("prearm1=5"));
        assertTrue(io.operations.contains("set2=5"));
        assertTrue(!containsNativeZero(io.operations));
    }

    @Test
    public void endingCcsSessionReprimesSharedDcBeforeIdleCcsPrearm() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.requestDcBudget(20);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargetsAndPrearm(2, false, 20, 0, 0, 0, false);
        io.operations.clear();

        limits.setGridTargetsAndPrearm(0, false, 0, 0, 5, 0, false);

        assertLimits(io, 5, 5, 5);
        int sharedPrime = io.operations.indexOf("set1=5");
        int ccsPrearm = io.operations.indexOf("prearm2=5");
        assertTrue(sharedPrime >= 0);
        assertTrue(ccsPrearm > sharedPrime);
        assertTrue(!io.operations.contains("set2=5"));
        assertTrue(!containsNativeZero(io.operations));
    }

    @Test
    public void observedNativeZeroIsActivelyReplacedByNotladen() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        io.value[1] = 0;
        io.value[2] = 0;
        io.value[3] = 0;
        io.operations.clear();

        limits.reconcile();

        assertLimits(io, 5, 5, 5);
        assertTrue(!containsNativeZero(io.operations));
    }

    @Test
    public void acAndDcKeepFiveKwNotladenForEveryLogicalBlock() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setGridTargets(1, true, 20, 20);
        String[] sources = { ChargingLimitCoordinator.STARTUP,
            ChargingLimitCoordinator.LOAD_METER, ChargingLimitCoordinator.FAILBACK,
            ChargingLimitCoordinator.CONFIGURATION, ChargingLimitCoordinator.LIMIT_MISMATCH,
            ChargingLimitCoordinator.SHUTDOWN };
        for (String source : sources) {
            limits.setBlocked(source, true);
            assertEquals(0, limits.effectiveDcKw());
            assertEquals(0, limits.effectiveAcKw());
            assertLimits(io, 5, 5, 5);
            assertEquals(io.value[1], limits.acMobiBusTargetKw());
            limits.setBlocked(source, false);
        }
    }

    @Test
    public void acAndDcUseNotladenForZeroBudgetGridAndStageCaps() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, true, 20, 20);
        // AC remains autonomous in this test; only DC starts with 0 permit.
        assertEquals(20, limits.acMobiBusTargetKw());
        limits.requestBudgets(50, 43);
        limits.setGridTargets(1, true, 20, 20);
        assertEquals(20, limits.acMobiBusTargetKw());
        limits.requestBudgets(0, 0);
        assertLimits(io, 5, 5, 5);
        assertEquals(5, limits.acMobiBusTargetKw());
        limits.requestBudgets(50, 43);
        limits.setGridTargets(1, true, 0, 0);
        assertEquals(5, limits.acMobiBusTargetKw());
        limits.setGridTargets(1, true, 20, 20);
        limits.setStageCaps(0, 0);
        assertLimits(io, 5, 5, 5);
        assertEquals(5, limits.acMobiBusTargetKw());
        limits.clearStageCaps();
        assertEquals(20, limits.acMobiBusTargetKw());
    }

    @Test
    public void releasingProtectionCapsKeepsCurrentAllocationWithoutRestoringOldTargets()
            throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.requestBudgets(50, 43);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, true, 35, 22);
        limits.setStageCaps(32, 20);
        limits.releaseStageCapsAtCurrentTargets();
        assertEquals(32, limits.effectiveDcKw());
        assertEquals(20, limits.effectiveAcKw());
        assertFalse(limits.snapshot().stageLimited);
        assertEquals(32, limits.snapshot().gridDcKw);
        assertEquals(20, limits.snapshot().gridAcKw);
        limits.setGridTargets(1, true, 33, 21);
        assertEquals(33, limits.effectiveDcKw());
        assertEquals(21, limits.effectiveAcKw());
    }

    @Test
    public void expiredDcPermitRetainsOnlyFiveKwAndCannotBeRestoredByKsemOrReboot() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen();
        limits.setCcsAvailable(true);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.requestDcBudget(35);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 35, 0, false);
        assertEquals(35, io.value[1]);
        limits.expireDcRequestIfStale(
            System.currentTimeMillis() + ChargingLimitCoordinator.DC_REQUEST_TIMEOUT_MS + 1L);
        assertEquals(5, limits.requestedDcKw());
        assertTrue(limits.dcNetworkFallbackActive());
        assertFalse(limits.dcPermitValid());
        assertEquals(5, io.value[1]);
        assertEquals(5, io.value[2]);
        limits.setGridTargetsAndPrearm(0, false, 0, 0, 35, 0, false);
        assertEquals(5, io.value[1]);
        assertEquals(0, coordinator(new FakeIo()).requestedDcKw());
    }

    @Test
    public void freshBudgetResumesControlFromFallbackWithoutRestoringOldGridTarget() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = coordinator(io);
        limits.initializeNotladen(); limits.setCcsAvailable(true);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.requestDcBudget(35); limits.setGridTargets(2, false, 30, 0);
        limits.expireDcRequestIfStale(System.currentTimeMillis()
            + ChargingLimitCoordinator.DC_REQUEST_TIMEOUT_MS + 1L);
        assertEquals(5, io.value[2]);
        limits.setGridTargets(2, false, 35, 0);
        assertEquals(5, io.value[2]);
        limits.requestDcBudget(35);
        assertFalse(limits.dcNetworkFallbackActive());
        assertTrue(limits.dcPermitValid());
        assertEquals("a fresh grid calculation is required before increasing", 5, io.value[2]);
        limits.setGridTargets(2, false, 6, 0);
        assertEquals(6, io.value[2]);
    }

    @Test
    public void explicitZeroRevokesNetworkFallbackImmediately() throws Exception {
        ChargingLimitCoordinator limits = coordinator(new FakeIo());
        limits.requestDcBudget(35);
        limits.expireDcRequestIfStale(System.currentTimeMillis()
            + ChargingLimitCoordinator.DC_REQUEST_TIMEOUT_MS + 1L);
        assertTrue(limits.dcNetworkFallbackActive());
        limits.requestDcBudget(0);
        assertFalse(limits.dcNetworkFallbackActive());
        assertFalse(limits.dcPermitValid());
        assertEquals(0, limits.requestedDcKw());
    }

    @Test public void permitClassificationAppliesTimeoutBeforeReturningItsState() throws Exception {
        ChargingLimitCoordinator limits = coordinator(new FakeIo());
        assertEquals(ChargingLimitCoordinator.DcPermitState.NONE, limits.dcPermitState());
        limits.requestDcBudget(35);
        assertEquals(ChargingLimitCoordinator.DcPermitState.FRESH, limits.dcPermitState());
        java.lang.reflect.Field last = ChargingLimitCoordinator.class.getDeclaredField("lastDcRequestAtMs");
        last.setAccessible(true);
        last.setLong(limits, System.currentTimeMillis() - ChargingLimitCoordinator.DC_REQUEST_TIMEOUT_MS - 1L);
        assertEquals(ChargingLimitCoordinator.DcPermitState.NETWORK_FALLBACK, limits.dcPermitState());
        assertEquals(5, limits.requestedDcKw());
        limits.requestDcBudget(0);
        assertEquals(ChargingLimitCoordinator.DcPermitState.NONE, limits.dcPermitState());
    }

    @Test
    public void zeroDcRequestRevokesPermitImmediately() throws Exception {
        ChargingLimitCoordinator limits = coordinator(new FakeIo());
        limits.requestDcBudget(35);
        assertTrue(limits.dcPermitValid(System.currentTimeMillis()));
        limits.requestDcBudget(0);
        assertTrue(!limits.dcPermitValid(System.currentTimeMillis()));
    }

    @Test public void nativeLimitAboveConfiguredMaximumIsReasserted() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 35, 5, 22);
        limits.initializeNotladen(); limits.setCcsAvailable(true);
        limits.requestBudgets(35, 22); limits.setGridTargets(2, true, 35, 22);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        io.value[2] = 50; io.value[3] = 43;
        limits.reconcile();
        assertEquals(35, io.value[2]); assertEquals(22, io.value[3]);
    }

    private static ChargingLimitCoordinator coordinator(FakeIo io) {
        return new ChargingLimitCoordinator(io, 5, 50, 5, 43);
    }

    @Test
    public void operatorFixedAcNeverReceivesCoordinatorWritesOrReassertions() throws Exception {
        FakeIo io = new FakeIo();
        io.value[3] = 22;
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43, false);
        limits.initializeSafeZero();
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setGridTargets(1, true, 20, 20);
        limits.requestBudgets(30, 0);
        limits.setStageCaps(5, 0);
        limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);
        limits.reassertConnectorLimit(3);
        io.failReadConnector = 3;
        limits.reconcile();
        assertEquals(22, io.value[3]);
        for (String operation : io.operations) {
            assertTrue("unexpected AC write: " + operation,
                !operation.startsWith("set3=") && !operation.startsWith("prearm3="));
        }
    }

    private static void assertLimits(FakeIo io, int c1, int c2, int c3) {
        assertEquals(c1, io.value[1]);
        assertEquals(c2, io.value[2]);
        assertEquals(c3, io.value[3]);
    }

    private static boolean containsNativeZero(List<String> operations) {
        for (String operation : operations) {
            if (operation.endsWith("=0")) return true;
        }
        return false;
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
