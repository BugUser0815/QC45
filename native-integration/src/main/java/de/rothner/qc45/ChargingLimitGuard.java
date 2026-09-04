package de.rothner.qc45;

/**
 * Independent reassertion loop for the coordinator's effective limits.
 *
 * This guard starts before optional integrations. If configuration, OCPP or a
 * reflection add-on fails, the startup blocker remains active and this thread
 * keeps all three connectors at 5 kW Notladen instead of writing QC45's
 * ambiguous native 0 kW value.
 */
final class ChargingLimitGuard extends Thread {
    private static final int POSITIVE_LIMIT_TOLERANCE_KW = 3;
    private static final long POSITIVE_LIMIT_GRACE_MS = 1000L;
    private static final long STOP_RETRY_MS = 2000L;

    private final ChargingSessionIo station;
    private final ChargingLimitCoordinator limits;
    private final int intervalMs;
    private final long[] overLimitSince = new long[] { 0L, 0L, 0L, 0L };
    private final long[] lastStopAttempt = new long[] { 0L, 0L, 0L, 0L };
    private volatile boolean running = true;
    private long lastErrorLog;

    ChargingLimitGuard(ChargingSessionIo station,
                       ChargingLimitCoordinator limits, int intervalMs) {
        super("QC45-ChargingLimitGuard");
        if (station == null || limits == null || intervalMs <= 0) {
            throw new IllegalArgumentException("station, limits and a positive interval are required");
        }
        this.station = station;
        this.limits = limits;
        this.intervalMs = intervalMs;
        setDaemon(true);
    }

    public void run() {
        System.out.println("[QC45] charging-limit guard started interval=" + intervalMs
            + "ms Notladen=" + ChargingLimitCoordinator.NOTLADEN_KW + "kW");
        while (running) {
            try {
                runCycle(System.currentTimeMillis());
            } catch (Throwable e) {
                long now = System.currentTimeMillis();
                if (now - lastErrorLog >= 5000L) {
                    System.err.println("[QC45] charging-limit guard enforcement failed: " + e);
                    lastErrorLog = now;
                }
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }
        System.out.println("[QC45] charging-limit guard stopped");
    }

    void runCycle(long now) throws Exception {
        Exception reconcileFailure = null;
        try { limits.reconcile(); }
        catch (Exception e) { reconcileFailure = e; }

        for (int connector = 1; connector <= 3; connector++) {
            int logicalEffectiveKw = limits.effectiveConnectorKw(connector);
            int enforcedKw = logicalEffectiveKw <= 0
                ? ChargingLimitCoordinator.NOTLADEN_KW : logicalEffectiveKw;
            boolean active = station.sessionActive(connector);
            if (!active) {
                overLimitSince[connector] = 0L;
                continue;
            }

            int actualKw = station.powerKw(connector);
            if (actualKw <= enforcedKw + POSITIVE_LIMIT_TOLERANCE_KW) {
                overLimitSince[connector] = 0L;
                continue;
            }

            if (overLimitSince[connector] == 0L) overLimitSince[connector] = now;
            try { limits.reassertConnectorLimit(connector); }
            catch (Exception e) { if (reconcileFailure == null) reconcileFailure = e; }
            if (now - overLimitSince[connector] < POSITIVE_LIMIT_GRACE_MS) continue;

            hardStop(connector, enforcedKw, actualKw, now, reconcileFailure);
        }
        if (reconcileFailure != null) throw reconcileFailure;
    }

    private void hardStop(int connector, int effectiveKw, int actualKw,
                          long now, Exception priorFailure) throws Exception {
        Exception blockFailure = priorFailure;
        try { limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true); }
        catch (Exception e) { if (blockFailure == null) blockFailure = e; }
        if (lastStopAttempt[connector] == 0L
                || now - lastStopAttempt[connector] >= STOP_RETRY_MS) {
            lastStopAttempt[connector] = now;
            System.err.println("[QC45] LIMIT MISMATCH HARD STOP connector=" + connector
                + " effective=" + effectiveKw + "kW actual=" + actualKw
                + "kW -> transaction abort; restart required");
            station.remoteStop(connector);
        }
        if (blockFailure != null) throw blockFailure;
    }

    void shutdown() {
        try { limits.setBlocked(ChargingLimitCoordinator.SHUTDOWN, true); }
        catch (Throwable e) { System.err.println("[QC45] shutdown Notladen enforcement failed: " + e); }
        running = false;
        interrupt();
    }
}
