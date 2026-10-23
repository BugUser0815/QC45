package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class KsemDcAllocatorTest {
    @Test
    public void rampIsAnUpperBoundAndFreshStartUsesFiveKw() {
        assertEquals(5, KsemDcAllocator.limitIncreaseKw(0, 35, 5, 5));
        assertEquals(10, KsemDcAllocator.limitIncreaseKw(5, 35, 5, 5));
        assertEquals(35, KsemDcAllocator.limitIncreaseKw(34, 35, 5, 5));
    }

    @Test
    public void dcSafetyReductionsAndStopAreImmediate() {
        assertEquals(0, KsemDcAllocator.limitIncreaseKw(35, 0, 5, 5));
        assertEquals(5, KsemDcAllocator.limitIncreaseKw(35, 5, 5, 5));
        assertEquals(13, KsemDcAllocator.limitIncreaseKw(35, 13, 5, 5));
    }

    @Test
    public void liveBoundaryDoesNotReleaseTheLastKwBeforeTheGuard() {
        // At 19:30:51 the old controller sent 34 -> 35 kW at 33.1 A;
        // the next KSEM reading was 35.2 A and forced 35 -> 5 kW.
        assertEquals(34, plan(34, 34, 33.1d, 1));
    }

    @Test
    public void noisyCurrentInsideHoldBandDoesNotRatchetPowerDown() {
        double[] samples = { 33.0d, 33.5d, 33.2d, 33.8d, 33.4d, 33.1d };
        int command = 34;
        for (int i = 0; i < 300; i++) {
            command = plan(command, 34, samples[i % samples.length], 1);
            assertEquals(34, command);
        }
    }

    @Test
    public void smallExcessGetsAProportionalReductionInsteadOfNotladen() {
        assertEquals(33, plan(34, 34, 34.1d, 5));
    }

    @Test
    public void doesNotRepeatReductionBeforeOutputHasFallen() {
        int first = plan(34, 34, 34.1d, 5);
        assertEquals(33, first);
        assertEquals(first, plan(first, 34, 34.1d, 5));
    }

    @Test
    public void increaseNearTargetIsLimitedToOneKwEvenWithFiveKwRamp() {
        assertEquals(21, plan(20, 20, 30.0d, 5));
    }

    @Test
    public void lowVehicleDemandCannotAccumulateFurtherIncreases() {
        assertEquals(10, plan(10, 5, 5.0d, 5));
        assertEquals(15, plan(10, 10, 5.0d, 5));
    }

    @Test
    public void reductionMustSettleBeforeReboundEvenWhenKsemIsAlreadyLow() {
        assertEquals(5, plan(5, 30, 20.0d, 5));
        assertEquals(10, plan(5, 5, 20.0d, 5));
    }

    @Test
    public void pendingOutputIsReservedEvenWhenMeasuredCurrentIsInsideHoldBand() {
        assertEquals(30, plan(35, 30, 33.1d, 5));
    }

    @Test
    public void undrawnPowerFitsConservativePhaseProjection() {
        int next = plan(20, 15, 28.0d, 5);
        double projectedA = 28.0d + Math.max(0, next - 15) / 0.60d;
        assertTrue(projectedA <= 33.9d);
    }

    @Test
    public void insufficientHeadroomForPhysicalMinimumKeepsLogicalPause() {
        assertEquals(0, plan(0, 0, 32.0d, 5));
        assertEquals(5, plan(0, 0, 5.0d, 5));
    }

    @Test
    public void maximumAndReducedEvccBudgetAreRespected() {
        assertEquals(35, plan(35, 35, 5.0d, 5));
        assertEquals(20, KsemDcAllocator.targetKw(35, 35,
            5.0d, 33.4d, 1.5d, 5, 20, 5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteMeterReading() {
        plan(20, 20, Double.NaN, 5);
    }

    private static int plan(int commanded, int actual, double criticalA, int ramp) {
        return KsemDcAllocator.targetKw(commanded, actual,
            criticalA, 33.4d, 1.5d, 5, 35, ramp);
    }
}
