package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection adapter for read/control values; remote commands go through qc45api. */
public final class ReflectionQC45 {
    private final Class<?> centralClass;
    private final Class<?> configurationClass;
    private final Qc45ApiClient api;

    public ReflectionQC45() throws Exception {
        this("http://127.0.0.1", "/qc45api/start.jsp", "/qc45api/stop.jsp", 5000);
    }

    public ReflectionQC45(String apiBaseUrl, String startPath, String stopPath, int apiTimeoutMs) throws Exception {
        centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        configurationClass = Class.forName("pt.efacec.es.mobie.agent.Configuration");
        api = new Qc45ApiClient(apiBaseUrl, startPath, stopPath, apiTimeoutMs);
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

    /** Remote commands deliberately use the same local qc45api endpoints as the proven old bridge. */
    public void remoteStart(String idTag, int connector) throws Exception {
        api.remoteStart(idTag, connector);
    }

    public void remoteStop(int connector) throws Exception {
        api.remoteStop(connector);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
