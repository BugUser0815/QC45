package de.rothner.qc45;

import java.lang.reflect.Method;

/** Native QC45 load manager using KOSTAL KSEM phase currents.
 *
 * Normal load balancing deliberately controls DC only. Type2/AC is never
 * manipulated here. Its grid consumption is still visible through the KSEM and
 * therefore reduces the headroom available to the active DC connector.
 *
 * The only EVCSD value modified by this class is SatelliteModule.setMaxPower()
 * on connector 1 or 2. No Configuration, fixed-power, CCS-current or AC values
 * are touched by the load manager.
 */
public final class LoadManager extends Thread {
    private static final double SQRT3_400_KW_PER_A = 0.692820323d;

    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final double targetA;
    private final double failbackGuardA;
    private final double hysteresisA;
    private final int minDcKw;
    private final int maxDcKw;
    private final int rampUpKwPerLoop;
    private final int intervalMs;

    private volatile boolean running = true;
    private boolean prevDcActive;
    private int prevDcConnector;
    private long lastErrorLog;

    public LoadManager(ReflectionQC45 station, KsemClient meter,
                       double targetA, double failbackGuardA, double hysteresisA,
                       int minDcKw, int maxDcKw,
                       int minAcKw, int maxAcKw,
                       int rampUpKwPerLoop, int intervalMs) {
        super("QC45-LoadManager");
        setDaemon(true);
        this.station = station;
        this.meter = meter;
        this.targetA = targetA;
        this.failbackGuardA = failbackGuardA;
        this.hysteresisA = hysteresisA;
        this.minDcKw = minDcKw;
        this.maxDcKw = maxDcKw;
        this.rampUpKwPerLoop = rampUpKwPerLoop;
        this.intervalMs = intervalMs;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        System.out.println("[QC45] LoadManager started DC-only target=" + one(targetA)
            + "A ramp=" + rampUpKwPerLoop + "kW/loop control=Satellite.setMaxPower");

        try {
            setConnectorPowerOnly(1, minDcKw);
            setConnectorPowerOnly(2, minDcKw);
        } catch (Throwable e) {
            System.err.println("[QC45] LoadManager startup reset failed: " + e);
        }

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents currents = meter.readCurrents();
                double criticalA = currents.max();
                Active active = detectActiveDc();

                boolean newDc = active.dc && (!prevDcActive || active.dcConnector != prevDcConnector);
                if (newDc) {
                    setConnectorPowerOnly(active.dcConnector, minDcKw);
                    prevDcActive = true;
                    prevDcConnector = active.dcConnector;
                    System.out.println("[QC45] LoadManager session start DC=" + active.dcConnector);
                    sleepLoop();
                    continue;
                }

                if (!active.dc && prevDcActive) {
                    if (prevDcConnector > 0) setConnectorPowerOnly(prevDcConnector, minDcKw);
                    prevDcActive = false;
                    prevDcConnector = 0;
                    System.out.println("[QC45] LoadManager session end");
                    sleepLoop();
                    continue;
                }

                if (!active.dc) {
                    sleepLoop();
                    continue;
                }

                prevDcActive = true;
                prevDcConnector = active.dcConnector;

                // GridFailback owns the emergency/reduction area. Do not fight it.
                if (criticalA >= failbackGuardA) {
                    sleepLoop();
                    continue;
                }

                int actualDcKw = station.powerKw(active.dcConnector);
                int currentLimitKw = station.limitKw(active.dcConnector);
                currentLimitKw = clamp(currentLimitKw, minDcKw, maxDcKw);
                double headroomA = targetA - criticalA;

                int targetKw;
                if (Math.abs(headroomA) < hysteresisA) {
                    targetKw = currentLimitKw;
                } else if (headroomA < 0.0d) {
                    // Above the grid target: only reduce from the currently
                    // commanded connector power. Never derive a higher target
                    // from lagging actual vehicle power.
                    double reducedRawKw = currentLimitKw
                        + headroomA * SQRT3_400_KW_PER_A;
                    targetKw = Math.min(currentLimitKw, (int)Math.round(reducedRawKw));
                } else {
                    // Below target: retain the proven fast ramp. Actual DC power
                    // is used to estimate available headroom, while the ramp is
                    // anchored to the current connector command.
                    double requestedRawKw = actualDcKw
                        + headroomA * SQRT3_400_KW_PER_A;
                    int requestedKw = clamp((int)Math.round(requestedRawKw), minDcKw, maxDcKw);
                    if (requestedKw > currentLimitKw) {
                        targetKw = Math.min(requestedKw, currentLimitKw + rampUpKwPerLoop);
                    } else {
                        targetKw = requestedKw;
                    }
                }

                targetKw = clamp(targetKw, minDcKw, maxDcKw);
                if (targetKw != currentLimitKw) {
                    setConnectorPowerOnly(active.dcConnector, targetKw);
                    System.out.println("[QC45] LoadManager set grid=" + one(criticalA)
                        + "A DC" + active.dcConnector + "=" + targetKw + "kW");
                }

            } catch (Throwable e) {
                if (now - lastErrorLog >= 5000L) {
                    System.err.println("[QC45] LoadManager error: " + e);
                    lastErrorLog = now;
                }
            }

            sleepLoop();
        }

        System.out.println("[QC45] LoadManager stopped");
    }

    /**
     * Pure connector-power control. This intentionally bypasses
     * ReflectionQC45.setConnectorLimitKw(), because that compatibility method
     * also changes global/fixed limits and CCS helper values.
     */
    private void setConnectorPowerOnly(int connector, int kw) throws Exception {
        if (connector != 1 && connector != 2) {
            throw new IllegalArgumentException("LoadManager controls DC connector 1 or 2 only");
        }
        kw = clamp(kw, minDcKw, maxDcKw);

        Class<?> centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object cm = centralClass.getMethod("getCurrentModule").invoke(null);
        if (cm == null) throw new IllegalStateException("CentralModule unavailable");

        Object[] sats = (Object[]) centralClass.getMethod("getSatellites").invoke(cm);
        if (sats == null) throw new IllegalStateException("Satellites unavailable");

        for (int i = 0; i < sats.length; i++) {
            Object sat = sats[i];
            if (sat == null) continue;
            Method getId = sat.getClass().getMethod("getSatelliteId");
            int id = ((Number)getId.invoke(sat)).intValue();
            if (id != connector) continue;

            sat.getClass().getMethod("setMaxPower", Integer.TYPE)
                .invoke(sat, Integer.valueOf(kw));
            return;
        }

        throw new IllegalArgumentException("Connector unavailable: " + connector);
    }

    private Active detectActiveDc() throws Exception {
        int p1 = station.powerKw(1);
        int p2 = station.powerKw(2);
        String u1 = station.idTag(1);
        String u2 = station.idTag(2);

        boolean c1 = p1 > 0 || u1.length() > 0;
        boolean c2 = p2 > 0 || u2.length() > 0;

        int dcConnector = 0;
        if (c1 && c2) dcConnector = p1 >= p2 ? 1 : 2;
        else if (c1) dcConnector = 1;
        else if (c2) dcConnector = 2;

        return new Active(dcConnector != 0, dcConnector);
    }

    private void sleepLoop() {
        try { Thread.sleep(intervalMs); }
        catch (InterruptedException e) { /* shutdown checked by loop */ }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    private static final class Active {
        final boolean dc;
        final int dcConnector;
        Active(boolean dc, int dcConnector) {
            this.dc = dc;
            this.dcConnector = dcConnector;
        }
    }
}
