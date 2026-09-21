package de.rothner.qc45;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hardware transport for Type2/AC limits on the original QC45 MobiBus path.
 *
 * The EVCSD Java setters only update cached values. A positive AC limit is
 * transported in START_CHARGE/ENERGY packets as deci-kW. Zero is special: the
 * stock load-shed code deliberately converts a computed 0 to 1, so a 0 power
 * payload cannot be treated as a physical pause. The satellite protocol has a
 * dedicated SUSPEND_CHARGE request used on transport shutdown. During an
 * authorized session, logical zero is sent as 5 kW Notladen, exactly as on DC.
 * KSEM/failback blocks cap power at Notladen; hard trips end the transaction
 * through GridFailback's independent RemoteStop path.
 */
final class AcPowerLimitTransport extends Thread {
    private static final int AC_CONNECTOR = 3;
    private static final int LOOP_MS = 250;
    private static final long SESSION_START_GRACE_MS = 5000L;
    private static final long DIAGNOSTIC_LOG_MS = 5000L;
    private static final long POWER_SAMPLE_MS = 1500L;
    private static final long POWER_STALE_MS = 3500L;
    private static final long ERROR_LOG_MS = 5000L;

    private static final String CENTRAL =
        "pt.efacec.es.mobie.agent.statemachines.CentralModule";
    private static final String MESSAGE =
        "pt.efacec.es.mobie.agent.stationcomponents.comms.MessageStateMachines";

    private final ChargingLimitCoordinator limits;
    private final ReflectionQC45 station;
    private final Class<?> centralClass;
    private final Class<?> messageClass;
    private final Object satRequestLock;

    private volatile boolean running = true;
    private int lastTargetKw = -1;
    private long lastErrorLogMs;
    private boolean sessionObserved;
    private long sessionStartedMs;
    private long lastDiagnosticLogMs;

    private long lastEnergyWh = -1L;
    private long lastEnergySampleMs;
    private int derivedPowerKw;
    private long derivedPowerMs;

    static AcPowerLimitTransport startRequired(Integration integration) throws Exception {
        if (integration == null) throw new IllegalArgumentException("integration is required");
        Field field = Integration.class.getDeclaredField("limits");
        field.setAccessible(true);
        Object value = field.get(integration);
        if (!(value instanceof ChargingLimitCoordinator)) {
            throw new IllegalStateException("ChargingLimitCoordinator unavailable");
        }
        AcPowerLimitTransport transport = new AcPowerLimitTransport(
            (ChargingLimitCoordinator)value, new ReflectionQC45());
        transport.start();
        return transport;
    }

    private AcPowerLimitTransport(ChargingLimitCoordinator limits,
                                  ReflectionQC45 station) throws Exception {
        super("QC45-AC-PowerLimitTransport");
        if (limits == null || station == null) {
            throw new IllegalArgumentException("limits and station are required");
        }
        this.limits = limits;
        this.station = station;
        this.centralClass = Class.forName(CENTRAL);
        this.messageClass = Class.forName(MESSAGE);
        Field lockField = findField(centralClass, "satRequest");
        if (lockField == null) throw new NoSuchFieldException("CentralModule.satRequest");
        lockField.setAccessible(true);
        this.satRequestLock = lockField.get(null);
        if (satRequestLock == null) throw new IllegalStateException("CentralModule.satRequest unavailable");
        setDaemon(true);
    }

