package de.rothner.qc45;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Forces the live EVCSD CCS communication stack to QC protocol V3.
 * Derby is deliberately left untouched.
 */
public final class CcsProtocolV3Enforcer {
    private static final int REQUIRED_VERSION = 3;

    private CcsProtocolV3Enforcer() {}

    public static void apply() throws Exception {
        Class<?> centralType = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object central = centralType.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");

        Object conf = centralType.getMethod("getConf").invoke(central);
        if (conf == null) throw new IllegalStateException("Configuration unavailable");

        Method getter = conf.getClass().getMethod("getQCProtocolVersion");
        int configBefore = ((Number)getter.invoke(conf)).intValue();
        Field configVersion = findField(conf.getClass(), "QCProtocolVersion");
        if (configVersion == null) throw new NoSuchFieldException("Configuration.QCProtocolVersion");
        setInt(configVersion, conf, REQUIRED_VERSION);
        int configAfter = ((Number)getter.invoke(conf)).intValue();

        PatchResult quick = patchQuickChargeSerializers(central);
        PatchResult master = patchMasterProtoSerializer(central);

        if (configAfter != REQUIRED_VERSION || quick.count == 0 || master.count == 0
                || quick.invalidAfter != 0 || master.invalidAfter != 0) {
            throw new IllegalStateException("CCS V3 enforcement failed: Configuration=" + configAfter
                + " QuickCharge=" + quick.summary() + " MasterProto=" + master.summary());
        }

        System.out.println("[QC45] CCS protocol enforcement Configuration=" + configAfter
            + " (was " + configBefore + ") QuickChargeSerializer=" + quick.summary()
            + " MasterProtoSerializer=" + master.summary()
            + " V2 disabled=true in-memory-only Derby-unchanged");
    }

    private static PatchResult patchQuickChargeSerializers(Object central) throws Exception {
        Field field = findField(central.getClass(), "commsSatellite");
        if (field == null) throw new NoSuchFieldException("CentralModule.commsSatellite");
        field.setAccessible(true);
        Object channels = field.get(central);
        if (channels == null || !channels.getClass().isArray()) {
            throw new IllegalStateException("CentralModule.commsSatellite unavailable");
        }

        PatchResult result = new PatchResult();
        int n = Array.getLength(channels);
        for (int i = 0; i < n; i++) {
            Object channel = Array.get(channels, i);
            if (channel == null) continue;
            Field serializerField = findField(channel.getClass(), "pSerializer");
            if (serializerField == null) continue;
            serializerField.setAccessible(true);
            Object serializer = serializerField.get(channel);
            if (serializer == null || serializer.getClass().getName().indexOf("QuickChargeSerializer") < 0) continue;
            patchSerializer(serializer, result);
        }
        return result;
    }

    private static PatchResult patchMasterProtoSerializer(Object central) throws Exception {
        Field field = findField(central.getClass(), "communications");
        if (field == null) throw new NoSuchFieldException("CentralModule.communications");
        field.setAccessible(true);
        Object communications = field.get(central);
        if (communications == null) throw new IllegalStateException("CentralModule.communications unavailable");

        Field serializerField = findField(communications.getClass(), "pSerializer");
        if (serializerField == null) throw new NoSuchFieldException(communications.getClass().getName() + ".pSerializer");
        serializerField.setAccessible(true);
        Object serializer = serializerField.get(communications);
        if (serializer == null || serializer.getClass().getName().indexOf("MasterProtoSerializer") < 0) {
            throw new IllegalStateException("physical serializer is not MasterProtoSerializer: "
                + (serializer == null ? "null" : serializer.getClass().getName()));
        }

        PatchResult result = new PatchResult();
        patchSerializer(serializer, result);
        return result;
    }

    private static void patchSerializer(Object serializer, PatchResult result) throws Exception {
        Field protocol = findField(serializer.getClass(), "protocolVersion");
        if (protocol == null) throw new NoSuchFieldException(serializer.getClass().getName() + ".protocolVersion");
        protocol.setAccessible(true);
        int before = ((Number)protocol.get(serializer)).intValue();
        setInt(protocol, serializer, REQUIRED_VERSION);
        int after = ((Number)protocol.get(serializer)).intValue();

        result.count++;
        if (before != REQUIRED_VERSION) result.changed++;
        if (after != REQUIRED_VERSION) result.invalidAfter++;
        result.lastBefore = before;
        result.lastAfter = after;
    }

    private static void setInt(Field field, Object target, int value) throws Exception {
        field.setAccessible(true);
        if (field.getType() == Integer.TYPE) field.setInt(target, value);
        else field.set(target, Integer.valueOf(value));
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }

    private static final class PatchResult {
        int count;
        int changed;
        int invalidAfter;
        int lastBefore = -1;
        int lastAfter = -1;

        String summary() {
            return "v3 count=" + count + " changed=" + changed
                + " last=" + lastBefore + "->" + lastAfter;
        }
    }
}
