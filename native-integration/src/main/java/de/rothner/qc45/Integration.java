package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/** Owns the safety controller and all optional QC45 integration services. */
public final class Integration {
    private static final int DEFAULT_MIN_DC_KW = 5;
    private static final int DEFAULT_MAX_DC_KW = 50;
    private static final int DEFAULT_MIN_AC_KW = 5;
    private static final int DEFAULT_MAX_AC_KW = 43;
    private static final double MAX_GRID_LIMIT_A = 35.0d;
    private static final double MAX_CONTROL_TARGET_A = 32.0d;
    private static final double MAX_REDUCE_THRESHOLD_A = 34.0d;
    private static final double SLS_E_INSTANT_TRIP_A = GridFailback.SLS_E_INSTANT_A;
    private static final double MIN_FAILBACK_GAP_A = 0.1d;
    private static final double KSEM_CURRENT_SCALE = 0.001d;
    private static final int MAX_KSEM_TIMEOUT_MS = 1000;
    private static final int MAX_FAILBACK_INTERVAL_MS = 100;
    private static final int MAX_LOADMANAGER_INTERVAL_MS = 1000;
    private static final int MAX_RAMP_UP_KW_PER_SECOND = 2;

    private final ChargingLimitCoordinator limits;
    private final ChargingLimitGuard limitGuard;
    private final ModbusServer modbus;
    private final OcppBridgeClient ocppBridge;
    private final Ocpp15BridgeServer ocpp15Bridge;
    private final LoadManager loadManager;
    private final GridFailback failback;
    private final KsemClient meter;
    private final EvcsdLagMonitor lagMonitor;
    private final RemoteStartAuthorizationFix remoteStartAuthorizationFix;

    private Integration(ChargingLimitCoordinator limits,
                        ChargingLimitGuard limitGuard,
                        ModbusServer modbus,
                        OcppBridgeClient ocppBridge,
                        Ocpp15BridgeServer ocpp15Bridge,
                        LoadManager loadManager,
                        GridFailback failback,
                        KsemClient meter,
                        EvcsdLagMonitor lagMonitor,
                        RemoteStartAuthorizationFix remoteStartAuthorizationFix) {
        this.limits = limits;
        this.limitGuard = limitGuard;
        this.modbus = modbus;
        this.ocppBridge = ocppBridge;
        this.ocpp15Bridge = ocpp15Bridge;
        this.loadManager = loadManager;
        this.failback = failback;
        this.meter = meter;
        this.lagMonitor = lagMonitor;
        this.remoteStartAuthorizationFix = remoteStartAuthorizationFix;
    }

    public static Integration start() throws Exception {
        ReflectionQC45 station = new ReflectionQC45();

        // Establish a persistent fail-closed state before reading configuration
        // or touching OCPP/CCS helpers. A malformed or missing properties file
        // therefore cannot leave legacy EVCSD limits active.
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            station, DEFAULT_MIN_DC_KW, DEFAULT_MAX_DC_KW,
            DEFAULT_MIN_AC_KW, DEFAULT_MAX_AC_KW);
        try { limits.initializeSafeZero(); }
        catch (Throwable e) { System.err.println("[QC45] initial safety zero failed; guard will retry: " + e); }
        ChargingLimitGuard limitGuard = new ChargingLimitGuard(station, limits, 250);
        limitGuard.start();

        Properties p;
        try {
            p = loadProperties();
        } catch (Throwable e) {
            return degraded(limits, limitGuard, "configuration load failed", e);
        }

        int minDcKw;
        int maxDcKw;
        int minAcKw;
        int maxAcKw;
        try {
            minDcKw = positiveInt(p, "loadmanager.minDcKw", DEFAULT_MIN_DC_KW);
            maxDcKw = positiveInt(p, "loadmanager.maxDcKw", DEFAULT_MAX_DC_KW);
            minAcKw = positiveInt(p, "loadmanager.minAcKw", DEFAULT_MIN_AC_KW);
            maxAcKw = positiveInt(p, "loadmanager.maxAcKw", DEFAULT_MAX_AC_KW);
            if (maxDcKw < minDcKw || maxDcKw > 50
                    || maxAcKw < minAcKw || maxAcKw > 43) {
                throw new IllegalArgumentException("connector limits exceed QC45 hardware or minimum");
            }
        } catch (Throwable e) {
            return degraded(limits, limitGuard, "connector limit validation failed", e);
        }

