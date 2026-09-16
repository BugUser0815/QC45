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
    public void hardStopRejectsRemoteStartBeforeCallingNativeStart() throws Exception {
        ReflectionQC45 station = new ReflectionQC45();
        station.setHardStopRequired(true);
        try {
            station.remoteStart("test", 2);
            throw new AssertionError("remote start accepted while latched");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("safety stop"));
        }
    }

    @Test
    public void hardStopDoesNotAuthorizeFiveKwEvenWithActiveTransaction() throws Exception {
        pt.efacec.es.mobie.agent.statemachines.CentralModule.Satellite sat =
            pt.efacec.es.mobie.agent.statemachines.CentralModule.INSTANCE.satellite;
        sat.transaction = new Object(); sat.power = 5;
        ReflectionQC45 station = new ReflectionQC45();
        station.refreshQuickChargeCurrentForPower(2, 5);
        assertTrue(sat.authorized);
        station.setHardStopRequired(true);
        station.refreshQuickChargeCurrentForPower(2, 5);
        assertTrue(!sat.authorized);
        sat.transaction = null; sat.power = 0;
    }

    @Test
    public void cachedRfidAloneDoesNotPreventIdleRecovery() throws Exception {
        pt.efacec.es.mobie.agent.statemachines.CentralModule.Satellite sat =
            pt.efacec.es.mobie.agent.statemachines.CentralModule.INSTANCE.satellite;
        sat.transaction = null; sat.power = 0;
        assertTrue(!new ReflectionQC45().sessionActive(2));
        sat.power = 5;
        assertTrue(new ReflectionQC45().sessionActive(2));
        sat.power = 0;
    }
    @Test
    public void positiveWirePowerPreventsFalseIdleDespiteZeroNativeCache() throws Exception {
        pt.efacec.es.mobie.agent.statemachines.CentralModule.Satellite sat =
            pt.efacec.es.mobie.agent.statemachines.CentralModule.INSTANCE.satellite;
        sat.transaction = null; sat.power = 0;
        CcsRawTracerV2.shutdown();
        byte[] frame = {0x63, 0, 50, 0, 0, 0, 0, 0, 0, (byte)0x90, 1, 13};
        CcsRawTracerV2.observeLiveRx(frame, 0, frame.length);
        ReflectionQC45 station = new ReflectionQC45();
        assertTrue(station.powerKw(2) == 5 && station.sessionActive(2));
        sat.power = 26;
        assertTrue(station.powerKw(2) == 26);
        sat.power = 0;
        CcsRawTracerV2.shutdown();
    }
}
