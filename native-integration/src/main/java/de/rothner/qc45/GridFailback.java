package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Independent grid-current failback.
 *
 * Stage 1: if any phase stays above reduceA long enough, force QC45 DC/AC budgets down.
 * Stage 2: if any phase stays above tripA long enough, or exceeds instantTripA, stop all connectors and latch.
 * KSEM communication loss is handled differently: set DC and AC limits to 0 kW,
 * keep the charging transactions alive, and automatically resume control after
 * stable meter readings return.
 *
 * A hard-trip latch can be reset locally with a deliberate emergency-stop cycle:
 * after the trip the emergency stop must first become active and then inactive.
 * Merely being inactive is never sufficient to clear the latch.
 */
public final class GridFailback extends Thread {
    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final double reduceA;
    private final long reduceDelayMs;
    private final double tripA;
    private final long tripDelayMs;
    private final double instantTripA;
    private final int reduceDcKw;
    private final int reduceAcKw;
    private final int intervalMs;
    private final boolean tripOnMeterFailure;
    private final long meterFailureMs;
    private final EmergencyStopProbe emergencyStopProbe = new EmergencyStopProbe();

    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile boolean meterPaused;
    private boolean emergencySeenWhileTripped;
    private Boolean lastEmergencyState;
    private long reduceSince;
    private long tripSince;
    private long lastGoodRead;
    private long lastLog;
    private long lastEmergencyProbeLog;
    private int goodReadsAfterMeterPause;

    public GridFailback(ReflectionQC45 station, KsemClient meter,
                        double reduceA, long reduceDelayMs,
                        double tripA, long tripDelayMs,
                        double instantTripA,
                        int reduceDcKw, int reduceAcKw,
                        int intervalMs,
                        boolean tripOnMeterFailure, long meterFailureMs) {
        super("QC45-Grid-Failback");
        setDaemon(true);
        this.station = station;
        this.meter = meter;
        this.reduceA = reduceA;
        this.reduceDelayMs = reduceDelayMs;
        this.tripA = tripA;
        this.tripDelayMs = tripDelayMs;
        this.instantTripA = instantTripA;
        this.reduceDcKw = reduceDcKw;
        this.reduceAcKw = reduceAcKw;
        this.intervalMs = intervalMs;
        this.tripOnMeterFailure = tripOnMeterFailure;
        this.meterFailureMs = meterFailureMs;
    }

    public boolean isTripped() { return tripped; }
    public boolean isMeterPaused() { return meterPaused; }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        lastGoodRead = System.currentTimeMillis();
        System.out.println("[QC45] GridFailback started emergency-reset=press-and-release");

        while (running) {
            long now = System.currentTimeMillis();
            try {
                KsemClient.Currents c = meter.readCurrents();
                lastGoodRead = now;
                double max = c.max();

                if (now - lastLog >= 5000L || max >= reduceA || tripped || meterPaused) {
                    System.out.println("[QC45] Grid L1=" + one(c.l1) + "A L2=" + one(c.l2)
                        + "A L3=" + one(c.l3) + "A max=" + one(max) + "A"
                        + (tripped ? " TRIPPED" : meterPaused ? " METER-PAUSED" : ""));
                    lastLog = now;
                }

                if (tripped) {
                    evaluateEmergencyReset(now);
                } else if (meterPaused) {
                    if (max < reduceA) {
                        goodReadsAfterMeterPause++;
                        if (goodReadsAfterMeterPause >= 5) clearMeterPause();
                    } else {
                        goodReadsAfterMeterPause = 0;
                    }
                } else {
                    evaluate(now, max);
                }
            } catch (Throwable e) {
                goodReadsAfterMeterPause = 0;
                if (now - lastLog >= 5000L) {
                    System.err.println("[QC45] GridFailback KSEM read failed: " + e);
                    lastLog = now;
                }
                if (!tripped && !meterPaused && tripOnMeterFailure
                        && now - lastGoodRead >= meterFailureMs) {
                    pauseForMeterFailure(now - lastGoodRead);
                }

                // The emergency-stop reset must still work if KSEM happens to be unavailable.
                if (tripped) {
                    try { evaluateEmergencyReset(now); }
                    catch (Throwable probeError) {
                        if (now - lastEmergencyProbeLog >= 5000L) {
                            System.err.println("[QC45] GRID FAILBACK RESET-PROBE failed: " + probeError);
                            lastEmergencyProbeLog = now;
                        }
                    }
                }
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }

        System.out.println("[QC45] GridFailback stopped");
    }

