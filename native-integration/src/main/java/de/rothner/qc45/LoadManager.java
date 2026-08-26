package de.rothner.qc45;

/**
 * Native QC45 load manager for one active DC connector and Type2/AC.
 *
 * DC and AC share one KSEM-derived station budget with equal priority. Limits
 * are reduced before another connector is increased, and unreached released
 * power is included in the safety projection.
 */
public final class LoadManager extends Thread {
    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final GridFailback failback;
    private final double targetA;
    private final double commandCeilingA;
    private final double hysteresisA;
    private final int minDcKw;
    private final int maxDcKw;
    private final int minAcKw;
    private final int maxAcKw;
    private final int rampUpKwPerLoop;
    private final int intervalMs;
    private final long demandStableMs;
    private final int demandReserveKw;
    private final DemandTracker dcDemand;
    private final DemandTracker acDemand;

    private volatile boolean running = true;
    private boolean prevDcActive;
    private boolean prevAcActive;
    private int prevDcConnector;
    private int commandedDcKw;
    private int commandedAcKw;
    private long lastErrorLog;
    private long lastIdlePreArmLog;

    public LoadManager(ReflectionQC45 station, KsemClient meter, GridFailback failback,
                       double targetA, double commandCeilingA, double hysteresisA,
                       int minDcKw, int maxDcKw,
                       int minAcKw, int maxAcKw,
                       int rampUpKwPerLoop, int intervalMs,
                       long demandStableMs, int demandReserveKw) {
        super("QC45-LoadManager");
        setDaemon(true);
        if (targetA >= commandCeilingA) {
            throw new IllegalArgumentException("loadmanager.targetA must be below its grid ceiling");
        }
        this.station = station;
        this.meter = meter;
        this.failback = failback;
        this.targetA = targetA;
        this.commandCeilingA = commandCeilingA;
        this.hysteresisA = hysteresisA;
        this.minDcKw = minDcKw;
        this.maxDcKw = maxDcKw;
        this.minAcKw = minAcKw;
        this.maxAcKw = maxAcKw;
        this.rampUpKwPerLoop = rampUpKwPerLoop;
        this.intervalMs = intervalMs;
        this.demandStableMs = Math.max(0L, demandStableMs);
        this.demandReserveKw = Math.max(1, demandReserveKw);
        this.dcDemand = new DemandTracker(this.demandStableMs, this.demandReserveKw);
        this.acDemand = new DemandTracker(this.demandStableMs, this.demandReserveKw);
        this.commandedDcKw = minDcKw;
        this.commandedAcKw = minAcKw;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started AC+DC target=" + one(targetA)
            + "A ceiling=" + one(commandCeilingA) + "A priority=equal ramp="
            + rampUpKwPerLoop + "kW/loop demandStable=" + this.demandStableMs
            + "ms demandReserve=" + this.demandReserveKw + "kW");

        try {
            KsemClient.Currents startupCurrents = meter.readCurrents();
            if ((failback != null && failback.isChargingBlocked())
                    || startupCurrents.max() >= commandCeilingA) {
                commandAllZero("startup grid safety");
            } else {
                preArmAll();
            }
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager startup KSEM/pre-arm failed: " + e);
            try { commandAllZero("startup KSEM unavailable"); }
            catch (Throwable zeroError) {
                System.err.println("[QC45] LoadManager startup zero-limit failed: " + zeroError);
            }
        }

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                double criticalA = currents.max();
                Active active = detectActive();

                if (failback != null && failback.isChargingBlocked()) {
                    resetDemandTracking();
                    commandAllZero("grid safety block");
                    rememberActive(active);
                    sleepLoop();
                    continue;
                }

                // Check the ceiling before applying even a session-start minimum.
                if (criticalA >= commandCeilingA) {
                    resetDemandTracking();
                    commandActive(active, 0, 0);
                    rememberActive(active);
                    System.err.println("[QC45] LoadManager GUARD grid=" + one(criticalA)
                        + "A -> active AC/DC budgets=0kW");
                    sleepLoop();
                    continue;
                }

