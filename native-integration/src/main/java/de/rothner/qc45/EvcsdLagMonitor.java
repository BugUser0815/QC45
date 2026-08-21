package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executor;

/**
 * Lightweight probe for backlog in EVCSD's internal executor.
 *
 * Persistent severe lag can optionally arm an automatic EVCSD restart. The
 * restart is never executed while a connector has an active transaction or
 * reports charging power. Once armed, the watchdog waits for a stable idle
 * period before starting a replacement EVCSD process and exiting this JVM.
 */
public final class EvcsdLagMonitor extends Thread {
    private final long intervalMs;
    private final long warnMs;
    private final boolean autoRestart;
    private final long restartLagMs;
    private final int restartConsecutive;
    private final long idleStableMs;
    private final String restartCommand;

    private volatile boolean running = true;
    private volatile Executor executor;
    private volatile String executorField = "unknown";
    private volatile boolean restartPending;
    private volatile long lastSevereLagMs;
    private volatile int severeLagCount;
    private long lastNormalLog;
    private long idleSince;

    public EvcsdLagMonitor(long intervalMs, long warnMs,
                           boolean autoRestart, long restartLagMs,
                           int restartConsecutive, long idleStableMs,
                           String restartCommand) {
        super("QC45-EVCSD-LagMonitor");
        setDaemon(true);
        this.intervalMs = Math.max(5000L, intervalMs);
        this.warnMs = Math.max(1L, warnMs);
        this.autoRestart = autoRestart;
        this.restartLagMs = Math.max(this.warnMs, restartLagMs);
        this.restartConsecutive = Math.max(1, restartConsecutive);
        this.idleStableMs = Math.max(5000L, idleStableMs);
        this.restartCommand = restartCommand == null ? "" : restartCommand.trim();
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public void run() {
        try {
            executor = findExecutor();
            if (executor == null) {
                System.err.println("[QC45] EVCSD lag monitor disabled: no CentralModule executor found");
                return;
            }
            System.out.println("[QC45] EVCSD lag monitor started executor=" + executorField
                + " interval=" + intervalMs + "ms warn=" + warnMs + "ms"
                + " autoRestart=" + autoRestart + " restartLag=" + restartLagMs + "ms"
                + " consecutive=" + restartConsecutive + " idleStable=" + idleStableMs + "ms");
        } catch (Throwable e) {
            System.err.println("[QC45] EVCSD lag monitor disabled: " + e);
            return;
        }

        long nextProbeAt = 0L;
        while (running) {
            long now = System.currentTimeMillis();
            if (now >= nextProbeAt) {
                submitProbe();
                nextProbeAt = now + intervalMs;
            }

            if (autoRestart && restartPending) {
                checkIdleAndRestart();
            }

            try {
                long sleepMs = restartPending ? 5000L : Math.min(5000L, Math.max(1000L, nextProbeAt - System.currentTimeMillis()));
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }

        System.out.println("[QC45] EVCSD lag monitor stopped");
    }

    private void submitProbe() {
        try {
            final long queuedAt = System.nanoTime();
            executor.execute(new Runnable() {
                public void run() {
                    long lagMs = (System.nanoTime() - queuedAt) / 1000000L;
                    long now = System.currentTimeMillis();
                    if (lagMs >= warnMs) {
                        System.err.println("[QC45] EVCSD EXECUTOR LAG=" + lagMs + "ms executor=" + executorField);
                    } else if (now - lastNormalLog >= 3600000L) {
                        System.out.println("[QC45] EVCSD executor healthy lag=" + lagMs + "ms executor=" + executorField);
                        lastNormalLog = now;
                    }

                    if (!autoRestart || restartPending) return;
                    if (lagMs >= restartLagMs) {
                        severeLagCount++;
                        lastSevereLagMs = lagMs;
                        System.err.println("[QC45] EVCSD severe lag sample " + severeLagCount + "/"
                            + restartConsecutive + " lag=" + lagMs + "ms");
                        if (severeLagCount >= restartConsecutive) {
                            restartPending = true;
                            idleSince = 0L;
                            System.err.println("[QC45] EVCSD AUTO-RESTART ARMED after persistent lag; waiting for all connectors to become idle");
                        }
                    } else {
                        severeLagCount = 0;
                    }
                }
            });
        } catch (Throwable e) {
            System.err.println("[QC45] EVCSD lag monitor submit failed: " + e);
            try {
                executor = findExecutor();
            } catch (Throwable ignored) {}
        }
    }

    private void checkIdleAndRestart() {
        try {
            if (anyChargingOrTransactionActive()) {
                if (idleSince != 0L) {
                    System.out.println("[QC45] EVCSD AUTO-RESTART idle timer reset: charging/session active");
                }
                idleSince = 0L;
                return;
            }

            long now = System.currentTimeMillis();
            if (idleSince == 0L) {
                idleSince = now;
                System.err.println("[QC45] EVCSD AUTO-RESTART all connectors idle; waiting " + idleStableMs + "ms stable idle");
                return;
            }
            if (now - idleSince < idleStableMs) return;

            performRestart();
        } catch (Throwable e) {
            idleSince = 0L;
            System.err.println("[QC45] EVCSD AUTO-RESTART idle check failed; restart deferred: " + e);
        }
    }

    private boolean anyChargingOrTransactionActive() throws Exception {
        Class<?> centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object cm = centralClass.getMethod("getCurrentModule").invoke(null);
        if (cm == null) return true;

        Object satellitesValue = centralClass.getMethod("getSatellites").invoke(cm);
        if (!(satellitesValue instanceof Object[])) return true;
        Object[] satellites = (Object[]) satellitesValue;
        for (int i = 0; i < satellites.length; i++) {
            Object sat = satellites[i];
            if (sat == null) continue;

            try {
                Object tx = sat.getClass().getMethod("getActiveTransaction").invoke(sat);
                if (tx != null) return true;
            } catch (NoSuchMethodException e) {
                return true;
            }

            try {
                Object p = sat.getClass().getMethod("getCurrentPower").invoke(sat);
                if (p instanceof Number && ((Number)p).intValue() > 0) return true;
            } catch (NoSuchMethodException e) {
                return true;
            }
        }
        return false;
    }

    private void performRestart() throws Exception {
        if (restartCommand.length() == 0) {
            throw new IllegalStateException("restart command is empty");
        }

        System.err.println("[QC45] EVCSD AUTO-RESTART executing after lag=" + lastSevereLagMs
            + "ms and stable idle; command=" + restartCommand);

        // Start the replacement after this JVM has had time to release the serial
        // port, Derby database and Tomcat resources.
        String shell = "sleep 5; " + restartCommand;
        Runtime.getRuntime().exec(new String[] { "/bin/sh", "-c", shell });
        System.err.println("[QC45] EVCSD AUTO-RESTART replacement scheduled; exiting JVM now");
        System.err.flush();
        System.out.flush();
        System.exit(0);
    }

    private Executor findExecutor() throws Exception {
        Class<?> centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object cm = centralClass.getMethod("getCurrentModule").invoke(null);
        if (cm == null) throw new IllegalStateException("CentralModule unavailable");

        Class<?> type = cm.getClass();
        while (type != null) {
            Field[] fields = type.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Executor.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(cm);
                    if (value instanceof Executor) {
                        executorField = type.getSimpleName() + "." + f.getName();
                        return (Executor) value;
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }

        return null;
    }
}
