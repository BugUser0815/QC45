package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

/** Owns OCPP bridge, SOAP endpoint, Modbus, load manager and grid failback. */
public final class Integration {
    private final ReflectionQC45 station;
    private final ModbusServer modbus;
    private final OcppBridgeClient ocppBridge;
    private final Ocpp15BridgeServer ocpp15Bridge;
    private final LoadManager loadManager;
    private final GridFailback failback;
    private final EvcsdLagMonitor lagMonitor;

    private Integration(ReflectionQC45 station, ModbusServer modbus,
                        OcppBridgeClient ocppBridge,
                        Ocpp15BridgeServer ocpp15Bridge,
                        LoadManager loadManager, GridFailback failback,
                        EvcsdLagMonitor lagMonitor) {
        this.station = station;
        this.modbus = modbus;
        this.ocppBridge = ocppBridge;
        this.ocpp15Bridge = ocpp15Bridge;
        this.loadManager = loadManager;
        this.failback = failback;
        this.lagMonitor = lagMonitor;
    }

    public static Integration start() throws Exception {
        Properties p = loadProperties();
        applyQcProtocolVersion(p);
        applyQuickChargeMaxCurrent(integer(p, "evcsd.quickChargeMaxCurrentA", 30));
        ReflectionQC45 station = new ReflectionQC45();
        ModbusServer modbus = new ModbusServer(station, integer(p, "modbus.port", 1502));

        OcppBridgeClient ocppBridge = new OcppBridgeClient(
            station,
            required(p, "ocpp.url"),
            required(p, "ocpp.username"),
            required(p, "ocpp.password"),
            p.getProperty("ocpp.tls.caFile", "").trim(),
            bool(p, "ocpp.tls.insecure", false));

        Ocpp15BridgeServer ocpp15Bridge = null;
        if (bool(p, "ocpp15.loopback.enabled", true)) {
            ocpp15Bridge = new Ocpp15BridgeServer(
                p.getProperty("ocpp15.loopback.bind", "127.0.0.1").trim(),
                integer(p, "ocpp15.loopback.port", 9000),
                p.getProperty("ocpp15.loopback.path", "/QC45").trim(),
                integer(p, "ocpp15.loopback.heartbeatInterval", 60),
                integer(p, "ocpp15.bridge.timeoutMs", 10000),
                ocppBridge,
                station);
        }

        boolean loadManagerEnabled = bool(p, "loadmanager.enabled", true);
        boolean failbackEnabled = bool(p, "failback.enabled", true);
        boolean lagMonitorEnabled = bool(p, "evcsd.lagmonitor.enabled", true);

        KsemClient meter = null;
        if (loadManagerEnabled || failbackEnabled) {
            meter = new KsemClient(
                p.getProperty("ksem.host", "10.0.0.70").trim(),
                integer(p, "ksem.port", 502),
                integer(p, "ksem.unit", 71),
                integer(p, "ksem.timeoutMs", 1000),
                decimal(p, "ksem.currentScale", 0.001d),
                bool(p, "ksem.legacyLowWord", true));
        }

        double failbackReduceA = decimal(p, "failback.reduceA", 34.0d);
        LoadManager loadManager = loadManagerEnabled ? new LoadManager(
            station, meter,
            decimal(p, "loadmanager.targetA", 32.0d),
            failbackEnabled ? failbackReduceA : Double.POSITIVE_INFINITY,
            decimal(p, "loadmanager.hysteresisA", 0.8d),
            integer(p, "loadmanager.minDcKw", 5),
            integer(p, "loadmanager.maxDcKw", 50),
            integer(p, "loadmanager.minAcKw", 5),
            integer(p, "loadmanager.maxAcKw", 22),
            integer(p, "loadmanager.rampUpKwPerLoop", 2),
            integer(p, "loadmanager.intervalMs", 1000)) : null;

        GridFailback failback = failbackEnabled ? new GridFailback(
            station, meter,
            failbackReduceA,
            integer(p, "failback.reduceDelayMs", 500),
            decimal(p, "failback.tripA", 35.0d),
            integer(p, "failback.tripDelayMs", 250),
            decimal(p, "failback.instantTripA", 38.0d),
            integer(p, "failback.reduceDcKw", 5),
            integer(p, "failback.reduceAcKw", 5),
            integer(p, "failback.intervalMs", 200),
            bool(p, "failback.tripOnMeterFailure", true),
            integer(p, "failback.meterFailureMs", 3000),
            integer(p, "failback.resetDelayMs", 60000)) : null;

        EvcsdLagMonitor lagMonitor = lagMonitorEnabled ? new EvcsdLagMonitor(
            integer(p, "evcsd.lagmonitor.intervalMs", 60000),
            integer(p, "evcsd.lagmonitor.warnMs", 250)) : null;

        Integration integration = new Integration(
            station, modbus, ocppBridge, ocpp15Bridge, loadManager, failback, lagMonitor);

        ocppBridge.start();
        if (ocpp15Bridge != null) ocpp15Bridge.start();
        modbus.start();
        if (loadManager != null) loadManager.start();
        if (failback != null) failback.start();
        if (lagMonitor != null) lagMonitor.start();
        System.out.println("[QC45] native integration started");
        return integration;
    }

