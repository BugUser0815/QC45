package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class KsemDcAllocatorTest {
    @Test
    public void fiveAmpCompanyLoadStartsAtTwentyKwWithoutBatteryAssumption() {
        assertEquals(20, KsemDcAllocator.targetKw(
            0, 5.0d, 35.0d, 5, 35));
    }

    @Test
    public void smaSupportSeenByKsemLetsNextCycleReleaseFullThirtyFiveKw() {
        // 5 A base load = 3.464 kW. At a 20 kW charger release, 17.5 kW
        // battery support leaves about 5.96 kW / 8.6 A grid import.
        assertEquals(35, KsemDcAllocator.targetKw(
            20, 8.6d, 35.0d, 5, 35));
    }

    @Test
    public void fullThirtyFiveKwRemainsAllowedWithSeventeenPointFiveKwSupport() {
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
