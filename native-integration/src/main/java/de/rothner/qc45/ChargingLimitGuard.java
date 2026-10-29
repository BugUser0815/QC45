package de.rothner.qc45;

/**
 * Independent reassertion loop for the coordinator's effective limits.
 *
 * This guard starts before optional integrations. If configuration, OCPP or a
 * reflection add-on fails, the startup blocker remains active and this thread
 * keeps all three connectors at 5 kW Notladen instead of writing QC45's
 * ambiguous native 0 kW value.
 *
 * The QC45 does not reduce DC power instantaneously. A connector above its
 * effective limit is therefore allowed to ramp down as long as telemetry keeps
 * reaching new lower power values. Only a sustained stall above the tolerated
 * limit is treated as a real limit mismatch and hard-stopped.
 */
final class ChargingLimitGuard extends Thread {
    private static final int POSITIVE_LIMIT_TOLERANCE_KW = 3;
    private static final int POSITIVE_LIMIT_PROGRESS_KW = 1;
    private static final long POSITIVE_LIMIT_STALL_MS = 5000L;
    private static final long STOP_RETRY_MS = 2000L;

    private final ChargingSessionIo station;
    private final ChargingLimitCoordinator limits;
    private final int intervalMs;
    private final long[] lastProgressAt = new long[] { 0L, 0L, 0L, 0L };
    private final int[] bestOverLimitPower = new int[] {
        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE
    };
    private final int[] trackedLimitKw = new int[] { -1, -1, -1, -1 };
    private final long[] lastStopAttempt = new long[] { 0L, 0L, 0L, 0L };
    private final boolean[] mismatchLatched = new boolean[] { false, false, false, false };
    private final String[] permittedDcSession = new String[3];
    private final boolean[] continuingDcSession = new boolean[3];
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
        SafetyDiagnostics.startModbus(limits);
        System.out.println("[QC45] charging-limit guard started interval=" + intervalMs
            + "ms Notladen DC=" + ChargingLimitCoordinator.NOTLADEN_KW
            + "kW AC=" + ChargingLimitCoordinator.AC_NOTLADEN_KW + "kW"
            + " mismatch-stall=" + POSITIVE_LIMIT_STALL_MS + "ms");
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
        // Missing evcc refreshes cap power at 5 kW. Only an already observed
        // permitted transaction may continue through that network fallback.
        Exception reconcileFailure = null;
        try { limits.expireDcRequestIfStale(); }
        catch (Exception e) { reconcileFailure = e; }
        try { limits.reconcile(); }
        catch (Exception e) { if (reconcileFailure == null) reconcileFailure = e; }

        for (int connector = 1; connector <= (limits.acManaged() ? 3 : 2); connector++) {
            try { guardConnector(connector, now); }
            catch (Exception e) { if (reconcileFailure == null) reconcileFailure = e; }
        }

        try { clearLimitMismatchIfStopped(); }
        catch (Exception e) { if (reconcileFailure == null) reconcileFailure = e; }

