package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AcFixedPowerBridgeTest {
    @Test
    public void convertsLogicalKwToSafeThreePhasePilotCurrent() {
        assertEquals(6, AcFixedPowerBridge.pilotCurrentAForKw(0));
        assertEquals(8, AcFixedPowerBridge.pilotCurrentAForKw(5));
        assertEquals(9, AcFixedPowerBridge.pilotCurrentAForKw(6));
        assertEquals(16, AcFixedPowerBridge.pilotCurrentAForKw(11));
        assertEquals(32, AcFixedPowerBridge.pilotCurrentAForKw(22));
        assertEquals(51, AcFixedPowerBridge.pilotCurrentAForKw(35));
        assertEquals(63, AcFixedPowerBridge.pilotCurrentAForKw(43));
    }
}
