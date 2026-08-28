package de.rothner.qc45;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Minimal Modbus/TCP reader for KOSTAL KSEM phase currents. */
public final class KsemClient {
    public static final class Currents {
        public final double l1;
        public final double l2;
        public final double l3;

        Currents(double l1, double l2, double l3) {
            this.l1 = l1;
            this.l2 = l2;
            this.l3 = l3;
        }

        public double max() {
            return Math.max(l1, Math.max(l2, l3));
        }
    }

    private final String host;
    private final int port;
    private final int unitId;
    private final int timeoutMs;
    private final double scale;
    private final WordOrder wordOrder;
    private int transactionId;
    private Socket socket;
    private InputStream input;
    private OutputStream output;

    public KsemClient(String host, int port, int unitId, int timeoutMs,
                      double scale, String wordOrder) {
        if (host == null || host.trim().length() == 0) throw new IllegalArgumentException("KSEM host is required");
        if (port < 1 || port > 65535 || unitId < 0 || unitId > 255
                || timeoutMs <= 0 || scale <= 0.0d || Double.isNaN(scale)
                || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("invalid KSEM connection or scale");
        }
        this.host = host.trim();
        this.port = port;
        this.unitId = unitId;
        this.timeoutMs = timeoutMs;
        this.scale = scale;
        this.wordOrder = WordOrder.parse(wordOrder);
    }

    public synchronized Currents readCurrents() throws Exception {
        try {
            ensureConnected();
            double l1 = readCurrent(input, output, 60);
            double l2 = readCurrent(input, output, 100);
            double l3 = readCurrent(input, output, 140);
            return new Currents(l1, l2, l3);
        } catch (Exception e) {
            // Never reuse a stream after a partial Modbus exchange. The next
            // caller establishes a fresh connection while both safety users
            // remain fail-closed for this failed reading.
            closeConnection();
            throw e;
        } catch (Error e) {
            closeConnection();
            throw e;
        }
    }

    public synchronized void close() {
        closeConnection();
    }

    private void ensureConnected() throws Exception {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        Socket connected = new Socket();
        try {
            connected.connect(new InetSocketAddress(host, port), timeoutMs);
            connected.setSoTimeout(timeoutMs);
            input = new BufferedInputStream(connected.getInputStream());
            output = new BufferedOutputStream(connected.getOutputStream());
            socket = connected;
        } catch (Exception e) {
            try { connected.close(); } catch (Throwable ignored) {}
            input = null;
            output = null;
            socket = null;
            throw e;
        }
    }

    private void closeConnection() {
        Socket current = socket;
        socket = null;
        input = null;
        output = null;
        if (current != null) {
            try { current.close(); } catch (Throwable ignored) {}
        }
    }

    private double readCurrent(InputStream in, OutputStream out, int register) throws Exception {
        int tx = (++transactionId) & 0xffff;
        byte[] request = new byte[12];
        putU16(request, 0, tx);
        putU16(request, 2, 0);
        putU16(request, 4, 6);
        request[6] = (byte) unitId;
        request[7] = 3; // Read Holding Registers
        putU16(request, 8, register);
        putU16(request, 10, 2);
        out.write(request);
        out.flush();

        byte[] header = new byte[7];
        readFully(in, header, 0, header.length);
        if (u16(header, 0) != tx || u16(header, 2) != 0
                || (header[6] & 0xff) != unitId) {
            throw new IllegalStateException("Invalid KSEM Modbus response header");
        }

        int len = u16(header, 4) - 1;
        if (len < 2 || len > 253) throw new IllegalStateException("Invalid KSEM Modbus length");
        byte[] pdu = new byte[len];
        readFully(in, pdu, 0, len);

        int function = pdu[0] & 0xff;
        if ((function & 0x80) != 0) {
            int code = pdu.length > 1 ? pdu[1] & 0xff : -1;
            throw new IllegalStateException("KSEM Modbus exception " + code + " at register " + register);
        }
        if (function != 3 || pdu.length != 6 || (pdu[1] & 0xff) != 4) {
            throw new IllegalStateException("Invalid KSEM current response at register " + register);
        }

        int word0 = u16(pdu, 2);
        int word1 = u16(pdu, 4);
        double current = decodeCurrent(word0, word1, scale, wordOrder);
        if (current < 0.0d || current > 10000.0d || Double.isNaN(current)
                || Double.isInfinite(current)) {
            throw new IllegalStateException("Implausible KSEM current " + current
                + "A at register " + register);
        }
        return current;
    }

    static double decodeCurrent(int word0, int word1, double scale, WordOrder order) {
        long high = order == WordOrder.HIGH_LOW ? word0 & 0xffffL : word1 & 0xffffL;
        long low = order == WordOrder.HIGH_LOW ? word1 & 0xffffL : word0 & 0xffffL;
        return ((high << 16) | low) * scale;
    }

    enum WordOrder {
        HIGH_LOW,
        LOW_HIGH;

        static WordOrder parse(String value) {
            String normalized = value == null ? "HIGH_LOW" : value.trim().toUpperCase(java.util.Locale.US);
            if ("HIGH_LOW".equals(normalized)) return HIGH_LOW;
            if ("LOW_HIGH".equals(normalized)) return LOW_HIGH;
            throw new IllegalArgumentException("ksem.wordOrder must be HIGH_LOW or LOW_HIGH");
        }
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private static void putU16(byte[] b, int o, int value) {
        b[o] = (byte) ((value >>> 8) & 0xff);
        b[o + 1] = (byte) (value & 0xff);
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws Exception {
        int done = 0;
        while (done < len) {
            int n = in.read(b, off + done, len - done);
            if (n < 0) throw new EOFException();
            done += n;
        }
    }
}
