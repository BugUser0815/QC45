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
}
