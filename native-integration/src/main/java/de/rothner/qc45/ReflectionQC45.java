package de.rothner.qc45;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/** Reflection adapter around the live EVCSD objects. */
public final class ReflectionQC45 implements ChargingLimitIo, ChargingSessionIo {
    private final Class<?> centralClass;
    private final Class<?> configurationClass;
    private final Set<Integer> remoteConnectors = new HashSet<Integer>();

    public ReflectionQC45() throws Exception {
        centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        configurationClass = Class.forName("pt.efacec.es.mobie.agent.Configuration");
    }

    private Object central() throws Exception {
        Method m = centralClass.getMethod("getCurrentModule");
        Object cm = m.invoke(null);
        if (cm == null) throw new IllegalStateException("CentralModule unavailable");
        return cm;
    }

    private Object configuration() throws Exception {
        Object c = centralClass.getMethod("getConf").invoke(central());
        if (c == null) throw new IllegalStateException("Configuration unavailable");
        return c;
    }

    private Object[] satellites() throws Exception {
        Object[] sats = (Object[]) centralClass.getMethod("getSatellites").invoke(central());
        if (sats == null) throw new IllegalStateException("Satellites unavailable");
        return sats;
    }

    private Object satellite(int connector) throws Exception {
        Object[] sats = satellites();
        for (int i = 0; i < sats.length; i++) {
            Object sat = sats[i];
            if (sat == null) continue;
            int id = ((Number) sat.getClass().getMethod("getSatelliteId").invoke(sat)).intValue();
            if (id == connector) return sat;
        }
        throw new IllegalArgumentException("Connector unavailable: " + connector);
    }

    public int powerKw(int connector) throws Exception {
        Object sat = satellite(connector);
        return Math.max(0, ((Number) sat.getClass().getMethod("getCurrentPower").invoke(sat)).intValue());
    }

    public int limitKw(int connector) throws Exception {
        Object sat = satellite(connector);
        return Math.max(0, ((Number) sat.getClass().getMethod("getMaxPower").invoke(sat)).intValue());
    }

    public int dcVoltageV(int connector) throws Exception {
        int ccsVoltage = ccsModuleNumber(connector, "voltage", 0);
        if (ccsVoltage >= 100 && ccsVoltage <= 1000) return ccsVoltage;

        Object sat = satellite(connector);
        Object value = sat.getClass().getMethod("getCurrentVoltage").invoke(sat);
        if (!(value instanceof int[])) return 0;
        int[] volts = (int[]) value;
        if (volts == null) return 0;
        for (int i = 0; i < volts.length; i++) {
            int v = volts[i];
            if (v >= 100 && v <= 1000) return v;
        }
        return 0;
    }

    public String ccsModuleTelemetry(int connector) {
        try {
            Object sat = satellite(connector);
            Field infoField = findSingleField(sat.getClass(), "infoState");
            if (infoField == null) return "n/a";
            infoField.setAccessible(true);
            Object info = infoField.get(sat);
            if (info == null) return "n/a";

            int voltage = numberField(info, "voltage", 0);
            int current = numberField(info, "electricCurrent", 0);
            int power = numberField(info, "power", 0);
            int dtc = numberField(info, "quickChargeDTC", 0);
            boolean epo = booleanField(info, "epoPressed", false);

            return "voltage=" + voltage + "V,current=" + current + "A,power=" + power
                + "kW,dtc=" + dtc + ",epo=" + epo;
        } catch (Throwable e) {
            return "n/a(" + e.getClass().getSimpleName() + ")";
        }
    }

