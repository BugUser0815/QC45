package de.rothner.qc45;

/** Independent, KSEM-backed proportional protection and thermal hard-trip. */
public final class GridFailback extends Thread {
    private static final int HEALTHY_READS_TO_RESUME = 5;
    private static final long REDUCTION_SETTLE_MS = 1000L;
    static final long MIN_HARD_TRIP_RESET_DELAY_MS = 60000L;

    private final ChargingSessionIo station;
    private final GridCurrentIo meter;
    private final ChargingLimitCoordinator limits;
    private final double nominalA;
    private final SlsThermalModel thermal;
    private final double reduceA;
    private final long reduceDelayMs;
    private final double tripA;
    private final long tripDelayMs;
    private final double instantTripA;
    private final int reduceDcKw;
    private final int reduceAcKw;
    private final int intervalMs;
    private final long resetDelayMs;

    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile boolean meterPaused = true;
    private long reduceSince = -1L;
    private long tripSince = -1L;
    private long lastReductionAt = -1L;
    private long resetSince;
    private long lastGridLog;
    private long lastErrorLog;
    private long lastResetDeferredLog;
    private long lastStopErrorLog;
    private final long[] lastStopAttempt = new long[] { 0L, 0L, 0L, 0L };
    private int goodMeterReads;
    private int goodRecoveryReads;
    private boolean stageReduced;
    private int dcCapKw = Integer.MAX_VALUE;
    private int acCapKw = Integer.MAX_VALUE;

    public GridFailback(ReflectionQC45 station, KsemClient meter,
                        ChargingLimitCoordinator limits,
                        double reduceA, long reduceDelayMs,
                        double tripA, long tripDelayMs, double instantTripA,
                        int reduceDcKw, int reduceAcKw, int intervalMs,
                        long resetDelayMs, double nominalA, long thermalTimeConstantMs) {
        this((ChargingSessionIo)station, (GridCurrentIo)meter, limits,
            reduceA, reduceDelayMs, tripA, tripDelayMs, instantTripA,
            reduceDcKw, reduceAcKw, intervalMs, resetDelayMs, nominalA, thermalTimeConstantMs);
    }

    GridFailback(ChargingSessionIo station, GridCurrentIo meter,
                 ChargingLimitCoordinator limits,
                 double reduceA, long reduceDelayMs,
                 double tripA, long tripDelayMs, double instantTripA,
                 int reduceDcKw, int reduceAcKw, int intervalMs,
                 long resetDelayMs, double nominalA, long thermalTimeConstantMs) {
        super("QC45-Grid-Failback");
        setDaemon(true);
        if (station == null || meter == null || limits == null) {
            throw new IllegalArgumentException("station, meter and limits are required");
        }
        Integration.validateFailbackThresholds(reduceA, tripA, instantTripA, nominalA);
        if (reduceDelayMs < 0L || tripDelayMs < 0L || intervalMs <= 0
                || reduceDcKw < 0 || reduceAcKw < 0
                || resetDelayMs < MIN_HARD_TRIP_RESET_DELAY_MS) {
            throw new IllegalArgumentException("invalid failback timing or power limits");
        }
        this.station = station;
        this.meter = meter;
        this.limits = limits;
        this.nominalA = nominalA;
        this.thermal = new SlsThermalModel(nominalA, thermalTimeConstantMs);
        this.reduceA = reduceA;
        this.reduceDelayMs = reduceDelayMs;
        this.tripA = tripA;
        this.tripDelayMs = tripDelayMs;
        this.instantTripA = instantTripA;
        this.reduceDcKw = reduceDcKw;
        this.reduceAcKw = reduceAcKw;
        this.intervalMs = intervalMs;
        this.resetDelayMs = resetDelayMs;
    }

    public boolean isTripped() { return tripped; }
    public boolean isMeterPaused() { return meterPaused; }
    public boolean isChargingBlocked() { return tripped || meterPaused; }
    double thermalExposure() { return thermal.exposure(); }

    public void shutdown() {
        safeSetBlocked(true);
        running = false;
        interrupt();
    }

    public void run() {
        safeSetBlocked(true);
        System.out.println("[QC45] GridFailback started SLS=E" + one(nominalA)
            + " reduce=" + one(reduceA) + "A fastReduce=" + one(tripA)
            + "A instant=" + one(instantTripA) + "A proportional=true thermal=per-phase"
            + " reset=" + resetDelayMs + "ms");
        while (running) {
            runCycle(System.currentTimeMillis());
            try { Thread.sleep(intervalMs); }
            catch (InterruptedException e) { if (!running) break; }
        }
        System.out.println("[QC45] GridFailback stopped");
    }

