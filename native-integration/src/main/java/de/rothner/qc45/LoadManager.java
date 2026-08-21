package de.rothner.qc45;

/** Native QC45 load manager using KOSTAL KSEM phase currents.
 *
 * Version 1.0 DC control logic with start-safety guards:
 * - idle DC limits are continuously pre-armed to minDcKw
 * - a private commanded limit is authoritative for ramp-up
 * - any higher EVCSD-reported limit is immediately overwritten
 *
 * Connector 3 (Type2/AC) is deliberately never detected or controlled here.
 * Its consumption is still included in the KSEM phase currents and therefore
 * reduces the headroom available to DC.
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
    private final int rampUpKwPerLoop;
    private final int intervalMs;

    private volatile boolean running = true;
    private boolean prevDcActive;
    private int prevDcConnector;
    private int commandedDcKw;
    private long lastErrorLog;
    private long lastIdlePreArmLog;

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
        this.rampUpKwPerLoop = rampUpKwPerLoop;
        this.intervalMs = intervalMs;
        this.commandedDcKw = minDcKw;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started DC-only target=" + one(targetA)
            + "A ramp=" + rampUpKwPerLoop + "kW/loop control=v1.0 prearm=" + minDcKw + "kW");

        try {
            commandedDcKw = minDcKw;
            preArmDc();
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager startup pre-arm failed: " + e);
        }

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                double criticalA = currents.max();
                Active active = detectActiveDc();

                boolean newDc = active.dc && (!prevDcActive || active.dcConnector != prevDcConnector);
                if (newDc) {
                    commandedDcKw = minDcKw;
                    setLimitNative(active.dcConnector, commandedDcKw);
                    prevDcActive = true;
                    prevDcConnector = active.dcConnector;
                    System.out.println("[QC45] LoadManager session start DC=" + active.dcConnector
                        + " prearmed=" + commandedDcKw + "kW");
                    sleepLoop();
                    continue;
                }

                if (!active.dc && prevDcActive) {
                    commandedDcKw = minDcKw;
                    if (prevDcConnector > 0) setLimitNative(prevDcConnector, commandedDcKw);
                    prevDcActive = false;
                    prevDcConnector = 0;
                    preArmDc();
                    System.out.println("[QC45] LoadManager session end; DC prearmed=" + commandedDcKw + "kW");
                    sleepLoop();
                    continue;
                }

                if (!active.dc) {
                    commandedDcKw = minDcKw;
                    if (needsIdlePreArm()) {
                        preArmDc();
                        if (now - lastIdlePreArmLog >= 5000L) {
                            System.out.println("[QC45] LoadManager idle DC limits re-armed to " + minDcKw + "kW");
                            lastIdlePreArmLog = now;
                        }
                    }
                    sleepLoop();
                    continue;
                }

                prevDcActive = true;
                prevDcConnector = active.dcConnector;

                int rawReportedLimitKw = station.limitKw(active.dcConnector);

                // Never allow an EVCSD-internal reset or another writer to jump
                // above the limit that this load manager has actually released.
                // This closes the 5 -> 50 kW startup race completely from the
                // load-manager side.
                if (rawReportedLimitKw > commandedDcKw) {
                    setLimitNative(active.dcConnector, commandedDcKw);
                    System.err.println("[QC45] LoadManager LIMIT OVERRIDE DC" + active.dcConnector
                        + " EVCSD=" + rawReportedLimitKw + "kW > released=" + commandedDcKw
                        + "kW; restored released limit");
                    sleepLoop();
                    continue;
                }

                // Do not merely wait for GridFailback here. If this loop sees the
                // guard threshold, force the DC limit to minimum immediately as a
                // second independent safety path.
                if (criticalA >= failbackGuardA) {
                    commandedDcKw = minDcKw;
                    if (rawReportedLimitKw != commandedDcKw) {
                        setLimitNative(active.dcConnector, commandedDcKw);
                    }
                    System.err.println("[QC45] LoadManager GUARD grid=" + one(criticalA)
                        + "A -> DC" + active.dcConnector + "=" + commandedDcKw + "kW");
                    sleepLoop();
                    continue;
                }

                int actualDcKw = station.powerKw(active.dcConnector);
                int currentTotalLimitKw = commandedDcKw;
                int minTotalKw = minDcKw;
                int maxTotalKw = maxDcKw;
                double headroomA = targetA - criticalA;

                int totalTargetKw;
                if (Math.abs(headroomA) < hysteresisA) {
                    totalTargetKw = currentTotalLimitKw;
                } else if (headroomA < 0.0d) {
                    double reducedRawKw = currentTotalLimitKw
                        + headroomA * SQRT3_400_KW_PER_A;
                    totalTargetKw = (int)Math.round(reducedRawKw);
                    totalTargetKw = Math.min(totalTargetKw, currentTotalLimitKw);
                } else {
                    double requestedRawKw = actualDcKw
                        + headroomA * SQRT3_400_KW_PER_A;
                    int requestedTotalKw = clamp((int)Math.round(requestedRawKw),
                        minTotalKw, maxTotalKw);
                    if (requestedTotalKw > currentTotalLimitKw) {
                        totalTargetKw = Math.min(requestedTotalKw,
                            currentTotalLimitKw + rampUpKwPerLoop);
                    } else {
                        totalTargetKw = requestedTotalKw;
                    }
                }

                totalTargetKw = clamp(totalTargetKw, minTotalKw, maxTotalKw);
                int targetDcKw = totalTargetKw;

                if (headroomA < -hysteresisA) {
                    targetDcKw = Math.min(targetDcKw, currentTotalLimitKw);
                }

                if (targetDcKw != commandedDcKw || rawReportedLimitKw != targetDcKw) {
                    commandedDcKw = targetDcKw;
                    setLimitNative(active.dcConnector, commandedDcKw);
                    System.out.println("[QC45] LoadManager set grid=" + one(criticalA)
                        + "A DC" + active.dcConnector + "=" + commandedDcKw + "kW");
                }

            } catch (Throwable e) {
                if (now - lastErrorLog >= 5000L) {
                    System.err.println("[QC45] LoadManager error: " + e);
                    lastErrorLog = now;
                }
            }

            sleepLoop();
        }

        System.out.println("[QC45] LoadManager stopped");
    }

    private void preArmDc() throws Exception {
        setLimitNative(1, minDcKw);
        setLimitNative(2, minDcKw);
    }

    private boolean needsIdlePreArm() throws Exception {
        return station.globalMaxPower() != minDcKw
            || station.dcMaxPowerFixed() != minDcKw
            || station.limitKw(1) != minDcKw
            || station.limitKw(2) != minDcKw;
    }

    /** Version 1.0 control path. Connector 3 is intentionally rejected. */
    private void setLimitNative(int connector, int kw) throws Exception {
        if (connector != 1 && connector != 2) {
            throw new IllegalArgumentException("LoadManager controls DC connector 1 or 2 only");
        }
        station.setConnectorLimitKw(connector, clamp(kw, minDcKw, maxDcKw));
    }

    private Active detectActiveDc() throws Exception {
        int p1 = station.powerKw(1);
        int p2 = station.powerKw(2);
        String u1 = station.idTag(1);
        String u2 = station.idTag(2);

        boolean c1 = p1 > 0 || u1.length() > 0;
        boolean c2 = p2 > 0 || u2.length() > 0;

        int dcConnector = 0;
        if (c1 && c2) dcConnector = p1 >= p2 ? 1 : 2;
        else if (c1) dcConnector = 1;
        else if (c2) dcConnector = 2;

        return new Active(dcConnector != 0, dcConnector);
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
        final int dcConnector;
        Active(boolean dc, int dcConnector) {
            this.dc = dc;
            this.dcConnector = dcConnector;
        }
    }
}