    private int ccsModuleNumber(int connector, String name, int fallback) {
        try {
            Object sat = satellite(connector);
            Field infoField = findSingleField(sat.getClass(), "infoState");
            if (infoField == null) return fallback;
            infoField.setAccessible(true);
            Object info = infoField.get(sat);
            if (info == null) return fallback;
            return numberField(info, name, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int numberField(Object owner, String name, int fallback) throws Exception {
        Field f = findSingleField(owner.getClass(), name);
        if (f == null) return fallback;
        f.setAccessible(true);
        Object value = f.get(owner);
        return value instanceof Number ? ((Number)value).intValue() : fallback;
    }

    private static boolean booleanField(Object owner, String name, boolean fallback) throws Exception {
        Field f = findSingleField(owner.getClass(), name);
        if (f == null) return fallback;
        f.setAccessible(true);
        Object value = f.get(owner);
        return value instanceof Boolean ? ((Boolean)value).booleanValue() : fallback;
    }

    public int quickChargeMaxCurrentA(int connector) throws Exception {
        Object sat = satellite(connector);
        Field f = findField(sat.getClass(), "quickChargeMaxCurrent", "QuickChargeMaxCurrent");
        if (f == null) throw new NoSuchFieldException("quickChargeMaxCurrent");
        f.setAccessible(true);
        Object value = f.get(sat);
        return value instanceof Number ? ((Number)value).intValue() : 0;
    }

    public String ccsMessageStateCurrent(int connector) {
        try {
            Object sat = satellite(connector);
            String direct = findMessageStateCurrentInObject(sat, "Satellite");
            if (direct != null) return direct;

            Object cm = central();
            String centralValue = findMessageStateCurrentInObject(cm, "CentralModule");
            if (centralValue != null) return centralValue;

            String[] classNames = new String[] {
                "pt.efacec.es.mobie.agent.statemachines.MessageStateMachines",
                "pt.efacec.es.mobie.agent.MessageStateMachines"
            };
            for (int i = 0; i < classNames.length; i++) {
                try {
                    Class<?> type = Class.forName(classNames[i]);
                    Field current = findSingleField(type, "current");
                    if (current != null && Modifier.isStatic(current.getModifiers())) {
                        current.setAccessible(true);
                        Object value = current.get(null);
                        if (value instanceof Number) {
                            return classNames[i] + ".current=" + ((Number)value).intValue() + "A";
                        }
                        if (value != null) return classNames[i] + ".current=" + String.valueOf(value);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return "n/a";
    }

    private String findMessageStateCurrentInObject(Object owner, String ownerLabel) {
        if (owner == null) return null;
        Class<?> t = owner.getClass();
        while (t != null) {
            Field[] fields = t.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (Modifier.isStatic(f.getModifiers())) continue;
                String fieldName = f.getName().toLowerCase(java.util.Locale.US);
                String typeName = f.getType().getName().toLowerCase(java.util.Locale.US);
                if (fieldName.indexOf("messagestate") < 0 && typeName.indexOf("messagestatemachines") < 0) continue;
                try {
                    f.setAccessible(true);
                    Object state = f.get(owner);
                    if (state == null) continue;
                    Field current = findSingleField(state.getClass(), "current");
                    if (current == null) continue;
                    current.setAccessible(true);
                    Object value = current.get(state);
                    if (value instanceof Number) {
                        return ownerLabel + "." + f.getName() + ".current=" + ((Number)value).intValue() + "A";
                    }
                    if (value != null) return ownerLabel + "." + f.getName() + ".current=" + String.valueOf(value);
                } catch (Throwable ignored) {}
            }
            t = t.getSuperclass();
        }
        return null;
    }

    private static Field findSingleField(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null) {
            try { return t.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { t = t.getSuperclass(); }
        }
        return null;
    }

    /**
     * Push the already configured CCS V3 power limit immediately.
     *
     * Reverse engineering of the original EVCSD shows that QuickChargeSerializer
     * V3 serializes MessageStateMachines.maxPower directly into byte 2 of the
     * 0x63 START_CHARGE packet. SatelliteModule.sendCcsStart() copies
     * SatelliteModule.satelliteMaxPower into that field. Therefore the value must
     * remain the requested power in kW; no P/U conversion or temporary Ampere
     * substitution is valid for CCS V3.
     */
    public int refreshQuickChargeCurrentForPower(int connector, int targetKw) throws Exception {
        if (connector != 2) return 0;
        Object sat = satellite(connector);
        Class<?> satType = sat.getClass();
        boolean ccs = ((Boolean) satType.getMethod("isCCSCharge").invoke(sat)).booleanValue();
        if (!ccs) return 0;

        int effectiveKw = clamp(targetKw, 0, 50);
        satType.getMethod("setMaxPower", Integer.TYPE).invoke(sat, Integer.valueOf(effectiveKw));

        Object cm = central();
        boolean loggedIn = ((Boolean) centralClass.getMethod("isLoggedIn").invoke(cm)).booleanValue();
        satType.getMethod("sendCcsStart", Boolean.TYPE).invoke(sat, Boolean.valueOf(loggedIn));

        int quickCurrent = 0;
        try { quickCurrent = quickChargeMaxCurrentA(connector); } catch (Throwable ignored) {}

        System.out.println("[QC45] CCS power target connector=" + connector
            + " ccsV3Byte2=" + effectiveKw + "kW"
            + " loggedIn=" + loggedIn
            + " quickChargeMaxCurrent=" + quickCurrent + "A diagnostic-only"
            + " satelliteMaxPower=" + limitKw(connector) + "kW"
            + " actualPower=" + powerKw(connector) + "kW"
            + " ccsRx[" + ccsModuleTelemetry(connector) + "]");
        return quickCurrent;
    }

    public long energyRaw(int connector) throws Exception {
        Object sat = satellite(connector);
        Object value = sat.getClass().getMethod("getEnergy").invoke(sat);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    public String idTag(int connector) {
        try {
            Object sat = satellite(connector);
            try {
                Object value = sat.getClass().getMethod("getUser").invoke(sat);
                if (value != null) return String.valueOf(value).trim();
            } catch (NoSuchMethodException ignored) {}
            Class<?> t = sat.getClass();
            while (t != null) {
                try {
                    Field f = t.getDeclaredField("user");
                    f.setAccessible(true);
                    Object value = f.get(sat);
                    return value == null ? "" : String.valueOf(value).trim();
                } catch (NoSuchFieldException ignored) { t = t.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public boolean remoteStarted() throws Exception {
        return ((Boolean) centralClass.getMethod("isRemoteStarted").invoke(central())).booleanValue();
    }

    public synchronized void setConnectorLimitKw(int connector, int kw) throws Exception {
        if (connector < 1 || connector > 3) throw new IllegalArgumentException("connector must be 1..3");
        kw = clamp(kw, 0, connector == 3 ? 22 : 50);

        Object cm = central();
        Object conf = centralClass.getMethod("getConf").invoke(cm);
        if (conf == null) throw new IllegalStateException("Configuration unavailable");
        Object target = satellite(connector);
        Class<?> satType = target.getClass();

        int oldTarget = ((Number) satType.getMethod("getMaxPower").invoke(target)).intValue();
        boolean ccs = ((Boolean) satType.getMethod("isCCSCharge").invoke(target)).booleanValue();

        if (connector == 3) {
            configurationClass.getMethod("setMaxPowerAC", Integer.TYPE).invoke(conf, Integer.valueOf(kw));
        } else {
            setGlobalMaxPowerOn(conf, kw);
        }

        satType.getMethod("setMaxPower", Integer.TYPE).invoke(target, Integer.valueOf(kw));

        if (connector == 3) {
            bestEffortSetMaxPowerACField(conf, kw);
            bestEffortSetAcMaxPowerFixed(conf, kw);
        } else {
            bestEffortSetDcMaxPowerFixed(conf, kw);
        }

        boolean sentCcsStart = false;
        int quickCurrent = 0;
        if (ccs && connector == 2) {
            quickCurrent = refreshQuickChargeCurrentForPower(connector, kw);
            sentCcsStart = true;
        }

        System.out.println("[QC45] NativeLimit connector=" + connector
            + " requested=" + kw + "kW oldSatellite=" + oldTarget
            + " newSatellite=" + limitKw(connector)
            + " ccs=" + ccs + " sentCcsStart=" + sentCcsStart
            + " quickChargeMaxCurrent=" + quickCurrent + "A diagnostic-only"
            + " messageState=" + (ccs ? ccsMessageStateCurrent(connector) : "n/a")
            + (ccs ? " ccsRx[" + ccsModuleTelemetry(connector) + "]" : "")
            + " globalMaxPower=" + safeGlobalMaxPower()
            + " maxPowerAC=" + safeMaxPowerAC()
            + " dcFixed=" + safeDcFixed()
            + " acFixed=" + safeAcFixed());
    }

    public boolean sessionActive(int connector) throws Exception {
        Object sat = satellite(connector);
        try {
            Object tx = sat.getClass().getMethod("getActiveTransaction").invoke(sat);
            if (tx != null) return true;
        } catch (NoSuchMethodException ignored) {}
        return powerKw(connector) > 0 || idTag(connector).length() > 0;
    }

    public boolean isCcsCharge(int connector) throws Exception {
        Object sat = satellite(connector);
        Object value = sat.getClass().getMethod("isCCSCharge").invoke(sat);
        return value instanceof Boolean && ((Boolean)value).booleanValue();
    }

    public boolean emergencyStopPressed() throws Exception {
        boolean observed = false;
        Throwable lastFailure = null;
        for (int connector = 1; connector <= 3; connector++) {
            try {
                Object sat = satellite(connector);
                Field infoField = findSingleField(sat.getClass(), "infoState");
                if (infoField == null) continue;
                infoField.setAccessible(true);
                Object info = infoField.get(sat);
                if (info == null || findSingleField(info.getClass(), "epoPressed") == null) continue;
                observed = true;
                if (booleanField(info, "epoPressed", false)) return true;
            } catch (Throwable e) {
                lastFailure = e;
            }
        }
        if (!observed) {
            if (lastFailure instanceof Exception) throw (Exception)lastFailure;
            throw new IllegalStateException("E-STOP state unavailable");
        }
        return false;
    }

    public synchronized boolean isRemoteSession(int connector) {
        Integer key = Integer.valueOf(connector);
        if (!remoteConnectors.contains(key)) return false;
        try {
            if (!sessionActive(connector)) {
                remoteConnectors.remove(key);
                return false;
            }
        } catch (Throwable ignored) {
            // Keep the marker on an observation failure; clearing it could drop
            // authorization from a still-active remote session.
        }
        return true;
    }

    /** Resolve a backend transaction after a bridge/JVM restart when possible. */
    public int connectorForTransactionId(int transactionId) {
        if (transactionId < 0) return 0;
        for (int connector = 1; connector <= 3; connector++) {
            try {
                Object tx = activeTransaction(connector);
                if (tx != null && transactionIdMatches(tx, transactionId)) return connector;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    /** Safe fallback only when exactly one connector currently has a session. */
    public int soleActiveConnector() {
        int found = 0;
        for (int connector = 1; connector <= 3; connector++) {
            try {
                if (!sessionActive(connector)) continue;
                if (found != 0) return 0;
                found = connector;
            } catch (Throwable ignored) {
                return 0;
            }
        }
        return found;
    }

    public int globalMaxPower() throws Exception {
        return ((Number) configurationClass.getMethod("getMaxPower").invoke(configuration())).intValue();
    }

    public int dcMaxPowerFixed() throws Exception {
        Object conf = configuration();
        try {
            return ((Number) configurationClass.getMethod("getDCMaxPowerFixed").invoke(conf)).intValue();
        } catch (NoSuchMethodException e) {
            Field f = findField(configurationClass, "DCMaxPowerFixed", "dcMaxPowerFixed");
            if (f == null) throw e;
            f.setAccessible(true);
            return ((Number) f.get(conf)).intValue();
        }
    }

    public int maxPowerAC() throws Exception {
        Object conf = configuration();
        try {
            return ((Number) configurationClass.getMethod("getMaxPowerAC").invoke(conf)).intValue();
        } catch (NoSuchMethodException e) {
            Field f = findField(configurationClass, "maxPowerAC", "MaxPowerAC");
            if (f == null) throw e;
            f.setAccessible(true);
            return ((Number) f.get(conf)).intValue();
        }
    }

    public int acMaxPowerFixed() throws Exception {
        Object conf = configuration();
        try {
            return ((Number) configurationClass.getMethod("getACMaxPowerFixed").invoke(conf)).intValue();
        } catch (NoSuchMethodException e) {
            Field f = findField(configurationClass, "ACMaxPowerFixed", "acMaxPowerFixed");
            if (f == null) throw e;
            f.setAccessible(true);
            return ((Number) f.get(conf)).intValue();
        }
    }

    private void setGlobalMaxPowerOn(Object conf, int kw) throws Exception {
        Field field = findField(configurationClass, "maxPower", "MaxPower");
        if (field == null) throw new NoSuchFieldException("maxPower");
        field.setAccessible(true);
        if (field.getType() == Integer.TYPE) field.setInt(conf, kw);
        else field.set(conf, Integer.valueOf(kw));
    }

    private void bestEffortSetDcMaxPowerFixed(Object conf, int kw) {
        try {
            try {
                configurationClass.getMethod("setDCMaxPowerFixed", Integer.TYPE).invoke(conf, Integer.valueOf(kw));
                return;
            } catch (NoSuchMethodException ignored) {}
            Field f = findField(configurationClass, "DCMaxPowerFixed", "dcMaxPowerFixed");
            if (f != null) setNumberField(f, conf, kw);
        } catch (Throwable e) {
            System.err.println("[QC45] NativeLimit optional DCMaxPowerFixed failed: " + e);
        }
    }

    private void bestEffortSetMaxPowerACField(Object conf, int kw) {
        try {
            Field f = findField(configurationClass, "maxPowerAC", "MaxPowerAC");
            if (f != null) setNumberField(f, conf, kw);
        } catch (Throwable e) {
            System.err.println("[QC45] NativeLimit optional maxPowerAC field failed: " + e);
        }
    }

    private void bestEffortSetAcMaxPowerFixed(Object conf, int kw) {
        try {
            try {
                configurationClass.getMethod("setACMaxPowerFixed", Integer.TYPE).invoke(conf, Integer.valueOf(kw));
                return;
            } catch (NoSuchMethodException ignored) {}
            Field f = findField(configurationClass, "ACMaxPowerFixed", "acMaxPowerFixed");
            if (f != null) setNumberField(f, conf, kw);
        } catch (Throwable e) {
            System.err.println("[QC45] NativeLimit optional ACMaxPowerFixed failed: " + e);
        }
    }

    private static void setNumberField(Field f, Object target, int value) throws Exception {
        f.setAccessible(true);
        if (f.getType() == Integer.TYPE) f.setInt(target, value);
        else f.set(target, Integer.valueOf(value));
    }

    private String safeGlobalMaxPower() {
        try { return String.valueOf(globalMaxPower()); } catch (Throwable e) { return "n/a"; }
    }

    private String safeMaxPowerAC() {
        try { return String.valueOf(maxPowerAC()); } catch (Throwable e) { return "n/a"; }
    }

    private String safeDcFixed() {
        try { return String.valueOf(dcMaxPowerFixed()); } catch (Throwable e) { return "n/a"; }
    }

    private String safeAcFixed() {
        try { return String.valueOf(acMaxPowerFixed()); } catch (Throwable e) { return "n/a"; }
    }

    private static Field findField(Class<?> type, String name1, String name2) {
        Class<?> t = type;
        while (t != null) {
            try { return t.getDeclaredField(name1); } catch (NoSuchFieldException ignored) {}
            try { return t.getDeclaredField(name2); } catch (NoSuchFieldException ignored) {}
            t = t.getSuperclass();
        }
        return null;
    }

    public void remoteStart(String idTag, int connector) throws Exception {
        if (idTag == null || idTag.trim().length() == 0) throw new IllegalArgumentException("missing idTag");
        if (connector < 1 || connector > 3) throw new IllegalArgumentException("connector must be 1..3");
        idTag = idTag.trim();
        Object cm = central();
        Object listener = newNmsListener(cm);
        Method m = listener.getClass().getMethod("remoteStartCharge", String.class, String.class, Integer.TYPE);
        Object value = m.invoke(listener, "", idTag, Integer.valueOf(connector));
        boolean result = value instanceof Boolean && ((Boolean) value).booleanValue();
        boolean remote = remoteStarted();
        System.out.println("[QC45] Native RemoteStart connector=" + connector
            + " remoteStartCharge=" + result + " remoteStarted=" + remote);
        if (!result) throw new IllegalStateException("remoteStartCharge returned false");
        synchronized (this) { remoteConnectors.add(Integer.valueOf(connector)); }
    }

    public void remoteStop(int connector) throws Exception {
        if (connector < 1 || connector > 3) throw new IllegalArgumentException("connector must be 1..3");
        Object cm = central();
        Object target = satellite(connector);
        Object listener = newNmsListener(cm);
        Object satInfo = target.getClass().getMethod("getSatelliteInfoDB").invoke(target);
        if (satInfo == null) throw new IllegalStateException("SatelliteInfoDB unavailable");
        String satelliteUniqueId = String.valueOf(satInfo.getClass().getMethod("getStationId").invoke(satInfo));
        String transactionUniqueId = "";
        Object tx = target.getClass().getMethod("getActiveTransaction").invoke(target);
        if (tx != null) {
            Object txId = tx.getClass().getMethod("getUniqueId").invoke(tx);
            if (txId != null) transactionUniqueId = String.valueOf(txId);
        }
        Method abort = listener.getClass().getMethod("abortCharge", String.class, String.class, String.class);
        Object value = abort.invoke(listener, satelliteUniqueId, transactionUniqueId, "");
        boolean result = value instanceof Boolean && ((Boolean) value).booleanValue();
        if (!result) throw new IllegalStateException("abortCharge returned false");
        synchronized (this) { remoteConnectors.remove(Integer.valueOf(connector)); }
        boolean anyRemote = hasAnyRemoteSession();
        if (!anyRemote) setRemoteStartedFalse(cm);
        boolean remote = remoteStarted();
        System.out.println("[QC45] Native RemoteStop connector=" + connector
            + " satelliteUniqueId=" + satelliteUniqueId + " transactionUniqueId=" + transactionUniqueId
            + " abortCharge=" + result + " remoteStarted=" + remote);
    }

    private boolean hasAnyRemoteSession() {
        for (int connector = 1; connector <= 3; connector++) {
            if (isRemoteSession(connector)) return true;
        }
        return false;
    }

    private Object newNmsListener(Object cm) throws Exception {
        Class<?> nmsClass = Class.forName("pt.efacec.es.mobie.agent.nms.NmsListenerImpl");
        Constructor<?>[] constructors = nmsClass.getDeclaredConstructors();
        for (int i = 0; i < constructors.length; i++) {
            Constructor<?> c = constructors[i];
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(cm.getClass())) {
                c.setAccessible(true);
                return c.newInstance(cm);
            }
        }
        throw new IllegalStateException("NmsListenerImpl(CentralModule) constructor not found");
    }

    private void setRemoteStartedFalse(Object cm) throws Exception {
        try {
            centralClass.getMethod("setRemoteStarted", Boolean.TYPE).invoke(cm, Boolean.FALSE);
            return;
        } catch (NoSuchMethodException ignored) {}
        Class<?> t = cm.getClass();
        while (t != null) {
            try {
                Field f = t.getDeclaredField("remoteStarted");
                f.setAccessible(true);
                if (f.getType() == Boolean.TYPE) f.setBoolean(cm, false);
                else f.set(cm, Boolean.FALSE);
                return;
            } catch (NoSuchFieldException ignored) { t = t.getSuperclass(); }
        }
        throw new IllegalStateException("Unable to clear remoteStarted");
    }

    private Object activeTransaction(int connector) throws Exception {
        Object sat = satellite(connector);
        Method method = findMethod(sat.getClass(), "getActiveTransaction");
        return method == null ? null : method.invoke(sat);
    }

    private static boolean transactionIdMatches(Object tx, int expected) {
        String[] methods = new String[] {
            "getTransactionId", "getCentralSystemTransactionId",
            "getNmsTransactionId", "getId", "getUniqueId"
        };
        for (int i = 0; i < methods.length; i++) {
            try {
                Method method = findMethod(tx.getClass(), methods[i]);
                if (method == null || method.getParameterTypes().length != 0) continue;
                Integer value = integerValue(method.invoke(tx));
                if (value != null && value.intValue() == expected) return true;
            } catch (Throwable ignored) {}
        }

        Class<?> type = tx.getClass();
        while (type != null) {
            Field[] fields = type.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field field = fields[i];
                String name = field.getName().toLowerCase(java.util.Locale.US);
                if (name.indexOf("id") < 0
                        || (name.indexOf("transaction") < 0 && name.indexOf("unique") < 0)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Integer value = integerValue(field.get(tx));
                    if (value != null && value.intValue() == expected) return true;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number) return Integer.valueOf(((Number)value).intValue());
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.length() == 0) return null;
        try { return Integer.valueOf(Integer.parseInt(text)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Method findMethod(Class<?> type, String name) {
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