    public void stop() {
        try { if (lagMonitor != null) lagMonitor.shutdown(); } catch (Throwable ignored) {}
        try { if (failback != null) failback.shutdown(); } catch (Throwable ignored) {}
        try { if (loadManager != null) loadManager.shutdown(); } catch (Throwable ignored) {}
        try { if (ocpp15Bridge != null) ocpp15Bridge.shutdown(); } catch (Throwable ignored) {}
        try { ocppBridge.shutdown(); } catch (Throwable ignored) {}
        try { modbus.shutdown(); } catch (Throwable ignored) {}
        System.out.println("[QC45] native integration stopped");
    }

    /**
     * Optional in-memory QC protocol override for testing. This deliberately does
     * not call Configuration.updateValue(), so the Derby configuration is left
     * untouched. QuickChargeSerializer caches the protocol version in its own
     * instance at CentralModule construction time, therefore both Configuration
     * and the already-created live serializer(s) must be patched.
     */
    private static void applyQcProtocolVersion(Properties p) throws Exception {
        Class<?> centralType = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object central = centralType.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable for QC protocol check");
        Object conf = centralType.getMethod("getConf").invoke(central);
        if (conf == null) throw new IllegalStateException("Configuration unavailable for QC protocol check");

        Method getter = conf.getClass().getMethod("getQCProtocolVersion");
        int original = ((Number)getter.invoke(conf)).intValue();
        String raw = p.getProperty("evcsd.qcProtocolVersion");
        if (raw == null || raw.trim().length() == 0) {
            System.out.println("[QC45] EVCSD QCProtocolVersion=" + original + " (native configuration; no override)");
            return;
        }

        int requested = Integer.parseInt(raw.trim());
        if (requested != 2 && requested != 3) {
            throw new IllegalArgumentException("evcsd.qcProtocolVersion must be 2 or 3");
        }

        Field versionField = findField(conf.getClass(), "QCProtocolVersion");
        if (versionField == null) throw new NoSuchFieldException("Configuration.QCProtocolVersion");
        versionField.setAccessible(true);
        if (versionField.getType() == Integer.TYPE) versionField.setInt(conf, requested);
        else versionField.set(conf, Integer.valueOf(requested));

        int serializers = patchQuickChargeSerializers(central, requested);
        int effective = ((Number)getter.invoke(conf)).intValue();
        if (effective != requested) {
            throw new IllegalStateException("QCProtocolVersion override did not stick: " + effective);
        }
        if (serializers <= 0) {
            throw new IllegalStateException("QCProtocolVersion changed in Configuration but no live QuickChargeSerializer was found");
        }

        System.out.println("[QC45] EVCSD QCProtocolVersion override " + original + " -> " + requested
            + " in-memory only; serializers=" + serializers + "; Derby unchanged");
    }

