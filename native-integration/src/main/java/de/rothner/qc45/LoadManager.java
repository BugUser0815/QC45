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
    static final double DAYTIME_PHASE_TARGET_A = 25.0d;
    static final double OFF_HOURS_PHASE_TARGET_A = 35.0d;
    static final int DC_HARD_MAX_KW = 35;

    private static final String OPERATING_TIME_ZONE = "Europe/Berlin";
    private static final int DAYTIME_OPEN_MINUTE = 6 * 60 + 30;
    private static final int DAYTIME_CLOSE_MINUTE = 18 * 60;

    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final ChargingLimitCoordinator limits;
    private final double configuredTargetA;
    private final double commandCeilingA;
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
    private double lastLoggedPhaseTargetA = -1.0d;

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
        this.minDcKw = minDcKw;
        this.maxDcKw = Math.min(DC_HARD_MAX_KW, maxDcKw);
        this.intervalMs = intervalMs;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started direct-KSEM DC control daytime="
            + one(DAYTIME_PHASE_TARGET_A) + "A Mo-Sa 06:30-18:00 "
            + OPERATING_TIME_ZONE + " offHours=" + one(OFF_HOURS_PHASE_TARGET_A)
            + "A hardMax=" + maxDcKw + "kW interval=" + intervalMs
            + "ms prearm/notladen=" + minDcKw
            + "kW; legacy configuredTarget=" + one(configuredTargetA)
            + "A/ramp/catch-up profile is not used");
        safeBlockMeter();

        while (running) {
            long now = System.currentTimeMillis();
            double activePhaseTargetA = phaseTargetA(now, commandCeilingA);
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
                    preparedDcKw = minDcKw;
                    limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                        active.dcConnector > 0 ? minDcKw : 0, 0,
                        active.dcConnector == 0 ? minDcKw : 0, 0, false);
                    if (meterHealthy) releasePreparedMeterBlocks();
                    previousDcConnector = active.dcConnector;
                    sleepLoop();
                    continue;
                }

                double criticalA = currents.max();
                if (criticalA >= commandCeilingA) {
                    preparedDcKw = minDcKw;
                    limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                        active.dcConnector > 0 ? minDcKw : 0, 0,
                        active.dcConnector == 0 ? minDcKw : 0, 0, false);
                    releasePreparedMeterBlocks();
                    previousDcConnector = active.dcConnector;
                    System.err.println("[QC45] LoadManager GUARD KSEM L1=" + one(currents.l1)
                        + "A L2=" + one(currents.l2) + "A L3=" + one(currents.l3)
                        + "A max=" + one(criticalA) + "A -> DC Notladen="
                        + minDcKw + "kW");
                    sleepLoop();
                    continue;
                }

                if (active.dcConnector == 0) {
                    // IMPORTANT: never pre-arm an idle QC45 above the emergency
                    // floor. The native charger interprets 0 kW as "no limit",
                    // so both DC outputs stay physically prepared at 5 kW until
                    // a real session is active and a fresh KSEM target is released.
                    int idleDcKw = minDcKw;
                    preparedDcKw = idleDcKw;
                    limits.setGridTargetsAndPrearm(0, active.ac,
                        0, 0, idleDcKw, 0, false);
                    releasePreparedMeterBlocks();
                    logTarget(currents, activePhaseTargetA,
                        idleDcKw, idleDcKw, 0, "prearm");
                    previousDcConnector = 0;
                    sleepLoop();
                    continue;
                }

                int commandedDcKw = limits.effectiveDcKw();
                int releasedDcKw = commandedDcKw;
                if (releasedDcKw <= 0 && preparedDcKw > 0) {
                    // A session can become active between two KSEM loops. The
                    // previously published 5 kW pre-arm limit is already the
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
                        activePhaseTargetA, minDcKw, requestedDcMax)
                    : 0;

                limits.setGridTargetsAndPrearm(active.dcConnector, active.ac,
                    targetDcKw, 0, 0, 0, false);
                releasePreparedMeterBlocks();
                preparedDcKw = targetDcKw > 0 ? targetDcKw : minDcKw;
                previousDcConnector = active.dcConnector;

                int actualDcKw = station.powerKw(active.dcConnector);
                logTarget(currents, activePhaseTargetA,
                    releasedDcKw, targetDcKw > 0 ? targetDcKw : minDcKw,
                    actualDcKw, "active");
            } catch (Throwable e) {
                markMeterOrControlFailure(now, e);
            }
            sleepLoop();
        }

        try {
            limits.setGridTargetsAndPrearm(0, false, 0, 0, minDcKw, 0, false);
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager stop Notladen failed: " + e);
        }
        System.out.println("[QC45] LoadManager stopped at DC Notladen=" + minDcKw + "kW");
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
        preparedDcKw = minDcKw;
        safeBlockMeter();
        if (now - lastErrorLog >= 5000L) {
            System.err.println("[QC45] LoadManager failure -> DC physical Notladen="
                + minDcKw + "kW: " + error);
            lastErrorLog = now;
        }
    }

    private void safeBlockMeter() {
        try { limits.setBlocked(ChargingLimitCoordinator.LOAD_METER, true); }
        catch (Throwable e) {
            System.err.println("[QC45] LoadManager Notladen enforcement failed: " + e);
        }
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

    private void logTarget(KsemClient.Currents currents, double phaseTargetA,
                           int releasedDcKw, int targetDcKw,
                           int actualDcKw, String mode) {
        if (targetDcKw == lastLoggedTargetKw
                && Math.abs(phaseTargetA - lastLoggedPhaseTargetA) < 0.000001d
                && "active".equals(mode)) return;
        System.out.println("[QC45] LoadManager KSEM-direct mode=" + mode
            + " L1=" + one(currents.l1) + "A L2=" + one(currents.l2)
            + "A L3=" + one(currents.l3) + "A max=" + one(currents.max())
            + "A phaseTarget=" + one(phaseTargetA)
            + "A releasedDC=" + releasedDcKw + "kW targetDC=" + targetDcKw
            + "kW actualDC=" + actualDcKw + "kW hardMax=" + maxDcKw + "kW");
        lastLoggedTargetKw = targetDcKw;
        lastLoggedPhaseTargetA = phaseTargetA;
    }

    private void sleepLoop() {
        try { Thread.sleep(intervalMs); }
        catch (InterruptedException e) { if (!running) return; }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    static double phaseTargetA(long epochMillis, double commandCeilingA) {
        double scheduledTarget = isDaytimeBuffer(epochMillis)
            ? DAYTIME_PHASE_TARGET_A : OFF_HOURS_PHASE_TARGET_A;
        return Math.min(scheduledTarget, Math.max(0.1d, commandCeilingA - 0.1d));
    }

    static boolean isDaytimeBuffer(long epochMillis) {
        Calendar local = Calendar.getInstance(TimeZone.getTimeZone(OPERATING_TIME_ZONE));
        local.setTimeInMillis(epochMillis);
        int day = local.get(Calendar.DAY_OF_WEEK);
        int minuteOfDay = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
        boolean mondayToSaturday = day >= Calendar.MONDAY && day <= Calendar.SATURDAY;
        return mondayToSaturday
            && minuteOfDay >= DAYTIME_OPEN_MINUTE
            && minuteOfDay < DAYTIME_CLOSE_MINUTE;
    }

    // Compatibility aliases for older diagnostics/tests.
    static double operatingTargetA(long epochMillis, double configuredTargetA,
                                   double commandCeilingA, double hysteresisA) {
        return phaseTargetA(epochMillis, commandCeilingA);
    }

    static boolean isBusinessHours(long epochMillis) {
        return isDaytimeBuffer(epochMillis);
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
