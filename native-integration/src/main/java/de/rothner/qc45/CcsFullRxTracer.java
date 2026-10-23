package de.rothner.qc45;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

/**
 * Full RX tracer for the physical EFACEC master/QuickCharge path.
 *
 * This is deliberately separate from CcsRawTracerV2: the latter remains the
 * always-on live telemetry hook and can optionally emit sparse 0x63 diagnostics.
 * This tracer is intended for short reverse-engineering captures (for example
 * while looking for an EVCCID/MAC during CCS plug-in) and is disabled by default.
 */
public final class CcsFullRxTracer {
    private static final String PREFIX = "[QC45] CCS-FULL-RX ";
    private static volatile TraceState liveState;
    private static Object hookedReader;
    private static Field hookedField;
    private static volatile Thread installer;
    private static String lastStatus;
    private static long lastStatusAt;
    private static InputStream originalInput;
    private static TraceInputStream wrapperInput;

    private CcsFullRxTracer() {}

    // The raw hook monitor must recognize this optional outer wrapper instead
    // of installing another raw wrapper on every tick.
    static boolean wraps(Object stream, InputStream raw) {
        return stream instanceof TraceInputStream && ((TraceInputStream)stream).source() == raw;
    }

    static void restoreRaw(Object stream, InputStream raw, InputStream original) {
        if (wraps(stream, raw)) {
            ((TraceInputStream)stream).replaceSource(original);
            if (originalInput == raw) originalInput = original;
        }
    }