        if (reconcileFailure != null) throw reconcileFailure;
    }

    private void guardConnector(int connector, long now) throws Exception {
        int logicalKw = limits.effectiveConnectorKw(connector);
        int enforcedKw = logicalKw <= 0
            ? (connector == 3 ? ChargingLimitCoordinator.AC_NOTLADEN_KW
                : ChargingLimitCoordinator.NOTLADEN_KW) : logicalKw;
        boolean active;
        try { active = station.sessionActive(connector); }
        catch (Exception error) {
            // Unknown state is not an inactive confirmation. Keep a previously
            // latched stop working even if session/power telemetry disappears.
            if (mismatchLatched[connector]) retryLatchedStop(connector, enforcedKw, -1, now);
            throw error;
        }
        if (!active) {
            if (connector <= 2) {
                permittedDcSession[connector] = null;
                continuingDcSession[connector] = false;
            }
            resetTracking(connector);
            mismatchLatched[connector] = false;
            lastStopAttempt[connector] = 0L;
            return;
        }
        // A network failure cannot stop the same permitted transaction, nor
        // grant its continuation to a replacement vehicle or a new session.
        if (connector <= 2 && !dcSessionPermitted(connector)) {
            Exception blockFailure = null;
            if (!mismatchLatched[connector]) {
                mismatchLatched[connector] = true;
                try { limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true); }
                catch (Exception error) { blockFailure = error; }
                System.err.println("[QC45] DC SESSION WITHOUT EVCC PERMIT connector="
                    + connector + " -> RemoteStop");
            }
            retryLatchedStop(connector, enforcedKw, -1, now);
            if (blockFailure != null) throw blockFailure;
            return;
        }
        if (mismatchLatched[connector]) {
            retryLatchedStop(connector, enforcedKw, -1, now);
            return;
        }
        int actualKw = station.powerKw(connector);
        if (actualKw <= enforcedKw + POSITIVE_LIMIT_TOLERANCE_KW) {
            resetTracking(connector);
            return;
        }
        trackRampDown(connector, enforcedKw, actualKw, now);
        Exception writeFailure = null;
        try { limits.reassertConnectorLimit(connector); }
        catch (Exception error) { writeFailure = error; }
        if (now - lastProgressAt[connector] >= POSITIVE_LIMIT_STALL_MS) {
            hardStop(connector, enforcedKw, actualKw, now, writeFailure);
        }
        if (writeFailure != null) throw writeFailure;
    }

    private boolean dcSessionPermitted(int connector) throws Exception {
        String identity = null;
        if (station instanceof ChargingSessionIdentityIo) {
            try { identity = ((ChargingSessionIdentityIo)station).sessionIdentity(connector); }
            catch (Exception ignored) { /* Unknown identity cannot inherit fallback. */ }
        }
        ChargingLimitCoordinator.DcPermitState permit = limits.dcPermitState();
        if (permit == ChargingLimitCoordinator.DcPermitState.FRESH) {
            if (identity != null && !identity.equals(permittedDcSession[connector])) {
                System.out.println("[QC45] DC session continuity registered connector=" + connector);
            }
            permittedDcSession[connector] = identity;
            continuingDcSession[connector] = false;
            return true;
        }
        boolean continuing = permit == ChargingLimitCoordinator.DcPermitState.NETWORK_FALLBACK && identity != null
            && identity.equals(permittedDcSession[connector]);
        if (continuing && !continuingDcSession[connector]) {
            System.out.println("[QC45] DC network fallback continuing the same transaction at 5kW connector=" + connector);
        }
        continuingDcSession[connector] = continuing;
        return continuing;
    }

    private void trackRampDown(int connector, int enforcedKw, int actualKw, long now) {
        if (trackedLimitKw[connector] != enforcedKw || lastProgressAt[connector] == 0L) {
            trackedLimitKw[connector] = enforcedKw;
            bestOverLimitPower[connector] = actualKw;
            lastProgressAt[connector] = now;
            return;
        }

        if (actualKw <= bestOverLimitPower[connector] - POSITIVE_LIMIT_PROGRESS_KW) {
            bestOverLimitPower[connector] = actualKw;
            lastProgressAt[connector] = now;
        }
    }

    private void resetTracking(int connector) {
        lastProgressAt[connector] = 0L;
        bestOverLimitPower[connector] = Integer.MAX_VALUE;
        trackedLimitKw[connector] = -1;
    }

    private void hardStop(int connector, int effectiveKw, int actualKw,
                          long now, Exception priorFailure) throws Exception {
        Exception blockFailure = priorFailure;
        mismatchLatched[connector] = true;
        try { limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true); }
        catch (Exception e) { if (blockFailure == null) blockFailure = e; }
        if (lastStopAttempt[connector] == 0L
                || now - lastStopAttempt[connector] >= STOP_RETRY_MS) {
            lastStopAttempt[connector] = now;
            System.err.println("[QC45] LIMIT MISMATCH HARD STOP connector=" + connector
                + " effective=" + effectiveKw + "kW actual=" + actualKw
                + "kW stalled=" + (now - lastProgressAt[connector])
                + "ms -> transaction abort; waiting for inactive confirmation");
            station.remoteStop(connector);
        }
        if (blockFailure != null) throw blockFailure;
    }

    private void retryLatchedStop(int connector, int effectiveKw,
                                  int actualKw, long now) throws Exception {
        try { limits.reassertConnectorLimit(connector); }
        catch (Exception e) {
            // Still attempt the transaction stop. Limit enforcement and
            // transaction termination are independent safety layers.
            if (lastStopAttempt[connector] == 0L
                    || now - lastStopAttempt[connector] >= STOP_RETRY_MS) {
                lastStopAttempt[connector] = now;
                station.remoteStop(connector);
            }
            throw e;
        }
        if (lastStopAttempt[connector] == 0L
                || now - lastStopAttempt[connector] >= STOP_RETRY_MS) {
            lastStopAttempt[connector] = now;
            System.err.println("[QC45] LIMIT MISMATCH STOP RETRY connector=" + connector
                + " effective=" + effectiveKw + "kW actual="
                + (actualKw < 0 ? "unknown" : actualKw + "kW")
                + "; waiting for inactive confirmation");
            station.remoteStop(connector);
        }
    }

    private void clearLimitMismatchIfStopped() throws Exception {
        if (!limits.isBlockedBy(ChargingLimitCoordinator.LIMIT_MISMATCH)) return;
        for (int connector = 1; connector <= 3; connector++) {
            if (mismatchLatched[connector]) return;
        }
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, false);
        System.out.println("[QC45] LIMIT MISMATCH CLEARED: all hard-stopped connectors inactive");
    }

    void shutdown() {
        try { limits.setBlocked(ChargingLimitCoordinator.SHUTDOWN, true); }
        catch (Throwable e) { System.err.println("[QC45] shutdown Notladen enforcement failed: " + e); }
        running = false;
        interrupt();
    }
}
