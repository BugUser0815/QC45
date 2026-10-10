package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class KsemDcAllocatorTest {
    @Test
    public void dcRampsFromFiveToThirtyFiveInFiveKwSteps() {
        assertEquals(5, KsemDcAllocator.limitIncreaseKw(0, 35, 5, 5));
        assertEquals(10, KsemDcAllocator.limitIncreaseKw(5, 35, 5, 5));
        assertEquals(15, KsemDcAllocator.limitIncreaseKw(10, 35, 5, 5));
        assertEquals(20, KsemDcAllocator.limitIncreaseKw(15, 35, 5, 5));
        assertEquals(25, KsemDcAllocator.limitIncreaseKw(20, 35, 5, 5));
        assertEquals(30, KsemDcAllocator.limitIncreaseKw(25, 35, 5, 5));
        assertEquals(35, KsemDcAllocator.limitIncreaseKw(30, 35, 5, 5));
        assertEquals(35, KsemDcAllocator.limitIncreaseKw(35, 35, 5, 5));
    }

    @Test
    public void dcSafetyReductionsAndStopAreImmediate() {
        assertEquals(0, KsemDcAllocator.limitIncreaseKw(35, 0, 5, 5));
        assertEquals(5, KsemDcAllocator.limitIncreaseKw(35, 5, 5, 5));
        assertEquals(13, KsemDcAllocator.limitIncreaseKw(35, 13, 5, 5));
        assertEquals(18, KsemDcAllocator.limitIncreaseKw(15, 18, 5, 5));
    }

    @Test
    public void dcRestartWithFreshPermitStartsAtFiveAgain() {
        assertEquals(0, KsemDcAllocator.limitIncreaseKw(0, 0, 5, 5));
        assertEquals(5, KsemDcAllocator.limitIncreaseKw(0, 30, 5, 5));
        assertEquals(10, KsemDcAllocator.limitIncreaseKw(5, 30, 5, 5));
    }

    @Test
    public void fiveAmpCompanyLoadStartsAtThirteenKwDuringDaytimeBuffer() {
        assertEquals(13, KsemDcAllocator.targetKw(
            0, 5.0d, 25.0d, 5, 35));
    }

    @Test
    public void fiveAmpCompanyLoadStartsAtTwentyKwOutsideDaytimeBuffer() {
        assertEquals(20, KsemDcAllocator.targetKw(
            0, 5.0d, 35.0d, 5, 35));
    }

    @Test
    public void smaSupportIsSeenOnlyThroughKsemDuringDaytimeBuffer() {
        // With 31 kW DC, 5 A/phase base load and 17.5 kW SMA/BYD support,
        // grid import is about 24.5 A/phase. The 25 A daytime target therefore
        // holds the charger at 31 kW without any explicit battery assumption.
        assertEquals(31, KsemDcAllocator.targetKw(
            31, 24.5d, 25.0d, 5, 35));
    }

    @Test
    public void smaSupportSeenByKsemLetsNightCycleReleaseFullThirtyFiveKw() {
        // 5 A base load = 3.464 kW. At a 20 kW charger release, 17.5 kW
        // battery support leaves about 5.96 kW / 8.6 A grid import.
        assertEquals(35, KsemDcAllocator.targetKw(
            20, 8.6d, 35.0d, 5, 35));
    }

    @Test
    public void fullThirtyFiveKwRemainsAllowedAtNightWithSeventeenPointFiveKwSupport() {
        // 35 kW charger + 3.464 kW company load - 17.5 kW SMA/BYD
        // = about 20.96 kW grid import = about 30.3 A/phase.
        assertEquals(35, KsemDcAllocator.targetKw(
            35, 30.3d, 35.0d, 5, 35));
    }

    @Test
    public void emptyBatteryIsAutomaticallyVisibleThroughKsem() {
        // 20 kW charger + 5 A/phase company load with no battery support
        // is about 33.9 A/phase. There is less than 1 kW headroom, so the
        // command remains at 20 kW instead of assuming unavailable battery power.
        assertEquals(20, KsemDcAllocator.targetKw(
            20, 33.9d, 35.0d, 5, 35));
    }

    @Test
    public void mostLoadedPhaseDefinesTheAvailablePower() {
        KsemClient.Currents currents = new KsemClient.Currents(5.0d, 8.0d, 12.0d);
        assertEquals(15, KsemDcAllocator.targetKw(
            0, currents, 35.0d, 5, 35));
    }

    @Test
    public void targetIsHardCappedAtThirtyFiveKw() {
        assertEquals(35, KsemDcAllocator.targetKw(
            30, 5.0d, 35.0d, 5, 35));
    }

    @Test
    public void overloadImmediatelyReducesReleasedPower() {
        assertEquals(34, KsemDcAllocator.targetKw(
            35, 36.0d, 35.0d, 5, 35));
    }
}
