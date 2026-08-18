package de.rothner.qc45;

/**
 * Independent grid-current failback.
 *
 * Stage 1: if any phase stays above reduceA long enough, force QC45 DC/AC budgets down.
 * Stage 2: if any phase stays above tripA long enough, or exceeds instantTripA, stop all connectors and latch.
 * Optional: trip if KSEM communication is lost for meterFailureMs.
 *
 * Over-current trips stay latched until integration/JVM restart.
 * Meter-communication trips stop once, then auto-recover after stable meter readings return.
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

    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile boolean meterFailureTrip;
    private long reduceSince;
    private long tripSince;
    private long lastGoodRead;
    private long lastLog;
    private int goodReadsAfterMeterTrip;

    public GridFailback(ReflectionQC45 station, KsemClient meter,
                        double reduceA, long reduceDelayMs,
                        double tripA, long tripDelayMs,
                        double instantTripA,
                        int reduceDcKw, int reduceAcKw,
                        int intervalMs,
                        boolean tripOnMeterFailure, long meterFailureMs) {
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
    }

    public boolean isTripped() {
        return tripped;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        lastGoodRead = System.currentTimeMillis();
        System.out.println("[QC45] GridFailback started");

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents c = meter.readCurrents();
                lastGoodRead = now;
                double max = c.max();

                if (now - lastLog >= 5000L || max >= reduceA || tripped) {
                    System.out.println("[QC45] Grid L1=" + one(c.l1) + "A L2=" + one(c.l2)
                        + "A L3=" + one(c.l3) + "A max=" + one(max) + "A"
                        + (tripped ? (meterFailureTrip ? " TRIPPED_METER" : " TRIPPED") : ""));
                    lastLog = now;
                }

                if (tripped) {
                    if (meterFailureTrip) {
                        if (max < reduceA) {
                            goodReadsAfterMeterTrip++;
                            if (goodReadsAfterMeterTrip >= 5) {
                                clearMeterTrip();
                            }
                        } else {
                            goodReadsAfterMeterTrip = 0;
                        }
                    }
                    // Do not repeatedly call remoteStop while tripped. The stop was
                    // executed once when entering the trip state.
                } else {
                    evaluate(now, max);
                }
            } catch (Throwable e) {
                goodReadsAfterMeterTrip = 0;
                if (now - lastLog >= 5000L) {
                    System.err.println("[QC45] GridFailback KSEM read failed: " + e);
                    lastLog = now;
                }
                if (!tripped && tripOnMeterFailure && now - lastGoodRead >= meterFailureMs) {
                    trip("KSEM communication lost for " + (now - lastGoodRead) + "ms", true);
                }
            }

            try { Thread.sleep(intervalMs); }
            catch (InterruptedException e) { if (!running) break; }
        }
        System.out.println("[QC45] GridFailback stopped");
    }

    private void evaluate(long now, double max) throws Exception {
        if (max >= instantTripA) {
            trip("instant phase current " + one(max) + "A >= " + one(instantTripA) + "A", false);
            return;
        }

        if (max >= tripA) {
            if (tripSince == 0L) tripSince = now;
            if (now - tripSince >= tripDelayMs) {
                trip("phase current " + one(max) + "A >= " + one(tripA) + "A for " + (now - tripSince) + "ms", false);
                return;
            }
        } else {
            tripSince = 0L;
        }

        if (max >= reduceA) {
            if (reduceSince == 0L) reduceSince = now;
            if (now - reduceSince >= reduceDelayMs) {
                forceMinimum();
            }
        } else {
            reduceSince = 0L;
        }
    }

    private void forceMinimum() throws Exception {
        station.setDcBudgetKw(reduceDcKw);
        station.setAcBudgetKw(reduceAcKw);
    }

    private synchronized void trip(String reason, boolean meterFailure) {
        if (tripped) return;
        tripped = true;
        meterFailureTrip = meterFailure;
        goodReadsAfterMeterTrip = 0;
        System.err.println("[QC45] GRID FAILBACK TRIP: " + reason
            + (meterFailure ? " [auto-recoverable]" : " [latched]"));
        enforceTripOnce();
    }

    private synchronized void clearMeterTrip() {
        if (!tripped || !meterFailureTrip) return;
        tripped = false;
        meterFailureTrip = false;
        goodReadsAfterMeterTrip = 0;
        reduceSince = 0L;
        tripSince = 0L;
        System.out.println("[QC45] GRID FAILBACK RECOVERED: KSEM communication stable again");
    }

    private void enforceTripOnce() {
        try { station.setDcBudgetKw(reduceDcKw); } catch (Throwable e) {
            System.err.println("[QC45] failback DC reduction failed: " + e);
        }
        try { station.setAcBudgetKw(reduceAcKw); } catch (Throwable e) {
            System.err.println("[QC45] failback AC reduction failed: " + e);
        }
        for (int connector = 1; connector <= 3; connector++) {
            try { station.remoteStop(connector); } catch (Throwable e) {
                // A connector may be idle; continue stopping the others.
            }
        }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }
}
