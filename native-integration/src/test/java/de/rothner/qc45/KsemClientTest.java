package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class KsemClientTest {
    @Test
    public void highLowDecodeDoesNotWrapAbove65535Milliamps() {
        // 70,000 mA = 0x00011170. The previous low-word-only path returned
        // 4.464 A instead of 70 A.
        double amps = KsemClient.decodeCurrent(
            0x0001, 0x1170, 0.001d, KsemClient.WordOrder.HIGH_LOW);
        assertEquals(70.0d, amps, 0.0001d);
    }

    @Test
    public void lowHighDecodeUsesConfiguredWordOrder() {
        double amps = KsemClient.decodeCurrent(
            0x1170, 0x0001, 0.001d, KsemClient.WordOrder.LOW_HIGH);
        assertEquals(70.0d, amps, 0.0001d);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidWordOrderIsRejected() {
        KsemClient.WordOrder.parse("AUTO");
    }
}
