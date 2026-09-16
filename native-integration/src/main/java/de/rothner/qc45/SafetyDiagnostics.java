package de.rothner.qc45;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Read-only diagnostic view of the native integration's safety state.
 *
 * This class deliberately does not participate in control decisions. It only
 * observes the already existing blockers and protection threads so UI/Modbus
 * can explain why charging is paused and what condition will release it.
 */
final class SafetyDiagnostics {
    static final int VERSION = 1;

    static final int STATE_NORMAL = 0;
    static final int STATE_STARTUP = 1;
    static final int STATE_KSEM_RECOVERY = 2;
    static final int STATE_GRID_OVER_LIMIT = 3;
    static final int STATE_GRID_HARD_TRIP = 4;
    static final int STATE_GRID_HARD_TRIP_WAIT_SESSION = 5;
    static final int STATE_LIMIT_MISMATCH = 6;
    static final int STATE_CONFIGURATION = 7;
    static final int STATE_SHUTDOWN = 8;
    static final int STATE_BLOCKED = 9;

    static final int UNIT_NONE = 0;
    static final int UNIT_READS = 1;
    static final int UNIT_SECONDS = 2;

    private static final int HEALTHY_READS_TO_RESUME = 5;

    private SafetyDiagnostics() {}

    static Snapshot capture(ChargingLimitCoordinator limits) {
        return capture(limits, System.currentTimeMillis());
    }

    static Snapshot capture(ChargingLimitCoordinator limits, long now) {
        if (limits == null) return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);

        ChargingLimitCoordinator.Snapshot state = limits.snapshot();
        if (!state.blocked) return new Snapshot(STATE_NORMAL, 0, 0, UNIT_NONE);

        // Highest-severity blockers win. LIMIT_MISMATCH intentionally precedes
        // FAILBACK because both may coexist while a failed ramp-down is being
        // aborted; operators need to see the immediate recovery condition.
        if (state.shutdownBlocked)
            return new Snapshot(STATE_SHUTDOWN, 0, 0, UNIT_NONE);
        if (state.configurationBlocked)
            return new Snapshot(STATE_CONFIGURATION, 0, 0, UNIT_NONE);
        if (state.limitMismatchBlocked)
            return new Snapshot(STATE_LIMIT_MISMATCH, 0, 0, UNIT_NONE);

        ThreadSet threads = findThreads();
        if (state.failbackBlocked) {
            Snapshot failback = failbackSnapshot(threads.failback, now);
            if (failback != null) return failback;
            return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);
        }

        if (state.loadMeterBlocked) {
            int healthy = intField(threads.loadManager, "healthyReads", 0);
            return new Snapshot(STATE_KSEM_RECOVERY,
                clamp(healthy, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        if (state.startupBlocked) {
            int healthy = intField(threads.loadManager, "healthyReads", 0);
            return new Snapshot(STATE_STARTUP,
                clamp(healthy, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);
    }

    private static Snapshot failbackSnapshot(GridFailback failback, long now) {
        if (failback == null) return null;

        if (booleanField(failback, "tripped", false)) {
            long resetDelayMs = longField(failback, "resetDelayMs", 60000L);
            long resetSince = longField(failback, "resetSince", 0L);
            int totalSeconds = clamp((int)Math.max(1L,
                (resetDelayMs + 999L) / 1000L), 1, 65535);
            int elapsedSeconds = resetSince <= 0L ? 0
                : clamp((int)Math.max(0L, (now - resetSince) / 1000L),
                    0, totalSeconds);
            int state = elapsedSeconds >= totalSeconds
                ? STATE_GRID_HARD_TRIP_WAIT_SESSION : STATE_GRID_HARD_TRIP;
            return new Snapshot(state, elapsedSeconds, totalSeconds, UNIT_SECONDS);
        }

        if (booleanField(failback, "meterPaused", false)) {
            int reads = intField(failback, "goodMeterReads", 0);
            return new Snapshot(STATE_KSEM_RECOVERY,
                clamp(reads, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        if (booleanField(failback, "overLimitPaused", false)) {
            int reads = intField(failback, "goodOverLimitReads", 0);
            return new Snapshot(STATE_GRID_OVER_LIMIT,
                clamp(reads, 0, HEALTHY_READS_TO_RESUME),
                HEALTHY_READS_TO_RESUME, UNIT_READS);
        }

        return new Snapshot(STATE_BLOCKED, 0, 0, UNIT_NONE);
    }

    private static ThreadSet findThreads() {
        GridFailback failback = null;
        LoadManager loadManager = null;
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (thread instanceof GridFailback) failback = (GridFailback)thread;
                else if (thread instanceof LoadManager) loadManager = (LoadManager)thread;
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect safety/control execution.
        }
        return new ThreadSet(failback, loadManager);
    }

    private static boolean booleanField(Object owner, String name, boolean fallback) {
        Object value = field(owner, name);
        return value instanceof Boolean ? ((Boolean)value).booleanValue() : fallback;
    }

    private static int intField(Object owner, String name, int fallback) {
        Object value = field(owner, name);
        return value instanceof Number ? ((Number)value).intValue() : fallback;
    }

    private static long longField(Object owner, String name, long fallback) {
        Object value = field(owner, name);
        return value instanceof Number ? ((Number)value).longValue() : fallback;
    }

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Snapshot {
        final int state;
        final int progress;
        final int total;
        final int unit;

        Snapshot(int state, int progress, int total, int unit) {
            this.state = state;
            this.progress = progress;
            this.total = total;
            this.unit = unit;
        }
    }

    private static final class ThreadSet {
        final GridFailback failback;
        final LoadManager loadManager;

        ThreadSet(GridFailback failback, LoadManager loadManager) {
            this.failback = failback;
            this.loadManager = loadManager;
        }
    }
}
