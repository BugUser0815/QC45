package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps the original EVCSD CCS V3 state machine authorized during OCPP
 * RemoteStart sessions.
 *
 * This is intentionally narrow: the loggedIn mirror is active only for a
 * connector-specific remote CCS session on connector 2. A value written by
 * this fix is restored only after every AC/DC session has ended, so parallel
 * charging cannot lose its global EVCSD authorization.
 */
public final class RemoteStartAuthorizationFix {
    private static final long INTERVAL_MS = 100L;

    private final ReflectionQC45 station;
    private final Class<?> centralClass;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean trackingRemoteCcs;
    private volatile boolean wroteLoggedIn;
    private volatile boolean restorePending;

    private RemoteStartAuthorizationFix(ReflectionQC45 station) throws Exception {
        if (station == null) throw new IllegalArgumentException("station is required");
        this.station = station;
        centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "qc45-remote-ccs-auth");
        worker.setDaemon(true);
    }

    public static RemoteStartAuthorizationFix start(ReflectionQC45 station) throws Exception {
        RemoteStartAuthorizationFix fix = new RemoteStartAuthorizationFix(station);
        fix.worker.start();
        System.out.println("[QC45] RemoteStart CCS authorization fix started interval="
            + INTERVAL_MS + "ms connector=2 control-authorized=0x02");
        return fix;
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
        try { worker.join(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { restoreIfSafe(); } catch (Throwable e) {
            System.err.println("[QC45] RemoteStart CCS authorization restore failed: " + e);
        }
        System.out.println("[QC45] RemoteStart CCS authorization fix stopped");
    }

    private void loop() {
        while (running) {
            try {
                applyOnce();
            } catch (Throwable e) {
                System.err.println("[QC45] RemoteStart CCS authorization check failed: " + e);
            }

            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException ignored) {
                if (!running) return;
            }
        }
    }

    private void applyOnce() throws Exception {
        Object central = central();
        boolean loggedIn = booleanMethod(central, "isLoggedIn");
        boolean shouldAuthorize = station.isRemoteSession(2)
            && station.sessionActive(2) && station.isCcsCharge(2);

        if (shouldAuthorize) {
            if (!trackingRemoteCcs) {
                trackingRemoteCcs = true;
                if (restorePending) {
                    wroteLoggedIn = true;
                    restorePending = false;
                } else wroteLoggedIn = false;
            }
            if (!loggedIn) {
                setLoggedIn(central, true);
                wroteLoggedIn = true;
                System.out.println("[QC45] RemoteStart CCS AUTH forced loggedIn=false->true"
                    + " remoteConnector=2 ccsV3Control=0x02");
            }
            return;
        }

        if (trackingRemoteCcs) {
            trackingRemoteCcs = false;
            restorePending = wroteLoggedIn;
            wroteLoggedIn = false;
        }

        if (restorePending && !anySessionActive()) {
            if (loggedIn) setLoggedIn(central, false);
            restorePending = false;
            System.out.println("[QC45] RemoteStart CCS AUTH restored loggedIn=true->false after all sessions ended");
        }
    }

    private void restoreIfSafe() throws Exception {
        if ((!restorePending && !(trackingRemoteCcs && wroteLoggedIn))
                || anySessionActive()) return;
        Object central = central();
        setLoggedIn(central, false);
        restorePending = false;
        wroteLoggedIn = false;
        trackingRemoteCcs = false;
    }

    private Object central() throws Exception {
        Object central = centralClass.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");
        return central;
    }

    private boolean anySessionActive() {
        for (int connector = 1; connector <= 3; connector++) {
            try {
                if (station.sessionActive(connector)) return true;
            } catch (Throwable e) {
                // An observation failure must never make us revoke a global
                // authorization that another connector may still need.
                return true;
            }
        }
        return false;
    }

    private static boolean booleanMethod(Object target, String name) throws Exception {
        Object value = target.getClass().getMethod(name).invoke(target);
        return value instanceof Boolean && ((Boolean)value).booleanValue();
    }

    private void setLoggedIn(Object central, boolean value) throws Exception {
        try {
            Method setter = centralClass.getMethod("setLoggedIn", Boolean.TYPE);
            setter.invoke(central, Boolean.valueOf(value));
            return;
        } catch (NoSuchMethodException ignored) {}

        Field field = findField(central.getClass(), "loggedIn");
        if (field == null) throw new NoSuchFieldException("CentralModule.loggedIn");
        field.setAccessible(true);
        if (field.getType() == Boolean.TYPE) field.setBoolean(central, value);
        else field.set(central, Boolean.valueOf(value));
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }
}
