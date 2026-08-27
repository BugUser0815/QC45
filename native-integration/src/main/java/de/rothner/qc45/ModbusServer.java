package de.rothner.qc45;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public final class ModbusServer extends Thread {
    static final int UI_BALANCING_FIRST_REGISTER = 126;
    static final int UI_BALANCING_REGISTER_COUNT = 20;
    static final int UI_BALANCING_VERSION = 1;

    static final int UI_FLAG_DC_SESSION = 1 << 0;
    static final int UI_FLAG_AC_SESSION = 1 << 1;
    static final int UI_FLAG_DC_FLOW = 1 << 2;
    static final int UI_FLAG_AC_FLOW = 1 << 3;
    static final int UI_FLAG_BLOCKED = 1 << 4;
    static final int UI_FLAG_FAILBACK = 1 << 5;
    static final int UI_FLAG_LOAD_METER = 1 << 6;
    static final int UI_FLAG_STARTUP = 1 << 7;
    static final int UI_FLAG_SHUTDOWN = 1 << 8;
    static final int UI_FLAG_DEMAND_TRANSFER = 1 << 9;
    static final int UI_FLAG_STAGE_LIMIT = 1 << 10;
    static final int UI_FLAG_CONFIGURATION = 1 << 11;

    private final ReflectionQC45 station;
    private final ChargingLimitCoordinator limits;
    private final String bindAddress;
    private final ClientRule[] allowedClients;
    private final int maxClients;
    private final int port;
    private final Set<Socket> clients = new HashSet<Socket>();
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private volatile long lastChargingScreenDiagnosticMs;

    public ModbusServer(ReflectionQC45 station, ChargingLimitCoordinator limits,
                        String bindAddress, int port, String allowedClients,
                        int maxClients) {
        super("qc45-modbus-" + port);
        if (station == null || limits == null) throw new IllegalArgumentException("station and limits are required");
        if (bindAddress == null || bindAddress.trim().length() == 0
                || port < 1 || port > 65535 || maxClients <= 0) {
            throw new IllegalArgumentException("invalid Modbus bind, port or client limit");
        }
        this.station = station;
        this.limits = limits;
        this.bindAddress = bindAddress.trim();
        this.port = port;
        this.allowedClients = splitClients(allowedClients);
        this.maxClients = maxClients;
        setDaemon(true);
    }

    public void shutdown() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        synchronized (clients) {
            for (Socket client : clients) {
                try { client.close(); } catch (Throwable ignored) {}
            }
            clients.clear();
        }
    }

    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
            System.out.println("[QC45] Modbus TCP listening on " + bindAddress + ":" + port
                + " maxClients=" + maxClients + " allowedClients=" + allowedClientSummary());

            while (running) {
                try {
                    final Socket socket = serverSocket.accept();
                    if (!clientAllowed(socket) || !reserveClient(socket)) {
                        System.err.println("[QC45] Modbus client rejected: " + socket.getRemoteSocketAddress());
                        try { socket.close(); } catch (Throwable ignored) {}
                        continue;
                    }
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
                                releaseClient(socket);
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

        ReadSnapshot snapshot = new ReadSnapshot();
        byte[] result = new byte[2 + count * 2];
        result[0] = (byte)fc;
        result[1] = (byte)(count * 2);
        for (int i = 0; i < count; i++) putU16(result, 2 + i * 2, register(address + i, snapshot));
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

        int dcKw = limits.requestedDcKw();
        int acKw = limits.requestedAcKw();
        for (int i = 0; i < count; i++) {
            int register = address + i;
            validateWritable(register);
            int value = u16(pdu, 6 + i * 2);
            if (register == 110) dcKw = value;
            else acKw = value;
        }
        // One atomic coordinator update preserves reduction-before-increase
        // ordering when evcc writes both budgets in a single Modbus request.
        limits.requestBudgets(dcKw, acKw);

        byte[] result = new byte[5];
        result[0] = 16;
        putU16(result, 1, address);
        putU16(result, 3, count);
        return result;
    }

    private int register(int address, ReadSnapshot snapshot) throws Exception {
        if (address >= UI_BALANCING_FIRST_REGISTER
                && address < UI_BALANCING_FIRST_REGISTER + UI_BALANCING_REGISTER_COUNT) {
            return snapshot.balancingRegisters[address - UI_BALANCING_FIRST_REGISTER];
        }
        switch (address) {
            case 0: return snapshot.stationPowerKw;
            case 1: return snapshot.power[1];
            case 2: return snapshot.power[2];
            case 3: return snapshot.power[3];
            case 4: return snapshot.activeDc;
            case 10: return snapshot.limit[1];
            case 11: return snapshot.limit[2];
            case 12: return snapshot.limit[3];
            case 20: return snapshot.session[1] ? 1 : 0;
            case 21: return snapshot.session[2] ? 1 : 0;
            case 22: return snapshot.session[3] ? 1 : 0;
            case 30: return snapshot.remoteStarted ? 1 : 0;
            case 40: return snapshot.globalMaxPower;
            case 41: return snapshot.maxPowerAc;
            case 50: return highWord(snapshot.energyWh[1]);
            case 51: return lowWord(snapshot.energyWh[1]);
            case 52: return highWord(snapshot.energyWh[2]);
            case 53: return lowWord(snapshot.energyWh[2]);
            case 54: return highWord(snapshot.energyWh[3]);
            case 55: return lowWord(snapshot.energyWh[3]);
            case 60: return snapshot.overallStatus;
            case 100: return snapshot.liveDcPowerKw;
            case 101: return snapshot.power[3];
            case 110: return snapshot.requestedDcKw;
            case 111: return snapshot.requestedAcKw;
            case 120: chargingScreenDiagnostic(snapshot.activeDc); return snapshot.liveDcPowerKw;
            case 121: return snapshot.activeDc == 0 ? 0 : snapshot.limit[snapshot.activeDc];
            case 122: chargingScreenDiagnostic(snapshot.activeDc); return snapshot.socPct;
            case 123: chargingScreenDiagnostic(snapshot.activeDc); return (int)Math.min(65535L, snapshot.chargingSeconds);
            case 124: chargingScreenDiagnostic(snapshot.activeDc); return highWord(snapshot.sessionEnergyWh);
            case 125: chargingScreenDiagnostic(snapshot.activeDc); return lowWord(snapshot.sessionEnergyWh);
            default: throw new ModbusException(2);
        }
    }

    private boolean sessionActive(int connector) throws Exception {
        Object sat = satellite(connector);
        if (activeTransaction(sat) != null) return true;
        if (station.powerKw(connector) > 0) return true;
        if (infoInt(connector, "chargingTime", 0) > 0) return true;
        return station.idTag(connector).length() > 0;
    }

    private int activeDcConnector() throws Exception {
        int score1 = dcActivityScore(1);
        int score2 = dcActivityScore(2);
        if (score1 == 0 && score2 == 0) return 0;
        if (score1 == score2) {
            int p1 = livePowerKw(1);
            int p2 = livePowerKw(2);
            if (p1 != p2) return p1 > p2 ? 1 : 2;
            int t1 = infoInt(1, "chargingTime", 0);
            int t2 = infoInt(2, "chargingTime", 0);
            if (t1 != t2) return t1 > t2 ? 1 : 2;
        }
        return score1 >= score2 ? 1 : 2;
    }

    private int dcActivityScore(int connector) throws Exception {
        Object sat = satellite(connector);
        int score = 0;
        if (activeTransaction(sat) != null) score += 1000;

        int power = station.powerKw(connector);
        if (power > 0) score += 600 + Math.min(100, power);

        int current = infoInt(connector, "electricCurrent", 0);
        int voltage = infoInt(connector, "voltage", 0);
        if (current > 0 && voltage > 0) score += 500;

        if (infoInt(connector, "chargingTime", 0) > 0) score += 300;
        if (station.idTag(connector).length() > 0) score += 200;

        boolean connected = connector == 1
            ? infoBoolean(connector, "chaConnected", false)
            : infoBoolean(connector, "ccsConnected", false);
        if (connected) score += 100;
        return score;
    }

    private int livePowerKw(int connector) throws Exception {
        int power = station.powerKw(connector);
        if (power > 0) return power;
        int voltage = infoInt(connector, "voltage", 0);
        int current = infoInt(connector, "electricCurrent", 0);
        if (voltage > 0 && current > 0) {
            long watts = (long)voltage * (long)current;
            long kw = (watts + 500L) / 1000L;
            return (int)Math.min(65535L, Math.max(0L, kw));
        }
        return 0;
    }

    private int liveSocPct(int connector) throws Exception {
        return clamp(infoInt(connector, "battEnergyPct", 0), 0, 100);
    }

    private long liveChargingTimeSeconds(int connector) throws Exception {
        int seconds = infoInt(connector, "chargingTime", 0);
        if (seconds > 0) return seconds;
        Object sat = satellite(connector);
        try {
            Method getStartTime = findMethod(sat.getClass(), "getStartTime");
            if (getStartTime != null) {
                Object value = getStartTime.invoke(sat);
                if (value instanceof Calendar) {
                    long elapsed = (System.currentTimeMillis() - ((Calendar)value).getTimeInMillis()) / 1000L;
                    if (elapsed > 0L) return elapsed;
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    private long sessionEnergyWh(int connector) throws Exception {
        long current = infoUnsignedInt(connector, "energy", 0L);
        long initial = infoUnsignedInt(connector, "initialEnergy", 0L);
        if (current >= initial) return current - initial;
        return (0x100000000L - initial) + current;
    }

    private Object activeTransaction(Object sat) {
        try {
            Method m = findMethod(sat.getClass(), "getActiveTransaction");
            return m == null ? null : m.invoke(sat);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object infoState(int connector) throws Exception {
        Object sat = satellite(connector);
        Field infoField = findField(sat.getClass(), "infoState");
        if (infoField == null) return null;
        infoField.setAccessible(true);
        return infoField.get(sat);
    }

    private int infoInt(int connector, String fieldName, int fallback) throws Exception {
        Object info = infoState(connector);
        if (info == null) return fallback;
        Field field = findField(info.getClass(), fieldName);
        if (field == null) return fallback;
        field.setAccessible(true);
        Object value = field.get(info);
        return value instanceof Number ? ((Number)value).intValue() : fallback;
    }

    private long infoUnsignedInt(int connector, String fieldName, long fallback) throws Exception {
        Object info = infoState(connector);
        if (info == null) return fallback;
        Field field = findField(info.getClass(), fieldName);
        if (field == null) return fallback;
        field.setAccessible(true);
        Object value = field.get(info);
        return value instanceof Number ? ((Number)value).intValue() & 0xffffffffL : fallback;
    }

    private boolean infoBoolean(int connector, String fieldName, boolean fallback) throws Exception {
        Object info = infoState(connector);
        if (info == null) return fallback;
        Field field = findField(info.getClass(), fieldName);
        if (field == null) return fallback;
        field.setAccessible(true);
        Object value = field.get(info);
        return value instanceof Boolean ? ((Boolean)value).booleanValue() : fallback;
    }

    private void chargingScreenDiagnostic(int dc) {
        long now = System.currentTimeMillis();
        if (now - lastChargingScreenDiagnosticMs < 10000L) return;
        lastChargingScreenDiagnosticMs = now;
        try {
            if (dc == 0) {
                System.out.println("[QC45] Modbus screen telemetry: no active DC connector"
                    + " score1=" + dcActivityScore(1) + " score2=" + dcActivityScore(2));
                return;
            }
            System.out.println("[QC45] Modbus screen telemetry: dc=" + dc
                + " power=" + livePowerKw(dc) + "kW"
                + " rawPower=" + station.powerKw(dc) + "kW"
                + " voltage=" + infoInt(dc, "voltage", 0) + "V"
                + " current=" + infoInt(dc, "electricCurrent", 0) + "A"
                + " limit=" + station.limitKw(dc) + "kW"
                + " soc=" + liveSocPct(dc) + "%"
                + " time=" + liveChargingTimeSeconds(dc) + "s"
                + " energy=" + infoUnsignedInt(dc, "energy", 0L) + "Wh"
                + " initialEnergy=" + infoUnsignedInt(dc, "initialEnergy", 0L) + "Wh"
                + " sessionEnergy=" + sessionEnergyWh(dc) + "Wh"
                + " score=" + dcActivityScore(dc));
        } catch (Throwable e) {
            System.out.println("[QC45] Modbus screen telemetry diagnostic failed: " + e);
        }
    }

    private long energyWh(int connector) throws Exception {
        Object sat = satellite(connector);
        Method method = findMethod(sat.getClass(), "getCurrentEnergy");
        if (method == null) return infoUnsignedInt(connector, "energy", 0L);
        Object value = method.invoke(sat);
        return value instanceof Number ? ((Number)value).intValue() & 0xffffffffL : 0L;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null) {
            try { return t.getDeclaredField(name); }
            catch (NoSuchFieldException e) { t = t.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null) {
            try {
                Method m = t.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                t = t.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
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
        if (address == 110) limits.requestDcBudget(value);
        else limits.requestAcBudget(value);
        System.out.println("[QC45] Modbus evcc request register=" + address
            + " value=" + value + " effectiveRequestDC=" + limits.requestedDcKw()
            + "kW effectiveRequestAC=" + limits.requestedAcKw() + "kW");
    }

    private boolean clientAllowed(Socket socket) {
        InetAddress remote = socket.getInetAddress();
        if (remote == null) return false;
        if (remote.isLoopbackAddress()) return true;
        for (int i = 0; i < allowedClients.length; i++) {
            if (allowedClients[i].matches(remote)) return true;
        }
        return false;
    }

    private boolean reserveClient(Socket socket) {
        synchronized (clients) {
            if (clients.size() >= maxClients) return false;
            clients.add(socket);
            return true;
        }
    }

    private void releaseClient(Socket socket) {
        synchronized (clients) { clients.remove(socket); }
    }

    private String allowedClientSummary() {
        if (allowedClients.length == 0) return "loopback-only";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < allowedClients.length; i++) {
            if (i > 0) out.append(',');
            out.append(allowedClients[i].text);
        }
        return out.toString();
    }

    private static ClientRule[] splitClients(String value) {
        if (value == null || value.trim().length() == 0) return new ClientRule[0];
        String[] tokens = value.split(",");
        ClientRule[] rules = new ClientRule[tokens.length];
        for (int i = 0; i < tokens.length; i++) rules[i] = ClientRule.parse(tokens[i]);
        return rules;
    }

    private static int highWord(long value) {
        return (int)((value >>> 16) & 0xffffL);
    }

    private static int lowWord(long value) {
        return (int)(value & 0xffffL);
    }

    static int[] uiBalancingBlock(int flags, int activeDc, int liveDcPowerKw,
                                  ChargingLimitCoordinator.Snapshot balancing,
                                  int socPct, long dcSeconds, long dcEnergyWh,
                                  int liveAcPowerKw, long acSeconds, long acEnergyWh) {
        if (balancing == null) throw new IllegalArgumentException("balancing snapshot is required");
        return new int[] {
            UI_BALANCING_VERSION,
            flags & 0xffff,
            activeDc,
            liveDcPowerKw,
            balancing.requestedDcKw,
            balancing.gridDcKw,
            balancing.stageDcCapKw,
            balancing.effectiveDcKw,
            socPct,
            (int)Math.min(65535L, Math.max(0L, dcSeconds)),
            highWord(dcEnergyWh),
            lowWord(dcEnergyWh),
            liveAcPowerKw,
            balancing.requestedAcKw,
            balancing.gridAcKw,
            balancing.stageAcCapKw,
            balancing.effectiveAcKw,
            (int)Math.min(65535L, Math.max(0L, acSeconds)),
            highWord(acEnergyWh),
            lowWord(acEnergyWh)
        };
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

    /** One coherent value set per Modbus read request, including 32-bit words. */
    private final class ReadSnapshot {
        final int[] power = new int[4];
        final int[] limit = new int[4];
        final boolean[] session = new boolean[4];
        final long[] energyWh = new long[4];
        final int activeDc;
        final int stationPowerKw;
        final boolean remoteStarted;
        final int globalMaxPower;
        final int maxPowerAc;
        final int overallStatus;
        final int requestedDcKw;
        final int requestedAcKw;
        final int liveDcPowerKw;
        final int liveAcPowerKw;
        final int socPct;
        final long chargingSeconds;
        final long sessionEnergyWh;
        final long acChargingSeconds;
        final long acSessionEnergyWh;
        final ChargingLimitCoordinator.Snapshot balancing;
        final int[] balancingRegisters;

        ReadSnapshot() throws Exception {
            synchronized (station) {
                for (int connector = 1; connector <= 3; connector++) {
                    power[connector] = clamp(station.powerKw(connector), 0, 65535);
                    limit[connector] = clamp(station.limitKw(connector), 0, 65535);
                    session[connector] = safeSessionActive(connector);
                    energyWh[connector] = safeEnergyWh(connector);
                }

                activeDc = activeDcConnector();
                stationPowerKw = clamp(Math.max(power[1], power[2]) + power[3], 0, 65535);
                remoteStarted = safeRemoteStarted();
                globalMaxPower = safeGlobalMaxPower();
                maxPowerAc = safeMaxPowerAc();

                boolean anyPower = power[1] > 0 || power[2] > 0 || power[3] > 0;
                boolean anySession = session[1] || session[2] || session[3];
                overallStatus = anyPower ? 2 : anySession ? 1 : 0;

                int dcPower = activeDc == 0 ? 0 : livePowerKw(activeDc);
                int dcSoc = activeDc == 0 ? 0 : liveSocPct(activeDc);
                long dcSeconds = activeDc == 0 ? 0L : liveChargingTimeSeconds(activeDc);
                long dcEnergy = activeDc == 0 ? 0L : sessionEnergyWh(activeDc);

                if (activeDc == 2 && CcsRawTracerV2.hasFreshLiveTelemetry()) {
                    if (dcPower == 0) dcPower = CcsRawTracerV2.livePowerKw();
                    if (dcSoc == 0) dcSoc = CcsRawTracerV2.liveSocPct();
                    if (dcSeconds == 0L) dcSeconds = CcsRawTracerV2.liveElapsedSeconds();
                    if (dcEnergy == 0L) dcEnergy = CcsRawTracerV2.liveEnergyWh();
                }

                liveDcPowerKw = clamp(dcPower, 0, 65535);
                liveAcPowerKw = clamp(session[3] ? livePowerKw(3) : 0, 0, 65535);
                socPct = clamp(dcSoc, 0, 100);
                chargingSeconds = Math.max(0L, dcSeconds);
                sessionEnergyWh = Math.max(0L, dcEnergy);
                acChargingSeconds = session[3] ? Math.max(0L, liveChargingTimeSeconds(3)) : 0L;
                acSessionEnergyWh = session[3] ? Math.max(0L, sessionEnergyWh(3)) : 0L;
            }
            // Never acquire the coordinator while holding the station monitor:
            // coordinator writes use the opposite order (coordinator -> station).
            balancing = limits.snapshot();
            requestedDcKw = balancing.requestedDcKw;
            requestedAcKw = balancing.requestedAcKw;

            int flags = 0;
            if (session[1] || session[2]) flags |= UI_FLAG_DC_SESSION;
            if (session[3]) flags |= UI_FLAG_AC_SESSION;
            if (liveDcPowerKw > 0) flags |= UI_FLAG_DC_FLOW;
            if (liveAcPowerKw > 0) flags |= UI_FLAG_AC_FLOW;
            if (balancing.blocked) flags |= UI_FLAG_BLOCKED;
            if (balancing.failbackBlocked) flags |= UI_FLAG_FAILBACK;
            if (balancing.loadMeterBlocked) flags |= UI_FLAG_LOAD_METER;
            if (balancing.startupBlocked) flags |= UI_FLAG_STARTUP;
            if (balancing.shutdownBlocked) flags |= UI_FLAG_SHUTDOWN;
            if (balancing.demandTransfer) flags |= UI_FLAG_DEMAND_TRANSFER;
            if (balancing.stageLimited) flags |= UI_FLAG_STAGE_LIMIT;
            if (balancing.configurationBlocked) flags |= UI_FLAG_CONFIGURATION;
            balancingRegisters = uiBalancingBlock(flags, activeDc, liveDcPowerKw,
                balancing, socPct, chargingSeconds, sessionEnergyWh,
                liveAcPowerKw, acChargingSeconds, acSessionEnergyWh);
        }

        private boolean safeSessionActive(int connector) {
            try { return ModbusServer.this.sessionActive(connector); }
            catch (Throwable e) { return power[connector] > 0; }
        }

        private long safeEnergyWh(int connector) {
            try { return Math.max(0L, ModbusServer.this.energyWh(connector)); }
            catch (Throwable e) { return 0L; }
        }

        private boolean safeRemoteStarted() {
            try { return station.remoteStarted(); }
            catch (Throwable e) { return false; }
        }

        private int safeGlobalMaxPower() {
            try { return clamp(station.globalMaxPower(), 0, 65535); }
            catch (Throwable e) { return 0; }
        }

        private int safeMaxPowerAc() {
            try { return clamp(station.maxPowerAC(), 0, 65535); }
            catch (Throwable e) { return 0; }
        }
    }

    static final class ClientRule {
        final String text;
        final byte[] network;
        final int prefixBits;

        private ClientRule(String text, byte[] network, int prefixBits) {
            this.text = text;
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static ClientRule parse(String raw) {
            String token = raw == null ? "" : raw.trim();
            if (token.length() == 0 || "*".equals(token)) {
                throw new IllegalArgumentException("empty or wildcard Modbus client rule is not allowed");
            }
            try {
                int slash = token.indexOf('/');
                String host = slash < 0 ? token : token.substring(0, slash).trim();
                if (!host.matches("[0-9A-Fa-f:.]+")) {
                    throw new IllegalArgumentException(
                        "Modbus client rules must use literal IP addresses: " + token);
                }
                InetAddress address = InetAddress.getByName(host);
                byte[] bytes = address.getAddress();
                int bits = slash < 0 ? bytes.length * 8
                    : Integer.parseInt(token.substring(slash + 1).trim());
                if (bits <= 0 || bits > bytes.length * 8) {
                    throw new IllegalArgumentException("invalid prefix in Modbus client rule: " + token);
                }
                return new ClientRule(token, bytes, bits);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid Modbus client rule: " + token, e);
            }
        }

        boolean matches(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = (0xff << (8 - remainingBits)) & 0xff;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private static final class ModbusException extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        ModbusException(int code) { this.code = code; }
    }
}
