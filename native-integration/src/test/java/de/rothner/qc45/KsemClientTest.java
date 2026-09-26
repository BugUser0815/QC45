package de.rothner.qc45;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

    @Test
    public void reusesOneTcpConnectionAcrossMeterReads() throws Exception {
        final ServerSocket server = new ServerSocket(
            0, 10, InetAddress.getByName("127.0.0.1"));
        final int[] acceptedConnections = new int[] { 0 };
        final Throwable[] serverFailure = new Throwable[] { null };
        Thread responder = new Thread(new Runnable() {
            public void run() {
                int requests = 0;
                try {
                    while (requests < 6) {
                        Socket connection = server.accept();
                        acceptedConnections[0]++;
                        try {
                            InputStream in = connection.getInputStream();
                            OutputStream out = connection.getOutputStream();
                            while (requests < 6) {
                                byte[] request = new byte[12];
                                try {
                                    readFully(in, request);
                                } catch (EOFException closed) {
                                    break;
                                }
                                int register = u16(request, 8);
                                int milliamps = register == 60 ? 4300
                                    : register == 100 ? 2100 : 1700;
                                byte[] response = new byte[13];
                                response[0] = request[0];
                                response[1] = request[1];
                                response[4] = 0;
                                response[5] = 7;
                                response[6] = request[6];
                                response[7] = 3;
                                response[8] = 4;
                                putU16(response, 9, 0);
                                putU16(response, 11, milliamps);
                                out.write(response);
                                out.flush();
                                requests++;
                            }
                        } finally {
                            connection.close();
                        }
                    }
                } catch (Throwable e) {
                    serverFailure[0] = e;
                } finally {
                    try { server.close(); } catch (Throwable ignored) {}
                }
            }
        }, "KsemClientTest-Responder");
        responder.start();

        KsemClient client = new KsemClient(
            "127.0.0.1", server.getLocalPort(), 71, 2000, 0.001d, "HIGH_LOW");
        try {
            KsemClient.Currents first = client.readCurrents();
            KsemClient.Currents second = client.readCurrents();
            assertEquals(4.3d, first.l1, 0.0001d);
            assertEquals(2.1d, second.l2, 0.0001d);
        } finally {
            client.close();
        }

        responder.join(3000L);
        assertFalse(responder.isAlive());
        if (serverFailure[0] != null) {
            throw new AssertionError("fake KSEM responder failed", serverFailure[0]);
        }
        assertEquals("meter reads must share one TCP connection",
            1, acceptedConnections[0]);
    }

    private static void readFully(InputStream in, byte[] bytes) throws Exception {
        int read = 0;
        while (read < bytes.length) {
            int count = in.read(bytes, read, bytes.length - read);
            if (count < 0) throw new EOFException();
            read += count;
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static void putU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) ((value >>> 8) & 0xff);
        bytes[offset + 1] = (byte) (value & 0xff);
    }
}
