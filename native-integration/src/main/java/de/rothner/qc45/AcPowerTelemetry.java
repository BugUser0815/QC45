package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Read-only Type2 power telemetry for the old QC45 EVCSD.
 *
 * The AC satellite leaves infoState.power at zero although cumulative energy is
 * updated while charging. Derive a short-window kW value from that counter and
 * publish it back into infoState.power so LoadManager, OCPP/UI and diagnostics
 * see the real AC load. This class never sends MobiBus commands and therefore
 * cannot reproduce the BMW i3 abort caused by our former custom ENERGY packet.
 */
final class AcPowerTelemetry extends Thread {
    private static final int AC_CONNECTOR = 3;
    private static final int LOOP_MS = 250;
    private static final long POWER_WINDOW_MS = 1000L;
    private static final long POWER_STALE_MS = 2500L;
    private static final long DIAGNOSTIC_LOG_MS = 5000L;
    private static final long ERROR_LOG_MS = 5000L;
    private static final String CENTRAL =
        "pt.efacec.es.mobie.agent.statemachines.CentralModule";

    private final ReflectionQC45 station;
    private final Class<?> centralClass;
    private volatile boolean running = true;
    private boolean sessionObserved;
    private long sessionStartedMs;
    private long lastDiagnosticLogMs;
    private long lastErrorLogMs;
    private long energyAnchorWh = -1L;
    private long energyAnchorMs;
    private int derivedPowerKw;
    private int lastRawPowerKw;
    private long derivedPowerMs;
    private final int[] rawPowerWindow = new int[3];
    private int rawPowerWindowCount;
    private int rawPowerWindowIndex;
    private String lastHardwareState;

    static AcPowerTelemetry startRequired() throws Exception {
        AcPowerTelemetry telemetry = new AcPowerTelemetry(new ReflectionQC45());
        telemetry.start();
        return telemetry;
    }

    private AcPowerTelemetry(ReflectionQC45 station) throws Exception {
        super("QC45-AC-PowerTelemetry");
        if (station == null) throw new IllegalArgumentException("station is required");
        this.station = station;
        this.centralClass = Class.forName(CENTRAL);
        setDaemon(true);
    }

