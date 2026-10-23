package de.rothner.qc45;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GridFailbackCharacteristicTest {
    @Test public void ratedAndSmallTestCurrentDoNotTripDuringAnHour() {
        for (double nominal : new double[] { 35.0d, 50.0d }) {
            SlsThermalModel model = new SlsThermalModel(nominal, 30000L);
            for (long now = 0; now <= 3600000L; now += 100L) {
                model.update(now, phase(nominal * 1.05d));
                assertFalse(model.overloaded());
            }
        }
    }

    @Test public void lightSymmetricOscillationDoesNotBecomeAHardTrip() {
        SlsThermalModel model = new SlsThermalModel(35.0d, 30000L);
        for (long now = 0; now <= 600000L; now += 100L) {
            double amps = (now / 2000L) % 2 == 0 ? 33.0d : 37.0d;
            model.update(now, phase(amps));
            assertFalse(model.overloaded());
        }
    }

    @Test public void sustainedOverloadTripsEarlierAsCurrentRises() {
        long at110 = tripTime(35, 38.5d);
        long at120 = tripTime(35, 42.0d);
        long at150 = tripTime(35, 52.5d);
        long at200 = tripTime(35, 70.0d);
        assertTrue(at110 > at120);
        assertTrue(at120 > at150);
        assertTrue(at150 > at200);
        // Deliberately earlier software interventions, not manufacturer trip times.
        assertTrue(at120 < 10000L);
        assertTrue(at200 < 1500L);
    }

    @Test public void equalCurrentMultiplesBehaveEquallyForThirtyFiveAndFiftyAmps() {
        assertEquals(tripTime(35, 42), tripTime(50, 60));
        assertEquals(tripTime(35, 52.5d), tripTime(50, 75));
    }

    @Test public void aBriefDipDoesNotEraseAccumulatedExposure() {
        SlsThermalModel model = new SlsThermalModel(35, 30000L);
        for (long now = 0; now <= 5000; now += 100) model.update(now, phase(42));
        double hot = model.exposure();
        model.update(5100L, phase(20));
        model.update(5200L, phase(20));
        assertTrue(model.exposure() > hot * 0.95d);
        for (long now = 5300; now <= 65000; now += 100) model.update(now, phase(20));
        assertTrue(model.exposure() < hot);
    }

    @Test public void missingMeterValuesCannotPretendToCoolTheBreaker() {
        SlsThermalModel model = new SlsThermalModel(35, 30000L);
        model.update(0, phase(42));
        model.update(3000, phase(42));
        double before = model.exposure();
        model.forgetReading();
        model.update(1000000L, phase(5));
        assertEquals(before, model.exposure(), 0.000001d);
    }

    @Test public void phaseHistoryIsNotTransferredWhenCriticalPhaseChanges() {
        SlsThermalModel model = new SlsThermalModel(35, 30000L);
        model.update(0, new KsemClient.Currents(42, 0, 0));
        model.update(4000, new KsemClient.Currents(42, 0, 0));
        double l1 = model.exposure();
        model.update(4100, new KsemClient.Currents(0, 42, 0));
        model.update(4200, new KsemClient.Currents(0, 42, 0));
        assertTrue(model.exposure() < 1);
        assertTrue(model.exposure() < l1 + 0.1d);
    }

    @Test public void backwardsClockDoesNotSuspendFutureIntegration() {
        SlsThermalModel model = new SlsThermalModel(35, 30000L);
        model.update(10000L, phase(42));
        model.update(9000L, phase(42));
        model.update(10000L, phase(42));
        assertTrue(model.exposure() > 0.1d);
    }

    private static long tripTime(double nominal, double amps) {
        SlsThermalModel model = new SlsThermalModel(nominal, 30000L);
        for (long now = 0; now < 120000; now += 10) {
            model.update(now, phase(amps));
            if (model.overloaded()) return now;
        }
        throw new AssertionError("overload did not trip");
    }

    private static KsemClient.Currents phase(double amps) {
        return new KsemClient.Currents(amps, 0, 0);
    }
}
