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

    private Object satellite(int connector) throws Exception {
        Object[] sats = (Object[]) centralClass.getMethod("getSatellites").invoke(central());
        if (sats == null) throw new IllegalStateException("Satellites unavailable");
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

    public void setDcBudgetKw(int kw) throws Exception {
        kw = clamp(kw, 1, 50);
        setGlobalMaxPower(kw);
        int active = activeDcConnector();
        if (active == 0) {
            setSatelliteLimit(1, kw, false);
            setSatelliteLimit(2, kw, false);
        } else {
            setSatelliteLimit(active, kw, active == 2);
        }
    }

    public void setAcBudgetKw(int kw) throws Exception {
        kw = clamp(kw, 1, 22);
        Object conf = configuration();
        configurationClass.getMethod("setMaxPowerAC", Integer.TYPE).invoke(conf, Integer.valueOf(kw));
        setSatelliteLimit(3, kw, false);
    }

    public int globalMaxPower() throws Exception {
        return ((Number) configurationClass.getMethod("getMaxPower").invoke(configuration())).intValue();
    }

    public int maxPowerAC() throws Exception {
        return ((Number) configurationClass.getMethod("getMaxPowerAC").invoke(configuration())).intValue();
    }

    private void setGlobalMaxPower(int kw) throws Exception {
        Object conf = configuration();
        Field field = configurationClass.getDeclaredField("maxPower");
        field.setAccessible(true);
        field.setInt(conf, kw);
    }

    private void setSatelliteLimit(int connector, int kw, boolean pushCcs) throws Exception {
        Object sat = satellite(connector);
        Class<?> type = sat.getClass();
        type.getMethod("setMaxPower", Integer.TYPE).invoke(sat, Integer.valueOf(kw));
        if (pushCcs) {
            boolean ccs = ((Boolean) type.getMethod("isCCSCharge").invoke(sat)).booleanValue();
            if (ccs) type.getMethod("sendCcsStart", Boolean.TYPE).invoke(sat, Boolean.TRUE);
        }
    }

    /** Exact native port of the proven qc45api/start.jsp logic. */
    public void remoteStart(String idTag, int connector) throws Exception {
        if (idTag == null || idTag.trim().length() == 0) throw new IllegalArgumentException("missing idTag");
        idTag = idTag.trim();
        Object cm = central();
        Object listener = newNmsListener(cm);

        Method m = listener.getClass().getMethod("remoteStartCharge", String.class, String.class, Integer.TYPE);
        Object value = m.invoke(listener, "", idTag, Integer.valueOf(connector));
        boolean result = value instanceof Boolean && ((Boolean) value).booleanValue();
        boolean remote = remoteStarted();

        System.out.println("[QC45] Native RemoteStart connector=" + connector
            + " idTag=" + idTag + " remoteStartCharge=" + result + " remoteStarted=" + remote);

        if (!result) throw new IllegalStateException("remoteStartCharge returned false");
    }

    /** Exact native port of the proven qc45api/stop.jsp logic. */
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
            + " satelliteUniqueId=" + satelliteUniqueId
            + " transactionUniqueId=" + transactionUniqueId
            + " abortCharge=" + result
            + " remoteStarted=" + remote);

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
