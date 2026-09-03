package de.rothner.qc45;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LoadManagerOperatingProfileTest {
    @Test
    public void mondayBusinessHoursUseTwentySevenAmps() {
        long mondayNoon = localTime(2026, Calendar.SEPTEMBER, 7, 12, 0);
        assertTrue(LoadManager.isBusinessHours(mondayNoon));
        assertEquals(27.0d,
            LoadManager.operatingTargetA(mondayNoon, 32.0d, 34.0d, 0.8d),
            0.000001d);
    }

    @Test
    public void mondayAfterClosingUsesTechnicalSafeMaximum() {
        long mondayAfternoon = localTime(2026, Calendar.SEPTEMBER, 7, 15, 0);
        assertFalse(LoadManager.isBusinessHours(mondayAfternoon));
        assertEquals(33.1d,
            LoadManager.operatingTargetA(mondayAfternoon, 32.0d, 34.0d, 0.8d),
            0.000001d);
    }

    @Test
    public void fridayClosesAtThirteenHundred() {
        long fridayBeforeClose = localTime(2026, Calendar.SEPTEMBER, 11, 12, 59);
        long fridayAtClose = localTime(2026, Calendar.SEPTEMBER, 11, 13, 0);
        assertTrue(LoadManager.isBusinessHours(fridayBeforeClose));
        assertFalse(LoadManager.isBusinessHours(fridayAtClose));
    }

    @Test
    public void weekendAlwaysUsesOffHoursProfile() {
        long saturday = localTime(2026, Calendar.SEPTEMBER, 12, 10, 0);
        assertFalse(LoadManager.isBusinessHours(saturday));
        assertEquals(33.1d,
            LoadManager.operatingTargetA(saturday, 32.0d, 34.0d, 0.8d),
            0.000001d);
    }

    @Test
    public void qc45GmtClockIsConvertedToBerlinLocalTime() {
        // The QC45 reports Thu Sep 3 14:14:15 GMT 2026. That absolute instant
        // is 16:14:15 CEST in Europe/Berlin, therefore already outside the
        // Mon-Thu business window ending at 15:00 local time.
        long qc45Clock = utcTime(2026, Calendar.SEPTEMBER, 3, 14, 14, 15);
        assertFalse(LoadManager.isBusinessHours(qc45Clock));
        assertEquals(33.1d,
            LoadManager.operatingTargetA(qc45Clock, 32.0d, 34.0d, 0.8d),
            0.000001d);
    }

    private static long localTime(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin"));
        c.clear();
        c.set(year, month, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static long utcTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        c.clear();
        c.set(year, month, day, hour, minute, second);
        return c.getTimeInMillis();
    }
}
