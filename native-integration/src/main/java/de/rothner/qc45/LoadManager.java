package de.rothner.qc45;

/**
 * Native QC45 load manager using KOSTAL KSEM phase currents.
 *
 * Rules:
 * - regulate against the most loaded grid phase
 * - target defaults to 32 A (35 A service with 3 A reserve)
 * - active DC = CHAdeMO OR CCS, plus optional simultaneous Type2
 * - start every newly active connector at minimum power
 * - ramp power up slowly, reduce immediately
 * - while charging, continuously re-assert the current targets every loop because EVCSD may overwrite them
 * - do nothing while completely idle to avoid fighting QC45 idle defaults
 * - yield completely to GridFailback at/above its reduction threshold
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
    private int dcTargetKw;
    private int acTargetKw;
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
        this.dcTargetKw = minDcKw;
        this.acTargetKw = minAcKw;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started target=" + one(targetA) + "A");

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                double criticalA = currents.max();

                Active active = detectActive();

                boolean newDc = active.dc && !prevDcActive;
                boolean newAc = active.ac && !prevAcActive;
                if (newDc || newAc) {
                    if (newDc) dcTargetKw = minDcKw;
                    if (newAc) acTargetKw = minAcKw;
                    enforceTargets(active);
                    prevDcActive = active.dc;
                    prevAcActive = active.ac;
                    log(now, currents, criticalA, active, 0.0d, "START-MIN");
                    sleepLoop();
                    continue;
                }

                if (!active.dc && prevDcActive) {
                    dcTargetKw = minDcKw;
                    station.setDcBudgetKw(dcTargetKw);
                }
                if (!active.ac && prevAcActive) {
                    acTargetKw = minAcKw;
                    station.setAcBudgetKw(acTargetKw);
                }
                prevDcActive = active.dc;
                prevAcActive = active.ac;

                if (!active.dc && !active.ac) {
                    log(now, currents, criticalA, active, targetA - criticalA, "IDLE");
                    sleepLoop();
                    continue;
                }

                // GridFailback owns the station from this point upward. Do not fight it.
                if (criticalA >= failbackGuardA) {
                    log(now, currents, criticalA, active, targetA - criticalA, "FAILBACK-GUARD");
                    sleepLoop();
                    continue;
                }

                double headroomA = targetA - criticalA;

                // HOLD still re-writes every active limit. EVCSD is known to overwrite
                // maxPower/maxPowerAC/fixed limits asynchronously.
                if (Math.abs(headroomA) < hysteresisA) {
                    enforceTargets(active);
                    log(now, currents, criticalA, active, headroomA,
                        "HOLD/REASSERT DC=" + (active.dc ? dcTargetKw : 0)
                        + "kW AC=" + (active.ac ? acTargetKw : 0) + "kW");
                    sleepLoop();
                    continue;
                }

                int actualDcKw = active.dc ? station.powerKw(active.dcConnector) : 0;
                int actualAcKw = active.ac ? station.powerKw(3) : 0;
                int actualTotalKw = actualDcKw + actualAcKw;

                double desiredRawKw = actualTotalKw + headroomA * SQRT3_400_KW_PER_A;
                int minTotal = (active.dc ? minDcKw : 0) + (active.ac ? minAcKw : 0);
                int maxTotal = (active.dc ? maxDcKw : 0) + (active.ac ? maxAcKw : 0);
                int desiredTotalKw = clamp((int)Math.floor(desiredRawKw), minTotal, maxTotal);

                int currentTargetTotal = (active.dc ? dcTargetKw : 0) + (active.ac ? acTargetKw : 0);
                if (desiredTotalKw > currentTargetTotal) {
                    desiredTotalKw = Math.min(desiredTotalKw, currentTargetTotal + rampUpKwPerLoop);
                }

                allocate(active, desiredTotalKw);
                enforceTargets(active);

                log(now, currents, criticalA, active, headroomA,
                    "SET DC=" + (active.dc ? dcTargetKw : 0) + "kW AC=" + (active.ac ? acTargetKw : 0) + "kW");

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

    private void enforceTargets(Active active) throws Exception {
        if (active.dc) station.setDcBudgetKw(dcTargetKw);
        if (active.ac) station.setAcBudgetKw(acTargetKw);
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

    private void allocate(Active active, int desiredTotalKw) {
        if (active.dc && !active.ac) {
            dcTargetKw = clamp(desiredTotalKw, minDcKw, maxDcKw);
            return;
        }
        if (!active.dc && active.ac) {
            acTargetKw = clamp(desiredTotalKw, minAcKw, maxAcKw);
            return;
        }

        int currentTotal = dcTargetKw + acTargetKw;
        if (desiredTotalKw < currentTotal) {
            int reduction = currentTotal - desiredTotalKw;
            int dcRoom = dcTargetKw - minDcKw;
            int fromDc = Math.min(reduction, dcRoom);
            dcTargetKw -= fromDc;
            reduction -= fromDc;
            if (reduction > 0) acTargetKw = Math.max(minAcKw, acTargetKw - reduction);
            return;
        }

        int addition = desiredTotalKw - currentTotal;
        while (addition > 0) {
            boolean dcCan = dcTargetKw < maxDcKw;
            boolean acCan = acTargetKw < maxAcKw;
            if (!dcCan && !acCan) break;
            if (dcCan) { dcTargetKw++; addition--; }
            if (addition > 0 && acCan) { acTargetKw++; addition--; }
        }
    }

    private void log(long now, KsemClient.Currents c, double criticalA,
                     Active active, double headroomA, String action) {
        if (now - lastLog < 5000L && !action.startsWith("SET") && !action.equals("START-MIN")
                && !action.equals("FAILBACK-GUARD") && !action.startsWith("HOLD/REASSERT")) return;

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
}
