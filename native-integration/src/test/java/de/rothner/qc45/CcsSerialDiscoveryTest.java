package de.rothner.qc45;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class CcsSerialDiscoveryTest {
    @Test
    public void ownerIdentityWinsOverConfiguredNameAndUnrelatedReaders() {
        CcsRawTracerV2Test.Channel configured = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        CcsRawTracerV2Test.Channel ac = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        ac.pSerializer = new CcsRawTracerV2Test.QuickChargeSerializer();
        CcsSerialDiscovery.Reader wrong = ref(ac, "SerialReader-/dev/ttyS0");
        CcsSerialDiscovery.Reader correct = ref(configured, "SerialReader-/dev/ttyACM0");
        CcsSerialDiscovery.Selection selected = CcsSerialDiscovery.select(
            new CcsRawTracerV2Test.Central(configured), new CcsSerialDiscovery.Reader[] { wrong, correct });
        assertSame(configured, selected.channel);
        assertSame(correct, selected.reader);
    }

    @Test
    public void unrelatedAcReaderIsNeverUsedAsUsbFallback() {
        CcsRawTracerV2Test.Channel configured = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        CcsRawTracerV2Test.Channel ac = new CcsRawTracerV2Test.Channel("/dev/ttyACM0");
        ac.pSerializer = new CcsRawTracerV2Test.QuickChargeSerializer();
        CcsSerialDiscovery.Selection selected = CcsSerialDiscovery.select(
            new CcsRawTracerV2Test.Central(configured), new CcsSerialDiscovery.Reader[] { ref(ac, "SerialReader-/dev/ttyACM0") });
        assertSame(configured, selected.channel);
        assertNull(selected.reader);
    }

    @Test
    public void ambiguousLiveMastersAreNotGuessed() {
        CcsRawTracerV2Test.Channel a = new CcsRawTracerV2Test.Channel("/dev/ttyACM0");
        CcsRawTracerV2Test.Channel b = new CcsRawTracerV2Test.Channel("/dev/ttyACM1");
        CcsSerialDiscovery.Selection selected = CcsSerialDiscovery.select(
            new CcsRawTracerV2Test.Central(null), new CcsSerialDiscovery.Reader[] {
                ref(a, "SerialReader-/dev/ttyACM0"), ref(b, "SerialReader-/dev/ttyACM1") });
        assertNull(selected.channel);
        assertNull(selected.reader);
    }

    @Test
    public void threadSubclassWithInheritedInputIsRecognized() {
        CcsRawTracerV2Test.Channel channel = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        SerialReaderThread thread = new SerialReaderThread(channel);
        CcsSerialDiscovery.Reader reader = CcsSerialDiscovery.reader("SerialReader-/dev/ttyS0", thread, null);
        assertSame(thread, reader.inputOwner);
        assertSame(channel, reader.channel);
    }

    @Test
    public void runningThreadSubclassIsFoundByRuntimeEnumeration() throws Exception {
        final CountDownLatch stop = new CountDownLatch(1);
        CcsRawTracerV2Test.Channel channel = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        SerialReaderThread thread = new SerialReaderThread(channel) {
            public void run() {
                try { stop.await(); }
                catch (InterruptedException ignored) {}
            }
        };
        thread.start();
        try {
            boolean found = false;
            for (CcsSerialDiscovery.Reader reader : CcsSerialDiscovery.readers()) {
                if (reader.inputOwner == thread && reader.channel == channel) found = true;
            }
            assertTrue("Live serial reader was not enumerated", found);
        } finally {
            stop.countDown();
            thread.join(2000L);
        }
    }

    @Test
    public void innerReaderCanBeMatchedByEnclosingChannel() {
        InnerChannel channel = new InnerChannel();
        InnerChannel.SerialReader runnable = channel.new SerialReader();
        CcsSerialDiscovery.Reader reader = CcsSerialDiscovery.reader("SerialReader-/dev/ttyACM0", null, runnable);
        assertSame(channel, reader.channel);
    }

    @Test
    public void nameFallbackRequiresAnExactPortAndUnknownOwner() {
        CcsRawTracerV2Test.Channel channel = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        CcsRawTracerV2Test.Central central = new CcsRawTracerV2Test.Central(channel);
        CcsSerialDiscovery.Reader wrong = ref(null, "SerialReader-/dev/ttyS01");
        assertNull(CcsSerialDiscovery.select(central, new CcsSerialDiscovery.Reader[] { wrong }).reader);
        CcsSerialDiscovery.Reader correct = ref(null, "SerialReader-/dev/ttyS0-Reader");
        assertSame(correct, CcsSerialDiscovery.select(central, new CcsSerialDiscovery.Reader[] { correct }).reader);
    }

    @Test
    public void duplicateReadersForSameMasterDoNotSelectAnArbitraryInput() {
        CcsRawTracerV2Test.Channel channel = new CcsRawTracerV2Test.Channel("/dev/ttyS0");
        CcsSerialDiscovery.Selection selected = CcsSerialDiscovery.select(
            new CcsRawTracerV2Test.Central(channel), new CcsSerialDiscovery.Reader[] {
                ref(channel, "SerialReader-A"), ref(channel, "SerialReader-B") });
        assertSame(channel, selected.channel);
        assertNull(selected.reader);
    }

    private static CcsSerialDiscovery.Reader ref(Object owner, String name) {
        return CcsSerialDiscovery.reader(name, null,
            new CcsRawTracerV2Test.SerialReader(owner, new ByteArrayInputStream(new byte[0])));
    }

    private static class ReaderThreadBase extends Thread {
        InputStream in = new ByteArrayInputStream(new byte[0]);
    }
    private static class SerialReaderThread extends ReaderThreadBase {
        final Object owner;
        SerialReaderThread(Object owner) { this.owner = owner; }
    }
    private static final class InnerChannel extends CcsRawTracerV2Test.Channel {
        InnerChannel() { super("/dev/ttyACM0"); }
        final class SerialReader {
            InputStream in = new ByteArrayInputStream(new byte[0]);
        }
    }
}
