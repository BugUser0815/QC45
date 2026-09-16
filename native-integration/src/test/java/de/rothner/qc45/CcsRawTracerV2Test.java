package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CcsRawTracerV2Test {
    @Test
    public void liveTelemetrySurvivesSplitSerialReads() {
        CcsRawTracerV2.shutdown();
        byte[] frame = new byte[12];
        frame[0] = 0x63;
        frame[1] = 0x10;
        frame[2] = 50;
        frame[9] = (byte)0x90;
        frame[10] = 0x01; // 400 V, little endian
        frame[11] = 20;   // 8 kW

        CcsRawTracerV2.observeLiveRx(frame, 0, 5);
        assertFalse(CcsRawTracerV2.hasFreshLiveTelemetry());
        CcsRawTracerV2.observeLiveRx(frame, 5, 7);

        assertTrue(CcsRawTracerV2.hasFreshLiveTelemetry());
        assertEquals(50, CcsRawTracerV2.liveSocPct());
        assertEquals(8, CcsRawTracerV2.livePowerKw());
        CcsRawTracerV2.shutdown();
    }
    @Test
    public void newestFrameWinsAndClearedChargingFlagDoesNotHidePower() {
        CcsRawTracerV2.shutdown();
        byte[] frame = new byte[12];
        frame[0] = 0x63; frame[1] = 0x10; frame[2] = 50;
        frame[9] = (byte)0x90; frame[10] = 1; frame[11] = 75;
        CcsRawTracerV2.observeLiveRx(frame, 0, frame.length);
        assertEquals(30, CcsRawTracerV2.freshPowerKw());
        frame[1] = 0; frame[11] = 13;
        CcsRawTracerV2.observeLiveRx(frame, 0, frame.length);
        assertEquals(5, CcsRawTracerV2.freshPowerKw());
        frame[11] = 0;
        CcsRawTracerV2.observeLiveRx(frame, 0, frame.length);
        assertEquals(0, CcsRawTracerV2.freshPowerKw());
        CcsRawTracerV2.shutdown();
        assertEquals(-1, CcsRawTracerV2.freshPowerKw());
    }

    @Test
    public void retainedOldFrameDoesNotRefreshSampleAge() throws Exception {
        CcsRawTracerV2.shutdown();
        byte[] frame = {0x63, 0x10, 50, 0, 0, 0, 0, 0, 0, (byte)0x90, 1, 13};
        CcsRawTracerV2.observeLiveRx(frame, 0, frame.length);
        java.lang.reflect.Field time = CcsRawTracerV2.class.getDeclaredField("liveLastRxMs");
        time.setAccessible(true);
        time.setLong(null, System.currentTimeMillis() - 10000L);
        CcsRawTracerV2.observeLiveRx(new byte[] {0, 0}, 0, 2);
        assertEquals(-1, CcsRawTracerV2.freshPowerKw());
        CcsRawTracerV2.shutdown();
    }
}