    /** Set the live CCS SatelliteModule quickChargeMaxCurrent in-memory only. */
    private static void applyQuickChargeMaxCurrent(int amps) throws Exception {
        if (amps < 1 || amps > 125) {
            throw new IllegalArgumentException("evcsd.quickChargeMaxCurrentA must be 1..125");
        }

        Class<?> centralType = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object central = centralType.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable for quickChargeMaxCurrent override");
        Object[] satellites = (Object[])centralType.getMethod("getSatellites").invoke(central);
        if (satellites == null) throw new IllegalStateException("Satellites unavailable for quickChargeMaxCurrent override");

        for (int i = 0; i < satellites.length; i++) {
            Object sat = satellites[i];
            if (sat == null) continue;
            int connector = ((Number)sat.getClass().getMethod("getSatelliteId").invoke(sat)).intValue();
            if (connector != 2) continue;

            Field currentField = findField(sat.getClass(), "quickChargeMaxCurrent");
            if (currentField == null) throw new NoSuchFieldException("SatelliteModule.quickChargeMaxCurrent");
            currentField.setAccessible(true);
            int old = ((Number)currentField.get(sat)).intValue();
            if (currentField.getType() == Integer.TYPE) currentField.setInt(sat, amps);
            else currentField.set(sat, Integer.valueOf(amps));
            int effective = ((Number)currentField.get(sat)).intValue();
            if (effective != amps) throw new IllegalStateException("quickChargeMaxCurrent override did not stick: " + effective);

            System.out.println("[QC45] EVCSD quickChargeMaxCurrent connector=2 " + old + "A -> " + amps
                + "A in-memory only; Derby unchanged");
            return;
        }

        throw new IllegalStateException("CCS connector 2 unavailable for quickChargeMaxCurrent override");
    }

    private static int patchQuickChargeSerializers(Object central, int version) throws Exception {
        Field commsField = findField(central.getClass(), "commsSatellite");
        if (commsField == null) throw new NoSuchFieldException("CentralModule.commsSatellite");
        commsField.setAccessible(true);
        Object comms = commsField.get(central);
        if (comms == null || !comms.getClass().isArray()) {
            throw new IllegalStateException("CentralModule.commsSatellite is unavailable");
        }

        int patched = 0;
        int length = Array.getLength(comms);
        for (int i = 0; i < length; i++) {
            Object channel = Array.get(comms, i);
            if (channel == null) continue;
            Field serializerField = findField(channel.getClass(), "pSerializer");
            if (serializerField == null) continue;
            serializerField.setAccessible(true);
            Object serializer = serializerField.get(channel);
            if (serializer == null || serializer.getClass().getName().indexOf("QuickChargeSerializer") < 0) continue;

            Field protocolField = findField(serializer.getClass(), "protocolVersion");
            if (protocolField == null) throw new NoSuchFieldException(serializer.getClass().getName() + ".protocolVersion");
            protocolField.setAccessible(true);
            if (protocolField.getType() == Integer.TYPE) protocolField.setInt(serializer, version);
            else protocolField.set(serializer, Integer.valueOf(version));
            patched++;
        }
        return patched;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null) {
            try { return t.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { t = t.getSuperclass(); }
        }
        return null;
    }

    private static Properties loadProperties() throws Exception {
        Properties p = new Properties();
        String explicit = System.getProperty("qc45.integration.config");
        File file = explicit == null || explicit.trim().length() == 0
            ? new File("/home/mobie/evcsd/qc45-integration.properties")
            : new File(explicit.trim());
        InputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); }
        return p;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().length() == 0)
            throw new IllegalArgumentException("Missing property: " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        return v == null || v.trim().length() == 0 ? fallback : Integer.parseInt(v.trim());
    }

    private static double decimal(Properties p, String key, double fallback) {
        String v = p.getProperty(key);
        return v == null || v.trim().length() == 0 ? fallback : Double.parseDouble(v.trim());
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null || v.trim().length() == 0 ? fallback : Boolean.parseBoolean(v.trim());
    }
}
