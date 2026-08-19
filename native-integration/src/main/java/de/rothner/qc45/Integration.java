package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/** Owns OCPP bridge, legacy SOAP endpoint, Modbus, load manager and grid failback. */
public final class Integration {
    private final ReflectionQC45 station;
    private final ModbusServer modbus;
    private final OcppBridgeClient ocppBridge;
    private final Ocpp15LegacyBridgeServer ocpp15Bridge;
    private final LoadManager loadManager;
    private final GridFailback failback;

    private Integration(ReflectionQC45 station, ModbusServer modbus,
                        OcppBridgeClient ocppBridge,
                        Ocpp15LegacyBridgeServer ocpp15Bridge,
                        LoadManager loadManager, GridFailback failback) {
        this.station = station;
        this.modbus = modbus;
        this.ocppBridge = ocppBridge;
        this.ocpp15Bridge = ocpp15Bridge;
        this.loadManager = loadManager;
        this.failback = failback;
    }

    public static Integration start() throws Exception {
        Properties p = loadProperties();
        ReflectionQC45 station = new ReflectionQC45();
        ModbusServer modbus = new ModbusServer(station, integer(p, "modbus.port", 1502));

        OcppBridgeClient ocppBridge = new OcppBridgeClient(
            station,
            required(p, "ocpp.url"),
            required(p, "ocpp.username"),
            required(p, "ocpp.password"),
            p.getProperty("ocpp.tls.caFile", "").trim(),
            bool(p, "ocpp.tls.insecure", false));

        Ocpp15LegacyBridgeServer ocpp15Bridge = null;
        if (bool(p, "ocpp15.loopback.enabled", true)) {
            ocpp15Bridge = new Ocpp15LegacyBridgeServer(
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
            integer(p, "failback.meterFailureMs", 3000)) : null;

        Integration integration = new Integration(
            station, modbus, ocppBridge, ocpp15Bridge, loadManager, failback);

        ocppBridge.start();
        if (ocpp15Bridge != null) ocpp15Bridge.start();
        modbus.start();
        if (loadManager != null) loadManager.start();
        if (failback != null) failback.start();
        System.out.println("[QC45] native integration started");
        return integration;
    }

    public void stop() {
        try { if (failback != null) failback.shutdown(); } catch (Throwable ignored) {}
        try { if (loadManager != null) loadManager.shutdown(); } catch (Throwable ignored) {}
        try { if (ocpp15Bridge != null) ocpp15Bridge.shutdown(); } catch (Throwable ignored) {}
        try { ocppBridge.shutdown(); } catch (Throwable ignored) {}
        try { modbus.shutdown(); } catch (Throwable ignored) {}
        System.out.println("[QC45] native integration stopped");
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
