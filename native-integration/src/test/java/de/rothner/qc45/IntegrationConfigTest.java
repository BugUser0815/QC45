package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class IntegrationConfigTest {
    @Test
    public void acceptsCanonicalFailbackEnvelope() {
        Integration.validateFailbackThresholds(34.0d, 35.0d, 175.0d);
    }

    @Test
    public void acceptsMoreConservativeOrderedThresholds() {
        Integration.validateFailbackThresholds(32.5d, 33.0d, 175.0d);
    }

    @Test
    public void migratesLegacyEqualInstantThresholdToSlsEEnvelope() {
        double[] value = Integration.conservativeFailbackThresholds(34.0d, 35.0d, 35.0d);
        assertEquals(34.0d, value[0], 0.000001d);
        assertEquals(35.0d, value[1], 0.000001d);
        assertEquals(175.0d, value[2], 0.000001d);
    }

    @Test
    public void migratesDeployedThirtyEightAmpInstantTrip() {
        double[] value = Integration.conservativeFailbackThresholds(34.0d, 35.0d, 38.0d);
        assertEquals(34.0d, value[0], 0.000001d);
        assertEquals(35.0d, value[1], 0.000001d);
        assertEquals(175.0d, value[2], 0.000001d);
    }

    @Test
    public void migratesPreviouslyPublishedSixPointTwoFiveMultiple() {
        double[] value = Integration.conservativeFailbackThresholds(34.0d, 35.0d, 218.75d);
        assertEquals(34.0d, value[0], 0.000001d);
        assertEquals(35.0d, value[1], 0.000001d);
        assertEquals(175.0d, value[2], 0.000001d);
    }

    @Test
    public void clampsOperationalThresholdsAndFixesMagneticInstantRange() {
        double[] value = Integration.conservativeFailbackThresholds(36.0d, 37.0d, 39.0d);
        assertEquals(34.0d, value[0], 0.000001d);
        assertEquals(35.0d, value[1], 0.000001d);
        assertEquals(175.0d, value[2], 0.000001d);
    }

    @Test
    public void reportsConfiguredValuesWhenNoPositiveOrderedRepairExists() {
        try {
            Integration.conservativeFailbackThresholds(0.05d, 0.05d, 0.05d);
            fail("expected irreparable threshold envelope");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be finite and > 0"));
        }
    }

    @Test
    public void migratesLegacyFailbackTimingOnlyTowardsFasterProtection() {
        long[] value = Integration.conservativeFailbackTiming(800L, 400L, 200);
        assertEquals(500L, value[0]);
        assertEquals(250L, value[1]);
        assertEquals(100L, value[2]);
    }
}
