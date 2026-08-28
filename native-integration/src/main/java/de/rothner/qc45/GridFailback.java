package de.rothner.qc45;

/** Independent, KSEM-backed last-resort protection for AC and DC. */
public final class GridFailback extends Thread {
    private static final int HEALTHY_READS_TO_RESUME = 5;
    static final double SLS_NOMINAL_A = 35.0d;
    // Use the lower current boundary of the magnetic E-characteristic. This
    // deliberately assumes a preloaded SLS instead of a cold upper tolerance.
    static final double SLS_E_INSTANT_MULTIPLIER = 5.0d;
    static final double SLS_E_INSTANT_A = SLS_NOMINAL_A * SLS_E_INSTANT_MULTIPLIER;

    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final ChargingLimitCoordinator limits;
    private final double reduceA;
    private final long reduceDelayMs;
    private final double tripA;
    private final long tripDelayMs;
    private final double instantTripA;
    private final int reduceDcKw;
    private final int reduceAcKw;
    private final int intervalMs;
    private final boolean autoResetHardTrip;
    private final long resetDelayMs;

    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile boolean meterPaused = true;
    private volatile boolean overLimitPaused;
    private long reduceSince;
    private long tripSince;
    private long currentTripDelayMs = Long.MAX_VALUE;
    private long resetSince;
    private long lastGridLog;
    private long lastControlErrorLog;
    private long lastMeterErrorLog;
    private long lastResetDeferredLog;
    private long lastStopErrorLog;
    private final long[] lastStopAttempt = new long[] { 0L, 0L, 0L, 0L };
    private int goodMeterReads;
    private int goodOverLimitReads;
    private int goodEStopReleaseReads;
    private boolean sawEStopPressed;
    private boolean stageReduced;

    public GridFailback(ReflectionQC45 station, KsemClient meter,
                        ChargingLimitCoordinator limits,
                        double reduceA, long reduceDelayMs,
                        double tripA, long tripDelayMs, double instantTripA,
                        int reduceDcKw, int reduceAcKw, int intervalMs,
                        boolean autoResetHardTrip, long resetDelayMs) {
        super("QC45-Grid-Failback");
        setDaemon(true);
        if (station == null || meter == null || limits == null) throw new IllegalArgumentException("station, meter and limits are required");
        if (reduceA <= 0.0d || reduceA >= tripA || tripA >= instantTripA
                || reduceDelayMs < 0L || tripDelayMs < 0L || intervalMs <= 0
                || reduceDcKw < 0 || reduceAcKw < 0 || resetDelayMs < 0L
                || (autoResetHardTrip && resetDelayMs == 0L)) {
            throw new IllegalArgumentException("invalid failback thresholds or timing");
        }
        this.station = station;
        this.meter = meter;
        this.limits = limits;
        this.reduceA = reduceA;
        this.reduceDelayMs = reduceDelayMs;
        this.tripA = tripA;
        this.tripDelayMs = tripDelayMs;
        this.instantTripA = instantTripA;
        this.reduceDcKw = reduceDcKw;
        this.reduceAcKw = reduceAcKw;
        this.intervalMs = intervalMs;
        this.autoResetHardTrip = autoResetHardTrip;
        this.resetDelayMs = resetDelayMs;
    }

    public boolean isTripped() { return tripped; }
    public boolean isMeterPaused() { return meterPaused; }
    public boolean isChargingBlocked() { return tripped || meterPaused || overLimitPaused; }

    public void shutdown() {
        safeSetBlocked(true);
        running = false;
        interrupt();
    }

