package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/** Owns the native OCPP and Modbus services inside the EVCSD JVM. */
public final class Integration {
    private final ReflectionQC45 station;
    private final ModbusServer modbus;
    private final OcppClient ocpp;

    private Integration(ReflectionQC45 station, ModbusServer modbus, OcppClient ocpp) {
        this.station = station;
        this.modbus = modbus;
        this.ocpp = ocpp;
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
        Integration integration = new Integration(station, modbus, ocpp);

        modbus.start();
        ocpp.start();
        System.out.println("[QC45] native integration started");
        return integration;
    }

    public void stop() {
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
}
