package de.rothner.qc45;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import javax.net.ssl.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class OcppBridgeClientTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unknownTransactionDoesNotTargetTheOnlyActiveSession() throws Exception {
        FakeStation station = new FakeStation();
        OcppBridgeClient client = client(station);
        assertEquals(0, client.connectorForTransaction(999));
        assertEquals(2, client.connectorForTransaction(42));
        assertEquals(0, client.connectorForTransaction(-1));
    }

    @Test public void rememberedBackendMappingSurvivesRestartAndIsRemovedWhenSessionEnds() throws Exception {
        FakeStation station = new FakeStation();
        OcppBridgeClient first = client(station);
        first.rememberTransaction(123, 2);
        OcppBridgeClient restarted = client(station);
        assertEquals(2, restarted.connectorForTransaction(123));
        station.active = false;
        assertEquals(0, restarted.connectorForTransaction(123));
        station.active = true;
        assertEquals(0, client(station).connectorForTransaction(123));
    }

    @Test public void idleTimeoutCanBeFollowedByACompleteFrame() throws Exception {
        Object socket = socket(-2, 0x81, 2, 'o', 'k');
        assertNull(read(socket));
        assertEquals("ok", read(socket));
    }

    @Test public void partialHeaderTimeoutRequiresReconnect() throws Exception {
        assertPartialTimeout(socket(0x81, -2, 2, 'o', 'k'));
    }

    @Test public void partialPayloadTimeoutRequiresReconnect() throws Exception {
        assertPartialTimeout(socket(0x81, 5, 'h', -2, 'e', 'l', 'l', 'o'));
    }

    @Test public void idleBetweenCompleteFragmentsRetainsTheMessage() throws Exception {
        Object socket = socket(0x01, 2, 'h', 'e', -2, 0x80, 3, 'l', 'l', 'o');
        assertNull(read(socket));
        assertEquals("hello", read(socket));
    }

    private OcppBridgeClient client(FakeStation station) throws Exception {
        return new OcppBridgeClient(station, "wss://localhost/", "u", "p", "", false,
            new File(temporary.getRoot(), "transactions.properties").getPath());
    }

    private static void assertPartialTimeout(Object socket) throws Exception {
        try { read(socket); fail("Partial frame must not be treated as idle"); }
        catch (IOException expected) {
            assertFalse(expected instanceof SocketTimeoutException);
            assertTrue(expected.getCause() instanceof SocketTimeoutException);
        }
    }

    private static Object socket(int... input) throws Exception {
        Class<?> type = Class.forName("de.rothner.qc45.OcppBridgeClient$WebSocket");
        Constructor<?> c = type.getDeclaredConstructor(URI.class, String.class, String.class, String.class, Boolean.TYPE);
        c.setAccessible(true);
        Object socket = c.newInstance(new URI("wss://localhost/"), "u", "p", "", false);
        set(socket, "open", true); set(socket, "socket", new TestSslSocket());
        set(socket, "in", new ScriptedInput(input));
        return socket;
    }

    private static String read(Object socket) throws Exception {
        Method method = socket.getClass().getDeclaredMethod("readText", Integer.TYPE);
        method.setAccessible(true);
        try { return (String)method.invoke(socket, 1000); }
        catch (InvocationTargetException error) { throw (Exception)error.getCause(); }
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(owner, value);
    }

    private static final class FakeStation implements OcppBridgeClient.Station {
        boolean active = true;
        public void remoteStart(String tag, int connector) {}
        public void remoteStop(int connector) {}
        public boolean sessionActive(int connector) { return active && connector == 2; }
        public int connectorForTransactionId(int tx) { return active && tx == 42 ? 2 : 0; }
    }

    private static final class ScriptedInput extends InputStream {
        final int[] bytes; int position;
        ScriptedInput(int[] bytes) { this.bytes = bytes; }
        public int read() throws IOException {
            if (position == bytes.length) return -1;
            int value = bytes[position++];
            if (value == -2) throw new SocketTimeoutException("packet delay");
            return value;
        }
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int value = read(); if (value < 0) return -1;
            bytes[offset] = (byte)value; return 1;
        }
    }

    private static final class TestSslSocket extends SSLSocket {
        public void setSoTimeout(int timeout) {}
        public String[] getSupportedCipherSuites() { return new String[0]; }
        public String[] getEnabledCipherSuites() { return new String[0]; }
        public void setEnabledCipherSuites(String[] suites) {}
        public String[] getSupportedProtocols() { return new String[0]; }
        public String[] getEnabledProtocols() { return new String[0]; }
        public void setEnabledProtocols(String[] protocols) {}
        public SSLSession getSession() { return null; }
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        public void startHandshake() {}
        public void setUseClientMode(boolean mode) {}
        public boolean getUseClientMode() { return true; }
        public void setNeedClientAuth(boolean value) {}
        public boolean getNeedClientAuth() { return false; }
        public void setWantClientAuth(boolean value) {}
        public boolean getWantClientAuth() { return false; }
        public void setEnableSessionCreation(boolean value) {}
        public boolean getEnableSessionCreation() { return true; }
    }
}