    public void run() {
        safeSetBlocked(true);
        System.out.println("[QC45] GridFailback started AC+DC hard-trip-reset="
            + (autoResetHardTrip ? "timed(" + resetDelayMs + "ms)" : "E-STOP press+release")
            + " stable-below=" + one(reduceA) + "A SLS=E35 instant="
            + one(instantTripA) + "A");

        while (running) {
            long now = System.currentTimeMillis();
            KsemClient.Currents currents;
            try {
                currents = meter.readCurrents();
            } catch (Throwable e) {
                onMeterFailure(now, e);
                if (tripped) retryRemoteStops(now);
                sleepLoop();
                continue;
            }

            double max = currents.max();
            if (now - lastGridLog >= 5000L) {
                System.out.println("[QC45] Grid L1=" + one(currents.l1) + "A L2="
                    + one(currents.l2) + "A L3=" + one(currents.l3) + "A max="
                    + one(max) + "A" + stateSuffix());
                lastGridLog = now;
            }

            try {
                evaluate(now, max);
            } catch (Throwable e) {
                safeSetBlocked(true);
                if (now - lastControlErrorLog >= 5000L) {
                    System.err.println("[QC45] GridFailback control failure -> AC/DC=0kW: " + e);
                    lastControlErrorLog = now;
                }
            }
            try {
                limits.reconcile();
            } catch (Throwable e) {
                safeSetBlocked(true);
                if (now - lastControlErrorLog >= 5000L) {
                    System.err.println("[QC45] GridFailback limit reconciliation failed -> AC/DC=0kW: " + e);
                    lastControlErrorLog = now;
                }
            }
            if (tripped) retryRemoteStops(now);
            sleepLoop();
        }
        System.out.println("[QC45] GridFailback stopped");
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

        if (max >= tripA) {
            goodOverLimitReads = 0;
            goodMeterReads = 0;
            long requiredDelayMs = requiredHardTripDelayMs(max, tripDelayMs);
            if (!overLimitPaused) {
                overLimitPaused = true;
                meterPaused = false;
                safeSetBlocked(true);
                System.err.println("[QC45] GRID FAILBACK OVER-LIMIT PAUSE: " + one(max)
                    + "A >= " + one(tripA) + "A -> AC/DC=0kW; SLS-E hard-trip="
                    + delayDescription(requiredDelayMs));
            }
            if (requiredDelayMs == Long.MAX_VALUE) {
                // Up to 1.05 x In is inside the SLS-E non-tripping test range.
                // The charging pause stays active, but it must not accumulate a
                // latched trip that would require an E-STOP reset.
                tripSince = 0L;
                currentTripDelayMs = Long.MAX_VALUE;
            } else if (tripSince == 0L) {
                tripSince = now;
                currentTripDelayMs = requiredDelayMs;
            } else {
                currentTripDelayMs = requiredDelayMs;
                if (now - tripSince >= requiredDelayMs) {
                    hardTrip("SLS-E time/current envelope exceeded at " + one(max)
                        + "A after " + (now - tripSince) + "ms (required "
                        + requiredDelayMs + "ms)");
                }
            }
            return;
        }

        // A trip timer represents continuous exposure and must also reset in
        // the 34..35 A band.
        tripSince = 0L;
        currentTripDelayMs = Long.MAX_VALUE;

        if (meterPaused) {
            if (max < reduceA) {
                goodMeterReads++;
                if (goodMeterReads >= HEALTHY_READS_TO_RESUME) {
                    prepareSafeResume();
                    limits.setBlocked(ChargingLimitCoordinator.FAILBACK, false);
                    meterPaused = false;
                    goodMeterReads = 0;
                    System.out.println("[QC45] GRID FAILBACK KSEM RECOVERED: five valid reads; charging may ramp");
                }
            } else {
                goodMeterReads = 0;
            }
            return;
        }

        if (overLimitPaused) {
            if (max < reduceA) {
                goodOverLimitReads++;
                if (goodOverLimitReads >= HEALTHY_READS_TO_RESUME) {
                    prepareSafeResume();
                    limits.setBlocked(ChargingLimitCoordinator.FAILBACK, false);
                    overLimitPaused = false;
                    goodOverLimitReads = 0;
                    System.out.println("[QC45] GRID FAILBACK OVER-LIMIT RECOVERED: five reads below "
                        + one(reduceA) + "A");
                }
            } else {
                goodOverLimitReads = 0;
            }
            return;
        }

        if (max >= reduceA) {
            if (reduceSince == 0L) reduceSince = now;
            if (!stageReduced && now - reduceSince >= reduceDelayMs) {
                limits.setStageCaps(reduceDcKw, reduceAcKw);
                stageReduced = true;
                System.err.println("[QC45] GRID FAILBACK REDUCE: DC<=" + reduceDcKw
                    + "kW AC<=" + reduceAcKw + "kW");
            }
        } else {
            reduceSince = 0L;
            if (stageReduced) {
                prepareSafeResume();
                limits.clearStageCaps();
                stageReduced = false;
                System.out.println("[QC45] GRID FAILBACK REDUCTION CLEARED");
            }
        }
    }

    private void onMeterFailure(long now, Throwable error) {
        goodMeterReads = 0;
        resetSince = 0L;
        tripSince = 0L;
        currentTripDelayMs = Long.MAX_VALUE;
        if (!tripped) {
            meterPaused = true;
            overLimitPaused = false;
        }
        safeSetBlocked(true);
        if (now - lastMeterErrorLog >= 5000L) {
            System.err.println("[QC45] GridFailback KSEM failure -> AC/DC=0kW: " + error);
            lastMeterErrorLog = now;
        }
    }

    private synchronized void hardTrip(String reason) {
        if (tripped) return;
        tripped = true;
        meterPaused = false;
        overLimitPaused = false;
        resetSince = 0L;
        goodEStopReleaseReads = 0;
        sawEStopPressed = false;
        safeSetBlocked(true);
        System.err.println("[QC45] GRID FAILBACK HARD TRIP: " + reason
            + " [latched; reset=" + (autoResetHardTrip ? "timed" : "E-STOP press+release") + "]");
    }

