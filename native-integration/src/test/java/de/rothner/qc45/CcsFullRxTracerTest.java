package de.rothner.qc45;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CcsFullRxTracerTest {
    @After public void shutdown() { CcsFullRxTracer.shutdown(); CcsRawTracerV2.shutdown(); }

    @Test public void delayedThreadSubclassReaderIsFoundAndStreamReplacementsAreFollowed() throws Exception {
        final Runtime runtime = new Runtime();
        CcsFullRxTracer.startInstaller(runtime, 40, 256, 10L);
        await(new Condition() { public boolean met() { return runtime.calls.get() >= 2; } });
        CcsRawTracerV2Test.Channel unopened = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        final CcsRawTracerV2Test.Channel live = new CcsRawTracerV2Test.Channel("/dev/ttyACM0");
        final InputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
        final SerialReaderThread reader = new SerialReaderThread(live, input);
        runtime.central = new CcsRawTracerV2Test.Central(unopened);
        runtime.readers = new CcsSerialDiscovery.Reader[] { CcsSerialDiscovery.reader(reader.getName(), reader, null) };
        await(new Condition() { public boolean met() { return reader.in != input; } });
        InputStream first = reader.in;
        final int calls = runtime.calls.get();
        await(new Condition() { public boolean met() { return runtime.calls.get() >= calls + 3; } });
        assertSame(first, reader.in);
        final InputStream replacement = new ByteArrayInputStream(new byte[] {4, 5});
        reader.in = replacement;
        await(new Condition() { public boolean met() { return reader.in != replacement; } });
        assertNotSame(first, reader.in);
        CcsFullRxTracer.shutdown();
        assertSame(replacement, reader.in);
        assertEquals(4, reader.in.read());
    }

    @Test public void fullBeforeRawFollowsReplacementsWithoutNesting() throws Exception { bothMonitors(true); }
    @Test public void rawBeforeFullFollowsReplacementsWithoutNesting() throws Exception { bothMonitors(false); }

    private void bothMonitors(boolean fullFirst) throws Exception {
        final Runtime runtime = new Runtime();
        final CcsRawTracerV2Test.Channel channel = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        final InputStream input = new ByteArrayInputStream(CcsTraceSessionTest.telemetryMasterFrame(50, 400, 20, true));
        final OutputStream output = new ByteArrayOutputStream(); channel.out = output;
        final SerialReaderThread reader = new SerialReaderThread(channel, input);
        runtime.central = new CcsRawTracerV2Test.Central(channel);
        runtime.readers = new CcsSerialDiscovery.Reader[] { CcsSerialDiscovery.reader(reader.getName(), reader, null) };
        if (fullFirst) {
            CcsFullRxTracer.startInstaller(runtime, 40, 256, 10L);
            await(new Condition() { public boolean met() { return reader.in != input; } });
            CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
        } else {
            CcsRawTracerV2.startInstaller(runtime, 1000L, false, 10L);
            await(new Condition() { public boolean met() { return reader.in != input; } });
            CcsFullRxTracer.startInstaller(runtime, 40, 256, 10L);
        }
        await(new Condition() { public boolean met() { return depth(reader.in) == 2 && channel.out != output; } });
        final InputStream wrapped = reader.in;
        final int calls = runtime.calls.get();
        await(new Condition() { public boolean met() { return runtime.calls.get() >= calls + 10; } });
        assertSame(wrapped, reader.in);
        reader.in.read(new byte[64]);
        assertEquals(8, CcsRawTracerV2.livePowerKw());
        final InputStream replacement = new ByteArrayInputStream(new byte[] {9, 8});
        reader.in = replacement;
        await(new Condition() { public boolean met() { return depth(reader.in) == 2; } });
        assertEquals(9, reader.in.read());
        CcsFullRxTracer.shutdown(); CcsRawTracerV2.shutdown();
        assertSame(replacement, reader.in); assertSame(output, channel.out);
        assertEquals(0, ((ByteArrayOutputStream)output).size());
    }

    private static int depth(InputStream stream) {
        int depth = 0;
        try {
            while (depth < 4) {
                java.lang.reflect.Method source = stream.getClass().getDeclaredMethod("source");
                source.setAccessible(true); stream = (InputStream)source.invoke(stream); depth++;
            }
        } catch (NoSuchMethodException end) { return depth; }
        catch (Exception error) { throw new AssertionError(error); }
        return depth;
    }

    private interface Condition { boolean met(); }
    private static void await(Condition condition) throws Exception {
        long end = System.currentTimeMillis() + 3000L;
        while (!condition.met() && System.currentTimeMillis() < end) Thread.sleep(5L);
        assertTrue("full RX attachment timed out", condition.met());
    }
    static final class SerialReaderThread extends Thread {
        final Object owner; volatile InputStream in;
        SerialReaderThread(Object owner, InputStream input) { super("SerialReader /dev/ttyACM0"); this.owner = owner; in = input; }
    }
    private static final class Runtime implements CcsRawTracerV2.RuntimeAccess {
        volatile Object central;
        volatile CcsSerialDiscovery.Reader[] readers = new CcsSerialDiscovery.Reader[0];
        final AtomicInteger calls = new AtomicInteger();
        public Object central() { calls.incrementAndGet(); return central; }
        public CcsSerialDiscovery.Reader[] readers() { return readers; }
    }
}