        if (minDcKw != DEFAULT_MIN_DC_KW || maxDcKw != DEFAULT_MAX_DC_KW
                || minAcKw != DEFAULT_MIN_AC_KW || maxAcKw != DEFAULT_MAX_AC_KW) {
            limitGuard.shutdown();
            joinQuietly(limitGuard, 1000L);
            limits = new ChargingLimitCoordinator(station, minDcKw, maxDcKw, minAcKw, maxAcKw);
            try { limits.initializeSafeZero(); }
            catch (Throwable e) { System.err.println("[QC45] configured safety zero failed; guard will retry: " + e); }
            limitGuard = new ChargingLimitGuard(station, limits, 250);
            limitGuard.start();
        }

        boolean ccsV3Available = false;
        try {
            CcsProtocolV3Enforcer.apply();
            ccsV3Available = true;
        } catch (Throwable e) {
            System.err.println("[QC45] CCS V3 enforcement failed; connector 2 remains at 0kW: " + e);
            e.printStackTrace();
        }
        try { limits.setCcsAvailable(ccsV3Available); }
        catch (Throwable e) { System.err.println("[QC45] CCS availability safety update failed: " + e); }

        LoadManager loadManager = null;
        GridFailback failback = null;
        KsemClient meter = null;
        boolean safetyReady = true;
        try {
            boolean loadManagerEnabled = bool(p, "loadmanager.enabled", true);
            boolean failbackEnabled = bool(p, "failback.enabled", true);
            double configuredFailbackReduceA = positiveDecimal(p, "failback.reduceA", 34.0d);
            double configuredFailbackTripA = positiveDecimal(p, "failback.tripA", 35.0d);
            double configuredFailbackInstantTripA = positiveDecimal(p, "failback.instantTripA",
                SLS_E_INSTANT_TRIP_A);
            double[] failbackThresholds = conservativeFailbackThresholds(
                configuredFailbackReduceA, configuredFailbackTripA,
                configuredFailbackInstantTripA);
            double failbackReduceA = failbackThresholds[0];
            double failbackTripA = failbackThresholds[1];
            double failbackInstantTripA = failbackThresholds[2];
            if (failbackReduceA != configuredFailbackReduceA
                    || failbackTripA != configuredFailbackTripA
                    || failbackInstantTripA != configuredFailbackInstantTripA) {
                System.err.println("[QC45] failback threshold compatibility migration: configured "
                    + thresholdValues(configuredFailbackReduceA, configuredFailbackTripA,
                        configuredFailbackInstantTripA)
                    + " -> effective " + thresholdValues(failbackReduceA, failbackTripA,
                        failbackInstantTripA));
            }
            long configuredFailbackReduceDelayMs = nonNegativeInt(p, "failback.reduceDelayMs", 500);
            long configuredFailbackTripDelayMs = nonNegativeInt(p, "failback.tripDelayMs", 250);
            int configuredFailbackIntervalMs = positiveInt(p, "failback.intervalMs", 100);
            long[] failbackTiming = conservativeFailbackTiming(
                configuredFailbackReduceDelayMs, configuredFailbackTripDelayMs,
                configuredFailbackIntervalMs);
            long failbackReduceDelayMs = failbackTiming[0];
            long failbackTripDelayMs = failbackTiming[1];
            int failbackIntervalMs = (int)failbackTiming[2];
            if (failbackReduceDelayMs != configuredFailbackReduceDelayMs
                    || failbackTripDelayMs != configuredFailbackTripDelayMs
                    || failbackIntervalMs != configuredFailbackIntervalMs) {
                System.err.println("[QC45] failback timing compatibility migration: configured "
                    + timingValues(configuredFailbackReduceDelayMs,
                        configuredFailbackTripDelayMs, configuredFailbackIntervalMs)
                    + " -> effective " + timingValues(failbackReduceDelayMs,
                        failbackTripDelayMs, failbackIntervalMs));
            }
            int reduceDcKw = nonNegativeInt(p, "failback.reduceDcKw", minDcKw);
            int reduceAcKw = nonNegativeInt(p, "failback.reduceAcKw", minAcKw);
            long resetDelayMs = hardTripResetDelay(p);
            validateReductionLimit("failback.reduceDcKw", reduceDcKw, minDcKw);
            validateReductionLimit("failback.reduceAcKw", reduceAcKw, minAcKw);

            String ksemHost = required(p, "ksem.host");
            int ksemPort = port(p, "ksem.port", 502);
            int ksemUnit = rangedInt(p, "ksem.unit", 71, 0, 255);
            int ksemTimeoutMs = positiveInt(p, "ksem.timeoutMs", 1000);
            double ksemScale = positiveDecimal(p, "ksem.currentScale", 0.001d);
            String wordOrder = p.getProperty("ksem.wordOrder", "HIGH_LOW").trim();
            if (p.getProperty("ksem.legacyLowWord") != null) {
                System.err.println("[QC45] ksem.legacyLowWord is obsolete and ignored; full 32-bit "
                    + wordOrder + " decoding prevents current rollover");
            }
            if (ksemTimeoutMs > MAX_KSEM_TIMEOUT_MS) {
                throw new IllegalArgumentException("ksem.timeoutMs must be <= " + MAX_KSEM_TIMEOUT_MS);
            }
            if (Math.abs(ksemScale - KSEM_CURRENT_SCALE) > 0.000000001d) {
                throw new IllegalArgumentException("ksem.currentScale must be 0.001 for KOSTAL KSEM current registers");
            }

            if (failbackEnabled || loadManagerEnabled) {
                // One serialized, persistent Modbus channel is shared by both
                // safety consumers. Separate clients used to open competing
                // TCP connections up to eleven times per second and could
                // make the KSEM alternate between recovery and connect timeout.
                meter = new KsemClient(
                    ksemHost, ksemPort, ksemUnit, ksemTimeoutMs, ksemScale, wordOrder);
            }

            if (failbackEnabled) {
                failback = new GridFailback(
                    station, meter, limits,
                    failbackReduceA, failbackReduceDelayMs,
                    failbackTripA, failbackTripDelayMs, failbackInstantTripA,
                    reduceDcKw, reduceAcKw, failbackIntervalMs,
                    resetDelayMs);
                // Close the scheduling gap before the failback thread performs
                // its first read and establishes its own startup pause.
                limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);
            }

