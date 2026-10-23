package de.rothner.qc45;

import org.junit.Test;
import org.junit.After;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;

public final class CcsRawTracerV2Test {
    @After
    public void stopInstaller() {
        CcsFullRxTracer.shutdown();
        CcsRawTracerV2.shutdown();
    }

    @Test
    public void delayedStartupAndLateRxAreRetriedWithoutSendingAnything() throws Exception {
        final Fixture runtime = new Fixture();
        final ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream originalLog = System.out;
        try {
            System.setOut(new PrintStream(logs, true, "UTF-8"));
            CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
            await(new Condition() { public boolean met() { return runtime.calls.get() >= 2; } });
            final Channel channel = new Channel("/dev/ttyS0");
            runtime.central = new Central(channel);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            channel.out = output;
            await(new Condition() { public boolean met() { return channel.out != output; } });
            assertFalse(logs.toString("UTF-8").contains("installed txStreams="));
            final SerialReader reader = new SerialReader(channel, null);
            runtime.readers = refs(reader);
            final InputStream input = new ByteArrayInputStream(frame());
            reader.in = input;
            await(new Condition() { public boolean met() { return reader.in != input; } });
            await(new Condition() { public boolean met() {
                try { return logs.toString("UTF-8").contains("installed txStreams=1 rxStreams=1"); }
                catch (Exception error) { throw new AssertionError(error); }
            } });

            assertEquals(0, output.size()); // Attaching must never send START/STOP.
            byte[] bytes = { 0x63, 0x02, 0x05, 0x00, 0x00 };
            channel.out.write(bytes);
            assertArrayEquals(bytes, output.toByteArray());
            byte[] received = new byte[12];
            assertEquals(12, reader.in.read(received));
            assertArrayEquals(frame(), received);
            assertEquals(8, CcsRawTracerV2.livePowerKw()); // RX works with raw logging disabled.
            assertTrue(logs.toString("UTF-8").contains("installed txStreams=1 rxStreams=1"));

            Thread worker = worker();
            CcsRawTracerV2.shutdown();
            worker.join(2000L);
            assertFalse(worker.isAlive());
            assertSame(output, channel.out);
            assertSame(input, reader.in);
            assertFalse(CcsRawTracerV2.hasFreshLiveTelemetry());
        } finally {
            CcsRawTracerV2.shutdown();
            System.setOut(originalLog);
        }
    }

    @Test
    public void rxCanAttachBeforeTxAndNewStreamsAreReattachedWithoutNesting() throws Exception {
        final Channel channel = new Channel("/dev/ttyACM0");
        final InputStream input = new ByteArrayInputStream(frame());
        final SerialReader reader = new SerialReader(channel, input);
        Fixture runtime = new Fixture(new Central(channel), refs(reader));
        CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
        await(new Condition() { public boolean met() { return reader.in != input; } });
        final OutputStream output = new ByteArrayOutputStream();
        channel.out = output;
        await(new Condition() { public boolean met() { return channel.out != output; } });
        Object firstWorker = worker();
        Object firstOut = channel.out;
        Object firstIn = reader.in;
        CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
        assertSame(firstWorker, worker());
        assertSame(firstOut, channel.out);
        assertSame(firstIn, reader.in);

        final OutputStream replacementOut = new ByteArrayOutputStream();
        final InputStream replacementIn = new ByteArrayInputStream(frame());
        channel.out = replacementOut;
        reader.in = replacementIn;
        await(new Condition() { public boolean met() {
            return channel.out != replacementOut && reader.in != replacementIn;
        } });
        InputStream detached = reader.in;
        CcsRawTracerV2.shutdown();
        assertSame(replacementOut, channel.out);
        assertSame(replacementIn, reader.in);
        assertEquals(12, detached.read(new byte[12]));
        assertFalse(CcsRawTracerV2.hasFreshLiveTelemetry());
    }

    @Test
    public void liveMasterOwnerOverridesUnopenedConfiguredPort() throws Exception {
        Channel unopened = new Channel("/dev/ttyS0");
        final Channel usb = new Channel("/dev/ttyACM0");
        final OutputStream output = new ByteArrayOutputStream();
        final InputStream input = new ByteArrayInputStream(frame());
        usb.out = output;
        final SerialReader reader = new SerialReader(usb, input);
        Fixture runtime = new Fixture(new Central(unopened), refs(reader));
        CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
        await(new Condition() { public boolean met() { return usb.out != output && reader.in != input; } });
        assertSame(null, unopened.out);
        CcsRawTracerV2.shutdown();
        assertSame(output, usb.out);
        assertSame(input, reader.in);
    }

