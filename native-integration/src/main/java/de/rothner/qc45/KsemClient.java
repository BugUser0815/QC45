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
    private final boolean legacyLowWord;
    private int transactionId;

    public KsemClient(String host, int port, int unitId, int timeoutMs,
                      double scale, boolean legacyLowWord) {
        this.host = host;
        this.port = port;
        this.unitId = unitId;
        this.timeoutMs = timeoutMs;
        this.scale = scale;
        this.legacyLowWord = legacyLowWord;
    }

    public Currents readCurrents() throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            double l1 = readCurrent(in, out, 60);
            double l2 = readCurrent(in, out, 100);
            double l3 = readCurrent(in, out, 140);
            return new Currents(l1, l2, l3);
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
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
        if (u16(header, 0) != tx || u16(header, 2) != 0) {
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
        if (function != 3 || pdu.length < 6 || (pdu[1] & 0xff) != 4) {
            throw new IllegalStateException("Invalid KSEM current response at register " + register);
        }

        int word0 = u16(pdu, 2);
        int word1 = u16(pdu, 4);
        long raw;
        if (legacyLowWord) {
            // Matches the previously proven Python reader: r1.registers[1].
            raw = word1;
        } else {
            // KSEM data is commonly word-swapped for 32-bit values.
            raw = ((long) word1 << 16) | (long) word0;
        }
        return raw * scale;
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
