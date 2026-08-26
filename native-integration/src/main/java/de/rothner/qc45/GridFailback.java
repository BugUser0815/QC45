package de.rothner.qc45;

/**
 * Independent grid-current failback for DC and Type2/AC.
 *
 * Stage 1 reduces both budgets. Reaching tripA immediately blocks and zeros all
 * charging; a persistent excess or instantTripA stops all connectors and latches.
 * KSEM communication loss also sets DC and AC to 0 kW while transactions remain.
 *
 * A hard-trip latch clears automatically after resetDelayMs of continuous valid KSEM readings
 * with every phase below reduceA. Any overcurrent or KSEM read failure restarts the timer.
 */
public final class GridFailback extends Thread {
    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final double reduceA;
    private final long reduceDelayMs;
    private final double tripA;
    private final long tripDelayMs;
    private final double instantTripA;
    private final int reduceDcKw;
    private final int reduceAcKw;
    private final int intervalMs;
    private final boolean tripOnMeterFailure;
    private final long meterFailureMs;
    private final long resetDelayMs;

    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile boolean meterPaused;
    private volatile boolean overLimitPaused;
    private long reduceSince;
    private long tripSince;
    private long resetSince;
    private long lastGoodRead;
    private long lastLog;
    private int goodReadsAfterMeterPause;
    private int goodReadsAfterOverLimit;
    private long lastBlockedEnforce;

    public GridFailback(ReflectionQC45 station, KsemClient meter, double reduceA, long reduceDelayMs,
                        double tripA, long tripDelayMs, double instantTripA, int reduceDcKw, int reduceAcKw,
                        int intervalMs, boolean tripOnMeterFailure, long meterFailureMs, long resetDelayMs) {
        super("QC45-Grid-Failback");
        setDaemon(true);
        this.station = station;
        this.meter = meter;
        this.reduceA = reduceA;
        this.reduceDelayMs = reduceDelayMs;
        this.tripA = tripA;
        this.tripDelayMs = tripDelayMs;
        this.instantTripA = instantTripA;
        this.reduceDcKw = reduceDcKw;
        this.reduceAcKw = reduceAcKw;
        this.intervalMs = intervalMs;
        this.tripOnMeterFailure = tripOnMeterFailure;
        this.meterFailureMs = meterFailureMs;
        this.resetDelayMs = resetDelayMs;
    }

    public boolean isTripped() { return tripped; }
    public boolean isMeterPaused() { return meterPaused; }
    public boolean isChargingBlocked() { return tripped || meterPaused || overLimitPaused; }
    public void shutdown() { running = false; interrupt(); }

    public void run() {
        lastGoodRead = System.currentTimeMillis();
        System.out.println("[QC45] GridFailback started AC+DC auto-reset=" + resetDelayMs
            + "ms stable-below=" + one(reduceA) + "A");

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents c = meter.readCurrents();
                lastGoodRead = now;
                double max = c.max();

                if (now - lastLog >= 5000L || max >= reduceA || tripped || meterPaused) {
                    System.out.println("[QC45] Grid L1=" + one(c.l1) + "A L2=" + one(c.l2) + "A L3=" + one(c.l3)
                        + "A max=" + one(max) + "A"
                        + (tripped ? " TRIPPED" : meterPaused ? " METER-PAUSED"
                            : overLimitPaused ? " OVER-LIMIT-PAUSED" : ""));
                    lastLog = now;
                }

                if (tripped) {
                    evaluateTimedReset(now, max);
                } else if (meterPaused) {
                    if (max < reduceA) {
                        goodReadsAfterMeterPause++;
                        if (goodReadsAfterMeterPause >= 5) clearMeterPause();
                    } else {
                        goodReadsAfterMeterPause = 0;
                    }
                } else if (overLimitPaused) {
                    evaluateOverLimitPause(now, max);
                } else {
                    evaluate(now, max);
                }
                enforceBlockedLimits(now);
            } catch (Throwable e) {
                goodReadsAfterMeterPause = 0;
                resetSince = 0L;
                if (now - lastLog >= 5000L) {
                    System.err.println("[QC45] GridFailback KSEM read failed: " + e);
                    lastLog = now;
                }
                if (!tripped && !meterPaused && tripOnMeterFailure && now - lastGoodRead >= meterFailureMs) {
                    pauseForMeterFailure(now - lastGoodRead);
                }
                enforceBlockedLimits(now);
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }

