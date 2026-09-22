package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Mirrors the logical Type2 kW target into EVCSD's native normal-AC limit path.
 *
 * Reverse engineering of the original evcsd.jar (build 57), together with the
 * live Type2 tests, shows that the normal-satellite START_CHARGE/ENERGY payload
 * called maxPower is a current limit in 0.1 A units, despite the legacy field
 * and configuration names saying "Power". EVCSD itself multiplies the configured
 * AC value by 10 before serializing it.
 *
 * Therefore the integration keeps SatelliteModule.satelliteMaxPower in kW for
 * its own LoadManager/Modbus model, but converts that kW budget to a three-phase
 * 400 V pilot-current ceiling before writing ACMaxPowerFixed. Examples:
 *
 *   5 kW  ->  8 A -> native payload 80
 *  11 kW  -> 16 A -> native payload 160
 *  22 kW  -> 32 A -> native payload 320
 *  43 kW  -> 63 A -> native payload 630
 *
 * The 6 A IEC 61851 minimum is enforced for every positive/notladen target.
 * A single-phase vehicle is intentionally conservative with this conversion:
 * it initially receives the same pilot-current ceiling and the KSEM-based
 * LoadManager can then ramp it upward while observing the actually loaded phase.
 *
 * EVCSD only selects its fixed normal-AC serializer path when BOTH
 * ACMaxPowerFixed and DCMaxPowerFixed are > 0. The stock firmware/configuration
 * can reset DCMaxPowerFixed to zero while idle, so this bridge keeps only that
 * selector value alive at the existing 5 kW DC Notladen floor. Positive DC
 * targets are never overwritten.
 *
 * This bridge never sends MobiBus messages itself. Stock EVCSD transmits the
 * limit in START_CHARGE and on its periodic ENERGY request.
 */
final class AcFixedPowerBridge extends Thread {
    private static final int AC_CONNECTOR = 3;
    private static final int LOOP_MS = 100;
    private static final long ERROR_LOG_MS = 5000L;
    private static final int MIN_PILOT_A = 6;
    private static final int MAX_PILOT_A = 63;
    private static final double THREE_PHASE_400V_KW_PER_A = Math.sqrt(3.0d) * 400.0d / 1000.0d;
    private static final String CENTRAL =
        "pt.efacec.es.mobie.agent.statemachines.CentralModule";

    private final Class<?> centralClass;
    private volatile boolean running = true;
    private long lastErrorLogMs;
    private int lastLoggedTargetKw = Integer.MIN_VALUE;
    private int lastLoggedPilotA = Integer.MIN_VALUE;
    private int lastLoggedDcFixed = Integer.MIN_VALUE;

    static AcFixedPowerBridge startRequired() throws Exception {
        AcFixedPowerBridge bridge = new AcFixedPowerBridge();
        bridge.sync(true);
        bridge.start();
        return bridge;
    }

    private AcFixedPowerBridge() throws Exception {
        super("QC45-AC-FixedPowerBridge");
        centralClass = Class.forName(CENTRAL);
        setDaemon(true);
    }

