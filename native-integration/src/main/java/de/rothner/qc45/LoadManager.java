package de.rothner.qc45;

/**
 * Native QC45 load manager using KOSTAL KSEM phase currents.
 *
 * The controller mirrors the proven legacy Python implementation, but the
 * actual power-limit write is now fully native through ReflectionQC45.
 */
public final class LoadManager extends Thread {
    private static final double SQRT3_400_KW_PER_A = 0.692820323d;
    private static final int SAFE_DC_RAMP_UP_KW = 1;
    private static final long DC_SETTLE_MS = 3000L;

    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final double targetA;
    private final double failbackGuardA;
    private final double hysteresisA;
    private final int minDcKw;
    private final int maxDcKw;
    private final int minAcKw;
    private final int maxAcKw;
    private final int rampUpKwPerLoop;
    private final int intervalMs;

    private volatile boolean running = true;
    private boolean prevDcActive;
    private boolean prevAcActive;
    private int prevDcConnector;
    private long lastLog;
    private long lastDcIncreaseMs;

    public LoadManager(ReflectionQC45 station, KsemClient meter,
                       double targetA, double failbackGuardA, double hysteresisA,
                       int minDcKw, int maxDcKw,
                       int minAcKw, int maxAcKw,
                       int rampUpKwPerLoop, int intervalMs) {
        super("QC45-LoadManager");
        setDaemon(true);
        this.station = station;
        this.meter = meter;
        this.targetA = targetA;
        this.failbackGuardA = failbackGuardA;
        this.hysteresisA = hysteresisA;
        this.minDcKw = minDcKw;
        this.maxDcKw = maxDcKw;
        this.minAcKw = minAcKw;
        this.maxAcKw = maxAcKw;
        this.rampUpKwPerLoop = rampUpKwPerLoop;
        this.intervalMs = intervalMs;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started target=" + one(targetA)
            + "A setter=native diagnostics=full ccs-current=live-voltage"
            + " dc-ramp=" + effectiveDcRampUpKw() + "kW dc-settle=" + DC_SETTLE_MS + "ms");

        try {
            setLimitNative(1, minDcKw, "startup");
            setLimitNative(2, minDcKw, "startup");
            setLimitNative(3, minAcKw, "startup");
            System.out.println("[QC45] LoadManager startup reset C1=" + minDcKw
                + "kW C2=" + minDcKw + "kW C3=" + minAcKw + "kW");
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager startup reset failed: " + e);
        }

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                double criticalA = currents.max();
                Active active = detectActive();

                boolean newDc = active.dc && !prevDcActive;
                boolean newAc = active.ac && !prevAcActive;
                if (newDc || newAc) {
                    dumpState("BEFORE-START", active, -1, -1, -1, "WRITE=yes session-start");
                    if (newDc) {
                        setLimitNative(active.dcConnector, minDcKw, "session-start-dc");
                        // Treat the initial minimum as the first DC step. Let the vehicle
                        // and charger settle before allowing any upward regulation.
                        lastDcIncreaseMs = now;
                    }
                    if (newAc) setLimitNative(3, minAcKw, "session-start-ac");
                    dumpState("AFTER-START", active, -1, -1, -1, "WRITE=done session-start");
                    prevDcActive = active.dc;
                    prevAcActive = active.ac;
                    prevDcConnector = active.dcConnector;
                    log(now, currents, criticalA, active, targetA - criticalA,
                        "START-MIN DC=" + (newDc ? minDcKw : stationLimitSafe(active.dcConnector))
                        + "kW AC=" + (newAc ? minAcKw : stationLimitSafe(3)) + "kW");
                    sleepLoop();
                    continue;
                }

                boolean sessionEnded = false;
                if (!active.dc && prevDcActive) {
                    if (prevDcConnector > 0) setLimitNative(prevDcConnector, minDcKw, "session-end-dc");
                    lastDcIncreaseMs = 0L;
                    sessionEnded = true;
                }
                if (!active.ac && prevAcActive) {
                    setLimitNative(3, minAcKw, "session-end-ac");
                    sessionEnded = true;
                }
                prevDcActive = active.dc;
                prevAcActive = active.ac;
                if (active.dc) prevDcConnector = active.dcConnector;

                if (sessionEnded) {
                    dumpState("SESSION-END", active, -1, -1, -1, "WRITE=done reset-min");
                    log(now, currents, criticalA, active, targetA - criticalA, "SESSION-END RESET-MIN");
                    sleepLoop();
                    continue;
                }

                if (!active.dc && !active.ac) {
                    dumpState("IDLE", active, -1, -1, -1, "WRITE=no idle");
                    log(now, currents, criticalA, active, targetA - criticalA, "IDLE");
                    sleepLoop();
                    continue;
                }

