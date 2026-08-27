package de.rothner.qc45;

import java.net.InetAddress;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
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
}
