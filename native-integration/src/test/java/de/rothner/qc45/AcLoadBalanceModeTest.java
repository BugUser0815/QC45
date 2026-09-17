package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class AcLoadBalanceModeTest {
    @Test
    public void enablesPrivateFirmwareFlag() throws Exception {
        DummyConfiguration conf = new DummyConfiguration();
        assertTrue(!AcLoadBalanceMode.isEnabledOn(conf));
        AcLoadBalanceMode.enableOn(conf);
        assertTrue(AcLoadBalanceMode.isEnabledOn(conf));
    }

    private static final class DummyConfiguration {
        @SuppressWarnings("unused")
        private boolean enableACLoadBalance;

        public boolean isEnableACLoadBalance() {
            return enableACLoadBalance;
        }
    }
}
