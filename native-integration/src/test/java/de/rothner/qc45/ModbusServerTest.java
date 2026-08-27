package de.rothner.qc45;

import java.net.InetAddress;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ModbusServerTest {
    @Test
    public void exactAndCidrRulesMatchOnlyTheirNetwork() throws Exception {
        ModbusServer.ClientRule exact = ModbusServer.ClientRule.parse("10.0.0.179");
        assertTrue(exact.matches(InetAddress.getByName("10.0.0.179")));
        assertFalse(exact.matches(InetAddress.getByName("10.0.0.180")));

        ModbusServer.ClientRule network = ModbusServer.ClientRule.parse("10.0.0.0/24");
        assertTrue(network.matches(InetAddress.getByName("10.0.0.44")));
        assertFalse(network.matches(InetAddress.getByName("10.0.1.44")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void hostnamesAreNotAcceptedAsAclRules() {
        ModbusServer.ClientRule.parse("evcc.local");
    }

    @Test(expected = IllegalArgumentException.class)
    public void wildcardAclIsRejected() {
        ModbusServer.ClientRule.parse("*");
    }

    @Test
    public void loadBalancingUiBlockIsVersionedContiguousAndOrdered() throws Exception {
        assertEquals(126, ModbusServer.UI_BALANCING_FIRST_REGISTER);
        assertEquals(20, ModbusServer.UI_BALANCING_REGISTER_COUNT);
        assertEquals(145, ModbusServer.UI_BALANCING_FIRST_REGISTER
            + ModbusServer.UI_BALANCING_REGISTER_COUNT - 1);
        assertEquals(1, ModbusServer.UI_BALANCING_VERSION);
        assertEquals(0, ModbusServer.UI_FLAG_DC_SESSION & ModbusServer.UI_FLAG_AC_SESSION);
        assertEquals(0, ModbusServer.UI_FLAG_BLOCKED & ModbusServer.UI_FLAG_DEMAND_TRANSFER);
        assertEquals(0, ModbusServer.UI_FLAG_CONFIGURATION & ModbusServer.UI_FLAG_STARTUP);
        assertEquals(0, ModbusServer.UI_FLAG_LIMIT_MISMATCH & ModbusServer.UI_FLAG_FAILBACK);
        assertEquals(0, ModbusServer.UI_FLAG_EVCC_DC & ModbusServer.UI_FLAG_EVCC_AC);

        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(
            new ChargingLimitIo() {
                public int limitKw(int connector) { return 0; }
                public void setConnectorLimitKw(int connector, int kw) {}
            }, 5, 50, 5, 43);
        limits.setCcsAvailable(true);
        limits.requestBudgets(50, 43);
        limits.setGridTargets(2, true, 17, 13, true);
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);

        int[] block = ModbusServer.uiBalancingBlock(0x2aa, 2, 17,
            limits.snapshot(), 78, 754L, 70000L, 11, 302L, 80000L);
        assertEquals(20, block.length);
        assertEquals(1, block[0]);
        assertEquals(0x2aa, block[1]);
        assertEquals(2, block[2]);
        assertEquals(17, block[3]);
        assertEquals(50, block[4]);
        assertEquals(17, block[5]);
        assertEquals(50, block[6]);
        assertEquals(17, block[7]);
        assertEquals(78, block[8]);
        assertEquals(70000L, ((long)block[10] << 16) | block[11]);
        assertEquals(11, block[12]);
        assertEquals(43, block[13]);
        assertEquals(13, block[14]);
        assertEquals(43, block[15]);
        assertEquals(13, block[16]);
        assertEquals(80000L, ((long)block[18] << 16) | block[19]);
    }
}
