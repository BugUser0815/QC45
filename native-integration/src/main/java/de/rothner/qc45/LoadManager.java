package de.rothner.qc45;

/**
 * Native QC45 load manager using KOSTAL KSEM phase currents.
 *
 * This intentionally mirrors the proven legacy Python controller:
 * - use QC45-reported connector limits as the current controller state
 * - derive total target from measured charger power + grid headroom
 * - ramp only positive changes
 * - reduce immediately
 * - allocate DC+AC from measured connector powers
 * - write a limit only when the QC45-reported value differs from the target
 * - if EVCSD overwrites a limit, the next status cycle observes that change and corrects it
 */
public final class LoadManager extends Thread {
    private static final double SQRT3_400_KW_PER_A = 0.692820323d;

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
    private long lastLog;

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
        System.out.println("[QC45] LoadManager started target=" + one(targetA) + "A legacy-control-model=true");

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                double criticalA = currents.max();
                Active active = detectActive();

                // Match the old controller: a newly active connector starts at 5 kW.
                boolean newDc = active.dc && !prevDcActive;
                boolean newAc = active.ac && !prevAcActive;
                if (newDc || newAc) {
                    if (newDc) station.setDcBudgetKw(minDcKw);
                    if (newAc) station.setAcBudgetKw(minAcKw);
                    prevDcActive = active.dc;
                    prevAcActive = active.ac;
                    log(now, currents, criticalA, active, targetA - criticalA,
                        "START-MIN DC=" + (newDc ? minDcKw : stationLimitSafe(active.dcConnector))
                        + "kW AC=" + (newAc ? minAcKw : stationLimitSafe(3)) + "kW");
                    sleepLoop();
                    continue;
                }

                // Match the old controller: session end is reset immediately, then wait
                // for the next fresh status cycle instead of using stale values.
                boolean sessionEnded = false;
                if (!active.dc && prevDcActive) {
                    station.setDcBudgetKw(minDcKw);
                    sessionEnded = true;
                }
                if (!active.ac && prevAcActive) {
                    station.setAcBudgetKw(minAcKw);
                    sessionEnded = true;
                }
                prevDcActive = active.dc;
                prevAcActive = active.ac;

                if (sessionEnded) {
                    log(now, currents, criticalA, active, targetA - criticalA, "SESSION-END RESET-MIN");
                    sleepLoop();
                    continue;
                }

                if (!active.dc && !active.ac) {
                    log(now, currents, criticalA, active, targetA - criticalA, "IDLE");
                    sleepLoop();
                    continue;
                }

                // GridFailback owns the station at/above its guard threshold.
                if (criticalA >= failbackGuardA) {
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

                // Exact legacy behavior: within the deadband keep the QC45-reported
                // current total limit. Outside it, ramp only upward; reductions are immediate.
                if (Math.abs(headroomA) < hysteresisA) {
                    totalTargetKw = Math.max(minTotalKw, currentTotalLimitKw);
                } else if (requestedTotalKw > currentTotalLimitKw) {
                    totalTargetKw = Math.min(requestedTotalKw, currentTotalLimitKw + rampUpKwPerLoop);
                } else {
                    totalTargetKw = requestedTotalKw;
                }
                totalTargetKw = clamp(totalTargetKw, minTotalKw, maxTotalKw);

                Targets targets = allocateFromActual(active, totalTargetKw, actualDcKw, actualAcKw);

                boolean changed = false;
                if (active.dc && targets.dcKw != reportedDcLimitKw) {
                    station.setDcBudgetKw(targets.dcKw);
                    changed = true;
                }
                if (active.ac && targets.acKw != reportedAcLimitKw) {
                    station.setAcBudgetKw(targets.acKw);
                    changed = true;
                }

                log(now, currents, criticalA, active, headroomA,
                    (changed ? "SET" : "HOLD")
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

        int dc = clamp((int)Math.round(dcTarget), minDcKw, maxDcKw);
        int ac = clamp((int)Math.round(acTarget), minAcKw, maxAcKw);
        return new Targets(dc, ac);
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
