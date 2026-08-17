package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/** Owns the native OCPP, Modbus and grid failback services inside the EVCSD JVM. */
public final class Integration {
    private final ReflectionQC45 station;
    private final ModbusServer modbus;
    private final OcppClient ocpp;
    private final GridFailback failback;

    private Integration(ReflectionQC45 station, ModbusServer modbus, OcppClient ocpp, GridFailback failback) {
        this.station = station;
        this.modbus = modbus;
        this.ocpp = ocpp;
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
        OcppClient ocpp = new OcppClient(station, url, user, password, serial, defaultIdTag);

        GridFailback failback = null;
        if (bool(p, "failback.enabled", true)) {
            String ksemHost = p.getProperty("ksem.host", "10.0.0.70").trim();
            int ksemPort = integer(p, "ksem.port", 502);
            int ksemUnit = integer(p, "ksem.unit", 71);
            int ksemTimeoutMs = integer(p, "ksem.timeoutMs", 1000);
            double ksemScale = decimal(p, "ksem.currentScale", 0.001d);
            boolean legacyLowWord = bool(p, "ksem.legacyLowWord", true);

            KsemClient meter = new KsemClient(ksemHost, ksemPort, ksemUnit, ksemTimeoutMs,
                ksemScale, legacyLowWord);

            failback = new GridFailback(
                station,
                meter,
                decimal(p, "failback.reduceA", 34.0d),
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

        Integration integration = new Integration(station, modbus, ocpp, failback);

        modbus.start();
        ocpp.start();
        if (failback != null) failback.start();
        System.out.println("[QC45] native integration started");
        return integration;
    }

    public void stop() {
        try { if (failback != null) failback.shutdown(); } catch (Throwable ignored) {}
        try { ocpp.shutdown(); } catch (Throwable ignored) {}
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
