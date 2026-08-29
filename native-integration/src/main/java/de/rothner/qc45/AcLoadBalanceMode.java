package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Enables the QC45 firmware's native AC power-limit transport.
 *
 * The original EVCSD only serializes Configuration.maxPowerAC into normal
 * START_CHARGE / ENERGY MobiBus messages when enableACLoadBalance is true.
 * Merely changing maxPowerAC or SatelliteModule.satelliteMaxPower therefore
 * updates Java-side diagnostics but does not make the Type2 satellite enforce
 * the requested limit while this flag is false.
 */
final class AcLoadBalanceMode {
    private static final String CENTRAL =
        "pt.efacec.es.mobie.agent.statemachines.CentralModule";

    private AcLoadBalanceMode() {}

    static void enableRequired() throws Exception {
        Class<?> centralClass = Class.forName(CENTRAL);
        Object central = centralClass.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");

        Object conf = centralClass.getMethod("getConf").invoke(central);
        if (conf == null) throw new IllegalStateException("Configuration unavailable");

        boolean before = isEnabledOn(conf);
        enableOn(conf);
        boolean after = isEnabledOn(conf);
        if (!after) {
            throw new IllegalStateException("Unable to enable AC load-balance transport");
        }

        int maxPowerAc = readInt(conf, "getMaxPowerAC", -1);
        System.out.println("[QC45] AC native power-limit transport enabled"
            + " acLoadBalance=" + before + "->" + after
            + " maxPowerAC=" + maxPowerAc + "kW"
            + " protocol=normal-MobiBus maxPower=x10");
    }

    static void enableOn(Object conf) throws Exception {
        if (conf == null) throw new IllegalArgumentException("configuration is required");
        Field field = findField(conf.getClass(), "enableACLoadBalance");
        if (field == null) throw new NoSuchFieldException("enableACLoadBalance");
        field.setAccessible(true);
        if (field.getType() == Boolean.TYPE) field.setBoolean(conf, true);
        else field.set(conf, Boolean.TRUE);
    }

    static boolean isEnabledOn(Object conf) throws Exception {
        if (conf == null) return false;
        try {
            Method method = conf.getClass().getMethod("isEnableACLoadBalance");
            Object value = method.invoke(conf);
            if (value instanceof Boolean) return ((Boolean)value).booleanValue();
        } catch (NoSuchMethodException ignored) {}

        Field field = findField(conf.getClass(), "enableACLoadBalance");
        if (field == null) return false;
        field.setAccessible(true);
        Object value = field.get(conf);
        return value instanceof Boolean && ((Boolean)value).booleanValue();
    }

    private static int readInt(Object owner, String methodName, int fallback) {
        try {
            Object value = owner.getClass().getMethod(methodName).invoke(owner);
            return value instanceof Number ? ((Number)value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
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