            if (loadManagerEnabled) {
                double configuredGridLimitA = positiveDecimal(p, "loadmanager.gridLimitA", 35.0d);
                if (configuredGridLimitA > MAX_GRID_LIMIT_A) {
                    throw new IllegalArgumentException("loadmanager.gridLimitA must not exceed 35A");
                }
                double commandCeilingA = failbackEnabled
                    ? Math.min(configuredGridLimitA, failbackReduceA)
                    : configuredGridLimitA;
                double targetA = positiveDecimal(p, "loadmanager.targetA", 32.0d);
                if (targetA > MAX_CONTROL_TARGET_A) {
                    throw new IllegalArgumentException("loadmanager.targetA must not exceed 32A");
                }
                double hysteresisA = nonNegativeDecimal(p, "loadmanager.hysteresisA", 0.8d);
                if (targetA + hysteresisA >= commandCeilingA) {
                    throw new IllegalArgumentException("loadmanager target plus hysteresis must remain below command ceiling");
                }
                int rampKw = positiveInt(p, "loadmanager.rampUpKwPerLoop", 2);
                int intervalMs = positiveInt(p, "loadmanager.intervalMs", 1000);
                long demandStableMs = nonNegativeInt(p, "loadmanager.demandStableMs", 5000);
                int demandReserveKw = positiveInt(p, "loadmanager.demandReserveKw", 2);
                if (intervalMs > MAX_LOADMANAGER_INTERVAL_MS
                        || (long)rampKw * 1000L
                            > (long)MAX_RAMP_UP_KW_PER_SECOND * (long)intervalMs) {
                    throw new IllegalArgumentException("LoadManager must poll within 1000ms and ramp at no more than 2kW/s");
                }
                loadManager = new LoadManager(
                    station, meter, limits,
                    targetA, commandCeilingA, hysteresisA,
                    minDcKw, maxDcKw, minAcKw, maxAcKw,
                    rampKw, intervalMs, demandStableMs, demandReserveKw);
            } else {
                System.err.println("[QC45] LoadManager disabled: startup safety block remains active; charging stays at 0kW");
            }
        } catch (Throwable e) {
            // OCPP and the local diagnostic interfaces must remain available
            // when only the load-safety configuration is invalid. The
            // coordinator's startup blocker stays latched, so neither OCPP,
            // Modbus nor a legacy EVCSD path can release charging power.
            safetyReady = false;
            loadManager = null;
            failback = null;
            if (meter != null) meter.close();
            meter = null;
            enterDegradedSafety(limits, "safety configuration failed", e);
        }

        if (safetyReady) {
            if (failback != null) failback.start();
            if (loadManager != null) loadManager.start();
        }

        RemoteStartAuthorizationFix authFix = null;
        try { authFix = RemoteStartAuthorizationFix.start(station); }
        catch (Throwable e) { System.err.println("[QC45] RemoteStart authorization helper disabled: " + e); }

        OcppBridgeClient ocppBridge = null;
        Ocpp15BridgeServer ocpp15Bridge = null;
        if (optionalEnabled(p, "ocpp.enabled", true)) {
            try {
                ocppBridge = new OcppBridgeClient(
                    station,
                    required(p, "ocpp.url"),
                    required(p, "ocpp.username"),
                    required(p, "ocpp.password"),
                    p.getProperty("ocpp.tls.caFile", "").trim(),
                    bool(p, "ocpp.tls.insecure", false),
                    p.getProperty("ocpp.transactionMapFile",
                        "/home/mobie/evcsd/qc45-active-transactions.properties").trim());
                ocppBridge.start();

                if (bool(p, "ocpp15.loopback.enabled", true)) {
                    ocpp15Bridge = new Ocpp15BridgeServer(
                        p.getProperty("ocpp15.loopback.bind", "127.0.0.1").trim(),
                        port(p, "ocpp15.loopback.port", 9000),
                        p.getProperty("ocpp15.loopback.path", "/QC45").trim(),
                        positiveInt(p, "ocpp15.loopback.heartbeatInterval", 60),
                        positiveInt(p, "ocpp15.bridge.timeoutMs", 10000),
                        ocppBridge, station);
                    ocpp15Bridge.start();
                }
            } catch (Throwable e) {
                System.err.println("[QC45] OCPP integration disabled after startup error; grid safety remains active: " + e);
                e.printStackTrace();
                if (ocpp15Bridge != null) try { ocpp15Bridge.shutdown(); } catch (Throwable ignored) {}
                if (ocppBridge != null) try { ocppBridge.shutdown(); } catch (Throwable ignored) {}
                ocpp15Bridge = null;
                ocppBridge = null;
            }
        }

        ModbusServer modbus = null;
        if (optionalEnabled(p, "modbus.enabled", true)) {
            try {
                modbus = new ModbusServer(
                    station, limits,
                    p.getProperty("modbus.bindAddress", "0.0.0.0").trim(),
                    port(p, "modbus.port", 1502),
                    p.getProperty("modbus.allowedClients", "127.0.0.1,10.0.0.179"),
                    rangedInt(p, "modbus.maxClients", 8, 1, 64));
                modbus.start();
            } catch (Throwable e) {
                System.err.println("[QC45] Modbus integration disabled after startup error: " + e);
                modbus = null;
            }
        }

        EvcsdLagMonitor lagMonitor = null;
        if (optionalEnabled(p, "evcsd.lagmonitor.enabled", true)) {
            try {
                lagMonitor = new EvcsdLagMonitor(
                    positiveInt(p, "evcsd.lagmonitor.intervalMs", 60000),
                    positiveInt(p, "evcsd.lagmonitor.warnMs", 250),
                    bool(p, "evcsd.lagmonitor.autoRestart", true),
                    positiveInt(p, "evcsd.lagmonitor.restartLagMs", 1000),
                    positiveInt(p, "evcsd.lagmonitor.restartConsecutive", 3),
                    positiveInt(p, "evcsd.lagmonitor.idleStableMs", 30000),
                    p.getProperty("evcsd.lagmonitor.restartCommand", "sudo -n /sbin/reboot"));
                lagMonitor.start();
            } catch (Throwable e) {
                System.err.println("[QC45] EVCSD lag monitor disabled after startup error: " + e);
                lagMonitor = null;
            }
        }

        Integration integration = new Integration(
            limits, limitGuard, modbus, ocppBridge, ocpp15Bridge,
            loadManager, failback, meter, lagMonitor, authFix);
        if (safetyReady) {
            System.out.println("[QC45] native integration started safety=fail-closed AC+DC coordinator=active");
            System.out.println("[QC45] power requests DC=AUTO " + maxDcKw
                + "kW AC=AUTO " + maxAcKw
                + "kW; first Modbus write takes control of that channel");
        } else {
            System.err.println("[QC45] native integration communications started in DEGRADED SAFE MODE; "
                + "OCPP/Modbus remain available, charging remains blocked at 0kW");
        }
        return integration;
    }

    private static Integration degraded(ChargingLimitCoordinator limits,
                                        ChargingLimitGuard guard,
                                        String message, Throwable error) {
        enterDegradedSafety(limits, message, error);
        return new Integration(limits, guard, null, null, null,
            null, null, null, null, null);
    }

    private static void enterDegradedSafety(ChargingLimitCoordinator limits,
                                            String message, Throwable error) {
        try { limits.setBlocked(ChargingLimitCoordinator.CONFIGURATION, true); }
        catch (Throwable ignored) {}
        try { limits.setBlocked(ChargingLimitCoordinator.STARTUP, true); }
        catch (Throwable ignored) {}
        System.err.println("[QC45] DEGRADED SAFE MODE: " + message + " -> all connectors remain at 0kW: " + error);
        error.printStackTrace();
    }

    static void validateFailbackThresholds(double reduceA, double tripA,
                                           double instantTripA) {
        String values = thresholdValues(reduceA, tripA, instantTripA);
        if (reduceA <= 0.0d || tripA <= 0.0d || instantTripA <= 0.0d
                || Double.isNaN(reduceA) || Double.isNaN(tripA)
                || Double.isNaN(instantTripA) || Double.isInfinite(reduceA)
                || Double.isInfinite(tripA) || Double.isInfinite(instantTripA)) {
            throw new IllegalArgumentException("failback thresholds must be finite and > 0 "
                + "(configured " + values + ")");
        }
        if (!(reduceA < tripA && tripA < instantTripA)) {
            throw new IllegalArgumentException("failback thresholds must satisfy "
                + "reduceA < tripA < instantTripA (configured " + values + ")");
        }
        if (reduceA > MAX_REDUCE_THRESHOLD_A || tripA > MAX_GRID_LIMIT_A) {
            throw new IllegalArgumentException("failback reduction and pause thresholds may only be made "
                + "more conservative than 34/35A (configured " + values + ")");
        }
        if (Math.abs(instantTripA - SLS_E_INSTANT_TRIP_A) > 0.000001d) {
            throw new IllegalArgumentException("failback.instantTripA is fixed at the conservative "
                + "lower 35A SLS-E magnetic boundary of " + SLS_E_INSTANT_TRIP_A
                + "A (configured " + values + ")");
        }
    }

    static double[] conservativeFailbackThresholds(double reduceA, double tripA,
                                                    double instantTripA) {
        // Reject nonsensical values instead of hiding them, but migrate every
        // historical instant threshold to the conservative lower E35 boundary.
        if (reduceA <= 0.0d || tripA <= 0.0d || instantTripA <= 0.0d
                || Double.isNaN(reduceA) || Double.isNaN(tripA)
                || Double.isNaN(instantTripA) || Double.isInfinite(reduceA)
                || Double.isInfinite(tripA) || Double.isInfinite(instantTripA)) {
            validateFailbackThresholds(reduceA, tripA, instantTripA);
        }
        double safeInstant = SLS_E_INSTANT_TRIP_A;
        double safeTrip = Math.min(tripA, MAX_GRID_LIMIT_A);
        double safeReduce = Math.min(Math.min(reduceA, MAX_REDUCE_THRESHOLD_A),
            safeTrip - MIN_FAILBACK_GAP_A);
        validateFailbackThresholds(safeReduce, safeTrip, safeInstant);
        return new double[] { safeReduce, safeTrip, safeInstant };
    }

    private static String thresholdValues(double reduceA, double tripA,
                                          double instantTripA) {
        return "reduceA=" + reduceA + "A, tripA=" + tripA
            + "A, instantTripA=" + instantTripA + "A";
    }

    static long[] conservativeFailbackTiming(long reduceDelayMs, long tripDelayMs,
                                             int intervalMs) {
        if (reduceDelayMs < 0L || tripDelayMs < 0L || intervalMs <= 0) {
            throw new IllegalArgumentException("failback timing must be non-negative with a positive interval");
        }
        return new long[] {
            Math.min(reduceDelayMs, 500L),
            Math.min(tripDelayMs, 250L),
            Math.min(intervalMs, MAX_FAILBACK_INTERVAL_MS)
        };
    }

    static long hardTripResetDelay(Properties p) {
        if (p.getProperty("failback.autoResetHardTrip") != null) {
            System.err.println("[QC45] failback.autoResetHardTrip is obsolete and ignored; "
                + "automatic hard-trip reset is always enabled");
        }
        long resetDelayMs = nonNegativeInt(p, "failback.resetDelayMs",
            (int)GridFailback.MIN_HARD_TRIP_RESET_DELAY_MS);
        if (resetDelayMs < GridFailback.MIN_HARD_TRIP_RESET_DELAY_MS) {
            throw new IllegalArgumentException("timed hard-trip reset must wait at least "
                + GridFailback.MIN_HARD_TRIP_RESET_DELAY_MS + "ms");
        }
        return resetDelayMs;
    }

    private static String timingValues(long reduceDelayMs, long tripDelayMs,
                                       int intervalMs) {
        return "reduceDelayMs=" + reduceDelayMs + ", tripDelayMs=" + tripDelayMs
            + ", intervalMs=" + intervalMs;
    }

    public void stop() {
        try { limits.setBlocked(ChargingLimitCoordinator.SHUTDOWN, true); }
        catch (Throwable e) { System.err.println("[QC45] shutdown safety zero failed: " + e); }

        try { if (remoteStartAuthorizationFix != null) remoteStartAuthorizationFix.shutdown(); } catch (Throwable ignored) {}
        try { if (lagMonitor != null) lagMonitor.shutdown(); } catch (Throwable ignored) {}
        try { if (loadManager != null) loadManager.shutdown(); } catch (Throwable ignored) {}
        try { if (failback != null) failback.shutdown(); } catch (Throwable ignored) {}
        try { if (ocpp15Bridge != null) ocpp15Bridge.shutdown(); } catch (Throwable ignored) {}
        try { if (ocppBridge != null) ocppBridge.shutdown(); } catch (Throwable ignored) {}
        try { if (modbus != null) modbus.shutdown(); } catch (Throwable ignored) {}
        try { if (limitGuard != null) limitGuard.shutdown(); } catch (Throwable ignored) {}

        joinQuietly(loadManager, 2000L);
        joinQuietly(failback, 2000L);
        try { if (meter != null) meter.close(); } catch (Throwable ignored) {}
        joinQuietly(lagMonitor, 2000L);
        joinQuietly(ocppBridge, 2000L);
        joinQuietly(modbus, 2000L);
        joinQuietly(limitGuard, 2000L);
        System.out.println("[QC45] native integration stopped at safe 0kW");
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
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException("Missing property: " + key);
        }
        return value.trim();
    }

    private static int integer(Properties p, String key, int fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().length() == 0
            ? fallback : Integer.parseInt(value.trim());
    }

    private static int positiveInt(Properties p, String key, int fallback) {
        int value = integer(p, key, fallback);
        if (value <= 0) throw new IllegalArgumentException(key + " must be > 0");
        return value;
    }

    private static int nonNegativeInt(Properties p, String key, int fallback) {
        int value = integer(p, key, fallback);
        if (value < 0) throw new IllegalArgumentException(key + " must be >= 0");
        return value;
    }

    private static int rangedInt(Properties p, String key, int fallback, int min, int max) {
        int value = integer(p, key, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be in " + min + ".." + max);
        }
        return value;
    }

    private static int port(Properties p, String key, int fallback) {
        return rangedInt(p, key, fallback, 1, 65535);
    }

    private static double decimal(Properties p, String key, double fallback) {
        String value = p.getProperty(key);
        double result = value == null || value.trim().length() == 0
            ? fallback : Double.parseDouble(value.trim());
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new IllegalArgumentException(key + " must be finite");
        }
        return result;
    }

    private static double positiveDecimal(Properties p, String key, double fallback) {
        double value = decimal(p, key, fallback);
        if (value <= 0.0d) throw new IllegalArgumentException(key + " must be > 0");
        return value;
    }

    private static double nonNegativeDecimal(Properties p, String key, double fallback) {
        double value = decimal(p, key, fallback);
        if (value < 0.0d) throw new IllegalArgumentException(key + " must be >= 0");
        return value;
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        if (value == null || value.trim().length() == 0) return fallback;
        String normalized = value.trim();
        if (!"true".equalsIgnoreCase(normalized) && !"false".equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(normalized);
    }

    private static boolean optionalEnabled(Properties p, String key, boolean fallback) {
        try { return bool(p, key, fallback); }
        catch (Throwable e) {
            System.err.println("[QC45] " + key + " invalid; optional component remains disabled: " + e);
            return false;
        }
    }

    private static void validateReductionLimit(String key, int value, int minimum) {
        if (value != 0 && value != minimum) {
            throw new IllegalArgumentException(key + " must be 0 or the technical minimum " + minimum);
        }
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(timeoutMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
