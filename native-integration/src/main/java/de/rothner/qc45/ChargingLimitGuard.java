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
    // Only a known downward step with continuing measured progress gets extra time.
    private static final long REDUCTION_MAX_MS = 5000L;
    private static final long REDUCTION_STALL_MS = 1500L;
    private static final long STOP_RETRY_MS = 2000L;

    private final ChargingSessionIo station;
    private final ChargingLimitCoordinator limits;
    private final int intervalMs;
    private final long[] overLimitSince = new long[] { 0L, 0L, 0L, 0L };
    private final long[] lastStopAttempt = new long[] { 0L, 0L, 0L, 0L };
    private final int[] previousLimit = new int[] { -1, -1, -1, -1 };
    private final int[] previousPower = new int[4];
    private final int[] bestReductionPower = new int[4];
    private final long[] reductionSince = new long[4];
    private final long[] reductionProgressAt = new long[4];
    private long idleSince;
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
                runCycle(System.nanoTime() / 1000000L);
            } catch (Throwable e) {
                idleSince = 0L;
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

        boolean allIdle = true;
        for (int connector = 1; connector <= 3; connector++) {
            try {
                boolean active = station.sessionActive(connector);
                int actualKw = station.powerKw(connector);
                if (active || actualKw > 0) allIdle = false;
                // A latched stop is independent of power compliance. Keep stopping
                // all channels, including newly started local/RFID sessions at 5 kW.
                if (limits.hardStopRequired()) {
                    overLimitSince[connector] = 0L;
                    reductionSince[connector] = 0L;
                    if (active || actualKw > 0) retryStop(connector, now);
                    continue;
                }
                checkLimit(connector, active, actualKw, now, reconcileFailure == null);
            } catch (Exception e) {
                allIdle = false;
                if (reconcileFailure == null) reconcileFailure = e;
            }
        }
        if (limits.isBlockedBy(ChargingLimitCoordinator.LIMIT_MISMATCH)) {
            try { if (station.emergencyStopPressed()) allIdle = false; }
            catch (Exception e) { allIdle = false; if (reconcileFailure == null) reconcileFailure = e; }
        }
        if (!allIdle || reconcileFailure != null) idleSince = 0L;
        else if (idleSince == 0L) idleSince = now;
        if (reconcileFailure == null && limits.clearMismatchIfRecovered(now, idleSince)) {
            idleSince = 0L;
            for (int i = 1; i <= 3; i++) previousLimit[i] = -1;
            System.out.println("[QC45] LIMIT MISMATCH RESET: all sessions ended; "
                + "zero measured power and fresh safe grid stable for "
                + limits.mismatchResetDelayMs() + "ms; fresh allocation required");
        }
        if (reconcileFailure != null) throw reconcileFailure;
    }

    private void checkLimit(int connector, boolean active, int actualKw,
                            long now, boolean writesHealthy) throws Exception {
        int logicalKw = limits.effectiveConnectorKw(connector);
        int enforcedKw = logicalKw <= 0 ? ChargingLimitCoordinator.NOTLADEN_KW : logicalKw;
        if (reductionSince[connector] == 0L && previousLimit[connector] >= enforcedKw + 5
                && previousPower[connector] > enforcedKw + POSITIVE_LIMIT_TOLERANCE_KW
                && actualKw <= previousPower[connector] + POSITIVE_LIMIT_TOLERANCE_KW) {
            reductionSince[connector] = now;
            reductionProgressAt[connector] = now;
            bestReductionPower[connector] = previousPower[connector];
            System.out.println("[QC45] LIMIT REDUCTION connector=" + connector
                + " from=" + previousLimit[connector] + "kW to=" + enforcedKw
                + "kW actual=" + actualKw + "kW maxSettle=" + REDUCTION_MAX_MS + "ms");
        }
        previousLimit[connector] = enforcedKw;
        previousPower[connector] = actualKw;
        if ((!active && actualKw == 0) || actualKw <= enforcedKw + POSITIVE_LIMIT_TOLERANCE_KW) {
            overLimitSince[connector] = 0L;
            reductionSince[connector] = 0L;
            return;
        }
        if (overLimitSince[connector] == 0L) overLimitSince[connector] = now;
        Exception writeFailure = null;
        try { limits.reassertConnectorLimit(connector); }
        catch (Exception e) { writeFailure = e; }
        if (actualKw < bestReductionPower[connector]) {
            bestReductionPower[connector] = actualKw;
            reductionProgressAt[connector] = now;
        }
        boolean settling = writesHealthy && writeFailure == null
            && reductionSince[connector] != 0L
            && now - reductionSince[connector] < REDUCTION_MAX_MS
            && now - reductionProgressAt[connector] < REDUCTION_STALL_MS
            && actualKw <= bestReductionPower[connector] + POSITIVE_LIMIT_TOLERANCE_KW;
        if (now - overLimitSince[connector] >= POSITIVE_LIMIT_GRACE_MS && !settling) {
            // setBlocked latches before native writes, even if those writes fail.
            try { limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true); }
            finally { retryStop(connector, now); }
            System.err.println("[QC45] LIMIT MISMATCH HARD STOP connector=" + connector
                + " effective=" + enforcedKw + "kW actual=" + actualKw
                + "kW -> stop requested; reset requires stable idle and healthy grid");
        }
        if (writeFailure != null) throw writeFailure;
    }

    private void retryStop(int connector, long now) throws Exception {
        if (lastStopAttempt[connector] == 0L
                || now - lastStopAttempt[connector] >= STOP_RETRY_MS) {
            lastStopAttempt[connector] = now;
            station.remoteStop(connector);
        }
    }

    void shutdown() {
        try { limits.setBlocked(ChargingLimitCoordinator.SHUTDOWN, true); }
        catch (Throwable e) { System.err.println("[QC45] shutdown Notladen enforcement failed: " + e); }
        running = false;
        interrupt();
    }
}