                if (criticalA >= failbackGuardA) {
                    dumpState("FAILBACK-GUARD", active, -1, -1, -1,
                        "WRITE=no failback guard critical=" + one(criticalA) + "A");
                    log(now, currents, criticalA, active, targetA - criticalA, "FAILBACK-GUARD");
                    sleepLoop();
                    continue;
                }

                int actualDcKw = active.dc ? station.powerKw(active.dcConnector) : 0;
                int actualAcKw = active.ac ? station.powerKw(3) : 0;
                int actualTotalKw = actualDcKw + actualAcKw;

                int reportedDcLimitKw = active.dc ? station.limitKw(active.dcConnector) : 0;
                int reportedAcLimitKw = active.ac ? station.limitKw(3) : 0;
                int currentTotalLimitKw = reportedDcLimitKw + reportedAcLimitKw;

                int activeCount = (active.dc ? 1 : 0) + (active.ac ? 1 : 0);
                int minTotalKw = (active.dc ? minDcKw : 0) + (active.ac ? minAcKw : 0);
                int maxTotalKw = (active.dc ? maxDcKw : 0) + (active.ac ? maxAcKw : 0);
                double headroomA = targetA - criticalA;

                double requestedRawKw = actualTotalKw + headroomA * SQRT3_400_KW_PER_A;
                int requestedTotalKw = clamp((int)Math.round(requestedRawKw), minTotalKw, maxTotalKw);
                int totalTargetKw;

                if (Math.abs(headroomA) < hysteresisA) {
                    totalTargetKw = Math.max(minTotalKw, currentTotalLimitKw);
                } else if (requestedTotalKw > currentTotalLimitKw) {
                    int ramp = active.dc ? effectiveDcRampUpKw() : rampUpKwPerLoop;
                    totalTargetKw = Math.min(requestedTotalKw, currentTotalLimitKw + ramp);
                } else {
                    // Reductions are intentionally never delayed.
                    totalTargetKw = requestedTotalKw;
                }
                totalTargetKw = clamp(totalTargetKw, minTotalKw, maxTotalKw);

                Targets targets = allocateFromActual(active, totalTargetKw, actualDcKw, actualAcKw);

                boolean dcSettling = false;
                long settleRemainingMs = 0L;
                if (active.dc && targets.dcKw > reportedDcLimitKw && lastDcIncreaseMs > 0L) {
                    long elapsed = now - lastDcIncreaseMs;
                    if (elapsed < DC_SETTLE_MS) {
                        dcSettling = true;
                        settleRemainingMs = DC_SETTLE_MS - elapsed;
                        // Hold only the upward DC movement. AC and every downward move
                        // remain available immediately. The held value also feeds the
                        // CCS current refresh below, so current cannot run ahead of kW.
                        targets = new Targets(reportedDcLimitKw, targets.acKw);
                        totalTargetKw = targets.dcKw + targets.acKw;
                    }
                }

                boolean writeDc = active.dc && targets.dcKw != reportedDcLimitKw;
                boolean writeAc = active.ac && targets.acKw != reportedAcLimitKw;
                dumpState("DECISION", active, targets.dcKw, targets.acKw, totalTargetKw,
                    "WRITE-DC=" + yesno(writeDc) + " WRITE-AC=" + yesno(writeAc)
                    + (dcSettling ? " DC-SETTLING remaining=" + settleRemainingMs + "ms" : "")
                    + " actualDC=" + actualDcKw + " actualAC=" + actualAcKw
                    + " reportedDC=" + reportedDcLimitKw + " reportedAC=" + reportedAcLimitKw
                    + " headroom=" + one(headroomA) + "A requestedRaw=" + one(requestedRawKw) + "kW");

                boolean changed = false;
                if (writeDc) {
                    setLimitNative(active.dcConnector, targets.dcKw,
                        "regulation-dc reported=" + reportedDcLimitKw + " target=" + targets.dcKw);
                    if (targets.dcKw > reportedDcLimitKw) {
                        lastDcIncreaseMs = now;
                        System.out.println("[QC45] LoadManager DC-SETTLE armed target=" + targets.dcKw
                            + "kW until=" + (now + DC_SETTLE_MS) + " (+" + DC_SETTLE_MS + "ms)");
                    }
                    changed = true;
                }
                if (writeAc) {
                    setLimitNative(3, targets.acKw,
                        "regulation-ac reported=" + reportedAcLimitKw + " target=" + targets.acKw);
                    changed = true;
                }

