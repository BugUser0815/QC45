package pt.efacec.es.evcsd.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Immutable decoder for the native integration's versioned AC/DC UI block. */
final class LoadBalancingTelemetry {
    static final int FIRST_REGISTER = 126;
    static final int REGISTER_COUNT = 20;
    static final int VERSION = 1;

    static final int FLAG_DC_SESSION = 1 << 0;
    static final int FLAG_AC_SESSION = 1 << 1;
    static final int FLAG_DC_FLOW = 1 << 2;
    static final int FLAG_AC_FLOW = 1 << 3;
    static final int FLAG_BLOCKED = 1 << 4;
    static final int FLAG_FAILBACK = 1 << 5;
    static final int FLAG_LOAD_METER = 1 << 6;
    static final int FLAG_STARTUP = 1 << 7;
    static final int FLAG_SHUTDOWN = 1 << 8;
    static final int FLAG_DEMAND_TRANSFER = 1 << 9;
    static final int FLAG_STAGE_LIMIT = 1 << 10;
    static final int FLAG_CONFIGURATION = 1 << 11;
    static final int FLAG_LIMIT_MISMATCH = 1 << 12;
    static final int FLAG_EVCC_DC = 1 << 13;
    static final int FLAG_EVCC_AC = 1 << 14;
    static final int FLAG_REMOTE_START = 1 << 15;

    final int flags;
    final int activeDcConnector;
    final int dcActualKw;
    final int dcRequestedKw;
    final int dcGridKw;
    final int dcStageCapKw;
    final int dcEffectiveKw;
    final int dcSocPct;
    final int dcSeconds;
    final long dcEnergyWh;
    final int acActualKw;
    final int acRequestedKw;
    final int acGridKw;
    final int acStageCapKw;
    final int acEffectiveKw;
    final int acSeconds;
    final long acEnergyWh;

    private LoadBalancingTelemetry(int[] value) {
        flags = value[1];
        activeDcConnector = value[2];
        dcActualKw = value[3];
        dcRequestedKw = value[4];
        dcGridKw = value[5];
        dcStageCapKw = value[6];
        dcEffectiveKw = value[7];
        dcSocPct = value[8];
        dcSeconds = value[9];
        dcEnergyWh = words(value[10], value[11]);
        acSeconds = value[17];
        acEnergyWh = words(value[18], value[19]);

        int nativeAcKw = value[12];
        int directAcKw = nativeAcKw > 0 ? 0 : liveType2PowerKw();
        acActualKw = nativeAcKw > 0 ? nativeAcKw
            : directAcKw > 0 ? directAcKw
            : averagePowerKw(acEnergyWh, acSeconds);

        acRequestedKw = value[13];
        acGridKw = value[14];
        acStageCapKw = value[15];
        acEffectiveKw = value[16];
    }

    static LoadBalancingTelemetry decode(int[] value) {
        if (value == null || value.length != REGISTER_COUNT || value[0] != VERSION) {
            throw new IllegalArgumentException("unsupported AC/DC UI telemetry block");
        }
        if (value[2] < 0 || value[2] > 2) {
            throw new IllegalArgumentException("invalid active DC connector");
        }
        return new LoadBalancingTelemetry(value);
    }

    boolean has(int flag) {
        return (flags & flag) != 0;
    }

    boolean dcSession() { return has(FLAG_DC_SESSION); }
    boolean acSession() { return has(FLAG_AC_SESSION); }
    boolean blocked() { return has(FLAG_BLOCKED); }
    boolean demandTransfer() { return has(FLAG_DEMAND_TRANSFER); }
    boolean evccControlsDc() { return has(FLAG_EVCC_DC); }
    boolean evccControlsAc() { return has(FLAG_EVCC_AC); }
    boolean remoteStarted() { return has(FLAG_REMOTE_START); }
    int totalActualKw() { return dcActualKw + acActualKw; }
    int totalEffectiveKw() { return dcEffectiveKw + acEffectiveKw; }
    long totalEnergyWh() { return dcEnergyWh + acEnergyWh; }

