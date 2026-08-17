package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/** Owns the native OCPP, OCPP15 loopback, Modbus, load manager and grid failback services inside the EVCSD JVM. */
public final class Integration {
    private final ReflectionQC45 station;
    private final ModbusServer modbus;
    private final OcppClient ocpp;
    private final Ocpp15LoopbackServer ocpp15Loopback;
    private final LoadManager loadManager;
    private final GridFailback failback;

    private Integration(ReflectionQC45 station, ModbusServer modbus, OcppClient ocpp,
                        Ocpp15LoopbackServer ocpp15Loopback,
                        LoadManager loadManager, GridFailback failback) {
        this.station = station;
        this.modbus = modbus;
        this.ocpp = ocpp;
        this.ocpp15Loopback = ocpp15Loopback;
        this.loadManager = loadManager;
        this.failback = failback;
    }

    public static Integration start() throws Exception {
        Properties p = loadProperties();
        ReflectionQC45 station = new ReflectionQC45();

        int modbusPort = integer(p, "modbus.port", 1502);
        ModbusServer modbus = new ModbusServer(station, modbusPort);

        String url = required(p, "ocpp.url");
        String user = required(p, "ocpp.username");
        String password = required(p, "ocpp.password");
        String serial = p.getProperty("ocpp.serial", "QC45").trim();
        String defaultIdTag = p.getProperty("ocpp.defaultIdTag", "LOCAL").trim();

        String caFile = p.getProperty("ocpp.tls.caFile", "").trim();
        boolean insecureTls = bool(p, "ocpp.tls.insecure", false);
        TlsSupport.configure(caFile, insecureTls);

        OcppClient ocpp = new OcppClient(station, url, user, password, serial, defaultIdTag);

        Ocpp15LoopbackServer ocpp15Loopback = null;
        if (bool(p, "ocpp15.loopback.enabled", true)) {
            ocpp15Loopback = new Ocpp15LoopbackServer(
                p.getProperty("ocpp15.loopback.bind", "127.0.0.1").trim(),
                integer(p, "ocpp15.loopback.port", 9000),
                p.getProperty("ocpp15.loopback.path", "/QC45").trim(),
                integer(p, "ocpp15.loopback.heartbeatInterval", 60)
            );
        }

        boolean loadManagerEnabled = bool(p, "loadmanager.enabled", true);
        boolean failbackEnabled = bool(p, "failback.enabled", true);
        KsemClient meter = null;

        if (loadManagerEnabled || failbackEnabled) {
            String ksemHost = p.getProperty("ksem.host", "10.0.0.70").trim();
            int ksemPort = integer(p, "ksem.port", 502);
            int ksemUnit = integer(p, "ksem.unit", 71);
            int ksemTimeoutMs = integer(p, "ksem.timeoutMs", 1000);
            double ksemScale = decimal(p, "ksem.currentScale", 0.001d);
            boolean legacyLowWord = bool(p, "ksem.legacyLowWord", true);
            meter = new KsemClient(ksemHost, ksemPort, ksemUnit, ksemTimeoutMs,
                ksemScale, legacyLowWord);
        }

        double failbackReduceA = decimal(p, "failback.reduceA", 34.0d);
        double loadManagerGuardA = failbackEnabled ? failbackReduceA : Double.POSITIVE_INFINITY;

        LoadManager loadManager = null;
        if (loadManagerEnabled) {
            loadManager = new LoadManager(
                station,
                meter,
                decimal(p, "loadmanager.targetA", 32.0d),
                loadManagerGuardA,
                decimal(p, "loadmanager.hysteresisA", 0.8d),
                integer(p, "loadmanager.minDcKw", 5),
                integer(p, "loadmanager.maxDcKw", 50),
                integer(p, "loadmanager.minAcKw", 5),
                integer(p, "loadmanager.maxAcKw", 22),
                integer(p, "loadmanager.rampUpKwPerLoop", 2),
                integer(p, "loadmanager.intervalMs", 1000)
            );
        }

        GridFailback failback = null;
        if (failbackEnabled) {
            failback = new GridFailback(
                station,
                meter,
                failbackReduceA,
                integer(p, "failback.reduceDelayMs", 500),
                decimal(p, "failback.tripA", 35.0d),
                integer(p, "failback.tripDelayMs", 250),
                decimal(p, "failback.instantTripA", 38.0d),
                integer(p, "failback.reduceDcKw", 5),
                integer(p, "failback.reduceAcKw", 5),
                integer(p, "failback.intervalMs", 200),
                bool(p, "failback.tripOnMeterFailure", true),
                integer(p, "failback.meterFailureMs", 3000)
            );
        }

        Integration integration = new Integration(station, modbus, ocpp, ocpp15Loopback, loadManager, failback);

        if (ocpp15Loopback != null) ocpp15Loopback.start();
        modbus.start();
        ocpp.start();
        if (loadManager != null) loadManager.start();
        if (failback != null) failback.start();
        System.out.println("[QC45] native integration started");
        return integration;
    }

    public void stop() {
        try { if (failback != null) failback.shutdown(); } catch (Throwable ignored) {}
        try { if (loadManager != null) loadManager.shutdown(); } catch (Throwable ignored) {}
        try { ocpp.shutdown(); } catch (Throwable ignored) {}
        try { modbus.shutdown(); } catch (Throwable ignored) {}
        try { if (ocpp15Loopback != null) ocpp15Loopback.shutdown(); } catch (Throwable ignored) {}
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
        if (value == null || value.trim().length() == 0) throw new IllegalArgumentException("Missing property: " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().length() == 0 ? fallback : Integer.parseInt(value.trim());
    }

    private static double decimal(Properties p, String key, double fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().length() == 0 ? fallback : Double.parseDouble(value.trim());
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().length() == 0 ? fallback : Boolean.parseBoolean(value.trim());
    }
}