    public void run() {
        System.out.println("[QC45] AC power telemetry started mode=energy-delta-1s-median3 read-only no-MobiBus-writes");
        while (running) {
            long now = System.currentTimeMillis();
            try {
                Object satellite = acSatellite();
                boolean session = station.sessionActive(AC_CONNECTOR);
                int actualKw = updateLivePowerEstimate(satellite, session, now);

                if (!session) {
                    if (sessionObserved) {
                        System.out.println("[QC45] AC telemetry session ended age="
                            + Math.max(0L, now - sessionStartedMs) + "ms");
                    }
                    sessionObserved = false;
                    sessionStartedMs = 0L;
                    lastDiagnosticLogMs = 0L;
                    lastHardwareState = null;
                    resetPowerEstimate(satellite);
                } else {
                    // A Type2 contactor can open within seconds while EVCSD
                    // keeps the OCPP transaction alive until its no-energy
                    // timeout. Record the native board state as it changes.
                    String hardwareState = hardwareState(satellite);
                    String stateKey = hardwareStateKey(satellite);
                    if (!stateKey.equals(lastHardwareState)) {
                        System.out.println("[QC45] AC hardware state " + hardwareState);
                        lastHardwareState = stateKey;
                    }
                    if (!sessionObserved) {
                        sessionObserved = true;
                        sessionStartedMs = now;
                        lastDiagnosticLogMs = now;
                        System.out.println("[QC45] AC telemetry session started limit="
                            + station.limitKw(AC_CONNECTOR) + "kW energy="
                            + currentEnergyWh(satellite) + " " + hardwareState);
                    }
                    if (now - lastDiagnosticLogMs >= DIAGNOSTIC_LOG_MS) {
                        System.out.println("[QC45] AC telemetry age="
                            + Math.max(0L, now - sessionStartedMs)
                            + "ms actual=" + actualKw + "kW raw="
                            + lastRawPowerKw + "kW limit="
                            + station.limitKw(AC_CONNECTOR) + "kW energy="
                            + currentEnergyWh(satellite) + " " + hardwareState);
                        lastDiagnosticLogMs = now;
                    }
                }
            } catch (Throwable error) {
                if (now - lastErrorLogMs >= ERROR_LOG_MS) {
                    System.err.println("[QC45] AC power telemetry failed: " + error);
                    lastErrorLogMs = now;
                }
            }

            try { Thread.sleep(LOOP_MS); }
            catch (InterruptedException e) { if (!running) break; }
        }
        System.out.println("[QC45] AC power telemetry stopped");
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    /**
     * Build a near-live value from the cumulative Wh counter.
     *
     * The counter has only whole-Wh resolution. A roughly one-second slope can
     * therefore jump several kW even when the real AC load is steady. Keep the
     * fast one-second raw sampling, but publish a median over the three latest
     * valid samples. A single quantisation outlier (for example 7/14/21 kW)
     * cannot move the value seen by LoadManager and the dashboard. Real step
     * changes become authoritative after at most two additional samples.
     */
    private int updateLivePowerEstimate(Object satellite, boolean session, long now) {
        if (!session) return 0;
        try {
            Object direct = satellite.getClass().getMethod("getCurrentPower").invoke(satellite);
            int directKw = direct instanceof Number ? ((Number)direct).intValue() : 0;
            long energyWh = currentEnergyWh(satellite);

            if (energyAnchorWh < 0L || energyWh < energyAnchorWh) {
                energyAnchorWh = energyWh;
                energyAnchorMs = now;
            } else {
                long elapsedMs = now - energyAnchorMs;
                long deltaWh = energyWh - energyAnchorWh;
                if (deltaWh > 0L && elapsedMs >= POWER_WINDOW_MS) {
                    long watts = (deltaWh * 3600000L + elapsedMs / 2L) / elapsedMs;
                    int rawKw = (int)Math.min(43L,
                        Math.max(0L, (watts + 500L) / 1000L));
                    lastRawPowerKw = rawKw;
                    derivedPowerKw = pushRawPowerSample(rawKw);
                    derivedPowerMs = now;
                    writeInfoPower(satellite, derivedPowerKw);
                    energyAnchorWh = energyWh;
                    energyAnchorMs = now;
                }
            }

            if (derivedPowerMs > 0L && now - derivedPowerMs > POWER_STALE_MS) {
                derivedPowerKw = 0;
                lastRawPowerKw = 0;
                clearRawPowerWindow();
                writeInfoPower(satellite, 0);
                // Re-anchor at the current counter so a later restart is based
                // only on newly delivered energy, not on the stale interval.
                energyAnchorWh = energyWh;
                energyAnchorMs = now;
            }
            return derivedPowerKw > 0 ? derivedPowerKw : Math.max(0, directKw);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int pushRawPowerSample(int kw) {
        rawPowerWindow[rawPowerWindowIndex] = kw;
        rawPowerWindowIndex = (rawPowerWindowIndex + 1) % rawPowerWindow.length;
        if (rawPowerWindowCount < rawPowerWindow.length) rawPowerWindowCount++;

        if (rawPowerWindowCount == 1) return rawPowerWindow[0];
        if (rawPowerWindowCount == 2) {
            return (rawPowerWindow[0] + rawPowerWindow[1] + 1) / 2;
        }
        return median3(rawPowerWindow[0], rawPowerWindow[1], rawPowerWindow[2]);
    }

    static int median3(int a, int b, int c) {
        if (a > b) { int t = a; a = b; b = t; }
        if (b > c) { int t = b; b = c; c = t; }
        if (a > b) { int t = a; a = b; b = t; }
        return b;
    }

    private void clearRawPowerWindow() {
        rawPowerWindow[0] = 0;
        rawPowerWindow[1] = 0;
        rawPowerWindow[2] = 0;
        rawPowerWindowCount = 0;
        rawPowerWindowIndex = 0;
    }

    private void resetPowerEstimate(Object satellite) {
        energyAnchorWh = -1L;
        energyAnchorMs = 0L;
        derivedPowerKw = 0;
        lastRawPowerKw = 0;
        derivedPowerMs = 0L;
        clearRawPowerWindow();
        try { writeInfoPower(satellite, 0); } catch (Throwable ignored) {}
    }

    private long currentEnergyWh(Object satellite) throws Exception {
        Object value = satellite.getClass().getMethod("getCurrentEnergy").invoke(satellite);
        if (!(value instanceof Number)) return 0L;
        return ((Number)value).intValue() & 0xffffffffL;
    }

    private String hardwareState(Object satellite) {
        try {
            Object info = fieldValue(satellite, "infoState");
            if (info == null) return "board=unavailable";
            Object phases = fieldValue(info, "voltagePhaseValue");
            String phaseValues = phases instanceof int[]
                ? Arrays.toString((int[])phases) : String.valueOf(phases);
            return "boardStatus=" + fieldValue(info, "status")
                + " normalStatus=[" + moduleState(info) + "]"
                + " acDTC=" + fieldValue(info, "acDTC")
                + " boardCurrentRaw=" + fieldValue(info, "electricCurrent")
                + " boardVoltageRaw=" + fieldValue(info, "voltage")
                + " phaseVoltageRaw=" + phaseValues
                + " epo=" + fieldValue(info, "epoPressed");
        } catch (Throwable error) {
            return "board=unavailable error=" + error.getClass().getSimpleName();
        }
    }

    private String hardwareStateKey(Object satellite) {
        try {
            Object info = fieldValue(satellite, "infoState");
            if (info == null) return "board=unavailable";
            return fieldValue(info, "status") + "/"
                + moduleState(info) + "/"
                + fieldValue(info, "acDTC") + "/"
                + fieldValue(info, "epoPressed");
        } catch (Throwable error) {
            return "board=unavailable:" + error.getClass().getSimpleName();
        }
    }

    private String moduleState(Object info) throws Exception {
        Object state = fieldValue(info, "normalStatus");
        if (state == null) return "unavailable";
        return "type=" + fieldValue(state, "type")
            + " energy=" + fieldValue(state, "energy")
            + " functional=" + fieldValue(state, "functional")
            + " additional=" + fieldValue(state, "additional")
            + " messageDTC=" + state.getClass().getMethod("getMessageDTC").invoke(state)
            + " statusResOK=" + state.getClass().getMethod("isStatusResOK").invoke(state)
            + " connectorId=" + state.getClass().getMethod("getConnectorId").invoke(state)
            + " errorInfo=" + state.getClass().getMethod("getErrorInfo").invoke(state)
            + " epoAC=" + fieldValue(state, "epoPressedAC");
    }

    private void writeInfoPower(Object satellite, int kw) throws Exception {
        Object info = fieldValue(satellite, "infoState");
        if (info == null) return;
        Field power = findField(info.getClass(), "power");
        if (power == null) return;
        power.setAccessible(true);
        if (power.getType() == Integer.TYPE) power.setInt(info, kw);
        else power.set(info, Integer.valueOf(kw));
    }

    private Object acSatellite() throws Exception {
        Object central = centralClass.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");
        Object value = centralClass.getMethod("getSatellites").invoke(central);
        if (!(value instanceof Object[])) throw new IllegalStateException("Satellites unavailable");
        Object[] satellites = (Object[])value;
        for (int i = 0; i < satellites.length; i++) {
            Object satellite = satellites[i];
            if (satellite == null) continue;
            Object id = satellite.getClass().getMethod("getSatelliteId").invoke(satellite);
            if (id instanceof Number && ((Number)id).intValue() == AC_CONNECTOR) return satellite;
        }
        throw new IllegalStateException("Type2 satellite unavailable");
    }

    private static Object fieldValue(Object owner, String name) throws Exception {
        Field field = findField(owner.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }
}