    /**
     * Read the live Type2 satellite in the EVCSD JVM. Older QC45 firmware often
     * leaves getCurrentPower() at zero for AC although phase telemetry is live.
     * Prefer a direct power field and then derive power from phase V/A arrays.
     */
    private static int liveType2PowerKw() {
        try {
            Class<?> centralClass = Class.forName(
                "pt.efacec.es.mobie.agent.statemachines.CentralModule");
            Object central = centralClass.getMethod("getCurrentModule").invoke(null);
            if (central == null) return 0;
            Object value = centralClass.getMethod("getSatellites").invoke(central);
            if (!(value instanceof Object[])) return 0;
            Object[] satellites = (Object[])value;
            Object ac = null;
            for (int i = 0; i < satellites.length; i++) {
                Object satellite = satellites[i];
                if (satellite == null) continue;
                Object id = satellite.getClass().getMethod("getSatelliteId").invoke(satellite);
                if (id instanceof Number && ((Number)id).intValue() == 3) {
                    ac = satellite;
                    break;
                }
            }
            if (ac == null) return 0;

            try {
                Object p = ac.getClass().getMethod("getCurrentPower").invoke(ac);
                if (p instanceof Number && ((Number)p).intValue() > 0) {
                    return ((Number)p).intValue();
                }
            } catch (Throwable ignored) {}

            Field infoField = findField(ac.getClass(), "infoState");
            if (infoField != null) {
                infoField.setAccessible(true);
                Object info = infoField.get(ac);
                if (info != null) {
                    int p = numericField(info, "power");
                    if (p > 0) return p;
                    int voltage = numericField(info, "voltage");
                    int current = numericField(info, "electricCurrent");
                    if (voltage > 0 && current > 0) {
                        return roundedKw((long)voltage * (long)current);
                    }
                }
            }

            int[] volts = intArrayGetter(ac, "getCurrentVoltage");
            String[] currentMethods = new String[] {
                "getCurrentCurrent", "getCurrentElectricCurrent", "getCurrentAmperage"
            };
            for (int i = 0; i < currentMethods.length; i++) {
                int[] amps = intArrayGetter(ac, currentMethods[i]);
                int kw = phasePowerKw(volts, amps);
                if (kw > 0) return kw;
            }
        } catch (Throwable ignored) {
            // Keep the UI functional on firmware variants without these members.
        }
        return 0;
    }

    private static int[] intArrayGetter(Object owner, String methodName) {
        if (owner == null) return null;
        try {
            Method method = owner.getClass().getMethod(methodName);
            Object value = method.invoke(owner);
            return value instanceof int[] ? (int[])value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int phasePowerKw(int[] volts, int[] amps) {
        if (volts == null || amps == null) return 0;
        int count = Math.min(volts.length, amps.length);
        if (count <= 0) return 0;
        long watts = 0L;
        for (int i = 0; i < count; i++) {
            if (volts[i] > 0 && amps[i] > 0) {
                watts += (long)volts[i] * (long)amps[i];
            }
        }
        return roundedKw(watts);
    }

    private static int roundedKw(long watts) {
        if (watts <= 0L) return 0;
        long kw = (watts + 500L) / 1000L;
        return (int)Math.min(65535L, kw);
    }

    private static int numericField(Object owner, String fieldName) {
        try {
            Field field = findField(owner.getClass(), fieldName);
            if (field == null) return 0;
            field.setAccessible(true);
            Object value = field.get(owner);
            return value instanceof Number ? ((Number)value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
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

    private static int averagePowerKw(long energyWh, int seconds) {
        if (energyWh <= 0L || seconds <= 0) return 0;
        long watts = (energyWh * 3600L + seconds / 2L) / seconds;
        long kw = (watts + 500L) / 1000L;
        return (int)Math.min(65535L, Math.max(0L, kw));
    }

    private static long words(int high, int low) {
        return (((long)high & 0xffffL) << 16) | ((long)low & 0xffffL);
    }
}
