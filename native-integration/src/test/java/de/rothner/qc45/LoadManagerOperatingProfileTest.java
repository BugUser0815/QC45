package de.rothner.qc45;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LoadManagerOperatingProfileTest {
    @Test
    public void mondayBefore0630UsesThirtyFiveAmps() {
        long before = localTime(2026, Calendar.SEPTEMBER, 7, 6, 29);
        assertFalse(LoadManager.isDaytimeBuffer(before));
        assertEquals(35.0d, LoadManager.phaseTargetA(before, 36.0d), 0.000001d);
    }

    @Test
    public void mondayAt0630StartsTwentyFiveAmpBuffer() {
        long start = localTime(2026, Calendar.SEPTEMBER, 7, 6, 30);
        assertTrue(LoadManager.isDaytimeBuffer(start));
        assertEquals(25.0d, LoadManager.phaseTargetA(start, 36.0d), 0.000001d);
    }

    @Test
    public void mondayUntil1759UsesTwentyFiveAmps() {
        long beforeClose = localTime(2026, Calendar.SEPTEMBER, 7, 17, 59);
        assertTrue(LoadManager.isDaytimeBuffer(beforeClose));
        assertEquals(25.0d, LoadManager.phaseTargetA(beforeClose, 36.0d), 0.000001d);
    }

    @Test
    public void mondayAt1800ReturnsToThirtyFiveAmps() {
        long close = localTime(2026, Calendar.SEPTEMBER, 7, 18, 0);
        assertFalse(LoadManager.isDaytimeBuffer(close));
        assertEquals(35.0d, LoadManager.phaseTargetA(close, 36.0d), 0.000001d);
    }

    @Test
    public void saturdayUsesDaytimeBuffer() {
        long saturday = localTime(2026, Calendar.SEPTEMBER, 12, 12, 0);
        assertTrue(LoadManager.isDaytimeBuffer(saturday));
        assertEquals(25.0d, LoadManager.phaseTargetA(saturday, 36.0d), 0.000001d);
    }

    @Test
    public void sundayHasNoDaytimeBuffer() {
        long sunday = localTime(2026, Calendar.SEPTEMBER, 13, 12, 0);
        assertFalse(LoadManager.isDaytimeBuffer(sunday));
        assertEquals(35.0d, LoadManager.phaseTargetA(sunday, 36.0d), 0.000001d);
    }

    @Test
    public void commandCeilingStillOverridesScheduleWhenLower() {
        long sunday = localTime(2026, Calendar.SEPTEMBER, 13, 12, 0);
        assertEquals(33.9d, LoadManager.phaseTargetA(sunday, 34.0d), 0.000001d);
    }

    @Test
    public void qc45GmtClockIsConvertedToBerlinLocalTime() {
        // 04:30 UTC on Sep 3 is 06:30 CEST in Europe/Berlin.
        long qc45Clock = utcTime(2026, Calendar.SEPTEMBER, 3, 4, 30, 0);
        assertTrue(LoadManager.isDaytimeBuffer(qc45Clock));
        assertEquals(25.0d, LoadManager.phaseTargetA(qc45Clock, 36.0d), 0.000001d);
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
