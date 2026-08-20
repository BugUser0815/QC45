package de.rothner.qc45;

/** Native QC45 load manager using KOSTAL KSEM phase currents.
 *
 * This is the proven Version 1.0 control logic, restricted to the two DC
 * connectors. Connector 3 (Type2/AC) is deliberately never detected or
 * controlled here. Its consumption is still included in the KSEM phase
 * currents and therefore reduces the headroom available to DC.
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
    private long lastErrorLog;

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
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started DC-only target=" + one(targetA)
            + "A ramp=" + rampUpKwPerLoop + "kW/loop control=v1.0");

        try {
            setLimitNative(1, minDcKw);
            setLimitNative(2, minDcKw);
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager startup reset failed: " + e);
        }

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                double criticalA = currents.max();
                Active active = detectActiveDc();

                boolean newDc = active.dc && (!prevDcActive || active.dcConnector != prevDcConnector);
                if (newDc) {
                    setLimitNative(active.dcConnector, minDcKw);
                    prevDcActive = true;
                    prevDcConnector = active.dcConnector;
                    System.out.println("[QC45] LoadManager session start DC=" + active.dcConnector);
                    sleepLoop();
                    continue;
                }

                if (!active.dc && prevDcActive) {
                    if (prevDcConnector > 0) setLimitNative(prevDcConnector, minDcKw);
                    prevDcActive = false;
                    prevDcConnector = 0;
                    System.out.println("[QC45] LoadManager session end");
                    sleepLoop();
                    continue;
                }

                if (!active.dc) {
                    sleepLoop();
                    continue;
                }

                prevDcActive = true;
                prevDcConnector = active.dcConnector;

                // GridFailback owns the emergency/reduction area. Do not fight it.
                if (criticalA >= failbackGuardA) {
                    sleepLoop();
                    continue;
                }

                int actualDcKw = station.powerKw(active.dcConnector);
                int reportedDcLimitKw = station.limitKw(active.dcConnector);
                int currentTotalLimitKw = reportedDcLimitKw;
                int minTotalKw = minDcKw;
                int maxTotalKw = maxDcKw;
                double headroomA = targetA - criticalA;

                int totalTargetKw;
                if (Math.abs(headroomA) < hysteresisA) {
                    // Version 1.0 deadband: keep the currently commanded limit.
                    totalTargetKw = currentTotalLimitKw;
                } else if (headroomA < 0.0d) {
                    // Version 1.0 safety rule: when above grid target, reduce from
                    // the commanded limit and never derive a higher limit from
                    // lagging vehicle power.
                    double reducedRawKw = currentTotalLimitKw
                        + headroomA * SQRT3_400_KW_PER_A;
                    totalTargetKw = (int)Math.round(reducedRawKw);
                    totalTargetKw = Math.min(totalTargetKw, currentTotalLimitKw);
                } else {
                    // Proven Version 1.0 fast ramp, normally +2 kW per loop.
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

                // Preserve the Version 1.0 negative-headroom protection.
                if (headroomA < -hysteresisA) {
                    targetDcKw = Math.min(targetDcKw, reportedDcLimitKw);
                }

                if (targetDcKw != reportedDcLimitKw) {
                    setLimitNative(active.dcConnector, targetDcKw);
                    System.out.println("[QC45] LoadManager set grid=" + one(criticalA)
                        + "A DC" + active.dcConnector + "=" + targetDcKw + "kW");
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
