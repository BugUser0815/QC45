package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import java.net.Socket;
import java.io.DataInputStream;

public final class SafetyDiagnosticsTest {
    @Test
    public void reportsStartupRecoveryProgressShape() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_STARTUP, status.state);
        assertEquals(0, status.progress);
        assertEquals(5, status.total);
        assertEquals(SafetyDiagnostics.UNIT_READS, status.unit);
    }

    @Test
    public void reportsStickyConfigurationBeforeOtherRecoverableBlocks() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);
        limits.setBlocked(ChargingLimitCoordinator.CONFIGURATION, true);

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_CONFIGURATION, status.state);
    }

    @Test
    public void reportsLimitMismatchRecoveryCondition() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.STARTUP, false);
        limits.setBlocked(ChargingLimitCoordinator.LIMIT_MISMATCH, true);

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_LIMIT_MISMATCH, status.state);
        assertEquals(SafetyDiagnostics.UNIT_NONE, status.unit);
    }

    @Test
    public void reportsShutdownAsHighestPriority() throws Exception {
        FakeIo io = new FakeIo();
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(io, 5, 50, 5, 43);
        limits.initializeNotladen();
        limits.setBlocked(ChargingLimitCoordinator.CONFIGURATION, true);
        limits.setBlocked(ChargingLimitCoordinator.SHUTDOWN, true);

        SafetyDiagnostics.Snapshot status = SafetyDiagnostics.capture(limits, 1000L);
        assertEquals(SafetyDiagnostics.STATE_SHUTDOWN, status.state);
    }

    @Test public void persistentClientDoesNotBlockOtherReadersAndShutdownClosesClients() throws Exception {
        ChargingLimitCoordinator limits = new ChargingLimitCoordinator(new FakeIo(), 5, 50, 5, 43);
        SafetyDiagnostics.DiagnosticModbusServer server = new SafetyDiagnostics.DiagnosticModbusServer(limits, 0);
        Socket first = null, second = null;
        server.start();
        try {
            long end = System.currentTimeMillis() + 3000L;
            while (server.localPort() <= 0 && System.currentTimeMillis() < end) Thread.sleep(5L);
            assertTrue(server.localPort() > 0);
            first = new Socket("127.0.0.1", server.localPort()); first.setSoTimeout(1000);
            assertEquals(3, read(first)[7]);
            second = new Socket("127.0.0.1", server.localPort()); second.setSoTimeout(500);
            assertEquals(3, read(second)[7]);
            assertEquals(3, read(first)[7]);
            server.shutdown(); server.join(2000L);
            assertFalse(server.isAlive());
            assertEquals(-1, first.getInputStream().read());
            assertEquals(-1, second.getInputStream().read());
        } finally {
            if (first != null) first.close(); if (second != null) second.close(); server.shutdown();
        }
    }

    private static byte[] read(Socket socket) throws Exception {
        byte[] request = {0, 1, 0, 0, 0, 6, 1, 3, 0, (byte)186, 0, 5};
        socket.getOutputStream().write(request);
        byte[] reply = new byte[19]; new DataInputStream(socket.getInputStream()).readFully(reply);
        return reply;
    }

    private static final class FakeIo implements ChargingLimitIo {
        final int[] limit = new int[] { 0, 0, 0, 0 };

        public int limitKw(int connector) { return limit[connector]; }
        public void setConnectorLimitKw(int connector, int kw) { limit[connector] = kw; }
        public void preArmConnectorLimitKw(int connector, int kw) { limit[connector] = kw; }
    }
}
