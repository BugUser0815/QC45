package de.rothner.qc45;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Native DC load manager driven directly by KSEM phase-current headroom.
 *
 * Type2/AC is operator-fixed in the original QC45 and is therefore not
 * controlled here. Any AC load, company load, PV, SMA/BYD support or other
 * site effect is automatically reflected in the KSEM currents. The DC target
 * is calculated from the most heavily loaded phase only.
 */
public final class LoadManager extends Thread {
    private static final int HEALTHY_READS_TO_RESUME = 5;
    static final double KSEM_PHASE_TARGET_A = 35.0d;
    static final int DC_HARD_MAX_KW = 35;

    // Kept only for compatibility with historical tests/config diagnostics.
    // The live controller no longer applies a time-of-day current profile.
    private static final double BUSINESS_TARGET_A = 27.0d;
    private static final double OFF_HOURS_CEILING_MARGIN_A = 0.1d;
    private static final String BUSINESS_TIME_ZONE = "Europe/Berlin";
    private static final int BUSINESS_OPEN_MINUTE = 7 * 60;
    private static final int BUSINESS_CLOSE_MON_THU_MINUTE = 15 * 60;
    private static final int BUSINESS_CLOSE_FRI_MINUTE = 13 * 60;

    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final ChargingLimitCoordinator limits;
    private final double configuredTargetA;
    private final double commandCeilingA;
    private final double ksemPhaseTargetA;
    private final int minDcKw;
    private final int maxDcKw;
    private final int intervalMs;

    private volatile boolean running = true;
    private boolean meterHealthy;
    private int healthyReads;
    private int preparedDcKw;
    private int previousDcConnector;
    private long lastErrorLog;
    private int lastLoggedTargetKw = -1;

    public LoadManager(ReflectionQC45 station, KsemClient meter,
                       ChargingLimitCoordinator limits,
                       double targetA, double commandCeilingA, double hysteresisA,
                       int minDcKw, int maxDcKw, int minAcKw, int maxAcKw,
                       int rampUpKwPerLoop, int intervalMs,
                       long demandStableMs, int demandReserveKw) {
        super("QC45-LoadManager");
        setDaemon(true);
        if (station == null || meter == null || limits == null) {
            throw new IllegalArgumentException("station, meter and limits are required");
        }
        if (targetA <= 0.0d || commandCeilingA <= 0.0d
                || hysteresisA < 0.0d || minDcKw <= 0 || minAcKw <= 0
                || maxDcKw < minDcKw || maxAcKw < minAcKw
                || rampUpKwPerLoop <= 0 || intervalMs <= 0) {
            throw new IllegalArgumentException("invalid load-manager limits or timing");
        }
        this.station = station;
        this.meter = meter;
        this.limits = limits;
        this.configuredTargetA = targetA;
        this.commandCeilingA = commandCeilingA;
        this.ksemPhaseTargetA = Math.min(KSEM_PHASE_TARGET_A,
            Math.max(0.1d, commandCeilingA - 0.1d));
        this.minDcKw = minDcKw;
        this.maxDcKw = Math.min(DC_HARD_MAX_KW, maxDcKw);
        this.intervalMs = intervalMs;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started direct-KSEM DC control phaseTarget="
            + one(ksemPhaseTargetA) + "A hardMax=" + maxDcKw
            + "kW interval=" + intervalMs + "ms; legacy configuredTarget="
            + one(configuredTargetA) + "A/ramp/catch-up profile is not used");
        safeBlockMeter();

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                markMeterReadHealthy();
                Active active = detectActive();

                // Close writes made by legacy EVCSD code before calculating a
                // new KSEM-derived target.
                limits.reconcile();

                int requestedDcMax = Math.min(maxDcKw, limits.requestedDcKw());
                boolean dcEligible = active.dcConnector > 0
                    && (active.dcConnector != 2 || limits.isCcsAvailable())
                    && requestedDcMax >= minDcKw;

