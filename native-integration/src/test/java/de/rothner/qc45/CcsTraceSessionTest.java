package de.rothner.qc45;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CcsTraceSessionTest {
    // QuickCharge V3 status frames, including the original Modbus CRC16.
    private static final byte[] IDLE = hex("63 00 FF 00 00 00 00 00 00 00 00 00 D5 75");
    private static final byte[] CONNECTED = hex("63 40 FF 00 00 00 00 00 00 00 00 00 80 B4");
    private static final byte[] CHARGING = hex("63 70 51 00 00 00 00 00 00 00 00 00 18 C3");
    private static final byte[] ERROR = hex("63 40 FF 00 00 00 00 A8 0F 00 00 00 E2 78");

    @After public void reset() {
        CcsTraceSession.CURRENT.reset();
        CcsTraceSession.CURRENT.configure(true);
    }

    @Test public void explicitOverrideAllowsContinuousDiagnosisWithoutAConnectedVehicle() {
        CcsTraceSession gate = new CcsTraceSession();
        assertFalse(gate.capturing(1000L));
        gate.configure(false);
        assertTrue(gate.capturing(1000L));
        gate.configure(true);
        assertFalse(gate.capturing(1000L));
    }

    @Test public void cableDetectionOpensCaptureBeforeSocVoltageOrCurrentAreAvailable() {
        CcsTraceSession gate = new CcsTraceSession();
        feed(gate, IDLE, 1000L);
        assertFalse(gate.capturing(1000L));
        feed(gate, CONNECTED, 2000L);
        assertTrue(gate.capturing(2000L));
        feed(gate, CHARGING, 3000L);
        feed(gate, ERROR, 4000L);
        assertTrue("DTC 4008 must remain visible while the cable is connected", gate.capturing(4000L));
    }

    @Test public void idleRepliesDoNotExtendTheTailAfterDisconnect() {
        CcsTraceSession gate = new CcsTraceSession();
        feed(gate, CONNECTED, 1000L);
        feed(gate, IDLE, 2000L);
        feed(gate, IDLE, 6900L);
        assertTrue(gate.capturing(7000L));
        assertFalse(gate.capturing(7001L));
        feed(gate, IDLE, 10000L);
        assertFalse(gate.capturing(10000L));
        feed(gate, CONNECTED, 11000L);
        assertTrue(gate.capturing(11000L));
    }

    @Test public void lostResponsesExpireInsteadOfLeavingCaptureOnForever() {
        CcsTraceSession gate = new CcsTraceSession();
        feed(gate, CONNECTED, 1000L);
        assertTrue("Preserve the approximately 63-second startup timeout", gate.capturing(64000L));
        assertFalse(gate.capturing(91001L));
    }

    @Test public void acceptsBytewiseReadsAndHeadersSplitAcrossMasterFrames() {
        CcsTraceSession gate = new CcsTraceSession();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        byte[] data = new byte[CONNECTED.length + 1];
        data[0] = 0x78; // Tail of an earlier QuickCharge frame.
        System.arraycopy(CONNECTED, 0, data, 1, CONNECTED.length);
        for (int off = 0; off < data.length; off++) {
            byte[] one = master(data, off, 1, 0x34);
            input.write(one, 0, one.length);
        }
        byte[] stream = input.toByteArray();
        for (int i = 0; i < stream.length; i++) gate.observeRx(stream, i, 1, 1000L);
        assertTrue(gate.capturing(1000L));
    }

    @Test public void rejectsCorruptFramesUnrelatedDevicesAndFalse63Matches() {
        CcsTraceSession gate = new CcsTraceSession();
        byte[] packet = master(CONNECTED, 0, CONNECTED.length, 0x34);
        packet[packet.length - 1] ^= 1;
        gate.observeRx(packet, 0, packet.length, 1000L);
        assertFalse(gate.capturing(1000L));
        byte[] badInner = CONNECTED.clone();
        badInner[4] ^= 1;
        feed(gate, badInner, 2000L);
        assertFalse(gate.capturing(2000L));
        packet = master(CONNECTED, 0, CONNECTED.length, 0x38);
        gate.observeRx(packet, 0, packet.length, 3000L);
        assertFalse(gate.capturing(3000L));
        feed(gate, CONNECTED, 4000L);
        assertTrue(gate.capturing(4000L));
    }

    @Test public void logsOnlyOnConnectionWithRecentPrerollAndDisconnectTail() throws Exception {
        CcsTraceSession.CURRENT.reset();
        CcsTraceSession.LogBuffer buffer = new CcsTraceSession.LogBuffer();
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(logs, true, "UTF-8"));
            buffer.write("RX t=1 OLD", 1L);
            buffer.write("RX t=2500 BEFORE_PLUG", 2500L);
            assertTrue(logs.size() == 0);
            feed(CcsTraceSession.CURRENT, CONNECTED, 3000L);
            buffer.write("RX t=3000 HANDSHAKE", 3000L);
            feed(CcsTraceSession.CURRENT, IDLE, 4000L);
            buffer.write("TX t=4500 STOP", 4500L);
            buffer.write("RX t=9001 IDLE_AFTER", 9001L);
            String text = logs.toString("UTF-8");
            assertFalse(text.contains("OLD"));
            assertTrue(text.contains("preRoll=true t=2500 BEFORE_PLUG"));
            assertTrue(text.contains("HANDSHAKE"));
            assertTrue(text.contains("STOP"));
            assertFalse(text.contains("IDLE_AFTER"));
        } finally { System.setOut(original); }
    }

    @Test public void idlePrerollIsBoundedEvenDuringHeavyTraffic() throws Exception {
        CcsTraceSession.CURRENT.reset();
        CcsTraceSession.LogBuffer buffer = new CcsTraceSession.LogBuffer();
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(logs, true, "UTF-8"));
            for (int i = 0; i < 10000; i++) buffer.write("RX t=1000 RECORD_" + i, 1000L);
            assertTrue(logs.size() == 0);
            feed(CcsTraceSession.CURRENT, CONNECTED, 2000L);
            buffer.write("RX t=2000 CURRENT", 2000L);
            String text = logs.toString("UTF-8");
            assertFalse(text.contains("RECORD_0\n"));
            assertTrue(text.contains("RECORD_9999"));
            assertTrue(text.length() < 110000); // Includes preRoll markers.
        } finally { System.setOut(original); }
    }

    @Test public void fullRxUsesTheSameGateAndPreservesOtherwiseFilteredBytes() throws Exception {
        CcsTraceSession.CURRENT.reset();
        Class<?> type = Class.forName("de.rothner.qc45.CcsFullRxTracer$TraceState");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, Integer.TYPE, Integer.TYPE);
        constructor.setAccessible(true);
        Method observe = type.getDeclaredMethod("observe", byte[].class, Integer.TYPE, Integer.TYPE);
        Method shutdown = type.getDeclaredMethod("shutdown");
        observe.setAccessible(true);
        shutdown.setAccessible(true);
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream original = System.out;
        Object state = null;
        try {
            System.setOut(new PrintStream(logs, true, "UTF-8"));
            state = constructor.newInstance("/dev/ttyS0", "MasterProtoSerializer", 1000, 32);
            byte[] zeros = new byte[32];
            observe.invoke(state, zeros, 0, zeros.length); // Max-size flush while idle.
            assertTrue(logs.size() == 0);
            feed(CcsTraceSession.CURRENT, CONNECTED, System.currentTimeMillis());
            observe.invoke(state, zeros, 0, zeros.length);
            assertTrue(logs.toString("UTF-8").contains("CCS-FULL-RX RX"));
            assertTrue(logs.toString("UTF-8").contains("preRoll=true"));
            assertTrue(logs.toString("UTF-8").contains("raw=00 00 00"));
        } finally {
            if (state != null) shutdown.invoke(state);
            System.setOut(original);
        }
    }

    static byte[] telemetryMasterFrame(int soc, int voltage, int current, boolean charging) {
        byte[] quick = new byte[14];
        quick[0] = 0x63; quick[1] = (byte)(charging ? 0x70 : 0); quick[2] = (byte)soc;
        quick[9] = (byte)voltage; quick[10] = (byte)(voltage >>> 8); quick[11] = (byte)current;
        int crc = 0xffff;
        for (int i = 0; i < 12; i++) {
            crc ^= quick[i] & 255;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
        }
        quick[12] = (byte)crc; quick[13] = (byte)(crc >>> 8);
        return master(quick, 0, quick.length, 0x34);
    }

    static byte[] connectedMasterFrame() { return master(CONNECTED, 0, CONNECTED.length, 0x34); }

    private static void feed(CcsTraceSession gate, byte[] quick, long now) {
        for (int off = 0; off < quick.length; off += 3) {
            byte[] frame = master(quick, off, Math.min(3, quick.length - off), 0x34);
            gate.observeRx(frame, 0, frame.length, now);
        }
    }

    private static byte[] master(byte[] data, int off, int len, int device) {
        byte[] result = new byte[len + 4];
        result[0] = 0x2a;
        result[1] = (byte)device;
        result[2] = (byte)len;
        System.arraycopy(data, off, result, 3, len);
        int sum = 0;
        for (int i = 0; i < result.length - 1; i++) sum += result[i] & 255;
        result[result.length - 1] = (byte)-sum;
        return result;
    }

    private static byte[] hex(String text) {
        String[] parts = text.split(" ");
        byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = (byte)Integer.parseInt(parts[i], 16);
        return result;
    }
}