    private void evaluate(long now, double max) throws Exception {
        if (max >= instantTripA) {
            hardTrip("instant phase current " + one(max) + "A >= " + one(instantTripA) + "A");
            return;
        }

        if (max >= tripA) {
            if (tripSince == 0L) tripSince = now;
            if (now - tripSince >= tripDelayMs) {
                hardTrip("phase current " + one(max) + "A >= " + one(tripA)
                    + "A for " + (now - tripSince) + "ms");
                return;
            }
        } else {
            tripSince = 0L;
        }

        if (max >= reduceA) {
            if (reduceSince == 0L) reduceSince = now;
            if (now - reduceSince >= reduceDelayMs) forceMinimum();
        } else {
            reduceSince = 0L;
        }
    }

    private void forceMinimum() throws Exception {
        station.setDcBudgetKw(reduceDcKw);
        station.setAcBudgetKw(reduceAcKw);
    }

    private synchronized void pauseForMeterFailure(long outageMs) {
        if (tripped || meterPaused) return;
        meterPaused = true;
        goodReadsAfterMeterPause = 0;
        System.err.println("[QC45] GRID FAILBACK METER PAUSE: KSEM communication lost for "
            + outageMs + "ms -> DC=0kW AC=0kW, transactions remain active");
        try { station.setDcBudgetKw(0); }
        catch (Throwable e) { System.err.println("[QC45] meter-pause DC=0 failed: " + e); }
        try { station.setAcBudgetKw(0); }
        catch (Throwable e) { System.err.println("[QC45] meter-pause AC=0 failed: " + e); }
    }

    private synchronized void clearMeterPause() {
        if (!meterPaused || tripped) return;
        meterPaused = false;
        goodReadsAfterMeterPause = 0;
        reduceSince = 0L;
        tripSince = 0L;
        System.out.println("[QC45] GRID FAILBACK METER RECOVERED: KSEM stable, LoadManager may ramp charging again");
    }

    private synchronized void hardTrip(String reason) {
        if (tripped) return;
        tripped = true;
        meterPaused = false;
        emergencySeenWhileTripped = false;
        lastEmergencyState = null;
        lastEmergencyProbeLog = 0L;
        System.err.println("[QC45] GRID FAILBACK TRIP: " + reason
            + " [latched; reset requires emergency-stop press + release]");
        enforceHardTripOnce();
    }

    private void evaluateEmergencyReset(long now) throws Exception {
        EmergencyStopProbe.Result result = emergencyStopProbe.read();

        if (lastEmergencyState == null || lastEmergencyState.booleanValue() != result.active) {
            System.out.println("[QC45] GRID FAILBACK RESET-PROBE emergencyActive=" + result.active
                + " candidates=" + result.description);
            lastEmergencyState = Boolean.valueOf(result.active);
            lastEmergencyProbeLog = now;
        } else if (now - lastEmergencyProbeLog >= 5000L) {
            System.out.println("[QC45] GRID FAILBACK RESET-PROBE emergencyActive=" + result.active
                + " armed=" + emergencySeenWhileTripped
                + " candidates=" + result.description);
            lastEmergencyProbeLog = now;
        }

        if (result.active) {
            if (!emergencySeenWhileTripped) {
                emergencySeenWhileTripped = true;
                System.out.println("[QC45] GRID FAILBACK RESET ARMED: emergency stop pressed; release it to clear trip latch");
            }
            return;
        }

        if (emergencySeenWhileTripped) {
            clearHardTripAfterEmergencyCycle();
        }
    }

    private synchronized void clearHardTripAfterEmergencyCycle() {
        if (!tripped || !emergencySeenWhileTripped) return;
        tripped = false;
        emergencySeenWhileTripped = false;
        lastEmergencyState = Boolean.FALSE;
        reduceSince = 0L;
        tripSince = 0L;
        goodReadsAfterMeterPause = 0;
        System.out.println("[QC45] GRID FAILBACK RESET: emergency stop press/release cycle completed; latch cleared");
    }