                boolean externalBlock = limits.hasBlockerOtherThan(
                    ChargingLimitCoordinator.STARTUP,
                    ChargingLimitCoordinator.LOAD_METER);
                if (!meterHealthy || externalBlock) {
                    preparedDcKw = 0;
                    limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                        0, 0, 0, 0, false);
                    if (meterHealthy) releasePreparedMeterBlocks();
                    previousDcConnector = active.dcConnector;
                    sleepLoop();
                    continue;
                }

                double criticalA = currents.max();
                if (criticalA >= commandCeilingA) {
                    preparedDcKw = 0;
                    limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                        0, 0, 0, 0, false);
                    releasePreparedMeterBlocks();
                    previousDcConnector = active.dcConnector;
                    System.err.println("[QC45] LoadManager GUARD KSEM L1=" + one(currents.l1)
                        + "A L2=" + one(currents.l2) + "A L3=" + one(currents.l3)
                        + "A max=" + one(criticalA) + "A -> DC=0kW");
                    sleepLoop();
                    continue;
                }

                if (active.dcConnector == 0) {
                    int idleDcKw = limits.requestedDcKw() >= minDcKw
                        ? KsemDcAllocator.targetKw(0, currents, ksemPhaseTargetA,
                            minDcKw, requestedDcMax)
                        : 0;
                    preparedDcKw = idleDcKw;
                    limits.setGridTargetsAndPrearm(0, active.ac,
                        0, 0, idleDcKw, 0, false);
                    releasePreparedMeterBlocks();
                    logTarget(currents, 0, idleDcKw, 0, "prearm");
                    previousDcConnector = 0;
                    sleepLoop();
                    continue;
                }

                int commandedDcKw = limits.effectiveDcKw();
                int releasedDcKw = commandedDcKw;
                if (releasedDcKw <= 0 && preparedDcKw > 0) {
                    // A session can become active between two KSEM loops. The
                    // previously published idle/pre-arm limit is already the
                    // released hardware budget and must be the base of the next
                    // headroom calculation instead of restarting from zero.
                    releasedDcKw = preparedDcKw;
                }
                if (active.dcConnector != previousDcConnector && releasedDcKw <= 0) {
                    releasedDcKw = minDcKw;
                }
                releasedDcKw = Math.min(releasedDcKw, requestedDcMax);

                int targetDcKw = dcEligible
                    ? KsemDcAllocator.targetKw(releasedDcKw, currents,
                        ksemPhaseTargetA, minDcKw, requestedDcMax)
                    : 0;

                limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                    targetDcKw, 0, 0, 0, false);
                releasePreparedMeterBlocks();
                preparedDcKw = targetDcKw;
                previousDcConnector = active.dcConnector;

                int actualDcKw = station.powerKw(active.dcConnector);
                logTarget(currents, releasedDcKw, targetDcKw, actualDcKw, "active");
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
            System.out.println("[QC45] LoadManager KSEM qualified: direct power calculation enabled");
        }
    }

    private void releasePreparedMeterBlocks() throws Exception {
        boolean releasing = limits.isBlockedBy(ChargingLimitCoordinator.LOAD_METER)
            || limits.isBlockedBy(ChargingLimitCoordinator.STARTUP);
        if (!releasing) return;
        limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, false);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        System.out.println("[QC45] LoadManager fresh KSEM target prepared: charging release enabled");
    }

    private void markMeterOrControlFailure(long now, Throwable error) {
        meterHealthy = false;
        healthyReads = 0;
        preparedDcKw = 0;
        safeBlockMeter();
        if (now - lastErrorLog >= 5000L) {
            System.err.println("[QC45] LoadManager failure -> DC=0kW: " + error);
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

    private void logTarget(KsemClient.Currents currents, int releasedDcKw,
                           int targetDcKw, int actualDcKw, String mode) {
        if (targetDcKw == lastLoggedTargetKw && "active".equals(mode)) return;
        System.out.println("[QC45] LoadManager KSEM-direct mode=" + mode
            + " L1=" + one(currents.l1) + "A L2=" + one(currents.l2)
            + "A L3=" + one(currents.l3) + "A max=" + one(currents.max())
            + "A phaseTarget=" + one(ksemPhaseTargetA)
            + "A releasedDC=" + releasedDcKw + "kW targetDC=" + targetDcKw
            + "kW actualDC=" + actualDcKw + "kW hardMax=" + maxDcKw + "kW");
        lastLoggedTargetKw = targetDcKw;
    }

    private void sleepLoop() {
        try { Thread.sleep(intervalMs); }
        catch (InterruptedException e) { if (!running) return; }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    /*
     * Historical helpers retained for source/test compatibility. The live run()
     * path intentionally does not call them anymore; KSEM_PHASE_TARGET_A is the
     * sole normal DC operating target.
     */
    static double operatingTargetA(long epochMillis, double configuredTargetA,
                                   double commandCeilingA, double hysteresisA) {
        if (isBusinessHours(epochMillis)) {
            return Math.min(BUSINESS_TARGET_A, configuredTargetA);
        }
        double envelopeTargetA = commandCeilingA - hysteresisA - OFF_HOURS_CEILING_MARGIN_A;
        return Math.max(configuredTargetA, envelopeTargetA);
    }

    static boolean isBusinessHours(long epochMillis) {
        Calendar local = Calendar.getInstance(TimeZone.getTimeZone(BUSINESS_TIME_ZONE));
        local.setTimeInMillis(epochMillis);
        int day = local.get(Calendar.DAY_OF_WEEK);
        int minuteOfDay = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
        if (day >= Calendar.MONDAY && day <= Calendar.THURSDAY) {
            return minuteOfDay >= BUSINESS_OPEN_MINUTE
                && minuteOfDay < BUSINESS_CLOSE_MON_THU_MINUTE;
        }
        if (day == Calendar.FRIDAY) {
            return minuteOfDay >= BUSINESS_OPEN_MINUTE
                && minuteOfDay < BUSINESS_CLOSE_FRI_MINUTE;
        }
        return false;
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
