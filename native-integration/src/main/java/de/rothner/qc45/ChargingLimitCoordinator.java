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
    public static final String SHUTDOWN = "shutdown";

    private final ChargingLimitIo io;
    private final int minDcKw;
    private final int maxDcKw;
    private final int minAcKw;
    private final int maxAcKw;
    private final Set<String> blockers = new HashSet<String>();

    private int requestedDcKw;
    private int requestedAcKw;
    private int gridDcKw;
    private int gridAcKw;
    private int stageDcCapKw;
    private int stageAcCapKw;
    private int activeDcConnector;
    private boolean acActive;
    private boolean ccsAvailable;
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
        // No evcc request has been received yet. Starting at zero avoids an
        // unintended charge after a JVM/webapp restart while evcc is offline.
        this.requestedDcKw = 0;
        this.requestedAcKw = 0;
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
        apply(false);
    }

    public synchronized void requestDcBudget(int kw) throws Exception {
        int nextKw = normalize(kw, minDcKw, maxDcKw);
        if (nextKw < requestedDcKw) gridDcKw = Math.min(gridDcKw, nextKw);
        requestedDcKw = nextKw;
        apply(false);
    }

    public synchronized void requestAcBudget(int kw) throws Exception {
        int nextKw = normalize(kw, minAcKw, maxAcKw);
        if (nextKw < requestedAcKw) gridAcKw = Math.min(gridAcKw, nextKw);
        requestedAcKw = nextKw;
        apply(false);
    }

    /** Publish the load manager's grid-safe allocation. */
    public synchronized void setGridTargets(int dcConnector, boolean acIsActive,
                                            int dcKw, int acKw) throws Exception {
        if (dcConnector < 0 || dcConnector > 2) throw new IllegalArgumentException("DC connector must be 0..2");
        activeDcConnector = dcConnector;
        acActive = acIsActive;
        gridDcKw = dcConnector == 0 ? 0 : normalize(dcKw, minDcKw, maxDcKw);
        gridAcKw = acIsActive ? normalize(acKw, minAcKw, maxAcKw) : 0;
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
    public synchronized int effectiveDcKw() { return targets()[activeDcConnector == 0 ? 1 : activeDcConnector]; }
    public synchronized int effectiveAcKw() { return targets()[3]; }

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
        if (activeDcConnector == 1) target[1] = dc;
        else if (activeDcConnector == 2 && ccsAvailable) target[2] = dc;
        if (acActive) target[3] = ac;
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
                    io.setConnectorLimitKw(connector, target[connector]);
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
                        io.setConnectorLimitKw(connector, target[connector]);
                        applied[connector] = target[connector];
                    } catch (Exception e) {
                        if (first == null) first = e;
                    }
                }
            }
        }
        if (first != null) throw first;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int normalize(int value, int min, int max) {
        if (value < min) return 0;
        return clamp(value, min, max);
    }
}
