package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SafetyDiagnosticsTest {
    @Test
    public void reportsStartupRecoveryProgressShape() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_STARTUP, status.state);
        assertEquals(0, status.progress);
        assertEquals(5, status.total);
        assertEquals(SafetyDiagnostics.UNIT_READS, status.unit);
    }

    @Test
    public void reportsStickyConfigurationBeforeOtherRecoverableBlocks() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);
        limits.setBlocked(ChargingLimitCoordinator.CONFIGURATION, true);

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_CONFIGURATION, status.state);
    }

    @Test
    public void reportsLimitMismatchRecoveryCondition() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_LIMIT_MISMATCH, status.state);
        assertEquals(SafetyDiagnostics.UNIT_NONE, status.unit);
    }

    @Test
    public void reportsShutdownAsHighestPriority() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.CONFIGURATION, true);
        limits.setBlocked(ChargingLimitCoordinator.SHUTDOWN, true);

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_SHUTDOWN, status.state);
    }

    private static final class FakeIo implements ChargingLimitIo {
        final int[] limit = new int[] { 0, 0, 0, 0 };

        public int limitKw(int connector) { return limit[connector]; }
        public void setConnectorLimitKw(int connector, int kw) { limit[connector] = kw; }
        public void preArmConnectorLimitKw(int connector, int kw) { limit[connector] = kw; }
    }
}
