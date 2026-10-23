package de.rothner.qc45;

import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class IntegrationConfigTest {
    @Test public void existingInstallationDefaultsToThirtyFiveAmpE() {
        Integration.GridSettings s = Integration.gridSettings(new Properties());
        assertEquals(35.0d, s.nominalA, 0.000001d);
        assertEquals(34.0d, s.targetA, 0.000001d);
        assertEquals(36.0d, s.reduceA, 0.000001d);
        assertEquals(38.0d, s.tripA, 0.000001d);
        assertEquals(175.0d, s.instantA, 0.000001d);
    }

    @Test public void liveTargetAndHysteresisArePreserved() {
        Properties p = new Properties();
        p.setProperty("loadmanager.targetA", "34");
        p.setProperty("loadmanager.gridLimitA", "35");
        p.setProperty("loadmanager.hysteresisA", "1.5");
        p.setProperty("failback.instantTripA", "70");
        Integration.GridSettings s = Integration.gridSettings(p);
        assertEquals(34.0d, s.targetA, 0.000001d);
        assertEquals(1.5d, s.hysteresisA, 0.000001d);
        assertEquals(70.0d, s.instantA, 0.000001d);
    }

    @Test public void fiftyAmpConfigurationHasNoHiddenThirtyFiveAmpCeiling() {
        Properties p = fiftyAmps();
        p.setProperty("loadmanager.targetA", "50");
        p.setProperty("loadmanager.daytimeTargetA", "45");
        Integration.GridSettings s = Integration.gridSettings(p);
        assertEquals(50.0d, s.nominalA, 0.000001d);
        assertEquals(50.0d, s.ceilingA, 0.000001d);
        assertEquals(50.0d, s.targetA, 0.000001d);
        assertEquals(45.0d, s.daytimeA, 0.000001d);
        assertEquals(51.0d, s.reduceA, 0.000001d);
        assertEquals(53.0d, s.tripA, 0.000001d);
        assertEquals(250.0d, s.instantA, 0.000001d);
    }

    @Test public void nominalChangeDoesNotRaiseExplicitOldLimits() {
        Properties p = fiftyAmps();
        p.setProperty("loadmanager.gridLimitA", "35");
        p.setProperty("loadmanager.targetA", "34");
        p.setProperty("failback.reduceA", "36");
        p.setProperty("failback.tripA", "38");
        p.setProperty("failback.instantTripA", "70");
        Integration.GridSettings s = Integration.gridSettings(p);
        assertEquals(34.0d, s.targetA, 0.000001d);
        assertEquals(35.0d, s.ceilingA, 0.000001d);
        assertEquals(36.0d, s.reduceA, 0.000001d);
        assertEquals(38.0d, s.tripA, 0.000001d);
        assertEquals(70.0d, s.instantA, 0.000001d);
    }

    @Test public void oversizedValuesAreBoundedByInstalledRating() {
        Properties p = new Properties();
        p.setProperty("loadmanager.gridLimitA", "50");
        p.setProperty("loadmanager.targetA", "50");
        p.setProperty("failback.reduceA", "50");
        p.setProperty("failback.tripA", "60");
        p.setProperty("failback.instantTripA", "250");
        Integration.GridSettings s = Integration.gridSettings(p);
        assertEquals(35.0d, s.ceilingA, 0.000001d);
        assertEquals(35.0d, s.targetA, 0.000001d);
        assertEquals(36.75d, s.reduceA, 0.000001d);
        assertEquals(42.0d, s.tripA, 0.000001d);
        assertEquals(175.0d, s.instantA, 0.000001d);
    }

    @Test public void lowerOperatorGridLimitAndReduceThresholdAreRespected() {
        Properties p = fiftyAmps();
        p.setProperty("loadmanager.targetA", "48");
        p.setProperty("loadmanager.gridLimitA", "45");
        assertEquals(45.0d, Integration.gridSettings(p).targetA, 0.000001d);
        p.setProperty("failback.reduceA", "40");
        assertEquals(40.0d, Integration.gridSettings(p).targetA, 0.000001d);
    }

    @Test public void unsupportedCharacteristicOrNonFiniteValuesAreRejected() {
        String[][] invalid = {
            { "sls.characteristic", "B" }, { "sls.nominalA", "NaN" },
            { "sls.nominalA", "0" }, { "sls.nominalA", "101" },
            { "sls.thermalTimeConstantMs", "30001" },
            { "loadmanager.targetA", "Infinity" }, { "loadmanager.hysteresisA", "-1" },
            { "failback.reduceA", "0" }, { "failback.tripA", "36" },
            { "failback.instantTripA", "35" }
        };
        for (String[] item : invalid) {
            Properties p = new Properties();
            p.setProperty(item[0], item[1]);
            try { Integration.gridSettings(p); fail("invalid setting accepted: " + item[0]); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void conservativeInstantSettingIsNeverRaised() {
        double[] value = Integration.conservativeFailbackThresholds(34, 35, 38, 35);
        assertEquals(38.0d, value[2], 0.000001d);
    }

    @Test public void oldTwoKwRampSettingIsMigratedToFive() {
        assertEquals(5, Integration.effectiveRampUpKw(2));
        assertEquals(1, Integration.effectiveRampUpKw(1));
    }

    @Test public void targetDoesNotSubtractHysteresisFromNominalCurrent() {
        assertEquals(35.0d, Integration.conservativeLoadManagerTargetA(35, 35, 1.5d), 0.000001d);
        assertEquals(30.0d, Integration.conservativeLoadManagerTargetA(32, 30, 0.8d), 0.000001d);
    }

    @Test public void legacyFailbackPollingIsKeptFast() {
        long[] value = Integration.conservativeFailbackTiming(800L, 400L, 200);
        assertEquals(500L, value[0]);
        assertEquals(250L, value[1]);
        assertEquals(100L, value[2]);
    }

    @Test public void hardTripAutoResetDefaultsToOneMinute() {
        assertEquals(60000L, Integration.hardTripResetDelay(new Properties()));
    }

    @Test public void legacyManualResetFlagCannotDisableTimedReset() {
        Properties p = new Properties();
        p.setProperty("failback.autoResetHardTrip", "false");
        assertEquals(60000L, Integration.hardTripResetDelay(p));
    }

    @Test public void rejectsHardTripResetBeforeOneMinute() {
        Properties p = new Properties();
        p.setProperty("failback.resetDelayMs", "59999");
        try { Integration.hardTripResetDelay(p); fail("minimum reset delay"); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("at least 60000ms")); }
    }

    private static Properties fiftyAmps() {
        Properties p = new Properties();
        p.setProperty("sls.nominalA", "50");
        p.setProperty("sls.characteristic", "E");
        return p;
    }
}
