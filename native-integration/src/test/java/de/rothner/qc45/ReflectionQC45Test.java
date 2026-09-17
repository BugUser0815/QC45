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
