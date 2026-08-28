package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LoadAllocatorTest {
    @Test
    public void splitsSharedBudgetEqually() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 22, 5, 50, 5, 43);
        assertEquals(11, t.dcKw);
        assertEquals(11, t.acKw);
    }

    @Test
    public void leavesOddKilowattAsNeutralReserve() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 21, 5, 50, 5, 43);
        assertEquals(10, t.dcKw);
        assertEquals(10, t.acKw);
    }

    @Test
    public void keepsEqualPriorityBelowAcMaximum() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 50, 5, 50, 5, 43);
        assertEquals(25, t.dcKw);
        assertEquals(25, t.acKw);
    }

    @Test
    public void redistributesOnlyAfterAcMaximumIsReached() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 93, 5, 50, 5, 43);
        assertEquals(50, t.dcKw);
        assertEquals(43, t.acKw);
    }

    @Test
    public void pausesBothWhenBothMinimumsDoNotFit() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 9, 5, 50, 5, 43);
        assertEquals(0, t.dcKw);
        assertEquals(0, t.acKw);
    }

    @Test
    public void unequalMinimumsNeverCreateImplicitPriority() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 12, 7, 50, 5, 43);
        assertEquals(0, t.dcKw);
        assertEquals(0, t.acKw);
    }

    @Test
    public void projectedUnreachedPowerCannotCrossCeiling() {
        LoadAllocator.Targets t = LoadAllocator.plan(
            true, true,
            0, 0,
            5, 5,
            32.0d, 32.0d, 34.0d, 0.8d,
            5, 50, 5, 43, 2);
        assertEquals(0, t.dcKw);
        assertEquals(0, t.acKw);
    }

    @Test
    public void reductionCannotIncreaseEitherConnector() {
        LoadAllocator.Targets t = LoadAllocator.plan(
            true, true,
            12, 12,
            12, 12,
            33.0d, 32.0d, 34.0d, 0.8d,
            5, 50, 5, 43, 2);
        assertEquals(11, t.dcKw);
        assertEquals(11, t.acKw);
    }

    @Test
    public void dcOnlyRetainsFullAvailableBudget() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, false, 30, 5, 50, 5, 43);
        assertEquals(30, t.dcKw);
        assertEquals(0, t.acKw);
    }

    @Test
    public void acOnlyRetainsFullAvailableBudget() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            false, true, 18, 5, 50, 5, 43);
        assertEquals(0, t.dcKw);
        assertEquals(18, t.acKw);
    }

    @Test
    public void unusedAcEntitlementMovesToHungryDc() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 15, 11, 15, 15, false, true,
            5, 50, 5, 43, 2, 2);
        assertEquals(17, t.dcKw);
        assertEquals(13, t.acKw);
    }

    @Test
    public void unusedDcEntitlementMovesToHungryAc() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 8, 15, 15, 15, true, false,
            5, 50, 5, 43, 2, 2);
        assertEquals(13, t.dcKw);
        assertEquals(17, t.acKw);
    }

    @Test
    public void transferWaitsUntilOtherConnectorUsesFairShare() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 10, 11, 15, 15, false, true,
            5, 50, 5, 43, 2, 2);
        assertEquals(15, t.dcKw);
        assertEquals(15, t.acKw);
    }

    @Test
    public void twoDemandLimitedVehiclesKeepFairLimits() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 10, 10, 15, 15, true, true,
            5, 50, 5, 43, 2, 2);
        assertEquals(15, t.dcKw);
        assertEquals(15, t.acKw);
    }

    @Test
    public void transferCannotCrossDestinationMaximum() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(43, 43);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 10, 43, 43, 43, true, false,
            5, 50, 5, 43, 2, 2);
        assertEquals(43, t.dcKw);
        assertEquals(43, t.acKw);
    }

    @Test
    public void demandTransferContinuesAtConfiguredRamp() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 17, 5, 17, 13, false, true,
            5, 50, 5, 43, 2, 2);
        assertEquals(19, t.dcKw);
        assertEquals(11, t.acKw);
    }

    @Test
    public void singlePhaseProjectionCanLimitTransferTowardAc() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets transferred = new LoadAllocator.Targets(13, 17);
        LoadAllocator.Targets safe = LoadAllocator.constrainDemandTransfer(
            fair, transferred, 22.0d, 8, 15, 34.0d);
        assertEquals(15, safe.dcKw);
        assertEquals(15, safe.acKw);
    }

    @Test
    public void idleCcsMinimumCanBePrearmedWhenGridProjectionIsSafe() {
        LoadAllocator.Targets prearm = LoadAllocator.safePrearm(
            false, false, 0, 0, 0, 0,
            true, false, 5, 5, 4.3d, 34.0d);
        assertEquals(5, prearm.dcKw);
        assertEquals(0, prearm.acKw);
    }

    @Test
    public void simultaneousIdlePrearmIsRejectedWhenBothMinimumsDoNotFit() {
        LoadAllocator.Targets prearm = LoadAllocator.safePrearm(
            false, false, 0, 0, 0, 0,
            true, true, 5, 5, 4.3d, 34.0d);
        assertEquals(0, prearm.dcKw);
        assertEquals(0, prearm.acKw);
    }

    @Test
    public void newSessionRemainsAtMinimumDuringSettlingWindow() {
        LoadAllocator.Targets held = LoadAllocator.constrainStartupSettling(
            new LoadAllocator.Targets(17, 13), true, false, 5, 5);
        assertEquals(5, held.dcKw);
        assertEquals(13, held.acKw);
    }

    @Test
    public void dcRampWaitsUntilVehicleReachesPreviousRelease() {
        LoadAllocator.Targets held = LoadAllocator.plan(
            true, false,
            0, 0,
            5, 0,
            7.0d, 28.0d, 34.0d, 0.8d,
            5, 50, 5, 43, 2);
        assertEquals(5, held.dcKw);
        assertEquals(0, held.acKw);
    }

    @Test
    public void dcRampReleasesOneStepAfterStableCatchup() {
        LoadAllocator.Targets next = LoadAllocator.plan(
            true, false,
            5, 0,
            5, 0,
            15.0d, 28.0d, 34.0d, 0.8d,
            5, 50, 5, 43, 2);
        assertEquals(7, next.dcKw);
        assertEquals(0, next.acKw);
    }

    @Test
    public void catchupGuardNeverDelaysReduction() {
        LoadAllocator.Targets reduced = LoadAllocator.plan(
            true, false,
            0, 0,
            11, 0,
            33.0d, 28.0d, 34.0d, 0.8d,
            5, 50, 5, 43, 2);
        assertEquals(0, reduced.dcKw);
        assertEquals(0, reduced.acKw);
    }
}
