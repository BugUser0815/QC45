package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LoadAllocatorTest {
    @Test
    public void splitsSharedBudgetEqually() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 22, 5, 50, 5, 22);
        assertEquals(11, t.dcKw);
        assertEquals(11, t.acKw);
    }

    @Test
    public void leavesOddKilowattAsNeutralReserve() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 21, 5, 50, 5, 22);
        assertEquals(10, t.dcKw);
        assertEquals(10, t.acKw);
    }

    @Test
    public void redistributesOnlyAfterAcMaximumIsReached() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 50, 5, 50, 5, 22);
        assertEquals(28, t.dcKw);
        assertEquals(22, t.acKw);
    }

    @Test
    public void pausesBothWhenBothMinimumsDoNotFit() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 9, 5, 50, 5, 22);
        assertEquals(0, t.dcKw);
        assertEquals(0, t.acKw);
    }

    @Test
    public void unequalMinimumsNeverCreateImplicitPriority() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, true, 12, 7, 50, 5, 22);
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
            5, 50, 5, 22, 2);
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
            5, 50, 5, 22, 2);
        assertEquals(11, t.dcKw);
        assertEquals(11, t.acKw);
    }

    @Test
    public void dcOnlyRetainsFullAvailableBudget() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            true, false, 30, 5, 50, 5, 22);
        assertEquals(30, t.dcKw);
        assertEquals(0, t.acKw);
    }

    @Test
    public void acOnlyRetainsFullAvailableBudget() {
        LoadAllocator.Targets t = LoadAllocator.fairTargets(
            false, true, 18, 5, 50, 5, 22);
        assertEquals(0, t.dcKw);
        assertEquals(18, t.acKw);
    }

    @Test
    public void unusedAcEntitlementMovesToHungryDc() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 15, 11, 15, 15, false, true,
            5, 50, 5, 22, 2, 2);
        assertEquals(17, t.dcKw);
        assertEquals(13, t.acKw);
    }

    @Test
    public void unusedDcEntitlementMovesToHungryAc() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 8, 15, 15, 15, true, false,
            5, 50, 5, 22, 2, 2);
        assertEquals(13, t.dcKw);
        assertEquals(17, t.acKw);
    }

    @Test
    public void transferWaitsUntilOtherConnectorUsesFairShare() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 10, 11, 15, 15, false, true,
            5, 50, 5, 22, 2, 2);
        assertEquals(15, t.dcKw);
        assertEquals(15, t.acKw);
    }

    @Test
    public void twoDemandLimitedVehiclesKeepFairLimits() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 10, 10, 15, 15, true, true,
            5, 50, 5, 22, 2, 2);
        assertEquals(15, t.dcKw);
        assertEquals(15, t.acKw);
    }

    @Test
    public void transferCannotCrossDestinationMaximum() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(25, 22);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 10, 22, 25, 22, true, false,
            5, 50, 5, 22, 2, 2);
        assertEquals(25, t.dcKw);
        assertEquals(22, t.acKw);
    }

    @Test
    public void demandTransferContinuesAtConfiguredRamp() {
        LoadAllocator.Targets fair = new LoadAllocator.Targets(15, 15);
        LoadAllocator.Targets t = LoadAllocator.redistributeForDemand(
            fair, 17, 5, 17, 13, false, true,
            5, 50, 5, 22, 2, 2);
        assertEquals(19, t.dcKw);
        assertEquals(11, t.acKw);
    }
}
