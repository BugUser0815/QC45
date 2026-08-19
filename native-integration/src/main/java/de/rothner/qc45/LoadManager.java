package de.rothner.qc45;

/** Native QC45 load manager using KOSTAL KSEM phase currents. */
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
            + "A ramp=" + rampUpKwPerLoop + "kW/loop");

        try {
            setLimitNative(1, minDcKw);
            setLimitNative(2, minDcKw);
            setLimitNative(3, minAcKw);
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
                    if (newDc) setLimitNative(active.dcConnector, minDcKw);
                    if (newAc) setLimitNative(3, minAcKw);
                    prevDcActive = active.dc;
                    prevAcActive = active.ac;
                    prevDcConnector = active.dcConnector;
                    System.out.println("[QC45] LoadManager session start DC="
                        + (active.dc ? String.valueOf(active.dcConnector) : "-")
                        + " AC=" + active.ac);
                    sleepLoop();
                    continue;
                }

                boolean sessionEnded = false;
                if (!active.dc && prevDcActive) {
                    if (prevDcConnector > 0) setLimitNative(prevDcConnector, minDcKw);
                    sessionEnded = true;
                }
                if (!active.ac && prevAcActive) {
                    setLimitNative(3, minAcKw);
                    sessionEnded = true;
                }
                prevDcActive = active.dc;
                prevAcActive = active.ac;
                if (active.dc) prevDcConnector = active.dcConnector;

                if (sessionEnded) {
                    System.out.println("[QC45] LoadManager session end");
                    sleepLoop();
                    continue;
                }

                if (!active.dc && !active.ac) {
                    sleepLoop();
                    continue;
                }

                // GridFailback owns the emergency/reduction area. Do not fight it.
                if (criticalA >= failbackGuardA) {
                    sleepLoop();
                    continue;
                }

                int actualDcKw = active.dc ? station.powerKw(active.dcConnector) : 0;
                int actualAcKw = active.ac ? station.powerKw(3) : 0;
                int actualTotalKw = actualDcKw + actualAcKw;

                int reportedDcLimitKw = active.dc ? station.limitKw(active.dcConnector) : 0;
                int reportedAcLimitKw = active.ac ? station.limitKw(3) : 0;
                int currentTotalLimitKw = reportedDcLimitKw + reportedAcLimitKw;

                int minTotalKw = (active.dc ? minDcKw : 0) + (active.ac ? minAcKw : 0);
                int maxTotalKw = (active.dc ? maxDcKw : 0) + (active.ac ? maxAcKw : 0);
                double headroomA = targetA - criticalA;

                int totalTargetKw;
                if (Math.abs(headroomA) < hysteresisA) {
                    // Deadband: keep the currently commanded limit.
                    totalTargetKw = currentTotalLimitKw;
                } else if (headroomA < 0.0d) {
                    // Important safety rule: once the grid is above target, NEVER
                    // derive a higher target from a lagging/overshooting actual power.
                    // Reduce from the currently commanded limit instead.
                    double reducedRawKw = currentTotalLimitKw
                        + headroomA * SQRT3_400_KW_PER_A;
                    totalTargetKw = (int)Math.round(reducedRawKw);
                    totalTargetKw = Math.min(totalTargetKw, currentTotalLimitKw);
                } else {
                    // Below target: preserve the proven legacy behaviour and ramp up
                    // quickly, default +2 kW per 1 s loop.
                    double requestedRawKw = actualTotalKw
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

                Targets targets = allocateFromActual(active, totalTargetKw,
                    actualDcKw, actualAcKw);

                // A negative headroom must not increase either connector as a side
                // effect of allocation from actual power.
                if (headroomA < -hysteresisA) {
                    if (active.dc) targets.dcKw = Math.min(targets.dcKw, reportedDcLimitKw);
                    if (active.ac) targets.acKw = Math.min(targets.acKw, reportedAcLimitKw);
                }

                boolean writeDc = active.dc && targets.dcKw != reportedDcLimitKw;
                boolean writeAc = active.ac && targets.acKw != reportedAcLimitKw;

                if (writeDc) setLimitNative(active.dcConnector, targets.dcKw);
                if (writeAc) setLimitNative(3, targets.acKw);

                if (writeDc || writeAc) {
                    System.out.println("[QC45] LoadManager set grid=" + one(criticalA)
                        + "A DC=" + (active.dc ? targets.dcKw + "kW" : "-")
                        + " AC=" + (active.ac ? targets.acKw + "kW" : "-"));
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

    private void setLimitNative(int connector, int kw) throws Exception {
        int max = connector == 3 ? maxAcKw : maxDcKw;
        int min = connector == 3 ? minAcKw : minDcKw;
        station.setConnectorLimitKw(connector, clamp(kw, min, max));
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
        int dcKw;
        int acKw;
        Targets(int dcKw, int acKw) {
            this.dcKw = dcKw;
            this.acKw = acKw;
        }
    }
}
