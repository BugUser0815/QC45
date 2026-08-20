package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executor;

/**
 * Lightweight probe for backlog in EVCSD's internal executor.
 *
 * Every intervalMs this thread submits one tiny marker Runnable to the first
 * live Executor/ExecutorService found on CentralModule and measures queue delay.
 * It does not modify charger state and does not poll the satellites.
 */
public final class EvcsdLagMonitor extends Thread {
    private final long intervalMs;
    private final long warnMs;
    private volatile boolean running = true;
    private volatile Executor executor;
    private volatile String executorField = "unknown";
    private long lastNormalLog;

    public EvcsdLagMonitor(long intervalMs, long warnMs) {
        super("QC45-EVCSD-LagMonitor");
        setDaemon(true);
        this.intervalMs = Math.max(5000L, intervalMs);
        this.warnMs = Math.max(1L, warnMs);
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
                + " interval=" + intervalMs + "ms warn=" + warnMs + "ms");
        } catch (Throwable e) {
            System.err.println("[QC45] EVCSD lag monitor disabled: " + e);
            return;
        }

        while (running) {
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
                    }
                });
            } catch (Throwable e) {
                System.err.println("[QC45] EVCSD lag monitor submit failed: " + e);
                try {
                    executor = findExecutor();
                } catch (Throwable ignored) {}
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }

        System.out.println("[QC45] EVCSD lag monitor stopped");
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