    @Test
    public void shutdownDoesNotOverwriteStreamsReplacedByEvcsd() throws Exception {
        final Channel channel = new Channel("/dev/ttyS0");
        final OutputStream output = new ByteArrayOutputStream();
        final InputStream input = new ByteArrayInputStream(frame());
        channel.out = output;
        final SerialReader reader = new SerialReader(channel, input);
        Fixture runtime = new Fixture(new Central(channel), refs(reader));
        CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10000L);
        await(new Condition() { public boolean met() { return channel.out != output && reader.in != input; } });
        OutputStream replacementOut = new ByteArrayOutputStream();
        InputStream replacementIn = new ByteArrayInputStream(frame());
        channel.out = replacementOut;
        reader.in = replacementIn;
        CcsRawTracerV2.shutdown();
        assertSame(replacementOut, channel.out);
        assertSame(replacementIn, reader.in);
    }

    @Test
    public void fullCaptureOutsideRawDoesNotCauseRepeatedWrapping() throws Exception {
        final Channel channel = new Channel("/dev/ttyS0");
        final InputStream input = new ByteArrayInputStream(frame());
        channel.out = new ByteArrayOutputStream();
        final SerialReader reader = new SerialReader(channel, input);
        final Fixture runtime = new Fixture(new Central(channel), refs(reader));
        CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
        await(new Condition() { public boolean met() { return reader.in != input; } });
        InputStream outer = installFullWrapper(reader);
        final int calls = runtime.calls.get();
        await(new Condition() { public boolean met() { return runtime.calls.get() >= calls + 3; } });
        assertSame(outer, reader.in);
        CcsRawTracerV2.shutdown();
        assertSame(outer, reader.in);
        CcsFullRxTracer.shutdown();
        assertSame(input, reader.in);
    }

    @Test
    public void fullCaptureInsideRawCanBeStoppedWithoutLeavingAnOldWrapper() throws Exception {
        final Channel channel = new Channel("/dev/ttyS0");
        final InputStream input = new ByteArrayInputStream(frame());
        channel.out = new ByteArrayOutputStream();
        final SerialReader reader = new SerialReader(channel, input);
        final InputStream full = installFullWrapper(reader);
        Fixture runtime = new Fixture(new Central(channel), refs(reader));
        CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
        await(new Condition() { public boolean met() { return reader.in != full; } });
        CcsFullRxTracer.shutdown();
        CcsRawTracerV2.shutdown();
        assertSame(input, reader.in);
    }

    private static InputStream installFullWrapper(SerialReader reader) throws Exception {
        // Exercise the real optional full-capture wrapper without loading the
        // proprietary EVCSD classes or opening a serial device.
        Class<?> stateType = Class.forName("de.rothner.qc45.CcsFullRxTracer$TraceState");
        java.lang.reflect.Constructor<?> stateConstructor = stateType.getDeclaredConstructor(
            String.class, String.class, Integer.TYPE, Integer.TYPE);
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance("/dev/ttyS0", "MasterProtoSerializer", 40, 256);
        Class<?> wrapperType = Class.forName("de.rothner.qc45.CcsFullRxTracer$TraceInputStream");
        java.lang.reflect.Constructor<?> wrapperConstructor = wrapperType.getDeclaredConstructor(
            InputStream.class, stateType);
        wrapperConstructor.setAccessible(true);
        InputStream original = reader.in;
        InputStream wrapper = (InputStream)wrapperConstructor.newInstance(original, state);
        Field inputField = SerialReader.class.getDeclaredField("in");
        inputField.setAccessible(true);
        setFullField("hookedReader", reader);
        setFullField("hookedField", inputField);
        setFullField("originalInput", original);
        setFullField("wrapperInput", wrapper);
        setFullField("liveState", state);
        reader.in = wrapper;
        return wrapper;
    }

    private static void setFullField(String name, Object value) throws Exception {
        Field field = CcsFullRxTracer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static CcsSerialDiscovery.Reader[] refs(SerialReader reader) {
        return new CcsSerialDiscovery.Reader[] {
            CcsSerialDiscovery.reader("SerialReader-" + CcsSerialDiscovery.port(reader.owner), null, reader)
        };
    }

    private static byte[] frame() {
        return new byte[] { 0x63, 0x10, 50, 0, 0, 0, 0, 0, 0, (byte)0x90, 0x01, 20 };
    }

    private static Thread worker() throws Exception {
        Field field = CcsRawTracerV2.class.getDeclaredField("installer");
        field.setAccessible(true);
        return (Thread)field.get(null);
    }

    private interface Condition { boolean met(); }

    private static void await(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (!condition.met() && System.currentTimeMillis() < deadline) Thread.sleep(5L);
        assertTrue("Tracer did not attach within 3 seconds", condition.met());
    }

    static final class MasterProtoSerializer {}
    static final class QuickChargeSerializer {}

    static class Channel {
        volatile OutputStream out;
        Object pSerializer = new MasterProtoSerializer();
        final String portName;
        boolean loaded;
        Channel(String port) { portName = port; }
    }

    static final class Central {
        final Object communications;
        Central(Object channel) { communications = channel; }
    }

    static class SerialReader {
        final Object owner;
        volatile InputStream in;
        SerialReader(Object owner, InputStream in) { this.owner = owner; this.in = in; }
    }

    private static final class Fixture implements CcsRawTracerV2.RuntimeAccess {
        volatile Object central;
        volatile CcsSerialDiscovery.Reader[] readers = new CcsSerialDiscovery.Reader[0];
        final AtomicInteger calls = new AtomicInteger();
        Fixture() {}
        Fixture(Object central, CcsSerialDiscovery.Reader[] readers) {
            this.central = central; this.readers = readers;
        }
        public Object central() { calls.incrementAndGet(); return central; }
        public CcsSerialDiscovery.Reader[] readers() { return readers; }
    }

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
}

