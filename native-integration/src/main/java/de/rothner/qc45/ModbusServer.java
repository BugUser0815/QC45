package de.rothner.qc45;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/** Minimal multi-client Modbus/TCP server exposing the QC45 to evcc and the local UI. */
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
            System.out.println("[QC45] Modbus TCP listening on " + port + " (multi-client)");

            while (running) {
                try {
                    final Socket socket = serverSocket.accept();
                    socket.setSoTimeout(10000);
                    Thread client = new Thread(new Runnable() {
                        public void run() {
                            try {
                                handle(socket);
                            } catch (SocketException e) {
                                if (running) System.err.println("[QC45] Modbus client socket error: " + e);
                            } catch (Throwable e) {
                                if (running) e.printStackTrace();
                            } finally {
                                try { socket.close(); } catch (Throwable ignored) {}
                            }
                        }
                    }, "qc45-modbus-client-" + socket.getRemoteSocketAddress());
                    client.setDaemon(true);
                    client.start();
                } catch (SocketException e) {
                    if (running) e.printStackTrace();
                } catch (Throwable e) {
                    if (running) e.printStackTrace();
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
                result = new byte[] { (byte)(fc | 0x80), (byte)e.code };
            } catch (Throwable e) {
                e.printStackTrace();
                int fc = pdu.length == 0 ? 0 : pdu[0] & 0xff;
                result = new byte[] { (byte)(fc | 0x80), 4 };
            }

            byte[] header = new byte[7];
            putU16(header, 0, tx);
            putU16(header, 2, 0);
            putU16(header, 4, result.length + 1);
            header[6] = (byte)unit;
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
        result[0] = (byte)fc;
        result[1] = (byte)(count * 2);
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
        if (count < 1 || count > 123 || bytes != count * 2 || pdu.length != 6 + bytes)
            throw new ModbusException(3);

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
            case 4: return activeDcConnector();
            case 10: return station.limitKw(1);
            case 11: return station.limitKw(2);
            case 12: return station.limitKw(3);
            case 20: return sessionActive(1) ? 1 : 0;
            case 21: return sessionActive(2) ? 1 : 0;
            case 22: return sessionActive(3) ? 1 : 0;
            case 30: return station.remoteStarted() ? 1 : 0;
            case 40: return station.globalMaxPower();
            case 41: return station.maxPowerAC();
            case 50: return energyWord(1, true);
            case 51: return energyWord(1, false);
            case 52: return energyWord(2, true);
            case 53: return energyWord(2, false);
            case 54: return energyWord(3, true);
            case 55: return energyWord(3, false);
            case 60: return overallStatus();
            case 100: {
                int dc = activeDcConnector();
                return dc == 0 ? 0 : station.powerKw(dc);
            }
            case 101: return station.powerKw(3);
            case 110: {
                int dc = activeDcConnector();
                return dc == 0 ? station.globalMaxPower() : station.limitKw(dc);
            }
            case 111: return station.maxPowerAC();

            // Local charging-screen block. Use the already parsed live EVCSD
            // SatelliteInfo state. CentralModule refreshes these fields from every
            // incoming status packet; this is the same state used by the stock UI.
            case 120: {
                int dc = activeDcConnector();
                if (dc == 0) return 0;
                int p = satelliteInfoInt(dc, "power", -1);
                return p >= 0 ? p : station.powerKw(dc);
            }
            case 121: {
                int dc = activeDcConnector();
                return dc == 0 ? station.globalMaxPower() : station.limitKw(dc);
            }
            case 122: {
                int dc = activeDcConnector();
                if (dc == 0) return 0;
                return clamp(satelliteInfoInt(dc, "battEnergyPct", 0), 0, 100);
            }
            case 123: {
                int dc = activeDcConnector();
                if (dc == 0) return 0;
                long seconds = satelliteInfoInt(dc, "chargingTime", 0);
                return (int)Math.min(65535L, Math.max(0L, seconds));
            }
            case 124: {
                long wh = activeSessionEnergyWh();
                return (int)((wh >>> 16) & 0xffffL);
            }
            case 125: {
                long wh = activeSessionEnergyWh();
                return (int)(wh & 0xffffL);
            }
            default: throw new ModbusException(2);
        }
    }

    private boolean sessionActive(int connector) throws Exception {
        Object sat = satellite(connector);
        try {
            Object tx = sat.getClass().getMethod("getActiveTransaction").invoke(sat);
            if (tx != null) return true;
        } catch (NoSuchMethodException ignored) {}
        return station.powerKw(connector) > 0 || station.idTag(connector).length() > 0;
    }

    private int activeDcConnector() throws Exception {
        int p1 = station.powerKw(1);
        int p2 = station.powerKw(2);
        if (p1 > 0 && p2 > 0) return p1 >= p2 ? 1 : 2;
        if (p1 > 0) return 1;
        if (p2 > 0) return 2;
        boolean s1 = sessionActive(1);
        boolean s2 = sessionActive(2);
        if (s1 && !s2) return 1;
        if (s2 && !s1) return 2;
        return 0;
    }

    private int overallStatus() throws Exception {
        if (station.powerKw(1) > 0 || station.powerKw(2) > 0 || station.powerKw(3) > 0) return 2;
        if (sessionActive(1) || sessionActive(2) || sessionActive(3)) return 1;
        return 0;
    }

    private int satelliteInfoInt(int connector, String fieldName, int fallback) throws Exception {
        Object sat = satellite(connector);
        Field infoField = findField(sat.getClass(), "infoState");
        if (infoField == null) return fallback;
        infoField.setAccessible(true);
        Object info = infoField.get(sat);
        if (info == null) return fallback;
        Field valueField = findField(info.getClass(), fieldName);
        if (valueField == null) return fallback;
        valueField.setAccessible(true);
        Object value = valueField.get(info);
        return value instanceof Number ? ((Number)value).intValue() : fallback;
    }

    private long activeSessionEnergyWh() throws Exception {
        int dc = activeDcConnector();
        if (dc == 0) return 0L;

        int energy = satelliteInfoInt(dc, "energy", -1);
        int initial = satelliteInfoInt(dc, "initialEnergy", -1);
        if (energy >= 0 && initial >= 0 && energy >= initial) {
            return ((long)energy - (long)initial) & 0xffffffffL;
        }

        // Fallback for meter configurations where initialEnergy is not populated.
        return energyWh(dc);
    }

    private long energyWh(int connector) throws Exception {
        Object sat = satellite(connector);
        Object value = sat.getClass().getMethod("getCurrentEnergy").invoke(sat);
        return value instanceof Number ? ((Number)value).longValue() & 0xffffffffL : 0L;
    }

    private int energyWord(int connector, boolean high) throws Exception {
        long value = energyWh(connector);
        return high ? (int)((value >>> 16) & 0xffffL) : (int)(value & 0xffffL);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null) {
            try { return t.getDeclaredField(name); }
            catch (NoSuchFieldException e) { t = t.getSuperclass(); }
        }
        return null;
    }

    private Object satellite(int connector) throws Exception {
        Method method = ReflectionQC45.class.getDeclaredMethod("satellite", Integer.TYPE);
        method.setAccessible(true);
        return method.invoke(station, Integer.valueOf(connector));
    }

    private void validateWritable(int address) throws ModbusException {
        if (address != 110 && address != 111) throw new ModbusException(2);
    }

    private void writeRegister(int address, int value) throws Exception {
        validateWritable(address);
        if (address == 110) station.setDcBudgetKw(value);
        else station.setAcBudgetKw(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private static void putU16(byte[] b, int o, int value) {
        b[o] = (byte)((value >>> 8) & 0xff);
        b[o + 1] = (byte)(value & 0xff);
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