    public void run() {
        System.out.println("[QC45] AC native fixed-limit bridge started"
            + " transport=stock-START_CHARGE/ENERGY"
            + " AC-unit=deci-A own-MobiBus-writes=false");
        while (running) {
            long now = System.currentTimeMillis();
            try {
                sync(false);
            } catch (Throwable error) {
                if (now - lastErrorLogMs >= ERROR_LOG_MS) {
                    System.err.println("[QC45] AC native fixed-limit bridge failed: " + error);
                    lastErrorLogMs = now;
                }
            }
            try { Thread.sleep(LOOP_MS); }
            catch (InterruptedException e) { if (!running) break; }
        }
        System.out.println("[QC45] AC native fixed-limit bridge stopped");
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    private void sync(boolean startup) throws Exception {
        Object central = centralClass.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");
        Object conf = centralClass.getMethod("getConf").invoke(central);
        if (conf == null) throw new IllegalStateException("Configuration unavailable");
        Object satellite = acSatellite(central);

        boolean acLoadBalanceBefore = acLoadBalance(conf);
        if (acLoadBalanceBefore) setAcLoadBalance(conf, false);
        if (acLoadBalance(conf)) {
            throw new IllegalStateException("Unable to disable AC load-balance divisor path");
        }

        // Stock build 57 enables the fixed AC payload only if the DC fixed
        // selector is positive as well. Do not let an idle firmware reset to 0
        // disable AC limiting. 5 kW is already our physical DC Notladen floor.
        int dcBefore = readInt(conf, "getDCMaxPowerFixed", "DCMaxPowerFixed", -1);
        if (dcBefore <= 0) setDcFixed(conf, ChargingLimitCoordinator.NOTLADEN_KW);
        int dcAfter = readInt(conf, "getDCMaxPowerFixed", "DCMaxPowerFixed", -1);
        if (dcAfter <= 0) {
            throw new IllegalStateException("DCMaxPowerFixed selector could not be armed; read back "
                + dcAfter);
        }

        int targetKw = ((Number)satellite.getClass().getMethod("getMaxPower").invoke(satellite)).intValue();
        if (targetKw <= 0) targetKw = ChargingLimitCoordinator.AC_NOTLADEN_KW;
        if (targetKw > 43) targetKw = 43;
        int pilotA = pilotCurrentAForKw(targetKw);

        int before = readInt(conf, "getACMaxPowerFixed", "ACMaxPowerFixed", -1);
        if (before != pilotA) setAcFixed(conf, pilotA);
        int after = readInt(conf, "getACMaxPowerFixed", "ACMaxPowerFixed", -1);
        if (after != pilotA) {
            throw new IllegalStateException("ACMaxPowerFixed did not accept pilot target " + pilotA
                + "A for logical " + targetKw + "kW; read back " + after);
        }

        // maxPowerAC uses the same normal-AC native unit. It is not the active
        // fixed-path source while AC load balancing is disabled, but keeping it
        // coherent makes an accidental path switch fail conservatively.
        try {
            conf.getClass().getMethod("setMaxPowerAC", Integer.TYPE)
                .invoke(conf, Integer.valueOf(pilotA));
        } catch (NoSuchMethodException ignored) {
            Field field = findField(conf.getClass(), "maxPowerAC");
            if (field != null) setInt(field, conf, pilotA);
        }

        if (startup || targetKw != lastLoggedTargetKw || pilotA != lastLoggedPilotA
                || dcAfter != lastLoggedDcFixed || acLoadBalanceBefore || dcBefore != dcAfter) {
            System.out.println("[QC45] AC native fixed target=" + targetKw + "kW"
                + " pilot=" + pilotA + "A payload=" + (pilotA * 10) + "deci-A"
                + " acFixed=" + before + "->" + after
                + " dcFixed=" + dcBefore + "->" + dcAfter
                + " acLoadBalance=" + acLoadBalanceBefore + "->false"
                + " divisorPath=disabled");
            lastLoggedTargetKw = targetKw;
            lastLoggedPilotA = pilotA;
            lastLoggedDcFixed = dcAfter;
        }
    }

    static int pilotCurrentAForKw(int kw) {
        if (kw <= 0) return MIN_PILOT_A;
        int amps = (int)Math.ceil(((double)kw) / THREE_PHASE_400V_KW_PER_A);
        if (amps < MIN_PILOT_A) amps = MIN_PILOT_A;
        if (amps > MAX_PILOT_A) amps = MAX_PILOT_A;
        return amps;
    }

    private Object acSatellite(Object central) throws Exception {
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

    private boolean acLoadBalance(Object conf) throws Exception {
        try {
            Object value = conf.getClass().getMethod("isEnableACLoadBalance").invoke(conf);
            if (value instanceof Boolean) return ((Boolean)value).booleanValue();
        } catch (NoSuchMethodException ignored) {}
        Field field = findField(conf.getClass(), "enableACLoadBalance");
        if (field == null) throw new NoSuchFieldException("enableACLoadBalance");
        field.setAccessible(true);
        return field.getBoolean(conf);
    }

    private void setAcLoadBalance(Object conf, boolean enabled) throws Exception {
        Field field = findField(conf.getClass(), "enableACLoadBalance");
        if (field == null) throw new NoSuchFieldException("enableACLoadBalance");
        field.setAccessible(true);
        if (field.getType() == Boolean.TYPE) field.setBoolean(conf, enabled);
        else field.set(conf, Boolean.valueOf(enabled));
    }

    private void setAcFixed(Object conf, int amps) throws Exception {
        try {
            Method method = conf.getClass().getMethod("setACMaxPowerFixed", Integer.TYPE);
            method.invoke(conf, Integer.valueOf(amps));
            return;
        } catch (NoSuchMethodException ignored) {}
        Field field = findField(conf.getClass(), "ACMaxPowerFixed");
        if (field == null) throw new NoSuchFieldException("ACMaxPowerFixed");
        setInt(field, conf, amps);
    }

    private void setDcFixed(Object conf, int kw) throws Exception {
        try {
            Method method = conf.getClass().getMethod("setDCMaxPowerFixed", Integer.TYPE);
            method.invoke(conf, Integer.valueOf(kw));
            return;
        } catch (NoSuchMethodException ignored) {}
        Field field = findField(conf.getClass(), "DCMaxPowerFixed");
        if (field == null) throw new NoSuchFieldException("DCMaxPowerFixed");
        setInt(field, conf, kw);
    }

    private static int readInt(Object owner, String methodName, String fieldName,
                               int fallback) throws Exception {
        try {
            Object value = owner.getClass().getMethod(methodName).invoke(owner);
            if (value instanceof Number) return ((Number)value).intValue();
        } catch (NoSuchMethodException ignored) {}
        Field field = findField(owner.getClass(), fieldName);
        if (field == null) return fallback;
        field.setAccessible(true);
        Object value = field.get(owner);
        return value instanceof Number ? ((Number)value).intValue() : fallback;
    }

    private static void setInt(Field field, Object owner, int value) throws Exception {
        field.setAccessible(true);
        if (field.getType() == Integer.TYPE) field.setInt(owner, value);
        else field.set(owner, Integer.valueOf(value));
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
