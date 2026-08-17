package de.rothner.qc45;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/** Minimal Modbus/TCP server exposing the QC45 to evcc. */
public final class ModbusServer extends Thread {
    private final ReflectionQC45 station;
    private final int port;
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;

    public ModbusServer(ReflectionQC45 station, int port) {
        super("qc45-modbus-" + port);
        this.station = station;
        this.port = port;
        setDaemon(true);
    }

    public void shutdown() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
    }

    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
            System.out.println("[QC45] Modbus TCP listening on " + port);

            while (running) {
                Socket socket = null;
                try {
                    socket = serverSocket.accept();
                    socket.setSoTimeout(10000);
                    handle(socket);
                } catch (SocketException e) {
                    if (running) e.printStackTrace();
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable e) {
            if (running) e.printStackTrace();
        }
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());

        while (running && !socket.isClosed()) {
            byte[] mbap = new byte[7];
            if (!readFullyOrEof(in, mbap, 0, 7)) return;

            int tx = u16(mbap, 0);
            int protocol = u16(mbap, 2);
            int length = u16(mbap, 4);
            int unit = mbap[6] & 0xff;
            if (protocol != 0 || length < 2 || length > 260) return;

            byte[] pdu = new byte[length - 1];
            if (!readFullyOrEof(in, pdu, 0, pdu.length)) return;

            byte[] result;
            try {
                result = process(pdu);
            } catch (ModbusException e) {
                int fc = pdu.length == 0 ? 0 : pdu[0] & 0xff;
                result = new byte[] { (byte) (fc | 0x80), (byte) e.code };
            } catch (Throwable e) {
                e.printStackTrace();
                int fc = pdu.length == 0 ? 0 : pdu[0] & 0xff;
                result = new byte[] { (byte) (fc | 0x80), 4 };
            }

            byte[] header = new byte[7];
            putU16(header, 0, tx);
            putU16(header, 2, 0);
            putU16(header, 4, result.length + 1);
            header[6] = (byte) unit;
            out.write(header);
            out.write(result);
            out.flush();
        }
    }

    private byte[] process(byte[] pdu) throws Exception {
        if (pdu.length < 1) throw new ModbusException(3);
        int fc = pdu[0] & 0xff;
        if (fc == 3 || fc == 4) return read(fc, pdu);
        if (fc == 6) return writeSingle(pdu);
        if (fc == 16) return writeMultiple(pdu);
        throw new ModbusException(1);
    }

    private byte[] read(int fc, byte[] pdu) throws Exception {
        if (pdu.length != 5) throw new ModbusException(3);
        int address = u16(pdu, 1);
        int count = u16(pdu, 3);
        if (count < 1 || count > 125) throw new ModbusException(3);

        byte[] result = new byte[2 + count * 2];
        result[0] = (byte) fc;
        result[1] = (byte) (count * 2);
        for (int i = 0; i < count; i++) putU16(result, 2 + i * 2, register(address + i));
        return result;
    }

    private byte[] writeSingle(byte[] pdu) throws Exception {
        if (pdu.length != 5) throw new ModbusException(3);
        writeRegister(u16(pdu, 1), u16(pdu, 3));
        return pdu;
    }

    private byte[] writeMultiple(byte[] pdu) throws Exception {
        if (pdu.length < 6) throw new ModbusException(3);
        int address = u16(pdu, 1);
        int count = u16(pdu, 3);
        int bytes = pdu[5] & 0xff;
        if (count < 1 || count > 123 || bytes != count * 2 || pdu.length != 6 + bytes) throw new ModbusException(3);

        for (int i = 0; i < count; i++) validateWritable(address + i);
        for (int i = 0; i < count; i++) writeRegister(address + i, u16(pdu, 6 + i * 2));

        byte[] result = new byte[5];
        result[0] = 16;
        putU16(result, 1, address);
        putU16(result, 3, count);
        return result;
    }

    private int register(int address) throws Exception {
        switch (address) {
            case 0: return station.stationPowerKw();
            case 1: return station.powerKw(1);
            case 2: return station.powerKw(2);
            case 3: return station.powerKw(3);
            case 4: return station.activeDcConnector();
            case 10: return station.limitKw(1);
            case 11: return station.limitKw(2);
            case 12: return station.limitKw(3);
            case 20: return station.powerKw(1) > 0 ? 1 : 0;
            case 21: return station.powerKw(2) > 0 ? 1 : 0;
            case 22: return station.powerKw(3) > 0 ? 1 : 0;
            case 30: return station.remoteStarted() ? 1 : 0;
            case 40: return station.globalMaxPower();
            case 41: return station.maxPowerAC();
            case 100: {
                int dc = station.activeDcConnector();
                return dc == 0 ? 0 : station.powerKw(dc);
            }
            case 101: return station.powerKw(3);
            case 110: {
                int dc = station.activeDcConnector();
                return dc == 0 ? station.globalMaxPower() : station.limitKw(dc);
            }
            case 111: return station.maxPowerAC();
            default: throw new ModbusException(2);
        }
    }

    private void validateWritable(int address) throws ModbusException {
        if (address != 110 && address != 111) throw new ModbusException(2);
    }

    private void writeRegister(int address, int value) throws Exception {
        validateWritable(address);
        if (address == 110) station.setDcBudgetKw(value);
        else station.setAcBudgetKw(value);
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static void putU16(byte[] b, int o, int value) {
        b[o] = (byte) ((value >>> 8) & 0xff);
        b[o + 1] = (byte) (value & 0xff);
    }

    private static boolean readFullyOrEof(InputStream in, byte[] b, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(b, off + done, len - done);
            if (n < 0) {
                if (done == 0) return false;
                throw new EOFException();
            }
            done += n;
        }
        return true;
    }

    private static final class ModbusException extends Exception {
        final int code;
        ModbusException(int code) { this.code = code; }
    }
}
