package de.rothner.qc45;

import java.io.ByteArrayInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FleetAutoChargeTest {
    private static final String ID = "02AABBCCDDEE", TAG = ID;
    private static final String TOKEN = "00000000000000000000000000000001";
    private static final String OTHER = "00000000000000000000000000000002";
    private static final String YES = "{\"idTagInfo\":{\"status\":\"Accepted\"}}";

    private static final class Rig implements FleetAutoCharge.Clock, FleetAutoCharge.Backend, FleetAutoCharge.Station {
        long time = 1000;
        int authorizations, starts, stops;
        boolean available = true, throwsAuthorize;
        String answer = YES, tag, expectedTag = ID;
        Runnable duringAuthorize, duringStart;
        final Object source = new Object();
        final FleetAutoCharge policy;
        Rig() {
            policy = new FleetAutoCharge(this, this, this, 3000L, 15000L);
        }
        public long monotonicMillis() { return time; }
        public long wallMillis() { return 1793000000000L; }
        public String authorize(String value) throws Exception {
            authorizations++; assertEquals(expectedTag, value);
            if (duringAuthorize != null) duringAuthorize.run();
            if (throwsAuthorize) throw new java.io.IOException("offline");
            return answer;
        }
        public boolean available() { return available; }
        public void start(String value) {
            starts++; tag = value; available = false;
            if (duringStart != null) duringStart.run();
        }
        public void stopIfOwned(String value) { assertEquals(TAG, value); stops++; available = true; }
        void observe() { policy.observe(source, TOKEN, ID); }
    }

    @Test public void backendAcceptedStartsNativePathWithVehicleTagOnlyOnce() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        assertEquals(1, rig.authorizations); assertEquals(1, rig.starts); assertEquals(TAG, rig.tag);
        rig.observe(); rig.policy.authorizeCurrent(); assertEquals(1, rig.authorizations);
    }

    @Test public void vehicleIsSentDirectlyToBackendAndRejectedThere() throws Exception {
        Rig rig = new Rig(); rig.expectedTag = "02AABBCCDD01";
        rig.answer = "{\"idTagInfo\":{\"status\":\"Invalid\"}}";
        rig.policy.observe(rig.source, TOKEN, rig.expectedTag); rig.policy.authorizeCurrent();
        assertEquals(1, rig.authorizations); assertEquals(0, rig.starts);
    }

    @Test public void everyBackendDenialAndMalformedReplyKeepsChargeLocked() throws Exception {
        String[] responses = {"{}", "{\"status\":\"Accepted\"}", "{\"idTagInfo\":{\"status\":\"Invalid\"}}",
            "{\"idTagInfo\":{\"status\":\"Blocked\"}}", "{\"idTagInfo\":{\"status\":\"Expired\"}}",
            "{\"idTagInfo\":{\"status\":\"ConcurrentTx\"}}", "{\"idTagInfo\":{\"status\":\"accepted\"}}",
            "{\"idTagInfo\":{\"status\":\"Accepted\"},\"idTagInfo\":{\"status\":\"Invalid\"}}", YES + " junk"};
        for (String answer : responses) {
            Rig rig = new Rig(); rig.answer = answer; rig.observe(); rig.policy.authorizeCurrent();
            assertEquals(answer, 0, rig.starts);
        }
    }

    @Test public void offlineHasNoCachedAuthorizationAndNoAutomaticRetry() throws Exception {
        Rig rig = new Rig(); rig.throwsAuthorize = true; rig.observe();
        try { rig.policy.authorizeCurrent(); fail(); } catch (java.io.IOException expected) {}
        rig.throwsAuthorize = false; rig.policy.authorizeCurrent();
        assertEquals(1, rig.authorizations); assertEquals(0, rig.starts);
    }

    @Test public void staleObservationNeverAuthorizes() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.time += 3001; rig.policy.authorizeCurrent();
        assertEquals(0, rig.authorizations);
    }

    @Test public void plugOutDuringBackendCallCannotStartAnotherVehicle() throws Exception {
        final Rig rig = new Rig(); rig.observe();
        rig.duringAuthorize = new Runnable() { public void run() { rig.policy.end(rig.source, TOKEN); } };
        rig.policy.authorizeCurrent(); assertEquals(0, rig.starts);
    }

    @Test public void replacementFlowDuringBackendCallCannotInheritAuthorization() throws Exception {
        final Rig rig = new Rig(); rig.observe();
        rig.duringAuthorize = new Runnable() { public void run() { rig.policy.observe(rig.source, OTHER, ID); } };
        rig.policy.authorizeCurrent(); assertEquals(0, rig.starts);
    }

    @Test public void observationMustRemainFreshThroughoutBackendCall() throws Exception {
        final Rig rig = new Rig(); rig.observe();
        rig.duringAuthorize = new Runnable() { public void run() { rig.time += 3001; } };
        rig.policy.authorizeCurrent(); assertEquals(0, rig.starts);
    }

    @Test public void busyConnectorCanBecomeReadyWithoutAnotherPlugIn() throws Exception {
        Rig rig = new Rig(); rig.available = false; rig.observe(); rig.policy.authorizeCurrent();
        assertEquals(0, rig.authorizations);
        rig.available = true; rig.policy.authorizeCurrent(); assertEquals(1, rig.starts);
    }

    @Test public void foreignDisconnectDoesNotInvalidateCurrentSource() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.end(new Object(), TOKEN); rig.policy.authorizeCurrent();
        assertEquals(1, rig.starts);
    }

    @Test public void backendStartRejectionStopsOnlyOwnedSession() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.policy.transactionStarted(2, TAG, -1, false); rig.policy.watchdog(); assertEquals(1, rig.stops);
    }

    @Test public void missingBackendTransactionCannotLeaveUnrecordedChargeRunning() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.time += 15000; rig.observe(); rig.policy.watchdog(); assertEquals(1, rig.stops);
    }

    @Test public void disconnectBeforeTransactionConfirmationAbortsPendingStart() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.policy.end(rig.source, TOKEN); rig.policy.watchdog(); assertEquals(1, rig.stops);
    }

    @Test public void confirmedTransactionUsesExistingNativeLifecycleEvenIfObserverFails() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.policy.transactionStarted(2, TAG, 42, true); rig.policy.end(rig.source, TOKEN);
        rig.time += 20000; rig.policy.watchdog(); assertEquals(0, rig.stops);
        rig.policy.transactionEnded(42); rig.available = true;
        rig.policy.observe(rig.source, OTHER, ID); rig.policy.authorizeCurrent(); assertEquals(2, rig.starts);
    }

    @Test public void otherConnectorOrTagCannotConfirmAnUnrecordedCharge() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.policy.transactionStarted(3, TAG, 42, true); rig.policy.transactionStarted(2, "another", 42, true);
        rig.time += 15000; rig.observe(); rig.policy.watchdog(); assertEquals(1, rig.stops);
    }

    @Test public void nativeStartRaceIsStoppedIfObservationEndsInsideStart() throws Exception {
        final Rig rig = new Rig(); rig.observe();
        rig.duringStart = new Runnable() { public void run() { rig.policy.end(rig.source, TOKEN); } };
        rig.policy.authorizeCurrent(); rig.policy.watchdog(); assertEquals(1, rig.stops);
    }

    @Test public void sameTcpFlowCannotRestartAfterBackendStop() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.policy.transactionStarted(2, TAG, 42, true); rig.policy.transactionEnded(42);
        rig.available = true; rig.observe(); rig.policy.authorizeCurrent(); assertEquals(1, rig.starts);
    }

    @Test public void noStartAfterShutdownDuringAuthorization() throws Exception {
        final Rig rig = new Rig(); rig.observe();
        rig.duringAuthorize = new Runnable() { public void run() { rig.policy.shutdown(); } };
        rig.policy.authorizeCurrent(); assertEquals(0, rig.starts);
    }

    @Test public void lateBackendConfirmationCannotResurrectAnEndedObservation() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.policy.end(rig.source, TOKEN);
        rig.policy.transactionStarted(2, TAG, 42, true);
        rig.policy.watchdog(); assertEquals(1, rig.stops);
    }

    @Test public void pendingStartIsStoppedDuringServiceShutdown() throws Exception {
        Rig rig = new Rig(); rig.observe(); rig.policy.authorizeCurrent();
        rig.policy.shutdown(); rig.policy.watchdog(); assertEquals(1, rig.stops);
    }

    @Test public void backendTransactionIdMustBeANonnegativeRootInteger() {
        assertEquals(42, OcppAuthorization.transactionId("{\"transactionId\":42}"));
        assertEquals(-1, OcppAuthorization.transactionId("{\"transactionId\":\"42\"}"));
        assertEquals(-1, OcppAuthorization.transactionId("{\"other\":{\"transactionId\":42}}"));
        assertEquals(-1, OcppAuthorization.transactionId("{\"transactionId\":2147483648}"));
        assertEquals(-1, OcppAuthorization.transactionId("{\"transactionId\":-1}"));
        assertEquals(-1, OcppAuthorization.transactionId("{\"transactionId\":1.5}"));
    }

    @Test public void validAndExpiredExpiryDatesAreChecked() {
        assertTrue(OcppAuthorization.accepted("{\"idTagInfo\":{\"status\":\"Accepted\",\"expiryDate\":\"2030-01-01T00:00:00Z\"}}", 1793000000000L));
        assertFalse(OcppAuthorization.accepted("{\"idTagInfo\":{\"status\":\"Accepted\",\"expiryDate\":\"2020-01-01T00:00:00Z\"}}", 1793000000000L));
        assertFalse(OcppAuthorization.accepted("{\"idTagInfo\":{\"status\":\"Accepted\",\"expiryDate\":null}}", 1793000000000L));
    }

    @Test(expected=IllegalArgumentException.class) public void identityCannotChangeInsideFlow() {
        Rig rig = new Rig(); rig.observe(); rig.policy.observe(rig.source, TOKEN, "020000000002");
    }

    @Test public void anotherVehicleRequiresNoLocalEnrollmentAndUsesItsOwnEvccid() throws Exception {
        Rig rig = new Rig(); rig.expectedTag = "02AABBCCDD01";
        rig.policy.observe(rig.source, TOKEN, rig.expectedTag); rig.policy.authorizeCurrent();
        assertEquals(1, rig.authorizations); assertEquals(rig.expectedTag, rig.tag);
    }

    @Test public void evccidTokenUsesUppercaseHexWithNoPrefixOrSeparators() throws Exception {
        Rig rig = new Rig();
        rig.policy.observe(rig.source, TOKEN, ID.toLowerCase(java.util.Locale.ROOT));
        rig.policy.authorizeCurrent();
        assertEquals("02AABBCCDDEE", rig.tag);
    }

    @Test public void observerAuthenticationMatchesPythonHmacAndLineLimits() throws Exception {
        byte[] key = new byte[32];
        String result = AutoChargeServer.signature(key, "0123456789abcdef");
        assertEquals("f3cc92b56bd4b6e7f929818c526370cff4bfb041b5fea50a192fd4a608f385b4", result);
        assertEquals("IDLE", AutoChargeServer.line(new ByteArrayInputStream("IDLE\n".getBytes("US-ASCII"))));
        try { AutoChargeServer.line(new ByteArrayInputStream("IDLE\r\n".getBytes("US-ASCII"))); fail(); }
        catch (java.io.IOException expected) {}
    }
}
