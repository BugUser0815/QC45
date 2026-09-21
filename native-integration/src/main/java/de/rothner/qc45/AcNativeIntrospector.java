package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Set;

/** Read-only runtime inventory of the stock Efacec AC implementation. */
final class AcNativeIntrospector {
    private static final String CENTRAL =
        "pt.efacec.es.mobie.agent.statemachines.CentralModule";
    private static volatile boolean dumped;

    private AcNativeIntrospector() {}

    static synchronized void dumpOnce() {
        if (dumped) return;
        dumped = true;
        try {
            Class<?> centralClass = Class.forName(CENTRAL);
            Object central = centralClass.getMethod("getCurrentModule").invoke(null);
            if (central == null) throw new IllegalStateException("CentralModule unavailable");

            System.out.println("[QC45] AC-INTROSPECT begin read-only");
            dumpType("central", central.getClass());

            Object conf = centralClass.getMethod("getConf").invoke(central);
            if (conf != null) dumpType("configuration", conf.getClass());

            Object sats = centralClass.getMethod("getSatellites").invoke(central);
            if (sats instanceof Object[]) {
                Object[] values = (Object[])sats;
                for (int i = 0; i < values.length; i++) {
                    Object sat = values[i];
                    if (sat == null) continue;
                    int id = number(invokeNoArg(sat, "getSatelliteId"), -1);
                    if (id != 3) continue;
                    dumpType("ac-satellite", sat.getClass());
                    dumpInterestingObjectFields("ac-satellite", sat);
                    break;
                }
            }
            System.out.println("[QC45] AC-INTROSPECT end");
        } catch (Throwable e) {
            System.err.println("[QC45] AC-INTROSPECT failed: " + e);
        }
    }

    private static void dumpType(String label, Class<?> type) {
        Set<String> seen = new LinkedHashSet<String>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            System.out.println("[QC45] AC-INTROSPECT type " + label + " class="
                + current.getName() + " source=" + source(current));

            Method[] methods = current.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (!interesting(m.getName())) continue;
                String sig = methodSignature(m);
                if (seen.add(sig)) System.out.println("[QC45] AC-INTROSPECT method " + label + " " + sig);
            }

            Field[] fields = current.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (!interesting(f.getName()) && !interesting(f.getType().getName())) continue;
                String sig = Modifier.toString(f.getModifiers()) + " "
                    + f.getType().getName() + " " + f.getName();
                if (seen.add("F:" + sig)) System.out.println("[QC45] AC-INTROSPECT field " + label + " " + sig);
            }
            current = current.getSuperclass();
        }
    }

    private static void dumpInterestingObjectFields(String label, Object owner) {
        Class<?> current = owner.getClass();
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (!interestingObjectField(f)) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(owner);
                    if (value == null) continue;
                    Class<?> valueType = value.getClass();
                    if (!valueType.getName().startsWith("pt.efacec.")) continue;
                    dumpType(label + "." + f.getName(), valueType);
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
    }

    private static boolean interestingObjectField(Field f) {
        String name = f.getName().toLowerCase();
        return name.indexOf("state") >= 0 || name.indexOf("comm") >= 0
            || name.indexOf("message") >= 0 || name.indexOf("serial") >= 0
            || name.indexOf("protocol") >= 0 || name.indexOf("module") >= 0;
    }

    private static boolean interesting(String value) {
        if (value == null) return false;
        String s = value.toLowerCase();
        return s.indexOf("power") >= 0 || s.indexOf("energy") >= 0
            || s.indexOf("load") >= 0 || s.indexOf("current") >= 0
            || s.indexOf("charge") >= 0 || s.indexOf("limit") >= 0
            || s.indexOf("satellite") >= 0 || s.indexOf("message") >= 0
            || s.indexOf("pilot") >= 0 || s.indexOf("pwm") >= 0
            || s.indexOf("balance") >= 0 || s.indexOf("mobibus") >= 0;
    }

    private static String methodSignature(Method m) {
        StringBuilder out = new StringBuilder();
        out.append(Modifier.toString(m.getModifiers())).append(' ')
            .append(m.getReturnType().getName()).append(' ')
            .append(m.getDeclaringClass().getName()).append('.')
            .append(m.getName()).append('(');
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append(',');
            out.append(params[i].getName());
        }
        out.append(')');
        return out.toString();
    }

    private static String source(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            URL url = source == null ? null : source.getLocation();
            return url == null ? "unknown" : url.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Object invokeNoArg(Object owner, String method) {
        try { return owner.getClass().getMethod(method).invoke(owner); }
        catch (Throwable ignored) { return null; }
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number)value).intValue() : fallback;
    }
}
