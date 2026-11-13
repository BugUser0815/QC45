package de.rothner.qc45;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Replays native SOAP ordering from the Sprinter session on 13 November. */
public final class Ocpp15StopStatusTest {
    @Test public void availableBeforeStopIsRestoredAfterBackendAcknowledgesStop() throws Exception {
        for (int connector = 1; connector <= 3; connector++) {
            Fixture f = new Fixture();
            f.start(connector);
            f.station.active = false;
            f.status(connector, "Available");
            assertTrue(f.backend.lastStatus().contains("Suspended"));
            f.stop(77);
            assertStatus(f, connector, "Available");
        }
    }

    @Test public void availableDuringStopCannotBeOverwrittenByFinishing() throws Exception {
        final Fixture f = new Fixture();
        f.start(2);
        f.station.active = false;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<String> stop = f.stopInBackground(worker);
        try {
            f.status(2, "Available");
            assertStatus(f, 2, "Available");
            f.backend.releaseStop.countDown();
            stop.get(2, TimeUnit.SECONDS);
            assertStatus(f, 2, "Available");
        } finally { f.backend.releaseStop.countDown(); worker.shutdownNow(); }
    }

    @Test public void availableAfterStopReleasesFinishing() throws Exception {
        Fixture f = new Fixture();
        f.start(2);
        f.station.active = false;
        f.stop(77);
        assertStatus(f, 2, "Finishing");
        f.status(2, "Available");
        assertStatus(f, 2, "Available");
    }

    @Test public void stopDoesNotInventAvailableWithoutANativeRelease() throws Exception {
        Fixture f = new Fixture();
        f.start(2);
        f.station.active = false;
        f.status(2, "Finishing");
        f.stop(77);
        assertStatus(f, 2, "Finishing");
    }

    @Test public void faultArrivingDuringStopKeepsItsErrorDetails() throws Exception {
        final Fixture f = new Fixture();
        f.start(2);
        f.station.active = false;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<String> stop = f.stopInBackground(worker);
        try {
            f.dispatch("statusNotification", "<statusNotificationRequest>"
                + "<connectorId>2</connectorId><status>Faulted</status>"
                + "<errorCode>OtherError</errorCode><vendorErrorCode>42</vendorErrorCode>"
                + "</statusNotificationRequest>");
            String fault = f.backend.lastStatus();
            f.backend.releaseStop.countDown();
            stop.get(2, TimeUnit.SECONDS);
            assertEquals(fault, f.backend.lastStatus());
            assertTrue(fault.contains("\"vendorErrorCode\":\"42\""));
        } finally { f.backend.releaseStop.countDown(); worker.shutdownNow(); }
    }

    @Test public void oldStopCannotOverwriteANewerTransaction() throws Exception {
        final Fixture f = new Fixture();
        f.start(2);
        f.station.active = false;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<String> stop = f.stopInBackground(worker);
        try {
            f.station.power = 12;
            f.start(2);
            assertStatus(f, 2, "Charging");
            f.backend.releaseStop.countDown();
            stop.get(2, TimeUnit.SECONDS);
            assertStatus(f, 2, "Charging");
        } finally { f.backend.releaseStop.countDown(); worker.shutdownNow(); }
    }

    @Test public void previousSessionsAvailableIsNotReusedForANewSession() throws Exception {
        Fixture f = new Fixture();
        f.start(2);
        f.station.active = false;
        f.status(2, "Available");
        f.stop(77);
        f.start(2);
        f.station.active = false;
        f.stop(78);
        assertStatus(f, 2, "Finishing");
    }

    private static void assertStatus(Fixture f, int connector, String status) {
        String last = f.backend.lastStatus();
        assertTrue(last, last.contains("\"connectorId\":" + connector));
        assertTrue(last, last.contains("\"status\":\"" + status + "\""));
    }

    private static final class Fixture {
        final FakeStation station = new FakeStation();
        final Backend backend = new Backend();
        final Ocpp15BridgeServer bridge = new Ocpp15BridgeServer("127.0.0.1", 9000,
            "/QC45", 60, 1000, backend, station,
            new ChargingLimitCoordinator(station, 5, 35, 5, 43));

        void start(int connector) throws Exception {
            station.active = true;
            dispatch("startTransaction", "<startTransactionRequest><connectorId>"
                + connector + "</connectorId><idTag>test</idTag><meterStart>0</meterStart>"
                + "</startTransactionRequest>");
        }

        void status(int connector, String status) throws Exception {
            dispatch("statusNotification", "<statusNotificationRequest><connectorId>"
                + connector + "</connectorId><status>" + status + "</status>"
                + "<errorCode>NoError</errorCode></statusNotificationRequest>");
        }

        String stop(int tx) throws Exception {
            return dispatch("stopTransaction", "<stopTransactionRequest><transactionId>"
                + tx + "</transactionId><meterStop>14360</meterStop></stopTransactionRequest>");
        }

        Future<String> stopInBackground(ExecutorService worker) throws Exception {
            backend.blockStop = true;
            Future<String> stop = worker.submit(new Callable<String>() {
                public String call() throws Exception { return stop(77); }
            });
            assertTrue("StopTransaction must reach the backend",
                backend.stopEntered.await(2, TimeUnit.SECONDS));
            return stop;
        }

        String dispatch(String operation, String xml) throws Exception {
            Method method = Ocpp15BridgeServer.class.getDeclaredMethod("dispatch", String.class, String.class);
            method.setAccessible(true);
            try { return (String) method.invoke(bridge, operation, xml); }
            catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
        }
    }

    private static final class FakeStation implements Ocpp15BridgeServer.Station, ChargingLimitIo {
        volatile boolean active;
        volatile int power;
        public int powerKw(int connector) { return power; }
        public boolean sessionActive(int connector) { return active; }
        public int limitKw(int connector) { return 5; }
        public void setConnectorLimitKw(int connector, int kw) {}
        public void preArmConnectorLimitKw(int connector, int kw) {}
    }

    private static final class Backend implements Ocpp15BridgeServer.Upstream {
        final List<String> statuses = new ArrayList<String>();
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final CountDownLatch releaseStop = new CountDownLatch(1);
        volatile boolean blockStop;
        int nextTx = 77;

        public String call(String action, String payload, int timeoutMs) throws Exception {
            if ("StartTransaction".equals(action))
                return "{\"transactionId\":" + nextTx++ + ",\"idTagInfo\":{\"status\":\"Accepted\"}}";
            if ("StopTransaction".equals(action) && blockStop) {
                stopEntered.countDown();
                if (!releaseStop.await(5, TimeUnit.SECONDS)) throw new AssertionError("test stop blocked");
            }
            if ("StatusNotification".equals(action)) synchronized (statuses) { statuses.add(payload); }
            return "{}";
        }
        public void rememberTransaction(int tx, int connector) {}
        public void forgetTransaction(int tx) {}
        String lastStatus() {
            synchronized (statuses) { return statuses.get(statuses.size() - 1); }
        }
    }
}