    void runCycle(long now) {
        try {
            KsemClient.Currents currents = meter.readCurrents();
            thermal.update(now, currents);
            double max = currents.max();
            if (now - lastGridLog >= 5000L) {
                System.out.println("[QC45] Grid L1=" + one(currents.l1) + "A L2="
                    + one(currents.l2) + "A L3=" + one(currents.l3) + "A max="
                    + one(max) + "A SLS=" + one(nominalA)
                    + "A thermal=" + one(100.0d * thermal.exposure()) + "%"
                    + (tripped ? " HARD-TRIPPED" : meterPaused ? " METER-PAUSED"
                        : stageReduced ? " PROPORTIONALLY-REDUCED" : ""));
                lastGridLog = now;
            }
            evaluate(now, max);
            limits.reconcile();
        } catch (Throwable error) {
            onMeterOrControlFailure(now, error);
        }
        if (tripped) retryRemoteStops(now);
    }

    private void evaluate(long now, double max) throws Exception {
        if (tripped) {
            evaluateHardTripReset(now, max);
            return;
        }
        if (max >= instantTripA) {
            hardTrip("instant phase current " + one(max) + "A >= " + one(instantTripA) + "A");
            return;
        }
        if (thermal.overloaded()) {
            hardTrip("accumulated SLS E" + one(nominalA) + " thermal exposure at "
                + one(max) + "A");
            return;
        }

        if (meterPaused) {
            if (max < Math.min(reduceA, nominalA)) {
                goodMeterReads++;
                if (goodMeterReads >= HEALTHY_READS_TO_RESUME) {
                    prepareSafeResume();
                    limits.setBlocked(ChargingLimitCoordinator.FAILBACK, false);
                    meterPaused = false;
                    goodMeterReads = 0;
                    System.out.println("[QC45] GRID FAILBACK KSEM RECOVERED: five valid reads");
                }
            } else {
                goodMeterReads = 0;
            }
            return;
        }

        if (max >= reduceA) {
            goodRecoveryReads = 0;
            if (reduceSince < 0L || now < reduceSince) reduceSince = now;
        } else {
            reduceSince = -1L;
        }
        if (max >= tripA) {
            goodRecoveryReads = 0;
            if (tripSince < 0L || now < tripSince) tripSince = now;
        } else {
            tripSince = -1L;
        }

        boolean fastReduction = tripSince >= 0L && now - tripSince >= tripDelayMs;
        boolean reduction = reduceSince >= 0L && now - reduceSince >= reduceDelayMs;
        if (fastReduction || reduction) {
            if (now < lastReductionAt) lastReductionAt = -1L;
            if (lastReductionAt < 0L || now - lastReductionAt >= REDUCTION_SETTLE_MS) {
                reduceProportionally(now, max, fastReduction);
            }
        } else if (max < Math.min(reduceA, nominalA) && stageReduced) {
            if (++goodRecoveryReads >= HEALTHY_READS_TO_RESUME) {
                // Keep the present allocation. Clearing a cap must neither
                // restore an old high value nor reset a good session to 5 kW.
                limits.releaseStageCapsAtCurrentTargets();
                stageReduced = false;
                dcCapKw = Integer.MAX_VALUE;
                acCapKw = Integer.MAX_VALUE;
                lastReductionAt = -1L;
                goodRecoveryReads = 0;
                System.out.println("[QC45] GRID FAILBACK REDUCTION CLEARED at current targets");
            }
        } else {
            goodRecoveryReads = 0;
        }
    }

    private void reduceProportionally(long now, double max, boolean fast) throws Exception {
        ChargingLimitCoordinator.Snapshot snapshot = limits.snapshot();
        double excessA = Math.max(0.0d, max - Math.min(nominalA, reduceA));
        int nextDc = dcCapKw;
        int nextAc = acCapKw;
        if (snapshot.activeDcConnector > 0) {
            nextDc = reducedCap(snapshot.activeDcConnector,
                snapshot.effectiveDcKw, dcCapKw, reduceDcKw, excessA, 0.692820323d);
        }
        if (limits.acManaged() && snapshot.acActive) {
            nextAc = reducedCap(3, snapshot.effectiveAcKw, acCapKw,
                reduceAcKw, excessA, 0.23d);
        }
        lastReductionAt = now;
        if (nextDc == dcCapKw && nextAc == acCapKw) return;
        limits.setStageCaps(nextDc, nextAc);
        dcCapKw = nextDc;
        acCapKw = nextAc;
        stageReduced = true;
        System.err.println("[QC45] GRID FAILBACK PROPORTIONAL REDUCE max=" + one(max)
            + "A SLS=" + one(nominalA) + "A fast=" + fast + " DCcap=" + cap(nextDc)
            + " ACcap=" + cap(nextAc) + " thermal=" + one(100.0d * thermal.exposure()) + "%");
    }

