package de.rothner.qc45;

/**
 * Independent reassertion loop for the coordinator's effective limits.
 *
 * This guard starts before optional integrations. If configuration, OCPP or a
 * reflection add-on fails, the startup blocker remains active and this thread
 * keeps all three connectors at 0 kW instead of leaving a one-time zero write
 * vulnerable to a later legacy EVCSD overwrite.
 */
final class ChargingLimitGuard extends Thread {
    private final ChargingLimitCoordinator limits;
    private final int intervalMs;
    private volatile boolean running = true;
    private long lastErrorLog;

    ChargingLimitGuard(ChargingLimitCoordinator limits, int intervalMs) {
        super("QC45-ChargingLimitGuard");
        if (limits == null || intervalMs <= 0) {
            throw new IllegalArgumentException("limits and a positive interval are required");
        }
        this.limits = limits;
        this.intervalMs = intervalMs;
        setDaemon(true);
    }

    public void run() {
        System.out.println("[QC45] charging-limit guard started interval=" + intervalMs + "ms");
        while (running) {
            try {
                limits.reconcile();
            } catch (Throwable e) {
                long now = System.currentTimeMillis();
                if (now - lastErrorLog >= 5000L) {
                    System.err.println("[QC45] charging-limit guard enforcement failed: " + e);
                    lastErrorLog = now;
                }
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }
        System.out.println("[QC45] charging-limit guard stopped");
    }

    void shutdown() {
        try { limits.setBlocked(ChargingLimitCoordinator.SHUTDOWN, true); }
        catch (Throwable e) { System.err.println("[QC45] shutdown zero enforcement failed: " + e); }
        running = false;
        interrupt();
    }
}