    public void run() {
        System.out.println("[QC45] AC MobiBus power-limit transport started"
            + " logical-zero=5kW limits=ENERGY hard-trip=RemoteStop"
            + " power=energy-delta");
        while (running) {
            long now = System.currentTimeMillis();
            try {
                Object satellite = acSatellite();
                boolean session = hasSession(satellite);
                int actualKw = updateLivePowerEstimate(satellite, session, now);

                if (!session) {
                    if (sessionObserved) {
                        System.out.println("[QC45] AC session ended age="
                            + Math.max(0L, now - sessionStartedMs) + "ms "
                            + diagnostics(satellite, actualKw));
                    }
                    sessionObserved = false;
                    sessionStartedMs = 0L;
                    lastDiagnosticLogMs = 0L;
                    lastTargetKw = -1;
                    resetPowerEstimate(satellite);
                } else {
                    int targetKw = limits.acMobiBusTargetKw();
                    if (!sessionObserved) {
                        sessionObserved = true;
                        sessionStartedMs = now;
                        lastDiagnosticLogMs = now;
                        // The stock EVCSD START_CHARGE already contains the
                        // pre-armed maxPower value. Do not race that handshake
                        // with our own ENERGY request. The BMW i3 trace showed
                        // this duplicate request immediately after START_CHARGE,
                        // followed by a no-power timeout after 30 seconds.
                        lastTargetKw = station.limitKw(AC_CONNECTOR);
                        System.out.println("[QC45] AC session started native-start-limit="
                            + lastTargetKw + "kW requested=" + targetKw
                            + "kW energy-update-deferred=" + SESSION_START_GRACE_MS
                            + "ms " + diagnostics(satellite, actualKw));
                    } else if (shouldSendEnergy(now, sessionStartedMs,
                                                targetKw, lastTargetKw)) {
                        send(satellite, "ENERGY", targetKw, true, false, 400L);
                        System.out.println("[QC45] AC MobiBus LIMIT target="
                            + targetKw + "kW packet=" + (targetKw * 10)
                            + " deci-kW actual=" + actualKw + "kW"
                            + " blockers=" + limits.blockReason());
                        lastTargetKw = targetKw;
                    }
                    if (now - lastDiagnosticLogMs >= DIAGNOSTIC_LOG_MS) {
                        System.out.println("[QC45] AC session trace age="
                            + Math.max(0L, now - sessionStartedMs) + "ms target="
                            + targetKw + "kW applied=" + lastTargetKw + "kW "
                            + diagnostics(satellite, actualKw));
                        lastDiagnosticLogMs = now;
                    }
                }
            } catch (Throwable error) {
                if (now - lastErrorLogMs >= ERROR_LOG_MS) {
                    System.err.println("[QC45] AC MobiBus power-limit transport failed: " + error);
                    lastErrorLogMs = now;
                }
            }

            try { Thread.sleep(LOOP_MS); }
            catch (InterruptedException e) { if (!running) break; }
        }
        try {
            Object satellite = acSatellite();
            if (hasSession(satellite)) {
                send(satellite, "SUSPEND_CHARGE", 0, false, false, 300L);
            }
        } catch (Throwable error) {
            System.err.println("[QC45] AC MobiBus shutdown suspend failed: " + error);
        }
        System.out.println("[QC45] AC MobiBus power-limit transport stopped");
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    static boolean shouldSendEnergy(long now, long sessionStarted,
                                    int targetKw, int appliedTargetKw) {
        return sessionStarted > 0L
            && now - sessionStarted >= SESSION_START_GRACE_MS
            && targetKw != appliedTargetKw;
    }

    /**
     * Send the exact packet family used by the stock EVCSD. Positive limits are
     * encoded in 0.1 kW units. A non-empty reply is required so a lost MobiBus
     * command is retried on the next control cycle.
     */
    private void send(Object satellite, String actionName, int kw,
                      boolean includePower, boolean includeKey,
                      long timeoutMs) throws Exception {
        synchronized (satRequestLock) {
            Object central = currentCentral();
            Object message = messageClass.newInstance();
            setEnumField(message, "satelliteAction", actionName);
            setEnumField(message, "device", "SATELLITE");
            setIntField(message, "satellite", AC_CONNECTOR);
            if (includePower) {
                setIntField(message, "maxPower", clamp(kw, 1, 43) * 10);
                setField(message, "text", "true");
            }
            if (includeKey) setIntField(message, "key", currentCreditKey(central));

            Object request = centralClass.getMethod("getCurrentSatRequest").invoke(central);
            if (request == null) throw new IllegalStateException("SatelliteRequest unavailable");
            Object communications = fieldValue(satellite, "communications");
            if (communications == null) throw new IllegalStateException("AC communications unavailable");
            Method wait = findWaitForAnswer(request.getClass());
            Object result = wait.invoke(request,
                Integer.valueOf(AC_CONNECTOR), Long.valueOf(timeoutMs), communications, message);
            if (!(result instanceof List) || ((List<?>)result).isEmpty()) {
                throw new IllegalStateException(actionName + " returned no MobiBus reply");
            }
            System.out.println("[QC45] AC MobiBus reply action=" + actionName
                + " entries=" + ((List<?>)result).size()
                + " first=" + compact(((List<?>)result).get(0)));
        }
    }

    private Object currentCentral() throws Exception {
        Object central = centralClass.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");
        return central;
    }

    private Object acSatellite() throws Exception {
        Object value = centralClass.getMethod("getSatellites").invoke(currentCentral());
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

    private boolean hasSession(Object satellite) throws Exception {
        // LoadManager must see the same authorized Type2 session before this
        // transport can release a positive grid-approved target. Divergent
        // legacy-user fallbacks caused SUSPEND_CHARGE to loop at 0 kW.
        return station.sessionActive(AC_CONNECTOR);
    }

    private String diagnostics(Object satellite, int actualKw) {
        StringBuilder out = new StringBuilder(192);
        out.append("activeTx=").append(call(satellite, "getActiveTransaction"));
        out.append(" status=").append(firstCall(satellite,
            new String[] { "getStatus", "getCurrentStatus", "getState" }));
        out.append(" charging=").append(firstCall(satellite,
            new String[] { "isCharging", "getCharging" }));
        out.append(" inUse=").append(firstCall(satellite,
            new String[] { "isInUse", "getInUse" }));
        out.append(" cable=").append(firstCall(satellite,
            new String[] { "isCableConnected", "getCableConnected", "getCableStatus" }));
        out.append(" power=").append(actualKw).append("kW");
        out.append(" energy=").append(call(satellite, "getCurrentEnergy"));
        Object info = null;
        try { info = fieldValue(satellite, "infoState"); } catch (Throwable ignored) {}
        out.append(" acDTC=").append(field(info, "acDTC"));
        out.append(" infoStatus=").append(firstField(info,
            new String[] { "status", "state", "chargingStatus" }));
        out.append(" infoCharging=").append(firstField(info,
            new String[] { "charging", "isCharging" }));
        out.append(" infoCable=").append(firstField(info,
            new String[] { "cableConnected", "cable", "plugged" }));
        return out.toString();
    }

    private static String firstCall(Object owner, String[] names) {
        for (int i = 0; i < names.length; i++) {
            String value = call(owner, names[i]);
            if (!"n/a".equals(value)) return names[i] + "=" + value;
        }
        return "n/a";
    }

    private static String call(Object owner, String name) {
        if (owner == null) return "n/a";
        try {
            Method method = findZeroArgMethod(owner.getClass(), name);
            if (method == null) return "n/a";
            return compact(method.invoke(owner));
        } catch (Throwable ignored) {
            return "n/a";
        }
    }

    private static String firstField(Object owner, String[] names) {
        for (int i = 0; i < names.length; i++) {
            String value = field(owner, names[i]);
            if (!"n/a".equals(value)) return names[i] + "=" + value;
        }
        return "n/a";
    }

    private static String field(Object owner, String name) {
        if (owner == null) return "n/a";
        try {
            Field value = findField(owner.getClass(), name);
            if (value == null) return "n/a";
            value.setAccessible(true);
            return compact(value.get(owner));
        } catch (Throwable ignored) {
            return "n/a";
        }
    }

    private static String compact(Object value) {
        if (value == null) return "null";
        Class<?> type = value.getClass();
        if (type.isArray()) {
            StringBuilder out = new StringBuilder("[");
            int length = Math.min(Array.getLength(value), 6);
            for (int i = 0; i < length; i++) {
                if (i > 0) out.append(',');
                out.append(String.valueOf(Array.get(value, i)));
            }
            if (Array.getLength(value) > length) out.append(",...");
            return out.append(']').toString();
        }
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    /**
     * Normal MobiBus ENERGY replies contain cumulative energy but the old EVCSD
     * leaves SatelliteInfo.power at zero for Type2. Derive a short-window live
     * value from the energy counter and publish it into the existing infoState
     * field so Modbus/UI and ChargingLimitGuard see real AC load instead of a
     * session-long average or zero.
     */
    private int updateLivePowerEstimate(Object satellite, boolean session, long now) {
        if (!session) return 0;
        try {
            Object direct = satellite.getClass().getMethod("getCurrentPower").invoke(satellite);
            int directKw = direct instanceof Number ? ((Number)direct).intValue() : 0;
            // Ignore our own derived value as a fresh hardware source. Energy
            // sampling below remains authoritative for normal Type2.
            long energyWh = currentEnergyWh(satellite);
            if (lastEnergyWh < 0L) {
                lastEnergyWh = energyWh;
                lastEnergySampleMs = now;
            } else if (now - lastEnergySampleMs >= POWER_SAMPLE_MS) {
                long elapsedMs = now - lastEnergySampleMs;
                long deltaWh = energyWh >= lastEnergyWh ? energyWh - lastEnergyWh : -1L;
                if (deltaWh >= 0L) {
                    long watts = elapsedMs <= 0L ? 0L
                        : (deltaWh * 3600000L + elapsedMs / 2L) / elapsedMs;
                    int kw = (int)Math.min(43L, Math.max(0L, (watts + 500L) / 1000L));
                    derivedPowerKw = kw;
                    derivedPowerMs = now;
                    writeInfoPower(satellite, kw);
                }
                lastEnergyWh = energyWh;
                lastEnergySampleMs = now;
            }
            if (now - derivedPowerMs > POWER_STALE_MS) {
                derivedPowerKw = 0;
                writeInfoPower(satellite, 0);
            }
            return derivedPowerKw > 0 ? derivedPowerKw : Math.max(0, directKw);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void resetPowerEstimate(Object satellite) {
        lastEnergyWh = -1L;
        lastEnergySampleMs = 0L;
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

    private int currentCreditKey(Object central) {
        try {
            Object current = centralClass.getMethod("getCurrent").invoke(central);
            if (current == null) return 999999;
            Object credit = current.getClass().getMethod("getCredit").invoke(current);
            int value = credit instanceof Number ? ((Number)credit).intValue() : 0;
            return value == 0 ? 999999 : value;
        } catch (Throwable ignored) {
            return 999999;
        }
    }

    private static Method findWaitForAnswer(Class<?> type) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            Method[] methods = current.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                if ("waitForAnswer".equals(method.getName())
                        && method.getParameterTypes().length == 4) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException("waitForAnswer");
    }

    private static Method findZeroArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
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

    private static void setEnumField(Object owner, String name, String value) throws Exception {
        Field field = findField(owner.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        Method valueOf = field.getType().getMethod("valueOf", String.class);
        field.set(owner, valueOf.invoke(null, value));
    }

    private static void setIntField(Object owner, String name, int value) throws Exception {
        Field field = findField(owner.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        if (field.getType() == Integer.TYPE) field.setInt(owner, value);
        else field.set(owner, Integer.valueOf(value));
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = findField(owner.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
