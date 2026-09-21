package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    private long derivedPowerMs;

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
        System.out.println("[QC45] AC power telemetry started mode=energy-delta-1s read-only no-MobiBus-writes");
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
                    resetPowerEstimate(satellite);
                } else {
                    if (!sessionObserved) {
                        sessionObserved = true;
                        sessionStartedMs = now;
                        lastDiagnosticLogMs = now;
                        System.out.println("[QC45] AC telemetry session started limit="
                            + station.limitKw(AC_CONNECTOR) + "kW energy="
                            + currentEnergyWh(satellite));
                    }
                    if (now - lastDiagnosticLogMs >= DIAGNOSTIC_LOG_MS) {
                        System.out.println("[QC45] AC telemetry age="
                            + Math.max(0L, now - sessionStartedMs)
                            + "ms actual=" + actualKw + "kW limit="
                            + station.limitKw(AC_CONNECTOR) + "kW energy="
                            + currentEnergyWh(satellite));
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
     * We intentionally do not move the energy/time anchor on every 250 ms poll.
     * Instead we accumulate counter changes for roughly one second and calculate
     * one fresh slope from that short interval. This avoids the old 1.5 s sample
     * lag and also avoids treating a single poll with no new Wh tick as 0 kW.
     * The most recent valid value is held briefly until a new energy delta arrives.
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
                    derivedPowerKw = (int)Math.min(43L,
                        Math.max(0L, (watts + 500L) / 1000L));
                    derivedPowerMs = now;
                    writeInfoPower(satellite, derivedPowerKw);
                    energyAnchorWh = energyWh;
                    energyAnchorMs = now;
                }
            }

            if (derivedPowerMs > 0L && now - derivedPowerMs > POWER_STALE_MS) {
                derivedPowerKw = 0;
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

    private void resetPowerEstimate(Object satellite) {
        energyAnchorWh = -1L;
        energyAnchorMs = 0L;
        derivedPowerKw = 0;
        derivedPowerMs = 0L;
        try { writeInfoPower(satellite, 0); } catch (Throwable ignored) {}
    }

    private long currentEnergyWh(Object satellite) throws Exception {
        Object value = satellite.getClass().getMethod("getCurrentEnergy").invoke(satellite);
        if (!(value instanceof Number)) return 0L;
        return ((Number)value).intValue() & 0xffffffffL;
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
