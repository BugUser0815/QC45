package de.rothner.qc45;

import java.util.HashSet;
import java.util.Set;

/**
 * Single writer for all native connector power limits.
 *
 * evcc requests, grid-approved targets and safety caps are independent inputs.
 * The smallest value wins. Safety blocks are latched by source and cannot be
 * overwritten by a later Modbus or load-manager write. All reductions are sent
 * before any increase, so AC/DC redistribution cannot create a transient peak.
 */
public final class ChargingLimitCoordinator {
    public static final String STARTUP = "startup";
    public static final String FAILBACK = "failback";
    public static final String LOAD_METER = "loadmanager-meter";
    public static final String CONFIGURATION = "configuration";
    public static final String LIMIT_MISMATCH = "limit-mismatch";
    public static final String SHUTDOWN = "shutdown";

    private final ChargingLimitIo io;
    private final int minDcKw;
    private final int maxDcKw;
    private final int minAcKw;
    private final int maxAcKw;
    private final Set<String> blockers = new HashSet<String>();

    private int requestedDcKw;
    private int requestedAcKw;
    private boolean evccControlsDc;
    private boolean evccControlsAc;
    private int gridDcKw;
    private int gridAcKw;
    private int prearmDcKw;
    private int prearmAcKw;
    private int stageDcCapKw;
    private int stageAcCapKw;
    private int activeDcConnector;
    private boolean acActive;
    private boolean ccsAvailable;
    private boolean demandTransfer;
    private final int[] applied = new int[] { -1, -1, -1, -1 };

    public ChargingLimitCoordinator(ChargingLimitIo io,
                                    int minDcKw, int maxDcKw,
                                    int minAcKw, int maxAcKw) {
        if (io == null) throw new IllegalArgumentException("io is required");
        if (minDcKw <= 0 || maxDcKw < minDcKw
                || minAcKw <= 0 || maxAcKw < minAcKw) {
            throw new IllegalArgumentException("invalid connector minimum/maximum");
        }
        this.io = io;
        this.minDcKw = minDcKw;
        this.maxDcKw = maxDcKw;
        this.minAcKw = minAcKw;
        this.maxAcKw = maxAcKw;
        // Native load balancing is autonomous until evcc explicitly writes a
        // budget for the respective output. Startup, KSEM and failback blockers
        // still keep the hardware at zero until a grid-safe target exists.
        this.requestedDcKw = maxDcKw;
        this.requestedAcKw = maxAcKw;
        this.stageDcCapKw = maxDcKw;
        this.stageAcCapKw = maxAcKw;
        this.ccsAvailable = false;
        blockers.add(STARTUP);
    }

    public synchronized void initializeSafeZero() throws Exception {
        applyTargets(new int[] { 0, 0, 0, 0 }, true);
    }

    /** Persistent evcc upper bounds. A value below the technical minimum pauses that side. */
    public synchronized void requestBudgets(int dcKw, int acKw) throws Exception {
        int nextDcKw = normalize(dcKw, minDcKw, maxDcKw);
        int nextAcKw = normalize(acKw, minAcKw, maxAcKw);
        if (nextDcKw < requestedDcKw) gridDcKw = Math.min(gridDcKw, nextDcKw);
        if (nextAcKw < requestedAcKw) gridAcKw = Math.min(gridAcKw, nextAcKw);
        requestedDcKw = nextDcKw;
        requestedAcKw = nextAcKw;
        evccControlsDc = true;
        evccControlsAc = true;
        apply(false);
    }

    public synchronized void requestDcBudget(int kw) throws Exception {
        int nextKw = normalize(kw, minDcKw, maxDcKw);
        if (nextKw < requestedDcKw) gridDcKw = Math.min(gridDcKw, nextKw);
        requestedDcKw = nextKw;
        evccControlsDc = true;
        apply(false);
    }

