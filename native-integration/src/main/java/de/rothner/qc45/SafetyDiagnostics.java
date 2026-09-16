package de.rothner.qc45;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;

/**
 * Read-only diagnostic view of the native integration's safety state.
 *
 * This class deliberately does not participate in control decisions. It only
 * observes the already existing blockers and protection threads so UI/Modbus
 * can explain why charging is paused and what condition will release it.
 *
 * A separate loopback-only Modbus endpoint keeps the production Modbus map on
 * port 1502 untouched. Diagnostic registers 186..190 are exposed on port 1503.
 */
final class SafetyDiagnostics {
    static final int VERSION = 1;

    static final int STATE_NORMAL = 0;
    static final int STATE_STARTUP = 1;
    static final int STATE_KSEM_RECOVERY = 2;
    static final int STATE_GRID_OVER_LIMIT = 3;
    static final int STATE_GRID_HARD_TRIP = 4;
    static final int STATE_GRID_HARD_TRIP_WAIT_SESSION = 5;
    static final int STATE_LIMIT_MISMATCH = 6;
    static final int STATE_CONFIGURATION = 7;
    static final int STATE_SHUTDOWN = 8;
    static final int STATE_BLOCKED = 9;

    static final int UNIT_NONE = 0;
    static final int UNIT_READS = 1;
    static final int UNIT_SECONDS = 2;

    static final int MODBUS_FIRST_REGISTER = 186;
    static final int MODBUS_REGISTER_COUNT = 5;
    static final int MODBUS_PORT = 1503;

    private static final int HEALTHY_READS_TO_RESUME = 5;
    private static volatile DiagnosticModbusServer server;

    private SafetyDiagnostics() {}

    static synchronized void startModbus(ChargingLimitCoordinator limits) {
        if (limits == null || server != null) return;
        DiagnosticModbusServer candidate = new DiagnosticModbusServer(limits);
        server = candidate;
        candidate.start();
    }

    static Snapshot capture(ChargingLimitCoordinator limits) {
        return capture(limits, System.currentTimeMillis());
    }

    static Snapshot capture(ChargingLimitCoordinator limits, long now) {
        if (limits == null) return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);

        ChargingLimitCoordinator.Snapshot state = limits.snapshot();
        if (!state.blocked) return new Snapshot(STATE_NORMAL, 0, 0, UNIT_NONE);

        // Highest-severity blockers win. LIMIT_MISMATCH intentionally precedes
        // FAILBACK because both may coexist while a failed ramp-down is being
        // aborted; operators need to see the immediate recovery condition.
        if (state.shutdownBlocked)
            return new Snapshot(STATE_SHUTDOWN, 0, 0, UNIT_NONE);
        if (state.configurationBlocked)
            return new Snapshot(STATE_CONFIGURATION, 0, 0, UNIT_NONE);
        if (state.limitMismatchBlocked)
            return new Snapshot(STATE_LIMIT_MISMATCH, 0, 0, UNIT_NONE);

