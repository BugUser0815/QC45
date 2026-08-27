package de.rothner.qc45;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
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
    private static InputStream originalInput;
    private static TraceInputStream wrapperInput;

    private CcsFullRxTracer() {}

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
        install(flushMs, maxBytes);
    }

    public static synchronized void shutdown() {
        try {
            if (hookedReader != null && hookedField != null && wrapperInput != null) {
                hookedField.setAccessible(true);
                if (hookedField.get(hookedReader) == wrapperInput) {
                    hookedField.set(hookedReader, originalInput);
                }
            }
        } catch (Throwable e) {
            System.err.println(PREFIX + "stream restore failed: " + e);
        }
        TraceState state = liveState;
        liveState = null;
        if (state != null) state.shutdown();
        hookedReader = null;
        hookedField = null;
        originalInput = null;
        wrapperInput = null;
    }

    private static void install(int flushMs, int maxBytes) throws Exception {
        Class<?> centralType = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object central = centralType.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");

        Field communicationsField = findField(central.getClass(), "communications");
        if (communicationsField == null) throw new NoSuchFieldException("CentralModule.communications");
        communicationsField.setAccessible(true);
        Object channel = communicationsField.get(central);
        if (channel == null) throw new IllegalStateException("CentralModule.communications is null");

        String port = stringField(channel, "portName", "physical-master");
        String serializer = objectClassField(channel, "pSerializer");
        Object reader = findSerialReader(channel, port);
        if (reader == null) throw new IllegalStateException("SerialReader unavailable for " + port);

        Field inField = findField(reader.getClass(), "in");
        if (inField == null) throw new NoSuchFieldException(reader.getClass().getName() + ".in");
        inField.setAccessible(true);
        Object inValue = inField.get(reader);
        if (!(inValue instanceof InputStream)) {
            throw new IllegalStateException("SerialReader.in is not an InputStream: " + className(inValue));
        }
        if (inValue instanceof TraceInputStream) {
            System.out.println(PREFIX + "already installed port=" + port);
            return;
        }

        TraceState state = new TraceState(port, serializer, flushMs, maxBytes);
        TraceInputStream wrapper = new TraceInputStream((InputStream)inValue, state);
        inField.set(reader, wrapper);
        hookedReader = reader;
        hookedField = inField;
        originalInput = (InputStream)inValue;
        wrapperInput = wrapper;
        liveState = state;

        System.out.println(PREFIX + "installed port=" + port + " serializer=" + serializer
            + " flushMs=" + state.flushMs + " maxBytes=" + state.maxBytes
            + " WARNING=high-volume-diagnostic-use-only");
    }

    private static Object findSerialReader(Object channel, String port) throws Exception {
        Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
        Field targetField = findField(Thread.class, "target");
        if (targetField != null) targetField.setAccessible(true);

        Object byOwner = null;
        Object byName = null;
        for (Thread thread : threads.keySet()) {
            if (thread == null) continue;
            String name = thread.getName();
            if (name == null || name.indexOf("SerialReader") < 0) continue;

            Object target = null;
            try { if (targetField != null) target = targetField.get(thread); } catch (Throwable ignored) {}
            if (target == null || target.getClass().getName().indexOf("SerialReader") < 0) continue;

            Field ownerField = findField(target.getClass(), "owner");
            if (ownerField != null) {
                ownerField.setAccessible(true);
                Object owner = ownerField.get(target);
                if (owner == channel) byOwner = target;
            }
            if (name.indexOf(port) >= 0) byName = target;
        }
        return byOwner != null ? byOwner : byName;
    }

    private static final class TraceInputStream extends FilterInputStream {
        private final TraceState state;

        TraceInputStream(InputStream in, TraceState state) {
            super(in);
            this.state = state;
        }

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
            System.out.println(PREFIX + "RX t=" + System.currentTimeMillis()
                + " port=" + port + " serializer=" + serializer
                + " bytes=" + bytes.length + " flush=" + reason
                + " raw=" + hex(bytes, 0, bytes.length)
                + printableSuffix(bytes));
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

    private static Object fieldObject(Object owner, String name) {
        try {
            Field f = findField(owner.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String objectClassField(Object owner, String name) {
        return className(fieldObject(owner, name));
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String stringField(Object owner, String name, String fallback) {
        Object value = fieldObject(owner, name);
        return value == null ? fallback : String.valueOf(value);
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