    public synchronized void requestAcBudget(int kw) throws Exception {
        int nextKw = normalize(kw, minAcKw, maxAcKw);
        if (nextKw < requestedAcKw) gridAcKw = Math.min(gridAcKw, nextKw);
        requestedAcKw = nextKw;
        evccControlsAc = true;
        apply(false);
    }

    /** Publish the load manager's grid-safe allocation. */
    public synchronized void setGridTargets(int dcConnector, boolean acIsActive,
                                            int dcKw, int acKw) throws Exception {
        setGridTargets(dcConnector, acIsActive, dcKw, acKw, false);
    }

    /** Publish the allocation and whether unused entitlement is being transferred. */
    public synchronized void setGridTargets(int dcConnector, boolean acIsActive,
                                            int dcKw, int acKw,
                                            boolean transferringDemand) throws Exception {
        setGridTargetsAndPrearm(dcConnector, acIsActive, dcKw, acKw,
            0, 0, transferringDemand);
    }

    /**
     * Atomically publish active allocations and safe next-session start values.
     * Pre-arm values apply only to currently inactive outputs and are written
     * through the non-authorizing satellite-only path.
     */
    public synchronized void setGridTargetsAndPrearm(
                                            int dcConnector, boolean acIsActive,
                                            int dcKw, int acKw,
                                            int idleDcKw, int idleAcKw,
                                            boolean transferringDemand) throws Exception {
        if (dcConnector < 0 || dcConnector > 2) throw new IllegalArgumentException("DC connector must be 0..2");
        int oldDcConnector = activeDcConnector;
        boolean oldAcActive = acActive;
        activeDcConnector = dcConnector;
        acActive = acIsActive;
        gridDcKw = dcConnector == 0 ? 0 : normalize(dcKw, minDcKw, maxDcKw);
        gridAcKw = acIsActive ? normalize(acKw, minAcKw, maxAcKw) : 0;
        prearmDcKw = dcConnector == 0
            ? normalize(idleDcKw, minDcKw, maxDcKw) : 0;
        prearmAcKw = acIsActive
            ? 0 : normalize(idleAcKw, minAcKw, maxAcKw);
        demandTransfer = transferringDemand && dcConnector > 0 && acIsActive;

        // A pre-armed satellite may already contain the same numeric limit. A
        // newly active session still needs one full writer call so CCS receives
        // sendCcsStart(true) only after transaction authorization is observable.
        if (dcConnector > 0 && dcConnector != oldDcConnector) {
            applied[dcConnector] = -1;
        }
        if (acIsActive && !oldAcActive) applied[3] = -1;
        apply(false);
    }

    public synchronized void setStageCaps(int dcKw, int acKw) throws Exception {
        stageDcCapKw = normalize(dcKw, minDcKw, maxDcKw);
        stageAcCapKw = normalize(acKw, minAcKw, maxAcKw);
        apply(false);
    }

    public synchronized void clearStageCaps() throws Exception {
        stageDcCapKw = maxDcKw;
        stageAcCapKw = maxAcKw;
        apply(false);
    }

    public synchronized void setBlocked(String source, boolean blocked) throws Exception {
        if (source == null || source.trim().length() == 0) throw new IllegalArgumentException("block source is required");
        boolean changed = blocked ? blockers.add(source) : blockers.remove(source);
        apply(blocked && changed);
    }

    public synchronized boolean isBlocked() { return !blockers.isEmpty(); }

    public synchronized boolean isBlockedBy(String source) {
        return blockers.contains(source);
    }

    /** True when a blocker outside the caller-owned preparation blockers exists. */
    public synchronized boolean hasBlockerOtherThan(String firstAllowed,
                                                     String secondAllowed) {
        for (String blocker : blockers) {
            if (!blocker.equals(firstAllowed) && !blocker.equals(secondAllowed)) return true;
        }
        return false;
    }

    public synchronized String blockReason() { return blockers.toString(); }

    public synchronized void setCcsAvailable(boolean available) throws Exception {
        ccsAvailable = available;
        apply(!available && activeDcConnector == 2);
    }