    private int reducedCap(int connector, int enforcedKw, int previousCapKw,
                           int floorKw, double excessA, double kwPerA) throws Exception {
        int actualKw = station.powerKw(connector);
        if (actualKw < 0) throw new IllegalArgumentException("invalid connector power");
        int decrement = Math.max(1, (int)Math.ceil(excessA * kwPerA));
        // An unimplemented decrease is not subtracted from the command again.
        int respondingKw = Math.max(enforcedKw, actualKw);
        int next = Math.max(floorKw, respondingKw - decrement);
        return Math.min(previousCapKw, Math.min(Math.max(floorKw, enforcedKw), next));
    }

    private void onMeterOrControlFailure(long now, Throwable error) {
        goodMeterReads = 0;
        goodRecoveryReads = 0;
        resetSince = 0L;
        reduceSince = -1L;
        tripSince = -1L;
        thermal.forgetReading();
        if (!tripped) meterPaused = true;
        safeSetBlocked(true);
        if (now - lastErrorLog >= 5000L) {
            System.err.println("[QC45] GridFailback measurement/control failure -> Notladen: " + error);
            lastErrorLog = now;
        }
    }

    private synchronized void hardTrip(String reason) {
        if (tripped) return;
        tripped = true;
        meterPaused = false;
        resetSince = 0L;
        safeSetBlocked(true);
        System.err.println("[QC45] GRID FAILBACK HARD TRIP: " + reason
            + " [latched; reset after cooling and " + resetDelayMs + "ms stable readings]");
    }

    private void evaluateHardTripReset(long now, double max) throws Exception {
        if (max >= Math.min(reduceA, nominalA) || thermal.exposure() > 0.25d) {
            resetSince = 0L;
            return;
        }
        if (resetSince == 0L || now < resetSince) resetSince = now;
        else if (now - resetSince >= resetDelayMs) clearHardTrip();
    }

    private synchronized void clearHardTrip() throws Exception {
        if (!tripped) return;
        if (anySessionActive()) {
            long now = System.currentTimeMillis();
            if (now - lastResetDeferredLog >= 5000L) {
                System.err.println("[QC45] GRID FAILBACK reset deferred: charging session still active");
                lastResetDeferredLog = now;
            }
            return;
        }
        prepareSafeResume();
        limits.clearStageCaps();
        limits.setBlocked(ChargingLimitCoordinator.FAILBACK, false);
        tripped = false;
        reduceSince = -1L;
        tripSince = -1L;
        lastReductionAt = -1L;
        resetSince = 0L;
        stageReduced = false;
        dcCapKw = Integer.MAX_VALUE;
        acCapKw = Integer.MAX_VALUE;
        // Retain actual thermal history; a reset is not instantaneous cooling.
        System.out.println("[QC45] GRID FAILBACK RESET: grid stable and thermal exposure recovered");
    }

    private void retryRemoteStops(long now) {
        for (int connector = 1; connector <= (limits.acManaged() ? 3 : 2); connector++) {
            if (lastStopAttempt[connector] > 0L && now - lastStopAttempt[connector] < 2000L) continue;
            lastStopAttempt[connector] = now;
            try {
                if (station.sessionActive(connector)) station.remoteStop(connector);
            } catch (Throwable error) {
                if (now - lastStopErrorLog >= 5000L) {
                    System.err.println("[QC45] hard-trip RemoteStop retry connector="
                        + connector + " failed: " + error);
                    lastStopErrorLog = now;
                }
            }
        }
    }

    private boolean anySessionActive() throws Exception {
        for (int connector = 1; connector <= 3; connector++) {
            if (station.sessionActive(connector)) return true;
        }
        return false;
    }

    private void prepareSafeResume() throws Exception {
        limits.setGridTargets(0, false, 0, 0);
    }

    private void safeSetBlocked(boolean blocked) {
        try { limits.setBlocked(ChargingLimitCoordinator.FAILBACK, blocked); }
        catch (Throwable error) { System.err.println("[QC45] failback limit enforcement failed: " + error); }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    private static String cap(int kw) {
        return kw == Integer.MAX_VALUE ? "none" : kw + "kW";
    }
}
