package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps the original EVCSD CCS V3 state machine authorized during OCPP
 * RemoteStart sessions.
 *
 * EVCSD builds the CCS V3 START control byte from CentralModule.isLoggedIn().
 * A RemoteStart sets remoteStarted but can leave loggedIn=false, causing the
 * original 500 ms CCS state-machine loop to emit control byte 0x82 instead of
 * the authorized 0x02. This helper mirrors remoteStarted into loggedIn only
 * while a CCS session is selected, and restores the previous false state when
 * the remote session ends.
 */
public final class RemoteStartAuthorizationFix {
    private static final long INTERVAL_MS = 100L;

    private final Class<?> centralClass;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean forcedLoggedIn;

    private RemoteStartAuthorizationFix() throws Exception {
        centralClass = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "qc45-remote-ccs-auth");
        worker.setDaemon(true);
    }

    public static RemoteStartAuthorizationFix start() throws Exception {
        RemoteStartAuthorizationFix fix = new RemoteStartAuthorizationFix();
        fix.worker.start();
        System.out.println("[QC45] RemoteStart CCS authorization fix started interval="
            + INTERVAL_MS + "ms control-authorized=0x02");
        return fix;
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
        try { worker.join(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { restoreIfForced(); } catch (Throwable e) {
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
        boolean remoteStarted = booleanMethod(central, "isRemoteStarted");
        boolean ccsSelected = hasCcsSelected(central);
        boolean loggedIn = booleanMethod(central, "isLoggedIn");

        if (remoteStarted && ccsSelected) {
            if (!loggedIn) {
                setLoggedIn(central, true);
                forcedLoggedIn = true;
                System.out.println("[QC45] RemoteStart CCS AUTH forced loggedIn=false->true"
                    + " remoteStarted=true ccsSelected=true ccsV3Control=0x02");
            }
            return;
        }

        if (forcedLoggedIn) {
            setLoggedIn(central, false);
            forcedLoggedIn = false;
            System.out.println("[QC45] RemoteStart CCS AUTH restored loggedIn=true->false"
                + " remoteStarted=" + remoteStarted + " ccsSelected=" + ccsSelected);
        }
    }

    private void restoreIfForced() throws Exception {
        if (!forcedLoggedIn) return;
        Object central = central();
        setLoggedIn(central, false);
        forcedLoggedIn = false;
    }

    private Object central() throws Exception {
        Object central = centralClass.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");
        return central;
    }

    private boolean hasCcsSelected(Object central) throws Exception {
        Object[] satellites = (Object[]) centralClass.getMethod("getSatellites").invoke(central);
        if (satellites == null) return false;
        for (int i = 0; i < satellites.length; i++) {
            Object sat = satellites[i];
            if (sat == null) continue;
            try {
                Object value = sat.getClass().getMethod("isCCSCharge").invoke(sat);
                if (value instanceof Boolean && ((Boolean)value).booleanValue()) return true;
            } catch (NoSuchMethodException ignored) {}
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
