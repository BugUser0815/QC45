package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

/** Sparse raw serial tracer for the EFACEC QuickCharge/CCS path. */
public final class CcsRawTracer {
    private static final String PREFIX = "[QC45] CCS-RAW ";

    private CcsRawTracer() {}

    public static void installFromDefaultConfig() throws Exception {
        Properties p = new Properties();
        String explicit = System.getProperty("qc45.integration.config");
        File file = explicit == null || explicit.trim().length() == 0
            ? new File("/home/mobie/evcsd/qc45-integration.properties")
            : new File(explicit.trim());
        InputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); }

        if (!bool(p, "evcsd.ccsRawTrace.enabled", true)) {
            System.out.println(PREFIX + "disabled");
            return;
        }
        install(integer(p, "evcsd.ccsRawTrace.repeatMs", 1000));
    }

    private static void install(long repeatMs) throws Exception {
        Class<?> centralType = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object central = centralType.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable for CCS raw trace");

        int[] counts = new int[] { 0, 0 };

        // On QC45 installations using the master protocol, this is the real UART.
        // commsSatellite then only contains PipeSerialReader instances.
        Field communicationsField = findField(central.getClass(), "communications");
        if (communicationsField != null) {
            communicationsField.setAccessible(true);
            Object physical = communicationsField.get(central);
            if (physical != null) hookChannel(physical, "physical-master", repeatMs, counts);
        }

        // Also hook a direct QuickCharge UART when the installation does not use
        // the master protocol. Virtual PipeSerialReader channels are ignored here.
        Field commsField = findField(central.getClass(), "commsSatellite");
        if (commsField != null) {
            commsField.setAccessible(true);
            Object comms = commsField.get(central);
            if (comms != null && comms.getClass().isArray()) {
                int length = Array.getLength(comms);
                for (int i = 0; i < length; i++) {
                    Object channel = Array.get(comms, i);
                    if (channel != null) hookChannel(channel, "satellite-" + i, repeatMs, counts);
                }
            }
        }

        System.out.println(PREFIX + "installed txStreams=" + counts[0] + " rxStreams=" + counts[1]
            + " repeatMs=" + repeatMs + " (physical master/direct serial; no protocol mutation)");
    }

    private static void hookChannel(Object channel, String fallbackPort, long repeatMs, int[] counts) {
        try {
            Field serializerField = findField(channel.getClass(), "pSerializer");
            if (serializerField == null) return;
            serializerField.setAccessible(true);
            Object serializer = serializerField.get(channel);
            if (serializer == null) return;

            String serializerName = serializer.getClass().getName();
            boolean quick = serializerName.indexOf("QuickChargeSerializer") >= 0;
            boolean master = serializerName.indexOf("MasterProtoSerializer") >= 0;
            if (!quick && !master) return;

            String port = stringField(channel, "portName", fallbackPort);
            boolean filter63 = true;

            Field outField = findField(channel.getClass(), "out");
            if (outField != null) {
                outField.setAccessible(true);
                Object value = outField.get(channel);
                if (value instanceof OutputStream && !(value instanceof TraceOutputStream)) {
                    outField.set(channel, new TraceOutputStream((OutputStream)value, port, serializerName, filter63, repeatMs));
                    counts[0]++;
                }
            }

            Object serialReader = findSerialReader(channel, port);
            if (serialReader != null) {
                Field inField = findField(serialReader.getClass(), "in");
                if (inField != null) {
                    inField.setAccessible(true);
                    Object value = inField.get(serialReader);
                    if (value instanceof InputStream && !(value instanceof TraceInputStream)) {
                        inField.set(serialReader, new TraceInputStream((InputStream)value, port, serializerName, filter63, repeatMs));
                        counts[1]++;
                    }
                }
            }

            System.out.println(PREFIX + "hook candidate class=" + channel.getClass().getName()
                + " port=" + port + " serializer=" + shortName(serializerName)
                + " tx=" + (outField != null) + " rxReader=" + (serialReader != null));
        } catch (Throwable e) {
            System.err.println(PREFIX + "hook failed class=" + channel.getClass().getName() + ": " + e);
        }
    }

    private static Object findSerialReader(Object channel, String port) {
        try {
            Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
            Field targetField = findField(Thread.class, "target");
            if (targetField == null) return null;
            targetField.setAccessible(true);

            for (Thread thread : threads.keySet()) {
                if (thread == null) continue;
                String name = thread.getName();
                if (name == null || !name.startsWith("SerialReader-")) continue;

                Object target = targetField.get(thread);
                if (target == null || target.getClass().getName().indexOf("SerialReader") < 0) continue;

                Field ownerField = findField(target.getClass(), "owner");
                if (ownerField == null) continue;
                ownerField.setAccessible(true);
                if (ownerField.get(target) != channel) continue;

                return target;
            }
        } catch (Throwable e) {
            System.err.println(PREFIX + "RX hook lookup failed port=" + port + ": " + e);
        }
        return null;
    }

    private static final class TraceOutputStream extends FilterOutputStream {
        private final TraceState state;
        TraceOutputStream(OutputStream out, String port, String serializer, boolean filter63, long repeatMs) {
            super(out);
            state = new TraceState("TX", port, serializer, filter63, repeatMs);
        }
        public void write(int b) throws IOException {
            out.write(b);
            byte[] one = new byte[] { (byte)b };
            state.observe(one, 0, 1);
        }
        public void write(byte[] b) throws IOException {
            out.write(b);
            state.observe(b, 0, b.length);
        }
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            state.observe(b, off, len);
        }
    }

    private static final class TraceInputStream extends FilterInputStream {
        private final TraceState state;
        TraceInputStream(InputStream in, String port, String serializer, boolean filter63, long repeatMs) {
            super(in);
            state = new TraceState("RX", port, serializer, filter63, repeatMs);
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
        private final String direction;
        private final String port;
        private final String serializer;
        private final boolean filter63;
        private final long repeatMs;
        private String lastHex = "";
        private long lastLogMs;

        TraceState(String direction, String port, String serializer, boolean filter63, long repeatMs) {
            this.direction = direction;
            this.port = port;
            this.serializer = shortName(serializer);
            this.filter63 = filter63;
            this.repeatMs = Math.max(100L, repeatMs);
        }

        synchronized void observe(byte[] data, int off, int len) {
            if (data == null || len <= 0) return;
            if (filter63 && !contains63(data, off, len)) return;
            String hex = hex(data, off, len);
            long now = System.currentTimeMillis();
            if (hex.equals(lastHex) && now - lastLogMs < repeatMs) return;
            lastHex = hex;
            lastLogMs = now;
            String decoded = decode(direction, data, off, len);
            System.out.println(PREFIX + direction + " t=" + now + " port=" + port
                + " serializer=" + serializer + " bytes=" + len + " raw=" + hex
                + (decoded.length() == 0 ? "" : " " + decoded));
        }
    }

    private static String decode(String direction, byte[] data, int off, int len) {
        int p = find63(data, off, len);
        if (p < 0) return "";
        int remain = off + len - p;
        if ("TX".equals(direction) && remain >= 3) {
            int flags = u8(data[p + 1]);
            int value = u8(data[p + 2]);
            String action;
            if ((flags & 0x7f) == 0x02) action = "START";
            else if ((flags & 0x7f) == 0x03) action = "ABORT";
            else if ((flags & 0x7f) == 0x00) action = "STOP";
            else action = "CMD";
            return "decoded[action=" + action + ",flags=0x" + hexByte(flags)
                + ",loggedIn=" + ((flags & 0x80) == 0) + ",value=" + value + "]";
        }
        if ("RX".equals(direction) && remain >= 12) {
            int flags = u8(data[p + 1]);
            int soc = u8(data[p + 2]);
            int voltage = u8(data[p + 9]) | (u8(data[p + 10]) << 8);
            int current = u8(data[p + 11]);
            int power = (voltage * current) / 1000;
            return "decoded[flags=0x" + hexByte(flags)
                + ",ccsConnected=" + ((flags & 0x02) != 0)
                + ",authorize=" + ((flags & 0x20) != 0)
                + ",charging=" + ((flags & 0x10) != 0)
                + ",soc=" + soc + "%,voltage=" + voltage + "V,current=" + current
                + "A,power=" + power + "kW]";
        }
        return "";
    }

    private static int find63(byte[] data, int off, int len) {
        int end = off + len;
        for (int i = off; i < end; i++) if (u8(data[i]) == 0x63) return i;
        return -1;
    }
    private static boolean contains63(byte[] data, int off, int len) { return find63(data, off, len) >= 0; }
    private static int u8(byte b) { return b & 0xff; }

    private static String hex(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        int end = off + len;
        for (int i = off; i < end; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(hexByte(u8(data[i])));
        }
        return sb.toString();
    }
    private static String hexByte(int value) {
        final char[] h = "0123456789ABCDEF".toCharArray();
        return new String(new char[] { h[(value >>> 4) & 0x0f], h[value & 0x0f] });
    }
    private static String shortName(String name) {
        if (name == null) return "?";
        int p = name.lastIndexOf('.');
        return p < 0 ? name : name.substring(p + 1);
    }
    private static String stringField(Object owner, String name, String fallback) {
        try {
            Field f = findField(owner.getClass(), name);
            if (f == null) return fallback;
            f.setAccessible(true);
            Object value = f.get(owner);
            return value == null ? fallback : String.valueOf(value);
        } catch (Throwable e) { return fallback; }
    }
    private static Field findField(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null) {
            try { return t.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { t = t.getSuperclass(); }
        }
        return null;
    }
    private static int integer(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        return v == null || v.trim().length() == 0 ? fallback : Integer.parseInt(v.trim());
    }
    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null || v.trim().length() == 0 ? fallback : Boolean.parseBoolean(v.trim());
    }
}