        ThreadSet threads = findThreads();
        if (state.failbackBlocked) {
            Snapshot failback = failbackSnapshot(threads.failback, now);
            if (failback != null) return failback;
            return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);
        }

        if (state.loadMeterBlocked) {
            int healthy = intField(threads.loadManager, "healthyReads", 0);
            return new Snapshot(STATE_KSEM_RECOVERY,
                clamp(healthy, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        if (state.startupBlocked) {
            int healthy = intField(threads.loadManager, "healthyReads", 0);
            return new Snapshot(STATE_STARTUP,
                clamp(healthy, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);
    }

    static int[] registers(ChargingLimitCoordinator limits) {
        Snapshot status = capture(limits);
        return new int[] {
            VERSION,
            clamp(status.state, 0, 65535),
            clamp(status.progress, 0, 65535),
            clamp(status.total, 0, 65535),
            clamp(status.unit, 0, 65535)
        };
    }

    private static Snapshot failbackSnapshot(GridFailback failback, long now) {
        if (failback == null) return null;

        if (booleanField(failback, "tripped", false)) {
            long resetDelayMs = longField(failback, "resetDelayMs", 60000L);
            long resetSince = longField(failback, "resetSince", 0L);
            int totalSeconds = clamp((int)Math.max(1L,
                (resetDelayMs + 999L) / 1000L), 1, 65535);
            int elapsedSeconds = resetSince <= 0L ? 0
                : clamp((int)Math.max(0L, (now - resetSince) / 1000L),
                    0, totalSeconds);
            int state = elapsedSeconds >= totalSeconds
                ? STATE_GRID_HARD_TRIP_WAIT_SESSION : STATE_GRID_HARD_TRIP;
            return new Snapshot(state, elapsedSeconds, totalSeconds, UNIT_SECONDS);
        }

        if (booleanField(failback, "meterPaused", false)) {
            int reads = intField(failback, "goodMeterReads", 0);
            return new Snapshot(STATE_KSEM_RECOVERY,
                clamp(reads, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        if (booleanField(failback, "overLimitPaused", false)) {
            int reads = intField(failback, "goodOverLimitReads", 0);
            return new Snapshot(STATE_GRID_OVER_LIMIT,
                clamp(reads, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);
    }

    private static ThreadSet findThreads() {
        GridFailback failback = null;
        LoadManager loadManager = null;
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (thread instanceof GridFailback) failback = (GridFailback)thread;
                else if (thread instanceof LoadManager) loadManager = (LoadManager)thread;
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect safety/control execution.
        }
        return new ThreadSet(failback, loadManager);
    }

    private static boolean booleanField(Object owner, String name, boolean fallback) {
        Object value = field(owner, name);
        return value instanceof Boolean ? ((Boolean)value).booleanValue() : fallback;
    }

    private static int intField(Object owner, String name, int fallback) {
        Object value = field(owner, name);
        return value instanceof Number ? ((Number)value).intValue() : fallback;
    }

    private static long longField(Object owner, String name, long fallback) {
        Object value = field(owner, name);
        return value instanceof Number ? ((Number)value).longValue() : fallback;
    }

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Snapshot {
        final int state;
        final int progress;
        final int total;
        final int unit;

        Snapshot(int state, int progress, int total, int unit) {
            this.state = state;
            this.progress = progress;
            this.total = total;
            this.unit = unit;
        }
    }

    private static final class ThreadSet {
        final GridFailback failback;
        final LoadManager loadManager;

        ThreadSet(GridFailback failback, LoadManager loadManager) {
            this.failback = failback;
            this.loadManager = loadManager;
        }
    }

    /** Minimal FC03/FC04 server for five read-only diagnostic registers. */
    private static final class DiagnosticModbusServer extends Thread {
        private final ChargingLimitCoordinator limits;
        private volatile ServerSocket listener;

        DiagnosticModbusServer(ChargingLimitCoordinator limits) {
            super("QC45-Safety-Diagnostics-Modbus");
            this.limits = limits;
            setDaemon(true);
        }

        public void run() {
            try {
                listener = new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), MODBUS_PORT));
                System.out.println("[QC45] safety diagnostics Modbus listening on 127.0.0.1:"
                    + MODBUS_PORT + " registers=" + MODBUS_FIRST_REGISTER + ".."
                    + (MODBUS_FIRST_REGISTER + MODBUS_REGISTER_COUNT - 1));
                while (true) {
                    Socket socket = listener.accept();
                    socket.setSoTimeout(1500);
                    try { handle(socket); }
                    catch (SocketException ignored) {}
                    catch (Throwable e) {
                        System.err.println("[QC45] safety diagnostics Modbus client failed: " + e);
                    } finally {
                        try { socket.close(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable e) {
                System.err.println("[QC45] safety diagnostics Modbus disabled: " + e);
            }
        }

        private void handle(Socket socket) throws Exception {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (!socket.isClosed()) {
                byte[] mbap = new byte[7];
                if (!readFullyOrEof(in, mbap, 0, mbap.length)) return;
                int tx = u16(mbap, 0);
                int protocol = u16(mbap, 2);
                int length = u16(mbap, 4);
                int unit = mbap[6] & 0xff;
                if (protocol != 0 || length < 2 || length > 260) return;

                byte[] pdu = new byte[length - 1];
                if (!readFullyOrEof(in, pdu, 0, pdu.length)) return;
                byte[] response = process(pdu);

                byte[] header = new byte[7];
                putU16(header, 0, tx);
                putU16(header, 2, 0);
                putU16(header, 4, response.length + 1);
                header[6] = (byte)unit;
                out.write(header);
                out.write(response);
                out.flush();
            }
        }

        private byte[] process(byte[] pdu) {
            if (pdu.length != 5) return exception(pdu, 3);
            int fc = pdu[0] & 0xff;
            if (fc != 3 && fc != 4) return exception(pdu, 1);
            int address = u16(pdu, 1);
            int count = u16(pdu, 3);
            if (count < 1 || count > MODBUS_REGISTER_COUNT
                    || address < MODBUS_FIRST_REGISTER
                    || address + count > MODBUS_FIRST_REGISTER + MODBUS_REGISTER_COUNT) {
                return exception(pdu, 2);
            }

            int[] values = registers(limits);
            byte[] response = new byte[2 + count * 2];
            response[0] = (byte)fc;
            response[1] = (byte)(count * 2);
            int offset = address - MODBUS_FIRST_REGISTER;
            for (int i = 0; i < count; i++) {
                putU16(response, 2 + i * 2, values[offset + i]);
            }
            return response;
        }

        private byte[] exception(byte[] pdu, int code) {
            int fc = pdu.length == 0 ? 0 : pdu[0] & 0xff;
            return new byte[] { (byte)(fc | 0x80), (byte)code };
        }
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte)((value >>> 8) & 0xff);
        data[offset + 1] = (byte)(value & 0xff);
    }

    private static boolean readFullyOrEof(InputStream in, byte[] data,
                                          int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int read = in.read(data, offset + done, length - done);
            if (read < 0) {
                if (done == 0) return false;
                throw new EOFException();
            }
            done += read;
        }
        return true;
    }
}
