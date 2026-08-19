package de.rothner.qc45;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection adapter around the live EVCSD objects. */
public final class ReflectionQC45 {
    private final Class<?> centralClass;
    private final Class<?> configurationClass;

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

    public int activeDcConnector() throws Exception {
        int c1 = powerKw(1), c2 = powerKw(2);
        if (c1 > 0 && c2 > 0) return c1 >= c2 ? 1 : 2;
        if (c1 > 0) return 1;
        if (c2 > 0) return 2;
        return 0;
    }

    public int stationPowerKw() throws Exception {
        return Math.max(powerKw(1), powerKw(2)) + powerKw(3);
    }

    /**
     * Native port of the proven currentlimit.jsp control path.
     *
     * Core writes are deliberately performed before optional firmware-specific
     * fixed fields. Missing optional fields can therefore never prevent the
     * actual SatelliteModule.setMaxPower()/CCS command from being sent.
     */
    public void setConnectorLimitKw(int connector, int kw) throws Exception {
        if (connector < 1 || connector > 3) throw new IllegalArgumentException("connector must be 1..3");
        kw = clamp(kw, 0, connector == 3 ? 22 : 50);

        Object cm = central();
        Object conf = centralClass.getMethod("getConf").invoke(cm);
        if (conf == null) throw new IllegalStateException("Configuration unavailable");
        Object target = satellite(connector);
        Class<?> satType = target.getClass();

        int oldTarget = ((Number) satType.getMethod("getMaxPower").invoke(target)).intValue();
        boolean ccs = ((Boolean) satType.getMethod("isCCSCharge").invoke(target)).booleanValue();

        // Proven currentlimit.jsp core path first.
        if (connector == 3) {
            configurationClass.getMethod("setMaxPowerAC", Integer.TYPE).invoke(conf, Integer.valueOf(kw));
        } else {
            setGlobalMaxPowerOn(conf, kw);
        }

        satType.getMethod("setMaxPower", Integer.TYPE).invoke(target, Integer.valueOf(kw));

        boolean sentCcsStart = false;
        if (ccs && connector == 2 && kw > 0) {
            satType.getMethod("sendCcsStart", Boolean.TYPE).invoke(target, Boolean.TRUE);
            sentCcsStart = true;
        }

        // Additional values requested for this firmware. These are best-effort:
        // they must never abort the proven core setter above.
        if (connector == 3) {
            bestEffortSetMaxPowerACField(conf, kw);
            bestEffortSetAcMaxPowerFixed(conf, kw);
        } else {
            bestEffortSetDcMaxPowerFixed(conf, kw);
        }

        System.out.println("[QC45] NativeLimit connector=" + connector
            + " requested=" + kw + "kW oldSatellite=" + oldTarget
            + " newSatellite=" + limitKw(connector)
            + " ccs=" + ccs + " sentCcsStart=" + sentCcsStart
            + " globalMaxPower=" + safeGlobalMaxPower()
            + " maxPowerAC=" + safeMaxPowerAC()
            + " dcFixed=" + safeDcFixed()
            + " acFixed=" + safeAcFixed());
    }

    public void setDcBudgetKw(int kw) throws Exception {
        kw = clamp(kw, 0, 50);
        int active = activeDcConnector();
        if (active == 0) {
            setConnectorLimitKw(1, kw);
            setConnectorLimitKw(2, kw);
        } else {
            setConnectorLimitKw(active, kw);
        }
    }

    public void setAcBudgetKw(int kw) throws Exception {
        setConnectorLimitKw(3, clamp(kw, 0, 22));
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
        idTag = idTag.trim();
        Object cm = central();
        Object listener = newNmsListener(cm);
        Method m = listener.getClass().getMethod("remoteStartCharge", String.class, String.class, Integer.TYPE);
        Object value = m.invoke(listener, "", idTag, Integer.valueOf(connector));
        boolean result = value instanceof Boolean && ((Boolean) value).booleanValue();
        boolean remote = remoteStarted();
        System.out.println("[QC45] Native RemoteStart connector=" + connector + " idTag=" + idTag
            + " remoteStartCharge=" + result + " remoteStarted=" + remote);
        if (!result) throw new IllegalStateException("remoteStartCharge returned false");
    }

    public void remoteStop(int connector) throws Exception {
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
        setRemoteStartedFalse(cm);
        boolean remote = remoteStarted();
        System.out.println("[QC45] Native RemoteStop connector=" + connector
            + " satelliteUniqueId=" + satelliteUniqueId + " transactionUniqueId=" + transactionUniqueId
            + " abortCharge=" + result + " remoteStarted=" + remote);
        if (!result) throw new IllegalStateException("abortCharge returned false");
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
