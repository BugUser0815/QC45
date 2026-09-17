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

    /**
     * QC45 hardware safety floor. On this charger a native 0 kW limit can be
     * interpreted as "no limit". Logical zero therefore remains an internal
     * pause/block value only; every native hardware write uses 5 kW instead.
     */
    public static final int NOTLADEN_KW = 5;

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
        // keep the hardware at Notladen instead of writing QC45's ambiguous zero.
        this.requestedDcKw = maxDcKw;
        this.requestedAcKw = maxAcKw;
        this.stageDcCapKw = maxDcKw;
        this.stageAcCapKw = maxAcKw;
        this.ccsAvailable = false;
        blockers.add(STARTUP);
    }

    /** Establish the persistent 5 kW QC45 safety floor ("Notladen"). */
    public synchronized void initializeNotladen() throws Exception {
        applyTargets(new int[] { 0, 0, 0, 0 }, true);
    }

    /** Compatibility alias for older integration code. */
    public synchronized void initializeSafeZero() throws Exception {
        initializeNotladen();
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
     * The inactive CCS satellite is pre-armed without a start command. While no
     * DC session is active, connector 1 also owns the shared native DC
     * configuration value so EVCSD cannot restore a stale 45/50 kW default when
     * a new CCS session starts.
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
        // When a DC session ends, force connector 1 through the full writer in
        // the reduction pass. Its writer updates the shared Configuration maxPower
        // without invoking the CCS START_CHARGE path, closing the start race before
        // connector 2 is merely satellite-prearmed.
        if (dcConnector == 0 && oldDcConnector > 0) {
            applied[1] = -1;
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
        io.setConnectorLimitKw(connector, hardwareTargetKw(target));
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
        int[] expectedTargets = targets();
        Exception readFailure = null;
        for (int connector = 1; connector <= 3; connector++) {
            try {
                int observed = clamp(io.limitKw(connector), 0,
                    connector == 3 ? maxAcKw : maxDcKw);
                applied[connector] = logicalAppliedTarget(observed, expectedTargets[connector]);
            } catch (Exception e) {
                // Unknown must force a write. In particular, one broken getter
                // must not prevent Notladen from being reasserted on the other
                // connectors while a safety blocker is active.
                applied[connector] = -1;
                if (readFailure == null) readFailure = e;
            }
        }
        Exception writeFailure = null;
        int[] reconciledTargets = readFailure == null
            ? expectedTargets : new int[] { 0, 0, 0, 0 };
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
        // reassert logical zero as physical 5 kW Notladen. A native 0 kW write
        // is never emitted because this QC45 may interpret it as unlimited.
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
        int hardwareKw = hardwareTargetKw(targetKw);
        if (connector == 3) {
            // AC owns a separate configuration value and its full writer does
            // not authorize a charge. Prime it even while idle so EVCSD cannot
            // restore the legacy AC maximum before LoadManager sees the session.
            io.setConnectorLimitKw(connector, hardwareKw);
            return;
        }

        if (activeDcConnector == connector) {
            io.setConnectorLimitKw(connector, hardwareKw);
            return;
        }

        if (activeDcConnector == 0 && connector == 1) {
            // Both DC satellites share Configuration.maxPower. Use the non-CCS
            // connector as the idle configuration owner: this updates the shared
            // default without calling the CCS sendCcsStart path. Connector 2 is
            // then satellite-prearmed to the same value below.
            io.setConnectorLimitKw(connector, hardwareKw);
            return;
        }

        // Never let an inactive DC sibling use the full writer while the other
        // DC output is active; that would overwrite their shared configuration.
        // Connector 2 also stays on the non-authorizing path while idle.
        io.preArmConnectorLimitKw(connector, hardwareKw);
    }

    private static int hardwareTargetKw(int logicalKw) {
        return logicalKw <= 0 ? NOTLADEN_KW : logicalKw;
    }

    private static int logicalAppliedTarget(int observedKw, int expectedLogicalKw) {
        if (expectedLogicalKw == 0) {
            if (observedKw == NOTLADEN_KW) return 0;
            // Native zero is unsafe/ambiguous on the QC45. Force a Notladen write.
            if (observedKw == 0) return -1;
        }
        return observedKw;
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
