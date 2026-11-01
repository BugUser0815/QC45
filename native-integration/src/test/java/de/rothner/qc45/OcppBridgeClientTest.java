package de.rothner.qc45;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.Properties;
import java.util.Map;
import java.util.concurrent.*;
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

    @Test public void responseTimeoutClosesTheTransportAndMarksCommunicationDisconnected() throws Exception {
        OcppBridgeClient client = client(new FakeStation());
        Object socket = socket();
        set(socket, "out", new ByteArrayOutputStream()); set(client, "ws", socket);
        Properties before = new Properties(); client.writeHealth(before);
        assertEquals("awaiting-response", before.getProperty("ocpp.state"));
        try { client.call("Heartbeat", "{}", 30); fail("Missing reply must time out"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("timeout")); }
        assertFalse(client.isConnected());
        Properties after = new Properties(); client.writeHealth(after);
        assertEquals("disconnected", after.getProperty("ocpp.state"));
        assertEquals("1", after.getProperty("ocpp.responseTimeouts"));
    }

    @Test public void deadlineCanAbortAWriterHoldingTheWebSocketSendLock() throws Exception {
        final OcppBridgeClient client = client(new FakeStation());
        Object socket = socket();
        final CountDownLatch writing = new CountDownLatch(1), closed = new CountDownLatch(1);
        set(socket, "transport", new Socket() { public void close() { closed.countDown(); } });
        set(socket, "out", new OutputStream() {
            public void write(int value) throws IOException {
                writing.countDown();
                try { if (!closed.await(2, TimeUnit.SECONDS)) throw new IOException("test write stuck"); }
                catch (InterruptedException e) { throw new IOException(e); }
                throw new IOException("socket closed");
            }
        });
        set(client, "ws", socket);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> call = executor.submit(new Callable<String>() {
            public String call() throws Exception { return client.call("Heartbeat", "{}", 30); }
        });
        try {
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            Thread.sleep(40L);
            Method expire = OcppBridgeClient.class.getDeclaredMethod("expireRequests");
            expire.setAccessible(true); expire.invoke(client);
            try { call.get(1, TimeUnit.SECONDS); fail("Writer must fail"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
            assertFalse(client.isConnected());
        } finally { closed.countDown(); executor.shutdownNow(); }
    }

    @Test public void backendCallErrorConfirmsConnectivityWithoutForcingReconnect() throws Exception {
        final OcppBridgeClient client = client(new FakeStation());
        Object socket = socket();
        set(socket, "out", new ByteArrayOutputStream()); set(client, "ws", socket);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> call = executor.submit(new Callable<String>() {
            public String call() throws Exception { return client.call("Authorize", "{}", 2000); }
        });
        try {
            Field field = OcppBridgeClient.class.getDeclaredField("pending"); field.setAccessible(true);
            Map<?,?> pending = (Map<?,?>)field.get(client);
            String uid = null;
            long end = System.nanoTime() + 1000000000L;
            while (uid == null && System.nanoTime() < end) {
                synchronized (pending) { if (!pending.isEmpty()) uid = String.valueOf(pending.keySet().iterator().next()); }
                if (uid == null) Thread.sleep(1L);
            }
            assertNotNull(uid);
            Method receive = OcppBridgeClient.class.getDeclaredMethod("onMessage", String.class);
            receive.setAccessible(true); receive.invoke(client, "[4,\""+uid+"\",\"NotSupported\",\"unsupported\",{}]");
            try { call.get(1, TimeUnit.SECONDS); fail("CALLERROR must propagate"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("CALLERROR")); }
            assertTrue(client.isConnected());
            Properties p = new Properties(); client.writeHealth(p);
            assertEquals("ready", p.getProperty("ocpp.state"));
            set(socket, "lastResponseNanos", System.nanoTime() - 121000000000L);
            p.clear(); client.writeHealth(p);
            assertEquals("unresponsive", p.getProperty("ocpp.state"));
        } finally { client.shutdown(); executor.shutdownNow(); }
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
