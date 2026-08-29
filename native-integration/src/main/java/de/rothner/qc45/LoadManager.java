package de.rothner.qc45;

/**
 * Native load manager for one active DC connector plus Type2/AC.
 *
 * AC has priority and is not power-limited by the normal grid allocator. Its
 * real consumption is already contained in the KSEM phase currents. DC receives
 * only the residual grid headroom that remains after AC and all other site loads.
 * {@link ChargingLimitCoordinator} remains the sole hardware writer and combines
 * these targets with explicit evcc requests and independent failback protection.
 */
public final class LoadManager extends Thread {
    private static final int HEALTHY_READS_TO_RESUME = 5;
    private static final long START_SETTLE_MS = 3000L;

    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final ChargingLimitCoordinator limits;
    private final double targetA;
    private final double commandCeilingA;
    private final double hysteresisA;
    private final int minDcKw;
    private final int maxDcKw;
    private final int minAcKw;
    private final int maxAcKw;
    private final int rampUpKwPerLoop;
    private final int intervalMs;

    private volatile boolean running = true;
    private boolean meterHealthy;
    private int healthyReads;
    private int previousDcConnector;
    private boolean previousAcActive;
    private int previousActualDcKw;
    private long dcSettleUntilMs;
    private int lastPrearmDcKw = -1;
    private int lastPrearmAcKw = -1;
    private long lastErrorLog;

    public LoadManager(ReflectionQC45 station, KsemClient meter,
                       ChargingLimitCoordinator limits,
                       double targetA, double commandCeilingA, double hysteresisA,
                       int minDcKw, int maxDcKw, int minAcKw, int maxAcKw,
                       int rampUpKwPerLoop, int intervalMs,
                       long demandStableMs, int demandReserveKw) {
        super("QC45-LoadManager");
        setDaemon(true);
        if (station == null || meter == null || limits == null) throw new IllegalArgumentException("station, meter and limits are required");
        if (targetA <= 0.0d || targetA >= commandCeilingA) throw new IllegalArgumentException("targetA must be positive and below command ceiling");
        if (hysteresisA < 0.0d || minDcKw <= 0 || minAcKw <= 0
                || maxDcKw < minDcKw || maxAcKw < minAcKw
                || rampUpKwPerLoop <= 0 || intervalMs <= 0) {
            throw new IllegalArgumentException("invalid load-manager limits or timing");
        }
        this.station = station;
        this.meter = meter;
        this.limits = limits;
        this.targetA = targetA;
        this.commandCeilingA = commandCeilingA;
        this.hysteresisA = hysteresisA;
        this.minDcKw = minDcKw;
        this.maxDcKw = maxDcKw;
        this.minAcKw = minAcKw;
        this.maxAcKw = maxAcKw;
        this.rampUpKwPerLoop = rampUpKwPerLoop;
        this.intervalMs = intervalMs;
        // demandStableMs/demandReserveKw are retained in the constructor for
        // configuration compatibility. AC/DC entitlement transfer is no longer
        // used because AC now has unconditional normal-operation priority.
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started AC-priority/DC-residual target=" + one(targetA)
            + "A ceiling=" + one(commandCeilingA) + "A ramp="
            + rampUpKwPerLoop + "kW/loop startup/recovery="
            + HEALTHY_READS_TO_RESUME + " valid KSEM reads");
        safeBlockMeter();

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                markMeterReadHealthy();
                Active active = detectActive();

                // Close writes made by legacy EVCSD code before calculating a
                // possible DC increase.
                limits.reconcile();

                int requestedDcMax = Math.min(maxDcKw, limits.requestedDcKw());
                int requestedAcMax = Math.min(maxAcKw, limits.requestedAcKw());
                boolean dcEligible = active.dcConnector > 0
                    && (active.dcConnector != 2 || limits.isCcsAvailable())
                    && requestedDcMax >= minDcKw;
                boolean acEligible = active.ac && requestedAcMax >= minAcKw;
                boolean acMayStart = requestedAcMax >= minAcKw;
                int unrestrictedAcKw = acEligible ? requestedAcMax : 0;
                int unrestrictedAcPrearmKw = !active.ac && acMayStart ? requestedAcMax : 0;