    public synchronized boolean isCcsAvailable() { return ccsAvailable; }
    public synchronized int requestedDcKw() { return requestedDcKw; }
    public synchronized int requestedAcKw() { return requestedAcKw; }
    public synchronized boolean evccControlsDc() { return evccControlsDc; }
    public synchronized boolean evccControlsAc() { return evccControlsAc; }
    public synchronized int effectiveDcKw() {
        return activeDcConnector == 0 ? 0 : targets()[activeDcConnector];
    }
    public synchronized int effectiveAcKw() { return acActive ? targets()[3] : 0; }

    public synchronized int effectiveConnectorKw(int connector) {
        if (connector < 1 || connector > 3) {
            throw new IllegalArgumentException("connector must be 1..3");
        }
        return targets()[connector];
    }

    /** Force the effective value through the native hardware path. */
    public synchronized void reassertConnectorLimit(int connector) throws Exception {
        if (connector < 1 || connector > 3) {
            throw new IllegalArgumentException("connector must be 1..3");
        }
        int target = targets()[connector];
        io.setConnectorLimitKw(connector, target);
        applied[connector] = target;
    }

    /** One coherent view for diagnostics, Modbus and the local charging screen. */
    public synchronized Snapshot snapshot() {
        int[] target = targets();
        int effectiveDc = activeDcConnector == 0 ? 0 : target[activeDcConnector];
        return new Snapshot(
            requestedDcKw, requestedAcKw,
            gridDcKw, gridAcKw,
            stageDcCapKw, stageAcCapKw,
            effectiveDc, target[3],
            activeDcConnector, acActive,
            evccControlsDc, evccControlsAc,
            !blockers.isEmpty(),
            blockers.contains(STARTUP),
            blockers.contains(FAILBACK),
            blockers.contains(LOAD_METER),
            blockers.contains(CONFIGURATION),
            blockers.contains(LIMIT_MISMATCH),
            blockers.contains(SHUTDOWN),
            demandTransfer && blockers.isEmpty(),
            stageDcCapKw < maxDcKw || stageAcCapKw < maxAcKw);
    }

    /** Reassert the effective values if EVCSD or another legacy path changed them. */
    public synchronized void reconcile() throws Exception {
        Exception readFailure = null;
        for (int connector = 1; connector <= 3; connector++) {
            try {
                applied[connector] = clamp(io.limitKw(connector), 0,
                    connector == 3 ? maxAcKw : maxDcKw);
            } catch (Exception e) {
                // Unknown must force a write. In particular, one broken getter
                // must not prevent zero from being reasserted on the other two
                // connectors while a safety blocker is active.
                applied[connector] = -1;
                if (readFailure == null) readFailure = e;
            }
        }
        Exception writeFailure = null;
        int[] reconciledTargets = readFailure == null
            ? targets() : new int[] { 0, 0, 0, 0 };
        try { applyTargets(reconciledTargets, readFailure != null); }
        catch (Exception e) { writeFailure = e; }
        if (writeFailure != null) throw writeFailure;
        if (readFailure != null) throw readFailure;
    }

    private void apply(boolean safetyCritical) throws Exception {
        applyTargets(targets(), safetyCritical);
    }

    private int[] targets() {
        int[] target = new int[] { 0, 0, 0, 0 };
        if (!blockers.isEmpty()) return target;

        int dc = Math.min(requestedDcKw, Math.min(gridDcKw, stageDcCapKw));
        int ac = Math.min(requestedAcKw, Math.min(gridAcKw, stageAcCapKw));
        int prearmDc = Math.min(requestedDcKw,
            Math.min(prearmDcKw, stageDcCapKw));
        int prearmAc = Math.min(requestedAcKw,
            Math.min(prearmAcKw, stageAcCapKw));
        if (activeDcConnector == 1) target[1] = dc;
        else if (activeDcConnector == 2 && ccsAvailable) target[2] = dc;
        else if (activeDcConnector == 0) {
            target[1] = prearmDc;
            if (ccsAvailable) target[2] = prearmDc;
        }
        if (acActive) target[3] = ac;
        else target[3] = prearmAc;
        return target;
    }