                // Even with a constant kW target, the EV battery voltage changes during
                // charging. Keep quickChargeMaxCurrent synchronized with P/U every loop.
                // During settling, targets.dcKw is deliberately the held reported value.
                if (active.dc && active.dcConnector == 2 && !writeDc) {
                    try {
                        int amps = station.refreshQuickChargeCurrentForPower(2, targets.dcKw);
                        System.out.println("[QC45] LoadManager CCS-CURRENT-REFRESH target="
                            + targets.dcKw + "kW current=" + amps + "A"
                            + (dcSettling ? " DC-SETTLING" : ""));
                    } catch (Throwable e) {
                        System.err.println("[QC45] LoadManager CCS-CURRENT-REFRESH failed: " + e);
                    }
                }

                dumpState("AFTER-DECISION", active, targets.dcKw, targets.acKw, totalTargetKw,
                    changed ? "WRITE=performed" : dcSettling ? "WRITE=no dc settling" : "WRITE=no targets already reported");

                log(now, currents, criticalA, active, headroomA,
                    (changed ? "SET-NATIVE" : dcSettling ? "DC-SETTLING" : "HOLD")
                    + " actualDC=" + actualDcKw + " actualAC=" + actualAcKw
                    + " reportedDC=" + reportedDcLimitKw + " reportedAC=" + reportedAcLimitKw
                    + " targetDC=" + targets.dcKw + " targetAC=" + targets.acKw
                    + " totalTarget=" + totalTargetKw + " activeCount=" + activeCount);

            } catch (Throwable e) {
                if (now - lastLog >= 5000L) {
                    System.err.println("[QC45] LoadManager error: " + e);
                    lastLog = now;
                }
            }

            sleepLoop();
        }

        System.out.println("[QC45] LoadManager stopped");
    }

    private int effectiveDcRampUpKw() {
        return Math.max(1, Math.min(SAFE_DC_RAMP_UP_KW, rampUpKwPerLoop));
    }

    private void setLimitNative(int connector, int kw, String reason) throws Exception {
        int max = connector == 3 ? maxAcKw : maxDcKw;
        int min = connector == 3 ? minAcKw : minDcKw;
        kw = clamp(kw, min, max);

        int before = station.limitKw(connector);
        System.out.println("[QC45] LoadManager WRITE-NATIVE connector=" + connector
            + " kw=" + kw + "kW before=" + before + "kW reason=" + reason);

        try {
            station.setConnectorLimitKw(connector, kw);
            int after = station.limitKw(connector);
            System.out.println("[QC45] LoadManager WRITTEN-NATIVE connector=" + connector
                + " kw=" + kw + "kW after=" + after + "kW");
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager WRITE-NATIVE-FAILED connector=" + connector
                + " kw=" + kw + "kW error=" + e);
            if (e instanceof Exception) throw (Exception)e;
            throw new RuntimeException(e);
        }
    }

    private void dumpState(String stage, Active active, int targetDcKw, int targetAcKw,
                           int totalTargetKw, String decision) {
        try {
            int p1 = station.powerKw(1);
            int p2 = station.powerKw(2);
            int p3 = station.powerKw(3);
            int l1 = station.limitKw(1);
            int l2 = station.limitKw(2);
            int l3 = station.limitKw(3);
            String global = safeGlobal();
            String maxAc = safeMaxAc();
            String dcFixed = safeDcFixed();
            String acFixed = safeAcFixed();
            String ccsVoltage = safeCcsVoltage();
            String ccsCurrent = safeCcsCurrent();

            System.out.println("[QC45] LoadManager DIAG stage=" + stage
                + " activeDC=" + active.dc + " dcConnector=" + active.dcConnector
                + " activeAC=" + active.ac
                + " power[C1=" + p1 + ",C2=" + p2 + ",C3=" + p3 + "]kW"
                + " satMax[C1=" + l1 + ",C2=" + l2 + ",C3=" + l3 + "]kW"
                + " ccs[voltage=" + ccsVoltage + "V,quickChargeMaxCurrent=" + ccsCurrent + "A]"
                + " conf[maxPower=" + global + ",maxPowerAC=" + maxAc
                + ",DCMaxPowerFixed=" + dcFixed + ",ACMaxPowerFixed=" + acFixed + "]"
                + " target[DC=" + valueOrDash(targetDcKw) + ",AC=" + valueOrDash(targetAcKw)
                + ",total=" + valueOrDash(totalTargetKw) + "]kW " + decision);
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager DIAG stage=" + stage + " failed: " + e);
        }
    }

    private String safeGlobal() {
        try { return String.valueOf(station.globalMaxPower()); }
        catch (Throwable e) { return "n/a"; }
    }

    private String safeMaxAc() {
        try { return String.valueOf(station.maxPowerAC()); }
        catch (Throwable e) { return "n/a"; }
    }

    private String safeDcFixed() {
        try { return String.valueOf(station.dcMaxPowerFixed()); }
        catch (Throwable e) { return "n/a"; }
    }

    private String safeAcFixed() {
        try { return String.valueOf(station.acMaxPowerFixed()); }
        catch (Throwable e) { return "n/a"; }
    }

    private String safeCcsVoltage() {
        try { return String.valueOf(station.dcVoltageV(2)); }
        catch (Throwable e) { return "n/a"; }
    }

    private String safeCcsCurrent() {
        try { return String.valueOf(station.quickChargeMaxCurrentA(2)); }
        catch (Throwable e) { return "n/a"; }
    }

    private static String valueOrDash(int value) {
        return value < 0 ? "-" : String.valueOf(value);
    }

    private static String yesno(boolean value) {
        return value ? "yes" : "no";
    }

    private Targets allocateFromActual(Active active, int totalTargetKw,
                                       int actualDcKw, int actualAcKw) {
        if (active.dc && !active.ac) {
            return new Targets(clamp(totalTargetKw, minDcKw, maxDcKw), 0);
        }
        if (!active.dc && active.ac) {
            return new Targets(0, clamp(totalTargetKw, minAcKw, maxAcKw));
        }

        int dcActual = Math.max(minDcKw, actualDcKw);
        int acActual = Math.max(minAcKw, actualAcKw);
        int delta = totalTargetKw - (dcActual + acActual);
        double dcTarget = dcActual;
        double acTarget = acActual;

        if (delta >= 0) {
            double half = delta / 2.0d;
            dcTarget += half;
            acTarget += half;
            if (acTarget > maxAcKw) {
                double overflow = acTarget - maxAcKw;
                acTarget = maxAcKw;
                dcTarget += overflow;
            }
            if (dcTarget > maxDcKw) {
                double overflow = dcTarget - maxDcKw;
                dcTarget = maxDcKw;
                acTarget += overflow;
            }
        } else {
            double reduction = -delta;
            double dcAvailable = Math.max(0.0d, dcTarget - minDcKw);
            double dcReduction = Math.min(reduction, dcAvailable);
            dcTarget -= dcReduction;
            reduction -= dcReduction;
            if (reduction > 0.0d) {
                double acAvailable = Math.max(0.0d, acTarget - minAcKw);
                double acReduction = Math.min(reduction, acAvailable);
                acTarget -= acReduction;
            }
        }

        return new Targets(
            clamp((int)Math.round(dcTarget), minDcKw, maxDcKw),
            clamp((int)Math.round(acTarget), minAcKw, maxAcKw));
    }

    private Active detectActive() throws Exception {
        int p1 = station.powerKw(1);
        int p2 = station.powerKw(2);
        String u1 = station.idTag(1);
        String u2 = station.idTag(2);
        String u3 = station.idTag(3);

        boolean c1 = p1 > 0 || u1.length() > 0;
        boolean c2 = p2 > 0 || u2.length() > 0;
        boolean ac = station.powerKw(3) > 0 || u3.length() > 0;

        int dcConnector = 0;
        if (c1 && c2) dcConnector = p1 >= p2 ? 1 : 2;
        else if (c1) dcConnector = 1;
        else if (c2) dcConnector = 2;

        return new Active(dcConnector != 0, ac, dcConnector);
    }

    private int stationLimitSafe(int connector) {
        if (connector <= 0) return 0;
        try { return station.limitKw(connector); }
        catch (Throwable ignored) { return 0; }
    }

    private void log(long now, KsemClient.Currents c, double criticalA,
                     Active active, double headroomA, String action) {
        if (now - lastLog < 5000L && !action.startsWith("SET")
                && !action.startsWith("DC-SETTLING")
                && !action.startsWith("START-MIN")
                && !action.startsWith("SESSION-END")
                && !action.equals("FAILBACK-GUARD")) return;

        String mode = active.dc && active.ac ? "DC+AC" : active.dc ? "DC" : active.ac ? "AC" : "IDLE";
        System.out.println("[QC45] LoadManager " + mode
            + " L1=" + one(c.l1) + "A L2=" + one(c.l2) + "A L3=" + one(c.l3)
            + "A critical=" + one(criticalA) + "A headroom=" + one(headroomA) + "A " + action);
        lastLog = now;
    }

    private void sleepLoop() {
        try { Thread.sleep(intervalMs); }
        catch (InterruptedException e) { /* shutdown checked by loop */ }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    private static final class Active {
        final boolean dc;
        final boolean ac;
        final int dcConnector;
        Active(boolean dc, boolean ac, int dcConnector) {
            this.dc = dc;
            this.ac = ac;
            this.dcConnector = dcConnector;
        }
    }

    private static final class Targets {
        final int dcKw;
        final int acKw;
        Targets(int dcKw, int acKw) {
            this.dcKw = dcKw;
            this.acKw = acKw;
        }
    }
}
