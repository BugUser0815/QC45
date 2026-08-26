package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DemandTrackerTest {
    @Test
    public void unusedEntitlementMustRemainStable() {
        DemandTracker tracker = new DemandTracker(5000L, 2);
        tracker.update(1000L, true, 11, 15, 15, 5);
        tracker.update(5999L, true, 11, 15, 15, 5);
        assertFalse(tracker.isDemandLimited());
        tracker.update(6000L, true, 11, 15, 15, 5);
        assertTrue(tracker.isDemandLimited());
    }

    @Test
    public void consumingProbeReserveRestoresEqualEntitlement() {
        DemandTracker tracker = new DemandTracker(0L, 2);
        tracker.update(1000L, true, 11, 15, 15, 5);
        assertTrue(tracker.isDemandLimited());

        // After redistribution the connector retains 13 kW. Reaching 12 kW
        // proves rising demand and immediately restores its fair 15-kW share.
        tracker.update(2000L, true, 12, 13, 15, 5);
        assertFalse(tracker.isDemandLimited());
    }

    @Test
    public void inactiveConnectorCannotRemainDemandLimited() {
        DemandTracker tracker = new DemandTracker(0L, 2);
        tracker.update(1000L, true, 0, 15, 15, 5);
        assertTrue(tracker.isDemandLimited());
        tracker.update(2000L, false, 0, 0, 0, 5);
        assertFalse(tracker.isDemandLimited());
    }
}