    private void evaluateHardTripReset(long now, double max) throws Exception {
        if (max >= tripA) {
            resetSince = 0L;
            goodEStopReleaseReads = 0;
            return;
        }

        if (autoResetHardTrip) {
            if (max >= reduceA) {
                resetSince = 0L;
            } else if (resetSince == 0L) {
                resetSince = now;
            } else if (now - resetSince >= resetDelayMs) {
                clearHardTrip("grid stable for " + (now - resetSince) + "ms");
            }
            return;
        }

        boolean pressed = station.emergencyStopPressed();
        if (pressed) {
            sawEStopPressed = true;
            goodEStopReleaseReads = 0;
        } else if (sawEStopPressed && max < reduceA) {
            goodEStopReleaseReads++;
            if (goodEStopReleaseReads >= HEALTHY_READS_TO_RESUME) {
                clearHardTrip("E-STOP press+release and five safe KSEM reads");
            }
        } else {
            goodEStopReleaseReads = 0;
        }
    }

    private synchronized void clearHardTrip(String reason) throws Exception {
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
        reduceSince = 0L;
        tripSince = 0L;
        currentTripDelayMs = Long.MAX_VALUE;
        resetSince = 0L;
        sawEStopPressed = false;
        goodEStopReleaseReads = 0;
        stageReduced = false;
        System.out.println("[QC45] GRID FAILBACK RESET: " + reason);
    }

    private void retryRemoteStops(long now) {
        for (int connector = 1; connector <= 3; connector++) {
            if (now - lastStopAttempt[connector] < 2000L) continue;
            lastStopAttempt[connector] = now;
            try {
                if (station.sessionActive(connector)) station.remoteStop(connector);
            } catch (Throwable e) {
                if (now - lastStopErrorLog >= 5000L) {
                    System.err.println("[QC45] hard-trip RemoteStop retry connector="
                        + connector + " failed: " + e);
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
        // Never expose an allocation calculated before the safety block. The
        // LoadManager must publish a fresh target from a post-recovery reading.
        limits.setGridTargets(0, false, 0, 0);
    }

    private void safeSetBlocked(boolean blocked) {
        try { limits.setBlocked(ChargingLimitCoordinator.FAILBACK, blocked); }
        catch (Throwable e) { System.err.println("[QC45] failback limit enforcement failed: " + e); }
    }

    private String stateSuffix() {
        if (tripped) return " HARD-TRIPPED";
        if (meterPaused) return " METER-PAUSED";
        if (overLimitPaused) return " OVER-LIMIT-PAUSED";
        if (stageReduced) return " REDUCED";
        return "";
    }

    private void sleepLoop() {
        long sleepMs = intervalMs;
        long now = System.currentTimeMillis();
        if (!tripped && overLimitPaused && tripSince > 0L) {
            long remaining = currentTripDelayMs - (now - tripSince);
            if (remaining > 0L) sleepMs = Math.min(sleepMs, remaining);
        }
        if (!tripped && !stageReduced && reduceSince > 0L) {
            long remaining = reduceDelayMs - (now - reduceSince);
            if (remaining > 0L) sleepMs = Math.min(sleepMs, remaining);
        }
        try { Thread.sleep(Math.max(1L, sleepMs)); }
        catch (InterruptedException e) { if (!running) return; }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    /**
     * Conservative software envelope derived from the SLS E time/current
     * characteristic. The charger is already blocked at tripA; this delay only
     * decides whether the event must additionally become a latched hard trip.
     */
    static long requiredHardTripDelayMs(double currentA, long minimumDelayMs) {
        double multiple = currentA / SLS_NOMINAL_A;
        long characteristicDelayMs;
        if (multiple < 1.05d) return Long.MAX_VALUE;
        if (multiple < 1.20d) characteristicDelayMs = 3600000L;
        else if (multiple < 1.50d) characteristicDelayMs = 300000L;
        else if (multiple < 2.00d) characteristicDelayMs = 60000L;
        else if (multiple < 3.00d) characteristicDelayMs = 10000L;
        else if (multiple < 5.00d) characteristicDelayMs = 1000L;
        else characteristicDelayMs = 100L;
        return Math.max(minimumDelayMs, characteristicDelayMs);
    }

    private static String delayDescription(long delayMs) {
        if (delayMs == Long.MAX_VALUE) return "disabled below 1.05xIn";
        if (delayMs >= 60000L && delayMs % 60000L == 0L) {
            return (delayMs / 60000L) + "min continuous";
        }
        if (delayMs >= 1000L && delayMs % 1000L == 0L) {
            return (delayMs / 1000L) + "s continuous";
        }
        return delayMs + "ms continuous";
    }
}
