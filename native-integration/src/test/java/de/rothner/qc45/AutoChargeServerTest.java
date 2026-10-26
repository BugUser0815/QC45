package de.rothner.qc45;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AutoChargeServerTest {
    @Test public void authenticatedObservationRequiresBackendAcceptanceAndCreatesNativeStart() throws Exception {
        final int[] counts = new int[2];
        FleetAutoCharge policy = new FleetAutoCharge(new FleetAutoCharge.Backend() {
            public String authorize(String tag) {
                assertEquals("020000000001", tag);
                synchronized (counts) { counts[0]++; }
                return "{\"idTagInfo\":{\"status\":\"Accepted\"}}";
            }
        }, new FleetAutoCharge.Station() {
            public boolean available() { synchronized (counts) { return counts[1] == 0; } }
            public void start(String tag) { assertEquals("020000000001", tag); synchronized (counts) { counts[1]++; } }
            public void stopIfOwned(String tag) {}
        }, new FleetAutoCharge.Clock() {
            public long monotonicMillis() { return System.nanoTime() / 1000000L; }
            public long wallMillis() { return System.currentTimeMillis(); }
        }, 3000L, 15000L);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket reserve = new ServerSocket(0, 1, loopback);
        int port = reserve.getLocalPort(); reserve.close();
        byte[] key = new byte[32]; key[0] = 42;
        AutoChargeServer receiver = new AutoChargeServer(loopback, port, loopback, key, policy);
        receiver.open();
        try {
            // An unauthenticated peer knows a fleet ID but cannot trigger OCPP.
            Socket invalid = new Socket(loopback, port); invalid.setSoTimeout(2000);
            AutoChargeServer.line(invalid.getInputStream());
            invalid.getOutputStream().write("AUTH invalid\nVEHICLE 00000000000000000000000000000001 020000000001\n".getBytes("US-ASCII"));
            try { assertEquals(-1, invalid.getInputStream().read()); } catch (java.net.SocketException expected) {}
            invalid.close();
            synchronized (counts) { assertEquals(0, counts[0]); assertEquals(0, counts[1]); }

            Socket valid = new Socket(loopback, port); valid.setSoTimeout(2000);
            try {
                InputStream input = valid.getInputStream(); OutputStream output = valid.getOutputStream();
                String challenge = AutoChargeServer.line(input);
                assertTrue(challenge.startsWith("QC45-AUTOCHARGE/1 "));
                output.write(("AUTH " + AutoChargeServer.signature(key, challenge.split(" ")[1]) + "\n").getBytes("US-ASCII"));
                assertEquals("OK", AutoChargeServer.line(input));
                output.write("VEHICLE 00000000000000000000000000000002 020000000001\n".getBytes("US-ASCII"));
                long end = System.nanoTime() + 2000000000L;
                while (System.nanoTime() < end) {
                    synchronized (counts) { if (counts[1] > 0) break; }
                    Thread.sleep(10L);
                }
                synchronized (counts) { assertEquals(1, counts[0]); assertEquals(1, counts[1]); }
                policy.transactionStarted(2, "020000000001", 123, true);
            } finally { valid.close(); }
        } finally { receiver.shutdown(); }
        assertFalse(receiver.isAlive());
    }
}