                boolean newDc = active.dc
                    && (!prevDcActive || active.dcConnector != prevDcConnector);
                boolean newAc = active.ac && !prevAcActive;
                if (newDc) {
                    dcDemand.reset();
                    commandedDcKw = minDcKw;
                    setLimitNative(active.dcConnector, commandedDcKw);
                }
                if (newAc) {
                    acDemand.reset();
                    commandedAcKw = minAcKw;
                    setLimitNative(3, commandedAcKw);
                }
                if (newDc || newAc) {
                    System.out.println("[QC45] LoadManager session start DC="
                        + (active.dc ? String.valueOf(active.dcConnector) : "-")
                        + " AC=" + active.ac + " prearmedDC=" + commandedDcKw
                        + "kW prearmedAC=" + commandedAcKw + "kW");
                }

                if (!active.dc && prevDcActive) {
                    dcDemand.reset();
                    commandedDcKw = minDcKw;
                    if (prevDcConnector > 0) setLimitNative(prevDcConnector, commandedDcKw);
                }
                if (!active.ac && prevAcActive) {
                    acDemand.reset();
                    commandedAcKw = minAcKw;
                    setLimitNative(3, commandedAcKw);
                }

                rememberActive(active);

                if (!active.dc && !active.ac) {
                    resetDemandTracking();
                    commandedDcKw = minDcKw;
                    commandedAcKw = minAcKw;
                    if (needsIdlePreArm()) {
                        preArmAll();
                        if (now - lastIdlePreArmLog >= 5000L) {
                            System.out.println("[QC45] LoadManager idle limits re-armed DC="
                                + minDcKw + "kW AC=" + minAcKw + "kW");
                            lastIdlePreArmLog = now;
                        }
                    }
                    sleepLoop();
                    continue;
                }

                reconcileReportedLimits(active);

                int actualDcKw = active.dc ? station.powerKw(active.dcConnector) : 0;
                int actualAcKw = active.ac ? station.powerKw(3) : 0;

                LoadAllocator.Targets fairTargets = LoadAllocator.plan(
                    active.dc, active.ac,
                    actualDcKw, actualAcKw,
                    commandedDcKw, commandedAcKw,
                    criticalA, targetA, commandCeilingA, hysteresisA,
                    minDcKw, maxDcKw, minAcKw, maxAcKw,
                    rampUpKwPerLoop);

                dcDemand.update(now, active.dc, actualDcKw, commandedDcKw,
                    fairTargets.dcKw, minDcKw);
                acDemand.update(now, active.ac, actualAcKw, commandedAcKw,
                    fairTargets.acKw, minAcKw);

                LoadAllocator.Targets targets = LoadAllocator.redistributeForDemand(
                    fairTargets, actualDcKw, actualAcKw,
                    commandedDcKw, commandedAcKw,
                    dcDemand.isDemandLimited(), acDemand.isDemandLimited(),
                    minDcKw, maxDcKw, minAcKw, maxAcKw,
                    demandReserveKw, rampUpKwPerLoop);

                int oldDcKw = commandedDcKw;
                int oldAcKw = commandedAcKw;
                commandActive(active, targets.dcKw, targets.acKw);