    private void applyTargets(int[] target, boolean safetyCritical) throws Exception {
        Exception first = null;

        // Unknown values are treated as potentially high. Safety paths always
        // write all zero targets, even if our last cached value was already zero.
        for (int connector = 1; connector <= 3; connector++) {
            if (target[connector] < applied[connector]
                    || applied[connector] < 0 || (safetyCritical && target[connector] == 0)) {
                try {
                    writeTarget(connector, target[connector]);
                    applied[connector] = target[connector];
                } catch (Exception e) {
                    if (first == null) first = e;
                }
            }
        }

        if (first == null) {
            for (int connector = 1; connector <= 3; connector++) {
                if (target[connector] > applied[connector]) {
                    try {
                        writeTarget(connector, target[connector]);
                        applied[connector] = target[connector];
                    } catch (Exception e) {
                        if (first == null) first = e;
                    }
                }
            }
        }
        if (first != null) throw first;
    }

    private void writeTarget(int connector, int targetKw) throws Exception {
        boolean idlePrearm = targetKw > 0
            && ((connector <= 2 && activeDcConnector == 0)
                || (connector == 3 && !acActive));
        if (idlePrearm) io.preArmConnectorLimitKw(connector, targetKw);
        else io.setConnectorLimitKw(connector, targetKw);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int normalize(int value, int min, int max) {
        if (value < min) return 0;
        return clamp(value, min, max);
    }

    public static final class Snapshot {
        public final int requestedDcKw;
        public final int requestedAcKw;
        public final int gridDcKw;
        public final int gridAcKw;
        public final int stageDcCapKw;
        public final int stageAcCapKw;
        public final int effectiveDcKw;
        public final int effectiveAcKw;
        public final int activeDcConnector;
        public final boolean acActive;
        public final boolean evccControlsDc;
        public final boolean evccControlsAc;
        public final boolean blocked;
        public final boolean startupBlocked;
        public final boolean failbackBlocked;
        public final boolean loadMeterBlocked;
        public final boolean configurationBlocked;
        public final boolean limitMismatchBlocked;
        public final boolean shutdownBlocked;
        public final boolean demandTransfer;
        public final boolean stageLimited;

        private Snapshot(int requestedDcKw, int requestedAcKw,
                         int gridDcKw, int gridAcKw,
                         int stageDcCapKw, int stageAcCapKw,
                         int effectiveDcKw, int effectiveAcKw,
                         int activeDcConnector, boolean acActive,
                         boolean evccControlsDc, boolean evccControlsAc,
                         boolean blocked, boolean startupBlocked,
                         boolean failbackBlocked, boolean loadMeterBlocked,
                         boolean configurationBlocked, boolean limitMismatchBlocked,
                         boolean shutdownBlocked, boolean demandTransfer,
                         boolean stageLimited) {
            this.requestedDcKw = requestedDcKw;
            this.requestedAcKw = requestedAcKw;
            this.gridDcKw = gridDcKw;
            this.gridAcKw = gridAcKw;
            this.stageDcCapKw = stageDcCapKw;
            this.stageAcCapKw = stageAcCapKw;
            this.effectiveDcKw = effectiveDcKw;
            this.effectiveAcKw = effectiveAcKw;
            this.activeDcConnector = activeDcConnector;
            this.acActive = acActive;
            this.evccControlsDc = evccControlsDc;
            this.evccControlsAc = evccControlsAc;
            this.blocked = blocked;
            this.startupBlocked = startupBlocked;
            this.failbackBlocked = failbackBlocked;
            this.loadMeterBlocked = loadMeterBlocked;
            this.configurationBlocked = configurationBlocked;
            this.limitMismatchBlocked = limitMismatchBlocked;
            this.shutdownBlocked = shutdownBlocked;
            this.demandTransfer = demandTransfer;
            this.stageLimited = stageLimited;
        }
    }
}
