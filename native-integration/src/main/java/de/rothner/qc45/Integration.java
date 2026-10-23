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
    private static final double KSEM_CURRENT_SCALE = 0.001d;
    private static final int MAX_KSEM_TIMEOUT_MS = 1000;
    private static final int MAX_FAILBACK_INTERVAL_MS = 100;
    private static final int MAX_LOADMANAGER_INTERVAL_MS = 1000;
    private static final int MAX_RAMP_UP_KW_PER_SECOND = 5;

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
    private boolean startupHealthy = true;

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
            DEFAULT_MIN_AC_KW, DEFAULT_MAX_AC_KW, false);
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
            limits = new ChargingLimitCoordinator(station, minDcKw, maxDcKw,
                minAcKw, maxAcKw, false);
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
            GridSettings grid = gridSettings(p);
            double failbackReduceA = grid.reduceA;
            double failbackTripA = grid.tripA;
            double failbackInstantTripA = grid.instantA;
            System.out.println("[QC45] grid settings SLS=E" + grid.nominalA
                + "A target=" + grid.targetA + "A ceiling=" + grid.ceilingA
                + "A daytime=" + grid.daytimeA + "A hysteresis=" + grid.hysteresisA
                + "A " + thresholdValues(grid.reduceA, grid.tripA, grid.instantA));
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
                    resetDelayMs, grid.nominalA, grid.thermalTimeConstantMs);
                // Close the scheduling gap before the failback thread performs
                // its first read and establishes its own startup pause.
                limits.setBlocked(ChargingLimitCoordinator.FAILBACK, true);
            }

            if (loadManagerEnabled) {
                int legacyRampKw = positiveInt(p, "loadmanager.rampUpKwPerLoop", 5);
                int rampKw = effectiveRampUpKw(legacyRampKw);
                if (legacyRampKw != rampKw) {
                    System.out.println("[QC45] loadmanager.rampUpKwPerLoop migrated "
                        + legacyRampKw + "kW -> " + rampKw
                        + "kW: operator requested 5kW ramp (2026-10-10)");
                }
                int intervalMs = positiveInt(p, "loadmanager.intervalMs", 1000);
                long demandStableMs = nonNegativeInt(p, "loadmanager.demandStableMs", 5000);
                int demandReserveKw = positiveInt(p, "loadmanager.demandReserveKw", 2);
                if (intervalMs > MAX_LOADMANAGER_INTERVAL_MS
                        || (long)rampKw * 1000L
                            > (long)MAX_RAMP_UP_KW_PER_SECOND * (long)intervalMs) {
                    throw new IllegalArgumentException("LoadManager must poll within 1000ms and ramp at no more than 5kW/s");
                }
                loadManager = new LoadManager(
                    station, meter, limits,
                    grid.targetA, grid.ceilingA, grid.hysteresisA,
                    minDcKw, maxDcKw, minAcKw, maxAcKw,
                    rampKw, intervalMs, demandStableMs, demandReserveKw, grid.daytimeA);
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
        boolean communicationsReady = true;
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
                        ocppBridge, station, limits);
                    ocpp15Bridge.start();
                }
            } catch (Throwable e) {
                System.err.println("[QC45] OCPP integration disabled after startup error; grid safety remains active: " + e);
                communicationsReady = false;
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
                communicationsReady = false;
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
        integration.startupHealthy = safetyReady && communicationsReady;
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

    void enterPersistentDegradedSafety(String message, Throwable error) {
        enterDegradedSafety(limits, message, error);
    }

    boolean isHealthy() {
        return startupHealthy && !limits.isBlockedBy(ChargingLimitCoordinator.CONFIGURATION)
            && !limits.isBlockedBy(ChargingLimitCoordinator.SHUTDOWN)
            && limitGuard != null && limitGuard.isAlive()
            && (loadManager == null || loadManager.isAlive())
            && (failback == null || failback.isAlive())
            && (modbus == null || modbus.isAlive())
            && (ocppBridge == null || ocppBridge.isAlive());
    }

    static final class GridSettings {
        final double nominalA, reduceA, tripA, instantA, ceilingA, targetA, hysteresisA, daytimeA;
        final long thermalTimeConstantMs;

        GridSettings(Properties p) {
            nominalA = positiveDecimal(p, "sls.nominalA", 35.0d);
            String characteristic = p.getProperty("sls.characteristic", "E").trim();
            if (!"E".equalsIgnoreCase(characteristic)) {
                throw new IllegalArgumentException("only sls.characteristic=E is supported");
            }
            thermalTimeConstantMs = positiveInt(p, "sls.thermalTimeConstantMs", 30000);
            // Validates the rated-current range and a conservative time constant.
            new SlsThermalModel(nominalA, thermalTimeConstantMs);
            double configuredReduce = positiveDecimal(p, "failback.reduceA", nominalA + 1.0d);
            double configuredTrip = positiveDecimal(p, "failback.tripA", nominalA + 3.0d);
            double configuredInstant = positiveDecimal(p, "failback.instantTripA",
                nominalA * SlsThermalModel.E_MAGNETIC_MULTIPLE);
            double[] thresholds = conservativeFailbackThresholds(
                configuredReduce, configuredTrip, configuredInstant, nominalA);
            reduceA = thresholds[0];
            tripA = thresholds[1];
            instantA = thresholds[2];
            if (reduceA != configuredReduce || tripA != configuredTrip || instantA != configuredInstant) {
                System.err.println("[QC45] SLS configuration bounded by E" + nominalA
                    + ": configured " + thresholdValues(configuredReduce, configuredTrip, configuredInstant)
                    + " -> " + thresholdValues(reduceA, tripA, instantA));
            }
            double configuredCeiling = positiveDecimal(p, "loadmanager.gridLimitA", nominalA);
            ceilingA = Math.min(nominalA, bool(p, "failback.enabled", true)
                ? Math.min(configuredCeiling, reduceA) : configuredCeiling);
            double configuredTarget = positiveDecimal(p, "loadmanager.targetA", nominalA - 1.0d);
            hysteresisA = nonNegativeDecimal(p, "loadmanager.hysteresisA", 0.8d);
            targetA = conservativeLoadManagerTargetA(configuredTarget, ceilingA, hysteresisA);
            double configuredDaytime = positiveDecimal(p, "loadmanager.daytimeTargetA",
                Math.min(25.0d, nominalA));
            daytimeA = Math.min(configuredDaytime, ceilingA);
            if (ceilingA != configuredCeiling || targetA != configuredTarget
                    || daytimeA != configuredDaytime) {
                System.out.println("[QC45] load-manager configured target=" + configuredTarget
                    + "A ceiling=" + configuredCeiling + "A daytime=" + configuredDaytime
                    + "A -> effective target=" + targetA + "A ceiling=" + ceilingA
                    + "A daytime=" + daytimeA + "A for SLS=" + nominalA + "A");
            }
        }
    }

    static GridSettings gridSettings(Properties p) { return new GridSettings(p); }

    static void validateFailbackThresholds(double reduceA, double tripA,
                                           double instantTripA, double nominalA) {
        String values = thresholdValues(reduceA, tripA, instantTripA);
        if (!SlsThermalModel.finite(nominalA) || nominalA < 16.0d || nominalA > 100.0d
                || !SlsThermalModel.finite(reduceA) || reduceA <= 0.0d
                || !SlsThermalModel.finite(tripA) || tripA <= 0.0d
                || !SlsThermalModel.finite(instantTripA) || instantTripA <= 0.0d) {
            throw new IllegalArgumentException("SLS rating and failback thresholds must be finite and > 0");
        }
        if (!(reduceA < tripA && tripA < instantTripA)) {
            throw new IllegalArgumentException("failback thresholds must satisfy "
                + "reduceA < tripA < instantTripA (configured " + values + ")");
        }
        if (reduceA > nominalA * 1.05d || tripA > nominalA * 1.2d
                || instantTripA > nominalA * SlsThermalModel.E_MAGNETIC_MULTIPLE) {
            throw new IllegalArgumentException("failback thresholds exceed the configured SLS E rating");
        }
    }

    static double[] conservativeFailbackThresholds(double reduceA, double tripA,
                                                    double instantTripA, double nominalA) {
        if (!SlsThermalModel.finite(reduceA) || reduceA <= 0.0d
                || !SlsThermalModel.finite(tripA) || tripA <= 0.0d
                || !SlsThermalModel.finite(instantTripA) || instantTripA <= 0.0d
                || !SlsThermalModel.finite(nominalA) || nominalA < 16.0d || nominalA > 100.0d) {
            throw new IllegalArgumentException("SLS rating and failback thresholds must be finite and > 0");
        }
        double safeReduce = Math.min(reduceA, nominalA * 1.05d);
        double safeTrip = Math.min(tripA, nominalA * 1.2d);
        double safeInstant = Math.min(instantTripA, nominalA * SlsThermalModel.E_MAGNETIC_MULTIPLE);
        // Explicit conservative values are never silently raised.
        validateFailbackThresholds(safeReduce, safeTrip, safeInstant, nominalA);
        return new double[] { safeReduce, safeTrip, safeInstant };
    }

    /** Upgrade the previous deployed 2-kW setting without editing secrets on the station. */
    static int effectiveRampUpKw(int configuredKw) {
        return configuredKw == 2 ? 5 : configuredKw;
    }

    static double conservativeLoadManagerTargetA(double configuredTargetA,
                                                 double commandCeilingA,
                                                 double hysteresisA) {
        if (configuredTargetA <= 0.0d
                || commandCeilingA <= 0.0d || hysteresisA < 0.0d
                || Double.isNaN(configuredTargetA) || Double.isInfinite(configuredTargetA)
                || Double.isNaN(commandCeilingA) || Double.isInfinite(commandCeilingA)
                || Double.isNaN(hysteresisA) || Double.isInfinite(hysteresisA)) {
            throw new IllegalArgumentException("invalid loadmanager target/ceiling/hysteresis");
        }
        // Hysteresis is a hold band around the target, not a permanent derating.
        return Math.min(configuredTargetA, commandCeilingA);
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
        SafetyDiagnostics.stopModbus();
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