                if ((active.dc && oldDcKw != commandedDcKw)
                        || (active.ac && oldAcKw != commandedAcKw)) {
                    System.out.println("[QC45] LoadManager set grid=" + one(criticalA)
                        + "A DC=" + (active.dc ? commandedDcKw + "kW" : "-")
                        + " AC=" + (active.ac ? commandedAcKw + "kW" : "-")
                        + " actualDC=" + actualDcKw + "kW actualAC=" + actualAcKw
                        + "kW priority=equal demandTransfer="
                        + (targets.dcKw != fairTargets.dcKw
                            || targets.acKw != fairTargets.acKw));
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

    private void reconcileReportedLimits(Active active) throws Exception {
        if (active.dc) {
            int reported = clamp(station.limitKw(active.dcConnector), 0, maxDcKw);
            if (reported > commandedDcKw) {
                setLimitNative(active.dcConnector, commandedDcKw);
                System.err.println("[QC45] LoadManager LIMIT OVERRIDE DC"
                    + active.dcConnector + " EVCSD=" + reported + "kW > released="
                    + commandedDcKw + "kW");
            } else if (reported < commandedDcKw) {
                commandedDcKw = reported;
            }
        }
        if (active.ac) {
            int reported = clamp(station.limitKw(3), 0, maxAcKw);
            if (reported > commandedAcKw) {
                setLimitNative(3, commandedAcKw);
                System.err.println("[QC45] LoadManager LIMIT OVERRIDE AC EVCSD="
                    + reported + "kW > released=" + commandedAcKw + "kW");
            } else if (reported < commandedAcKw) {
                commandedAcKw = reported;
            }
        }
    }

    /** Apply all decreases before any increase so rebalancing cannot overshoot. */
    private void commandActive(Active active, int targetDcKw, int targetAcKw) throws Exception {
        targetDcKw = active.dc ? normalize(targetDcKw, minDcKw, maxDcKw) : commandedDcKw;
        targetAcKw = active.ac ? normalize(targetAcKw, minAcKw, maxAcKw) : commandedAcKw;

        // Demand transfer and a return to equal sharing use the same proven
        // per-loop upward ramp as normal budget growth. Reductions stay immediate.
        if (active.dc && commandedDcKw > 0 && targetDcKw > commandedDcKw) {
            targetDcKw = Math.min(targetDcKw,
                commandedDcKw + Math.max(1, rampUpKwPerLoop));
        }
        if (active.ac && commandedAcKw > 0 && targetAcKw > commandedAcKw) {
            targetAcKw = Math.min(targetAcKw,
                commandedAcKw + Math.max(1, rampUpKwPerLoop));
        }

        if (active.dc && targetDcKw < commandedDcKw) {
            setLimitNative(active.dcConnector, targetDcKw);
            commandedDcKw = targetDcKw;
        }
        if (active.ac && targetAcKw < commandedAcKw) {
            setLimitNative(3, targetAcKw);
            commandedAcKw = targetAcKw;
        }

        if (failback != null && failback.isChargingBlocked()) return;

        if (active.dc && targetDcKw > commandedDcKw) {
            setLimitNative(active.dcConnector, targetDcKw);
            commandedDcKw = targetDcKw;
        }
        if (active.ac && targetAcKw > commandedAcKw) {
            setLimitNative(3, targetAcKw);
            commandedAcKw = targetAcKw;
        }
    }

    private void commandAllZero(String reason) throws Exception {
        boolean changed = commandedDcKw != 0 || commandedAcKw != 0
            || station.limitKw(1) != 0 || station.limitKw(2) != 0 || station.limitKw(3) != 0;
        setLimitNative(1, 0);
        setLimitNative(2, 0);
        setLimitNative(3, 0);
        commandedDcKw = 0;
        commandedAcKw = 0;
        if (changed) System.err.println("[QC45] LoadManager all connector budgets=0kW: " + reason);
    }

    private void preArmAll() throws Exception {
        setLimitNative(1, minDcKw);
        setLimitNative(2, minDcKw);
        setLimitNative(3, minAcKw);
        commandedDcKw = minDcKw;
        commandedAcKw = minAcKw;
    }

    private boolean needsIdlePreArm() throws Exception {
        return station.globalMaxPower() != minDcKw
            || station.dcMaxPowerFixed() != minDcKw
            || station.maxPowerAC() != minAcKw
            || station.acMaxPowerFixed() != minAcKw
            || station.limitKw(1) != minDcKw
            || station.limitKw(2) != minDcKw
            || station.limitKw(3) != minAcKw;
    }

    private void setLimitNative(int connector, int kw) throws Exception {
        int min = connector == 3 ? minAcKw : minDcKw;
        int max = connector == 3 ? maxAcKw : maxDcKw;
        station.setConnectorLimitKw(connector, normalize(kw, min, max));
    }

    private Active detectActive() throws Exception {
        int p1 = station.powerKw(1);
        int p2 = station.powerKw(2);
        int p3 = station.powerKw(3);
        String u1 = station.idTag(1);
        String u2 = station.idTag(2);
        String u3 = station.idTag(3);

        boolean c1 = p1 > 0 || u1.length() > 0;
        boolean c2 = p2 > 0 || u2.length() > 0;
        boolean ac = p3 > 0 || u3.length() > 0;

        int dcConnector = 0;
        if (c1 && c2) dcConnector = p1 >= p2 ? 1 : 2;
        else if (c1) dcConnector = 1;
        else if (c2) dcConnector = 2;

        return new Active(dcConnector != 0, ac, dcConnector);
    }

    private void rememberActive(Active active) {
        prevDcActive = active.dc;
        prevAcActive = active.ac;
        prevDcConnector = active.dc ? active.dcConnector : 0;
    }

    private void resetDemandTracking() {
        dcDemand.reset();
        acDemand.reset();
    }

    private void sleepLoop() {
        try { Thread.sleep(intervalMs); }
        catch (InterruptedException e) { /* shutdown checked by loop */ }
    }

    private static int normalize(int value, int min, int max) {
        if (value < min) return 0;
        return clamp(value, min, max);
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