                boolean externalBlock = limits.hasBlockerOtherThan(
                    ChargingLimitCoordinator.STARTUP,
                    ChargingLimitCoordinator.LOAD_METER);
                if (!meterHealthy || externalBlock) {
                    resetTracking();
                    limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                        0, 0, 0, 0, false);
                    if (meterHealthy) releasePreparedMeterBlocks();
                    rememberActive(active);
                    sleepLoop();
                    continue;
                }

                double criticalA = currents.max();
                if (criticalA >= commandCeilingA) {
                    resetTracking();
                    limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                        0, unrestrictedAcKw, 0, unrestrictedAcPrearmKw, false);
                    releasePreparedMeterBlocks();
                    rememberActive(active);
                    System.err.println("[QC45] LoadManager GUARD grid=" + one(criticalA)
                        + "A -> DC=0kW AC=unrestricted; GridFailback remains authoritative");
                    sleepLoop();
                    continue;
                }

                if (active.dcConnector != previousDcConnector) {
                    previousActualDcKw = 0;
                }

                if (active.dcConnector == 0 && !active.ac) {
                    resetTracking();
                    dcSettleUntilMs = 0L;
                    LoadAllocator.Targets dcPrearm = LoadAllocator.safePrearm(
                        false, false, 0, 0, 0, 0,
                        requestedDcMax >= minDcKw,
                        false,
                        minDcKw, minAcKw, criticalA, commandCeilingA);
                    LoadAllocator.Targets prearm = new LoadAllocator.Targets(
                        dcPrearm.dcKw, unrestrictedAcPrearmKw);
                    limits.setGridTargetsAndPrearm(0, false, 0, 0,
                        prearm.dcKw, prearm.acKw, false);
                    releasePreparedMeterBlocks();
                    logPrearm(prearm, criticalA);
                    rememberActive(active);
                    sleepLoop();
                    continue;
                }

                int actualDcKw = active.dcConnector > 0 ? station.powerKw(active.dcConnector) : 0;
                int actualAcKw = active.ac ? station.powerKw(3) : 0;
                int safetyCreditedDcKw = Math.min(actualDcKw, previousActualDcKw);
                int commandedDcKw = limits.effectiveDcKw();
                int commandedAcKw = limits.effectiveAcKw();

                // criticalA already contains the actual AC load and every other
                // site load. Plan DC as the only controllable consumer, so AC is
                // never assigned a share or reduced by the normal load manager.
                LoadAllocator.Targets dcPlan = LoadAllocator.plan(
                    dcEligible, false,
                    safetyCreditedDcKw, 0,
                    commandedDcKw, 0,
                    criticalA, targetA, commandCeilingA, hysteresisA,
                    minDcKw, requestedDcMax,
                    minAcKw, maxAcKw,
                    rampUpKwPerLoop);
                LoadAllocator.Targets target = new LoadAllocator.Targets(
                    dcPlan.dcKw, unrestrictedAcKw);

                if (active.dcConnector > 0 && commandedDcKw == 0
                        && target.dcKw > 0 && dcSettleUntilMs <= now) {
                    dcSettleUntilMs = now + START_SETTLE_MS;
                    System.out.println("[QC45] DC-SETTLE armed connector="
                        + active.dcConnector + " limit=" + minDcKw
                        + "kW until=" + dcSettleUntilMs);
                }
                if (active.dcConnector == 0) dcSettleUntilMs = 0L;
                boolean dcSettling = active.dcConnector > 0 && now < dcSettleUntilMs;
                target = LoadAllocator.constrainStartupSettling(target,
                    dcSettling, false,
                    minDcKw, minAcKw);

                // Only DC pre-arm is grid-projected. AC pre-arm intentionally
                // stays at its configured/explicit cap and actual AC draw will
                // immediately consume headroom in the next KSEM observation.
                LoadAllocator.Targets dcPrearm = LoadAllocator.safePrearm(
                    active.dcConnector > 0, false,
                    target.dcKw, 0,
                    actualDcKw, 0,
                    requestedDcMax >= minDcKw,
                    false,
                    minDcKw, minAcKw, criticalA, commandCeilingA);
                LoadAllocator.Targets prearm = new LoadAllocator.Targets(
                    dcPrearm.dcKw, unrestrictedAcPrearmKw);

                limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                    target.dcKw, target.acKw,
                    prearm.dcKw, prearm.acKw, false);
                releasePreparedMeterBlocks();
                logPrearm(prearm, criticalA);
                previousActualDcKw = actualDcKw;
                rememberActive(active);

                if (target.dcKw != commandedDcKw || target.acKw != commandedAcKw) {
                    System.out.println("[QC45] LoadManager set grid=" + one(criticalA)
                        + "A DC=" + target.dcKw + "kW AC=" + target.acKw
                        + "kW actualDC=" + actualDcKw + "kW actualAC=" + actualAcKw
                        + "kW evccCapDC=" + requestedDcMax + "kW evccCapAC="
                        + requestedAcMax + "kW priority=AC-first/DC-residual");
                }
            } catch (Throwable e) {
                markMeterOrControlFailure(now, e);
            }
            sleepLoop();
        }

        try { limits.setGridTargets(0, false, 0, 0); }
        catch (Throwable e) { System.err.println("[QC45] LoadManager stop zero failed: " + e); }
        System.out.println("[QC45] LoadManager stopped");
    }

    private void markMeterReadHealthy() {
        if (healthyReads < HEALTHY_READS_TO_RESUME) healthyReads++;
        if (!meterHealthy && healthyReads >= HEALTHY_READS_TO_RESUME) {
            meterHealthy = true;
            System.out.println("[QC45] LoadManager KSEM qualified: preparing a fresh grid-safe DC target");
        }
    }

    /**
     * The fresh target has already been published while blocked. Removing the
     * caller-owned blockers now can therefore expose only that target, never a
     * pre-failure allocation.
     */
    private void releasePreparedMeterBlocks() throws Exception {
        boolean releasing = limits.isBlockedBy(ChargingLimitCoordinator.LOAD_METER)
            || limits.isBlockedBy(ChargingLimitCoordinator.STARTUP);
        if (!releasing) return;
        limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        System.out.println("[QC45] LoadManager fresh grid target prepared: charging release enabled");
    }

    private void markMeterOrControlFailure(long now, Throwable error) {
        meterHealthy = false;
        healthyReads = 0;
        previousActualDcKw = 0;
        resetTracking();
        safeBlockMeter();
        if (now - lastErrorLog >= 5000L) {
            System.err.println("[QC45] LoadManager failure -> safety block: " + error);
            lastErrorLog = now;
        }
    }

    private void safeBlockMeter() {
        try { limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, true); }
        catch (Throwable e) { System.err.println("[QC45] LoadManager safety zero failed: " + e); }
    }

    private Active detectActive() throws Exception {
        boolean c1 = station.sessionActive(1);
        boolean c2 = station.sessionActive(2);
        boolean ac = station.sessionActive(3);
        int dc = 0;
        if (c1 && c2) dc = station.powerKw(1) >= station.powerKw(2) ? 1 : 2;
        else if (c1) dc = 1;
        else if (c2) dc = 2;
        return new Active(dc, ac);
    }

    private void rememberActive(Active active) {
        previousDcConnector = active.dcConnector;
        previousAcActive = active.ac;
    }

    private void resetTracking() {
        previousActualDcKw = 0;
    }

    private void logPrearm(LoadAllocator.Targets prearm, double criticalA) {
        if (prearm.dcKw == lastPrearmDcKw && prearm.acKw == lastPrearmAcKw) return;
        System.out.println("[QC45] LoadManager start pre-arm grid=" + one(criticalA)
            + "A DC=" + prearm.dcKw + "kW AC=" + prearm.acKw
            + "kW AC-unrestricted=true DC-settle=" + START_SETTLE_MS + "ms");
        lastPrearmDcKw = prearm.dcKw;
        lastPrearmAcKw = prearm.acKw;
    }

    private void sleepLoop() {
        try { Thread.sleep(intervalMs); }
        catch (InterruptedException e) { if (!running) return; }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    private static final class Active {
        final int dcConnector;
        final boolean ac;
        Active(int dcConnector, boolean ac) {
            this.dcConnector = dcConnector;
            this.ac = ac;
        }
    }
}
