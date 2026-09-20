package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class ReflectionQC45Test {
    @Test
    public void zeroTargetCanNeverAuthorizeCcsControl() {
        assertTrue(!ReflectionQC45.ccsControlAuthorized(0, true, true, true));
    }

    @Test
    public void activeLocalTransactionAuthorizesPositiveTarget() {
        assertTrue(ReflectionQC45.ccsControlAuthorized(5, false, true, false));
    }

    @Test
    public void positiveTargetWithoutAuthorizationRemainsDisabled() {
        assertTrue(!ReflectionQC45.ccsControlAuthorized(5, false, false, false));
    }

    @Test
    public void authoritativeTransactionApiIgnoresStaleIdTag() {
        assertTrue(!ReflectionQC45.sessionEvidence(true, false, 0, true));
    }

    @Test
    public void livePowerStillMarksSessionActiveWithoutTransactionObject() {
        assertTrue(ReflectionQC45.sessionEvidence(true, false, 5, false));
    }

    @Test
    public void legacyFirmwareCanUseIdTagWhenTransactionApiIsAbsent() {
        assertTrue(ReflectionQC45.sessionEvidence(false, false, 0, true));
    }

    @Test
    public void type2SessionUserIsRecognizedByLegacyFallback() {
        boolean userPresent = ReflectionQC45.hasSessionUser(
            new Type2SessionUser("card-authorized"));
        assertTrue(userPresent);
        assertTrue(ReflectionQC45.sessionEvidence(false, false, 0, userPresent));
    }

    @Test
    public void blankType2SessionUserDoesNotStartCharging() {
        assertTrue(!ReflectionQC45.hasSessionUser(new Type2SessionUser("  ")));
    }

    @Test
    public void observedNullTransactionStillOverridesType2SessionUser() {
        assertTrue(!ReflectionQC45.sessionEvidence(true, false, 0,
            ReflectionQC45.hasSessionUser(new Type2SessionUser("old-card"))));
    }

    private static final class Type2SessionUser {
        private final String value;
        Type2SessionUser(String value) { this.value = value; }
        public String getSessionUser() { return value; }
    }

    @Test
    public void remoteMarkerSurvivesPendingStartGrace() {
        assertTrue(ReflectionQC45.shouldKeepRemoteMarker(false, false, 1000L, 2000L));
    }

    @Test
    public void remoteMarkerClearsAfterObservedSessionEnds() {
        assertTrue(!ReflectionQC45.shouldKeepRemoteMarker(false, true, 1000L, 2000L));
    }

    @Test
    public void remoteMarkerExpiresWhenStartNeverBecomesActive() {
        assertTrue(!ReflectionQC45.shouldKeepRemoteMarker(false, false, 1000L, 62001L));
    }
}