    public static synchronized void installFromDefaultConfig() throws Exception {
        Properties p = new Properties();
        String explicit = System.getProperty("qc45.integration.config");
        File file = explicit == null || explicit.trim().length() == 0
            ? new File("/home/mobie/evcsd/qc45-integration.properties")
            : new File(explicit.trim());
        InputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); }

        if (!bool(p, "evcsd.ccsRawTrace.fullRx.enabled", false)) {
            System.out.println(PREFIX + "disabled");
            return;
        }

        int flushMs = integer(p, "evcsd.ccsRawTrace.fullRx.flushMs", 40);
        int maxBytes = integer(p, "evcsd.ccsRawTrace.fullRx.maxBytes", 256);
        startInstaller(CcsRawTracerV2.RUNTIME, flushMs, maxBytes, 1000L);
    }

    static synchronized void startInstaller(final CcsRawTracerV2.RuntimeAccess runtime,
                                             final int flushMs, final int maxBytes,
                                             final long retryMs) {
        if (installer != null) return;
        installer = new Thread(new Runnable() {
            public void run() {
                Thread self = Thread.currentThread();
                while (installer == self) {
                    synchronized (CcsSerialDiscovery.STREAM_LOCK) {
                        if (installer != self) return;
                        try { attachAvailable(runtime, flushMs, maxBytes); }
                        catch (Exception error) { report("waiting reason=" + error, false); }
                    }
                    try { Thread.sleep(Math.max(10L, retryMs)); }
                    catch (InterruptedException ignored) {}
                }
            }
        }, "qc45-ccs-full-rx-attach");
        installer.setDaemon(true);
        installer.start();
    }

    public static synchronized void shutdown() {
        Thread worker = installer;
        installer = null;
        if (worker != null) worker.interrupt();
        synchronized (CcsSerialDiscovery.STREAM_LOCK) {
            detach();
            lastStatus = null;
            lastStatusAt = 0L;
        }
    }

    private static void detach() {
        try {
            if (hookedReader != null && hookedField != null && wrapperInput != null) {
                hookedField.setAccessible(true);
                Object input = hookedField.get(hookedReader);
                if (input == wrapperInput) hookedField.set(hookedReader, originalInput);
                else CcsRawTracerV2.restoreFull(input, wrapperInput, originalInput);
            }
        } catch (Throwable error) {
            System.err.println(PREFIX + "stream restore failed: " + error);
        }
        TraceState state = liveState;
        liveState = null;
        if (state != null) state.shutdown();
        hookedReader = null;
        hookedField = null;
        originalInput = null;
        wrapperInput = null;
    }

    private static void attachAvailable(CcsRawTracerV2.RuntimeAccess runtime,
                                        int flushMs, int maxBytes) throws Exception {
        Object central = runtime.central();
        if (central == null) throw new IllegalStateException("CentralModule unavailable");
        CcsSerialDiscovery.Selection selection = CcsSerialDiscovery.select(central, runtime.readers());
        Object reader = selection.reader == null ? null : selection.reader.inputOwner;
        if (hookedReader != reader || !attached()) detach();
        if (reader == null) {
            report("waiting selection=" + selection.reason, false);
            return;
        }
        if (wrapperInput == null) {
            Field field = findField(reader.getClass(), "in");
            if (field == null) throw new NoSuchFieldException(reader.getClass().getName() + ".in");
            field.setAccessible(true);
            Object input = field.get(reader);
            if (!(input instanceof InputStream)) throw new IllegalStateException("SerialReader input unavailable");
            String port = CcsSerialDiscovery.port(selection.channel);
            String serializer = CcsSerialDiscovery.serializer(selection.channel);
            TraceState state = new TraceState(port, serializer, flushMs, maxBytes);
            TraceInputStream wrapper = new TraceInputStream((InputStream)input, state);
            try { field.set(reader, wrapper); }
            catch (Exception error) { state.shutdown(); throw error; }
            hookedReader = reader;
            hookedField = field;
            originalInput = (InputStream)input;
            wrapperInput = wrapper;
            liveState = state;
        }
        report("installed port=" + CcsSerialDiscovery.port(selection.channel)
            + " serializer=" + CcsSerialDiscovery.serializer(selection.channel)
            + " selection=" + selection.reason + " flushMs=" + liveState.flushMs
            + " maxBytes=" + liveState.maxBytes
            + " WARNING=high-volume-diagnostic-use-only", true);
    }

    private static boolean attached() throws Exception {
        if (wrapperInput == null) return false;
        Object input = hookedField.get(hookedReader);
        return input == wrapperInput || CcsRawTracerV2.wraps(input, wrapperInput);
    }

    private static void report(String status, boolean ready) {
        long now = System.currentTimeMillis();
        if (!status.equals(lastStatus) || (!ready && now - lastStatusAt >= 30000L)) {
            System.out.println(PREFIX + status);
            lastStatus = status;
            lastStatusAt = now;
        }
    }

    private static final class TraceInputStream extends FilterInputStream {
        private final TraceState state;

        TraceInputStream(InputStream in, TraceState state) {
            super(in);
            this.state = state;
        }

        InputStream source() { return in; }
        void replaceSource(InputStream source) { in = source; }

        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) {
                byte[] one = new byte[] { (byte)value };
                state.observe(one, 0, 1);
            }
            return value;
        }

        public int read(byte[] b) throws IOException {
            int n = in.read(b);
            if (n > 0) state.observe(b, 0, n);
            return n;
        }

        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) state.observe(b, off, n);
            return n;
        }
    }

    private static final class TraceState {
        private final CcsTraceSession.LogBuffer logBuffer = new CcsTraceSession.LogBuffer();
        private final Object lock = new Object();
        private final String port;
        private final String serializer;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final Thread flusher;
        private final int flushMs;
        private final int maxBytes;
        private volatile boolean running = true;
        private long lastByteMs;

        TraceState(String port, String serializer, int flushMs, int maxBytes) {
            this.port = port;
            this.serializer = serializer;
            this.flushMs = clamp(flushMs, 10, 1000);
            this.maxBytes = clamp(maxBytes, 32, 4096);
            flusher = new Thread(new Runnable() {
                public void run() { flushLoop(); }
            }, "qc45-ccs-full-rx-flush");
            flusher.setDaemon(true);
            flusher.start();
        }

        void observe(byte[] data, int off, int len) {
            if (!running || data == null || len <= 0) return;
            int end = off + len;
            synchronized (lock) {
                int p = off;
                while (p < end) {
                    int room = maxBytes - pending.size();
                    int n = Math.min(room, end - p);
                    pending.write(data, p, n);
                    p += n;
                    lastByteMs = System.currentTimeMillis();
                    if (pending.size() >= maxBytes) flushLocked("max");
                }
            }
        }

        void shutdown() {
            running = false;
            flusher.interrupt();
            synchronized (lock) { flushLocked("shutdown"); }
            logBuffer.clear();
        }

        private void flushLoop() {
            while (running) {
                try {
                    Thread.sleep(Math.max(10, flushMs / 2));
                } catch (InterruptedException ignored) {
                    if (!running) return;
                }
                long now = System.currentTimeMillis();
                synchronized (lock) {
                    if (pending.size() > 0 && lastByteMs > 0L && now - lastByteMs >= flushMs) {
                        flushLocked("idle");
                    }
                }
            }
        }

        private void flushLocked(String reason) {
            if (pending.size() == 0) return;
            byte[] bytes = pending.toByteArray();
            pending.reset();
            long now = System.currentTimeMillis();
            logBuffer.write(PREFIX + "RX t=" + now
                + " port=" + port + " serializer=" + serializer
                + " bytes=" + bytes.length + " flush=" + reason
                + " raw=" + hex(bytes, 0, bytes.length)
                + printableSuffix(bytes), now);
        }
    }

    private static String printableSuffix(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length);
        int printable = 0;
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            if (value >= 32 && value <= 126) {
                text.append((char)value);
                printable++;
            } else {
                text.append('.');
            }
        }
        return printable >= 4 ? " ascii=" + text.toString() : "";
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }

    private static String hex(byte[] bytes, int off, int len) {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder out = new StringBuilder(len * 3);
        for (int i = off; i < off + len; i++) {
            if (out.length() > 0) out.append(' ');
            int value = bytes[i] & 0xff;
            out.append(digits[(value >>> 4) & 0x0f]).append(digits[value & 0x0f]);
        }
        return out.toString();
    }

    private static int integer(Properties p, String key, int fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().length() == 0
            ? fallback : Integer.parseInt(value.trim());
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().length() == 0
            ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

