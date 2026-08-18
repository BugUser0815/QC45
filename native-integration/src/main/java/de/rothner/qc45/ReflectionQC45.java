package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reflection-only adapter around the live EVCSD objects. */
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
            } catch (NoSuchMethodException ignored) {
            }

            Class<?> t = sat.getClass();
            while (t != null) {
                try {
                    Field f = t.getDeclaredField("user");
                    f.setAccessible(true);
                    Object value = f.get(sat);
                    return value == null ? "" : String.valueOf(value).trim();
                } catch (NoSuchFieldException ignored) {
                    t = t.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public boolean remoteStarted() throws Exception {
        return ((Boolean) centralClass.getMethod("isRemoteStarted").invoke(central())).booleanValue();
    }

    public int activeDcConnector() throws Exception {
        int c1 = powerKw(1);
        int c2 = powerKw(2);
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

    /** Start through EVCSD's NMS path when available, with a direct SatelliteModule fallback. */
    public void remoteStart(String idTag, int connector) throws Exception {
        boolean before = false;
        try { before = remoteStarted(); } catch (Throwable ignored) {}
        System.out.println("[QC45] EVCSD RemoteStart request connector=" + connector
            + " idTag=" + idTag + " remoteStarted(before)=" + before);

        boolean stateSetterFound = setRemoteStartedState(true);
        System.out.println("[QC45] EVCSD remoteStarted setter found=" + stateSetterFound);

        boolean invoked = false;
        try {
            Object listener = findNmsListener();
            Method m = listener.getClass().getMethod("remoteStartCharge", String.class, String.class, Integer.TYPE);
            m.invoke(listener, "", idTag, Integer.valueOf(connector));
            invoked = true;
            System.out.println("[QC45] EVCSD RemoteStart path=NmsListenerImpl.remoteStartCharge");
        } catch (Throwable e) {
            System.err.println("[QC45] NmsListener remoteStart unavailable: " + e);
        }

        if (!invoked) {
            invoked = directSatelliteStart(idTag, connector);
        }
        if (!invoked) throw new IllegalStateException("No usable EVCSD remote-start path found");

        boolean after = false;
        try { after = remoteStarted(); } catch (Throwable ignored) {}
        System.out.println("[QC45] EVCSD RemoteStart invoked connector=" + connector
            + " remoteStarted(after)=" + after);

        if (stateSetterFound && !after) {
            throw new IllegalStateException("EVCSD remoteStarted state did not become true");
        }
    }

    public void remoteStop(int connector) throws Exception {
        Object sat = satellite(connector);
        sat.getClass().getMethod("stopCharging").invoke(sat);
    }

    private boolean directSatelliteStart(String idTag, int connector) throws Exception {
        Object sat = satellite(connector);
        setSatelliteUser(sat, idTag);

        Method[] methods = sat.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (!"startCharging".equals(m.getName())) continue;
            Object[] args = startArgs(m.getParameterTypes(), idTag, connector);
            System.out.println("[QC45] Satellite start candidate: " + signature(m));
            if (args == null) continue;
            try {
                m.invoke(sat, args);
                System.out.println("[QC45] EVCSD RemoteStart path=SatelliteModule." + signature(m));
                return true;
            } catch (Throwable e) {
                System.err.println("[QC45] Satellite start candidate failed: " + signature(m) + " -> " + e);
            }
        }
        return false;
    }

    private static Object[] startArgs(Class<?>[] types, String idTag, int connector) {
        Object[] args = new Object[types.length];
        int stringIndex = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == String.class) {
                args[i] = stringIndex++ == 0 && types.length > 1 ? "" : idTag;
            } else if (t == Integer.TYPE || t == Integer.class) {
                args[i] = Integer.valueOf(connector);
            } else if (t == Boolean.TYPE || t == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else {
                return null;
            }
        }
        return args;
    }

    private static String signature(Method m) {
        StringBuilder b = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) b.append(',');
            b.append(p[i].getSimpleName());
        }
        return b.append(')').toString();
    }

    private static void setSatelliteUser(Object sat, String idTag) {
        String[] methods = new String[] { "setUser", "setIdTag", "setUsername" };
        for (int i = 0; i < methods.length; i++) {
            try {
                sat.getClass().getMethod(methods[i], String.class).invoke(sat, idTag);
                System.out.println("[QC45] Satellite " + methods[i] + " applied idTag=" + idTag);
                return;
            } catch (Throwable ignored) {
            }
        }
        String[] fields = new String[] { "user", "idTag", "username" };
        for (int i = 0; i < fields.length; i++) {
            Class<?> t = sat.getClass();
            while (t != null) {
                try {
                    Field f = t.getDeclaredField(fields[i]);
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        f.set(sat, idTag);
                        System.out.println("[QC45] Satellite field " + fields[i] + " applied idTag=" + idTag);
                        return;
                    }
                } catch (Throwable ignored) {
                }
                t = t.getSuperclass();
            }
        }
    }

    private boolean setRemoteStartedState(boolean value) throws Exception {
        Object cm = central();
        try {
            Method m = centralClass.getMethod("setRemoteStarted", Boolean.TYPE);
            m.invoke(cm, Boolean.valueOf(value));
            return true;
        } catch (NoSuchMethodException ignored) {
        }
        if (value) {
            try {
                Method m = centralClass.getMethod("setRemoteStarted");
                m.invoke(cm);
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        Class<?> t = cm.getClass();
        while (t != null) {
            try {
                Field f = t.getDeclaredField("remoteStarted");
                if (f.getType() == Boolean.TYPE || f.getType() == Boolean.class) {
                    f.setAccessible(true);
                    if (f.getType() == Boolean.TYPE) f.setBoolean(cm, value);
                    else f.set(cm, Boolean.valueOf(value));
                    return true;
                }
            } catch (NoSuchFieldException ignored) {
            }
            t = t.getSuperclass();
        }
        return false;
    }

    private Object findNmsListener() throws Exception {
        Class<?> nmsClass = Class.forName("pt.efacec.es.mobie.agent.nms.NmsListenerImpl");

        Object direct = findAssignableInObject(central(), nmsClass);
        if (direct != null) return direct;

        try {
            direct = findAssignableInObject(configuration(), nmsClass);
            if (direct != null) return direct;
        } catch (Throwable ignored) {
        }

        for (int connector = 1; connector <= 3; connector++) {
            try {
                direct = findAssignableInObject(satellite(connector), nmsClass);
                if (direct != null) return direct;
            } catch (Throwable ignored) {
            }
        }

        Class<?> t = nmsClass;
        while (t != null) {
            Field[] fields = t.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!nmsClass.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(null);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
            t = t.getSuperclass();
        }

        Method[] methods = nmsClass.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0 || !nmsClass.isAssignableFrom(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
                Object value = m.invoke(null);
                if (value != null) return value;
            } catch (Throwable ignored) {
            }
        }
        throw new IllegalStateException("NmsListenerImpl instance not found");
    }

    private static Object findAssignableInObject(Object owner, Class<?> wanted) {
        if (owner == null) return null;
        Class<?> t = owner.getClass();
        while (t != null) {
            Field[] fields = t.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (!wanted.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(owner);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
            t = t.getSuperclass();
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
