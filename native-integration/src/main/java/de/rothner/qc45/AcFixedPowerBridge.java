package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Mirrors the logical Type2 setpoint into EVCSD's native ACMaxPowerFixed path.
 *
 * Reverse engineering of the original evcsd.jar (build 57) shows two different
 * normal-AC limit paths:
 *
 *  1. enableACLoadBalance=true -> getEnergy() sends
 *       maxPowerAC / getSatsInCharge() * 10
 *     without guarding getSatsInCharge()==0.
 *
 *  2. enableACLoadBalance=false with AC/DC fixed limits > 0 -> getEnergy()
 *     sends ACMaxPowerFixed * 10 directly.
 *
 * On this QC45 the normal satellite can remain logically IDLE while an AC
 * transaction is physically charging. The first path can therefore divide by
 * zero and effectively remove the limit. The fixed-limit path has no satellite
 * count dependency and is also the stock Efacec path used by START_CHARGE and
 * the ChargingState's periodic getEnergy() request.
 *
 * This bridge never sends MobiBus messages itself. It only keeps the original
 * configuration field aligned with SatelliteModule.satelliteMaxPower; stock
 * EVCSD transmits the value on its normal ~1 s ENERGY cycle.
 */
final class AcFixedPowerBridge extends Thread {
    private static final int AC_CONNECTOR = 3;
    private static final int LOOP_MS = 100;
    private static final long ERROR_LOG_MS = 5000L;
    private static final String CENTRAL =
        "pt.efacec.es.mobie.agent.statemachines.CentralModule";

    private final Class<?> centralClass;
    private volatile boolean running = true;
    private long lastErrorLogMs;
    private int lastLoggedTarget = Integer.MIN_VALUE;

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
            + " transport=stock-START_CHARGE/ENERGY scale=x10"
            + " own-MobiBus-writes=false");
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
            throw new IllegalStateException("Unable to disable buggy AC load-balance divisor path");
        }

        int dcFixed = readInt(conf, "getDCMaxPowerFixed", "DCMaxPowerFixed", -1);
        if (dcFixed <= 0) {
            throw new IllegalStateException("DCMaxPowerFixed must stay >0 for native fixed AC transport; was "
                + dcFixed);
        }

        int target = ((Number)satellite.getClass().getMethod("getMaxPower").invoke(satellite)).intValue();
        if (target <= 0) target = ChargingLimitCoordinator.AC_NOTLADEN_KW;
        if (target > 43) target = 43;

        int before = readInt(conf, "getACMaxPowerFixed", "ACMaxPowerFixed", -1);
        if (before != target) setAcFixed(conf, target);
        int after = readInt(conf, "getACMaxPowerFixed", "ACMaxPowerFixed", -1);
        if (after != target) {
            throw new IllegalStateException("ACMaxPowerFixed did not accept target " + target
                + "kW; read back " + after + "kW");
        }

        // Keep maxPowerAC coherent for diagnostics/UI, but with AC load balance
        // disabled it is not used by the native normal-AC packet serializer.
        try {
            conf.getClass().getMethod("setMaxPowerAC", Integer.TYPE)
                .invoke(conf, Integer.valueOf(target));
        } catch (NoSuchMethodException ignored) {
            Field field = findField(conf.getClass(), "maxPowerAC");
            if (field != null) setInt(field, conf, target);
        }

        if (startup || target != lastLoggedTarget || acLoadBalanceBefore) {
            System.out.println("[QC45] AC native fixed target=" + target + "kW"
                + " acFixed=" + before + "->" + after
                + " dcFixed=" + dcFixed
                + " acLoadBalance=" + acLoadBalanceBefore + "->false"
                + " divisorPath=disabled");
            lastLoggedTarget = target;
        }
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

    private void setAcFixed(Object conf, int kw) throws Exception {
        try {
            Method method = conf.getClass().getMethod("setACMaxPowerFixed", Integer.TYPE);
            method.invoke(conf, Integer.valueOf(kw));
            return;
        } catch (NoSuchMethodException ignored) {}
        Field field = findField(conf.getClass(), "ACMaxPowerFixed");
        if (field == null) throw new NoSuchFieldException("ACMaxPowerFixed");
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