        System.out.println("[QC45] GridFailback stopped");
    }

    private void evaluate(long now, double max) throws Exception {
        if (max >= instantTripA) {
            hardTrip("instant phase current " + one(max) + "A >= " + one(instantTripA) + "A");
            return;
        }

        if (max >= tripA) {
            pauseForOverLimit(now, max);
            return;
        } else {
            tripSince = 0L;
        }

        if (max >= reduceA) {
            if (reduceSince == 0L) reduceSince = now;
            if (now - reduceSince >= reduceDelayMs) forceMinimum();
        } else {
            reduceSince = 0L;
        }
    }

    private void evaluateOverLimitPause(long now, double max) {
        if (max >= instantTripA) {
            hardTrip("instant phase current " + one(max) + "A >= " + one(instantTripA) + "A");
            return;
        }

        if (max >= tripA) {
            goodReadsAfterOverLimit = 0;
            if (tripSince == 0L) tripSince = now;
            if (now - tripSince >= tripDelayMs) {
                hardTrip("phase current " + one(max) + "A >= " + one(tripA)
                    + "A for " + (now - tripSince) + "ms");
            }
            return;
        }

        if (max < reduceA) {
            goodReadsAfterOverLimit++;
            if (goodReadsAfterOverLimit >= 5) clearOverLimitPause();
        } else {
            goodReadsAfterOverLimit = 0;
        }
    }

    private void evaluateTimedReset(long now, double max) {
        if (max >= reduceA) {
            if (resetSince != 0L) {
                System.out.println("[QC45] GRID FAILBACK RESET WAIT restarted: grid=" + one(max) + "A >= " + one(reduceA) + "A");
            }
            resetSince = 0L;
            return;
        }

        if (resetSince == 0L) {
            resetSince = now;
            System.out.println("[QC45] GRID FAILBACK RESET WAIT started: grid below " + one(reduceA)
                + "A; latch clears after " + resetDelayMs + "ms stable");
            return;
        }

        if (now - resetSince >= resetDelayMs) {
            clearHardTripAfterDelay(now - resetSince);
        }
    }

    private void forceMinimum() throws Exception {
        // Stage 1 is reduction-only. It must never raise a connector which the
        // LoadManager or another safety path has already lowered to 0 kW.
        if (station.limitKw(1) > reduceDcKw) station.setConnectorLimitKw(1, reduceDcKw);
        if (station.limitKw(2) > reduceDcKw) station.setConnectorLimitKw(2, reduceDcKw);
        if (station.limitKw(3) > reduceAcKw) {
            station.setConnectorLimitKw(3, reduceAcKw);
        }
    }

    private synchronized void pauseForOverLimit(long now, double max) {
        if (tripped || meterPaused || overLimitPaused) return;
        overLimitPaused = true;
        tripSince = now;
        goodReadsAfterOverLimit = 0;
        System.err.println("[QC45] GRID FAILBACK OVER-LIMIT PAUSE: grid=" + one(max)
            + "A >= " + one(tripA) + "A -> DC=0kW AC=0kW immediately");
        forceZeroBudgets();
    }

    private synchronized void clearOverLimitPause() {
        if (!overLimitPaused || tripped || meterPaused) return;
        overLimitPaused = false;
        goodReadsAfterOverLimit = 0;
        reduceSince = 0L;
        tripSince = 0L;
        System.out.println("[QC45] GRID FAILBACK OVER-LIMIT RECOVERED: five reads below "
            + one(reduceA) + "A; LoadManager may ramp AC/DC again");
    }

    private synchronized void pauseForMeterFailure(long outageMs) {
        if (tripped || meterPaused) return;
        meterPaused = true;
        overLimitPaused = false;
        goodReadsAfterMeterPause = 0;
        System.err.println("[QC45] GRID FAILBACK METER PAUSE: KSEM communication lost for " + outageMs
            + "ms -> DC=0kW AC=0kW, transactions remain active");
        forceZeroBudgets();
    }

    private synchronized void clearMeterPause() {
        if (!meterPaused || tripped) return;
        meterPaused = false;
        goodReadsAfterMeterPause = 0;
        reduceSince = 0L;
        tripSince = 0L;
        System.out.println("[QC45] GRID FAILBACK METER RECOVERED: KSEM stable, LoadManager may ramp charging again");
    }

    private synchronized void hardTrip(String reason) {
        if (tripped) return;
        tripped = true;
        meterPaused = false;
        overLimitPaused = false;
        resetSince = 0L;
        System.err.println("[QC45] GRID FAILBACK TRIP: " + reason
            + " [AC+DC; latched; auto-reset after " + resetDelayMs
            + "ms stable below " + one(reduceA) + "A]");
        enforceHardTripOnce();
    }

    private synchronized void clearHardTripAfterDelay(long stableMs) {
        if (!tripped) return;
        tripped = false;
        resetSince = 0L;
        reduceSince = 0L;
        tripSince = 0L;
        goodReadsAfterMeterPause = 0;
        goodReadsAfterOverLimit = 0;
        System.out.println("[QC45] GRID FAILBACK RESET: grid stable below " + one(reduceA)
            + "A for " + stableMs + "ms; latch cleared");
    }

    private void enforceHardTripOnce() {
        forceZeroBudgets();
        for (int connector = 1; connector <= 3; connector++) {
            try { station.remoteStop(connector); } catch (Throwable ignored) {}
        }
    }

    private void enforceBlockedLimits(long now) {
        if (!isChargingBlocked() || now - lastBlockedEnforce < 1000L) return;
        lastBlockedEnforce = now;
        forceZeroBudgets();
    }

    private void forceZeroBudgets() {
        try { station.setDcBudgetKw(0); }
        catch (Throwable e) { System.err.println("[QC45] failback DC=0 failed: " + e); }
        try { station.setAcBudgetKw(0); }
        catch (Throwable e) { System.err.println("[QC45] failback AC=0 failed: " + e); }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }
}
