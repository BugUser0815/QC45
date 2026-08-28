package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class GridFailbackCharacteristicTest {
    @Test
    public void smallExcessPausesWithoutAccumulatingHardTrip() {
        assertEquals(Long.MAX_VALUE, GridFailback.requiredHardTripDelayMs(36.7d, 250L));
    }

    @Test
    public void loggedThirtyEightAmpSpikeUsesOneHourBand() {
        assertEquals(3600000L, GridFailback.requiredHardTripDelayMs(38.6d, 250L));
    }

    @Test
    public void delayFallsAsCurrentMultipleRises() {
        assertEquals(300000L, GridFailback.requiredHardTripDelayMs(42.0d, 250L));
        assertEquals(60000L, GridFailback.requiredHardTripDelayMs(52.5d, 250L));
        assertEquals(10000L, GridFailback.requiredHardTripDelayMs(70.0d, 250L));
        assertEquals(1000L, GridFailback.requiredHardTripDelayMs(105.0d, 250L));
        assertEquals(250L, GridFailback.requiredHardTripDelayMs(175.0d, 250L));
    }

    @Test
    public void configuredDebounceRemainsTheLowerTimeBound() {
        assertEquals(400L, GridFailback.requiredHardTripDelayMs(175.0d, 400L));
    }
}
