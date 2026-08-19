package de.rothner.qc45;

/**
 * Independent grid-current failback.
 *
 * Stage 1: if any phase stays above reduceA long enough, force QC45 DC/AC budgets down.
 * Stage 2: if any phase stays above tripA long enough, or exceeds instantTripA, stop all connectors and latch.
 * KSEM communication loss sets DC/AC to 0 kW while keeping transactions alive.
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
    private long reduceSince;
    private long tripSince;
    private long resetSince;
    private long lastGoodRead;
    private long lastLog;
    private int goodReadsAfterMeterPause;

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
    public void shutdown() { running = false; interrupt(); }

    public void run() {
        lastGoodRead = System.currentTimeMillis();
        System.out.println("[QC45] GridFailback started auto-reset=" + resetDelayMs + "ms stable-below=" + one(reduceA) + "A");

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents c = meter.readCurrents();
                lastGoodRead = now;
                double max = c.max();

                if (now - lastLog >= 5000L || max >= reduceA || tripped || meterPaused) {
                    System.out.println("[QC45] Grid L1=" + one(c.l1) + "A L2=" + one(c.l2) + "A L3=" + one(c.l3)
                        + "A max=" + one(max) + "A" + (tripped ? " TRIPPED" : meterPaused ? " METER-PAUSED" : ""));
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
                } else {
                    evaluate(now, max);
                }
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
            if (tripSince == 0L) tripSince = now;
            if (now - tripSince >= tripDelayMs) {
                hardTrip("phase current " + one(max) + "A >= " + one(tripA) + "A for " + (now - tripSince) + "ms");
                return;
            }
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
        station.setDcBudgetKw(reduceDcKw);
        station.setAcBudgetKw(reduceAcKw);
    }

    private synchronized void pauseForMeterFailure(long outageMs) {
        if (tripped || meterPaused) return;
        meterPaused = true;
        goodReadsAfterMeterPause = 0;
        System.err.println("[QC45] GRID FAILBACK METER PAUSE: KSEM communication lost for " + outageMs
            + "ms -> DC=0kW AC=0kW, transactions remain active");
        try { station.setDcBudgetKw(0); } catch (Throwable e) { System.err.println("[QC45] meter-pause DC=0 failed: " + e); }
        try { station.setAcBudgetKw(0); } catch (Throwable e) { System.err.println("[QC45] meter-pause AC=0 failed: " + e); }
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
        resetSince = 0L;
        System.err.println("[QC45] GRID FAILBACK TRIP: " + reason
            + " [latched; auto-reset after " + resetDelayMs + "ms stable below " + one(reduceA) + "A]");
        enforceHardTripOnce();
    }

    private synchronized void clearHardTripAfterDelay(long stableMs) {
        if (!tripped) return;
        tripped = false;
        resetSince = 0L;
        reduceSince = 0L;
        tripSince = 0L;
        goodReadsAfterMeterPause = 0;
        System.out.println("[QC45] GRID FAILBACK RESET: grid stable below " + one(reduceA)
            + "A for " + stableMs + "ms; latch cleared");
    }

    private void enforceHardTripOnce() {
        try { station.setDcBudgetKw(reduceDcKw); } catch (Throwable e) { System.err.println("[QC45] failback DC reduction failed: " + e); }
        try { station.setAcBudgetKw(reduceAcKw); } catch (Throwable e) { System.err.println("[QC45] failback AC reduction failed: " + e); }
        for (int connector = 1; connector <= 3; connector++) {
            try { station.remoteStop(connector); } catch (Throwable ignored) {}
        }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }
}