    private void enforceHardTripOnce() {
        try { station.setDcBudgetKw(reduceDcKw); }
        catch (Throwable e) { System.err.println("[QC45] failback DC reduction failed: " + e); }
        try { station.setAcBudgetKw(reduceAcKw); }
        catch (Throwable e) { System.err.println("[QC45] failback AC reduction failed: " + e); }
        for (int connector = 1; connector <= 3; connector++) {
            try { station.remoteStop(connector); }
            catch (Throwable e) { /* connector may be idle */ }
        }
    }

    private static String one(double value) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }

    /**
     * Best-effort discovery of the physical emergency-stop state in this old
     * EVCSD firmware. Only boolean methods/fields are allowed to drive a reset.
     * Numeric/string candidates are reported for diagnostics but never treated
     * as an asserted emergency stop.
     */
    private static final class EmergencyStopProbe {
        Result read() throws Exception {
            Class<?> centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
            Object cm = centralClass.getMethod("getCurrentModule").invoke(null);
            if (cm == null) throw new IllegalStateException("CentralModule unavailable");

            ProbeAccumulator acc = new ProbeAccumulator();
            inspectObject("CentralModule", cm, acc);

            try {
                Object conf = centralClass.getMethod("getConf").invoke(cm);
                if (conf != null) inspectObject("Configuration", conf, acc);
            } catch (Throwable ignored) {}

            try {
                Object value = centralClass.getMethod("getSatellites").invoke(cm);
                if (value instanceof Object[]) {
                    Object[] sats = (Object[]) value;
                    for (int i = 0; i < sats.length; i++) {
                        if (sats[i] == null) continue;
                        int id = i;
                        try {
                            id = ((Number) sats[i].getClass().getMethod("getSatelliteId").invoke(sats[i])).intValue();
                        } catch (Throwable ignored) {}
                        inspectObject("Satellite" + id, sats[i], acc);
                    }
                }
            } catch (Throwable ignored) {}

            String description = acc.description.length() == 0
                ? "none-found" : acc.description.toString();
            return new Result(acc.active, description);
        }

        private static void inspectObject(String label, Object target, ProbeAccumulator acc) {
            Class<?> type = target.getClass();

            Method[] methods = type.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getParameterTypes().length != 0) continue;
                if (!candidateName(m.getName())) continue;
                try {
                    Object value = m.invoke(target);
                    append(acc, label + "." + m.getName() + "()", value,
                        m.getReturnType() == Boolean.TYPE || m.getReturnType() == Boolean.class);
                } catch (Throwable e) {
                    appendText(acc, label + "." + m.getName() + "()=<error>");
                }
            }

            Class<?> t = type;
            while (t != null) {
                Field[] fields = t.getDeclaredFields();
                for (int i = 0; i < fields.length; i++) {
                    Field f = fields[i];
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!candidateName(f.getName())) continue;
                    try {
                        f.setAccessible(true);
                        Object value = f.get(target);
                        append(acc, label + "." + f.getName(), value,
                            f.getType() == Boolean.TYPE || f.getType() == Boolean.class);
                    } catch (Throwable e) {
                        appendText(acc, label + "." + f.getName() + "=<error>");
                    }
                }
                t = t.getSuperclass();
            }
        }

        private static void append(ProbeAccumulator acc, String name, Object value, boolean booleanCandidate) {
            appendText(acc, name + "=" + String.valueOf(value));
            if (booleanCandidate && Boolean.TRUE.equals(value)) acc.active = true;
        }

        private static void appendText(ProbeAccumulator acc, String text) {
            if (acc.description.length() > 0) acc.description.append(",");
            acc.description.append(text);
        }

        private static boolean candidateName(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(java.util.Locale.US);
            return n.indexOf("emergency") >= 0
                || n.indexOf("estop") >= 0
                || n.indexOf("e_stop") >= 0
                || n.indexOf("notaus") >= 0
                || n.indexOf("stopbutton") >= 0
                || n.indexOf("stop_button") >= 0
                || n.indexOf("panic") >= 0;
        }

        static final class Result {
            final boolean active;
            final String description;
            Result(boolean active, String description) {
                this.active = active;
                this.description = description;
            }
        }

        private static final class ProbeAccumulator {
            boolean active;
            final StringBuilder description = new StringBuilder();
        }
    }
}
