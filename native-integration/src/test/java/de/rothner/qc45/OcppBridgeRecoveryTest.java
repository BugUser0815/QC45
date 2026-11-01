package de.rothner.qc45;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real TLS/WebSocket regression for the silent-network-loss incident. */
public final class OcppBridgeRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout=20000) public void silentPeerIsReconnectedWithoutReplayingTransaction() throws Exception {
        File keyFile = new File(temporary.getRoot(), "ephemeral-test-server.p12");
        // Generate a new disposable key for each test; no key material is kept
        // in the repository or reused by a deployed station.
        Process keytool = new ProcessBuilder(
            new File(System.getProperty("java.home"), "bin/keytool").getPath(),
            "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", keyFile.getPath(),
            "-storepass", "test-password", "-keypass", "test-password",
            "-dname", "CN=localhost", "-validity", "1", "-noprompt")
            .redirectErrorStream(true).start();
        InputStream output = keytool.getInputStream();
        try { while (output.read() >= 0) {} } finally { output.close(); }
        assertEquals("Ephemeral test certificate generation", 0, keytool.waitFor());
        Backend backend = new Backend(keyFile);
        OcppBridgeClient client = new OcppBridgeClient(new Station(),
            "wss://localhost:" + backend.port() + "/", "test", "test", "", true,
            new File(temporary.getRoot(), "transactions.properties").getPath());
        try {
            client.start();
            awaitConnected(client, backend, 1);
            try {
                client.call("StartTransaction", "{\"connectorId\":2}", 250);
                fail("The first connection deliberately never answers");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("timeout"));
            }
            awaitConnected(client, backend, 2);
            assertEquals("{\"currentTime\":\"2026-11-01T10:30:00Z\"}",
                client.call("Heartbeat", "{}", 2000));
            assertEquals(1, backend.transactions.get());
            java.util.Properties p = new java.util.Properties();
            client.writeHealth(p);
            assertEquals("ready", p.getProperty("ocpp.state"));
            assertEquals("1", p.getProperty("ocpp.responseTimeouts"));
            assertEquals("2", p.getProperty("ocpp.connections"));
            assertNull(backend.failure);
        } finally { client.shutdown(); backend.close(); client.join(3000); }
        assertFalse(client.isAlive());
    }

    private static void awaitConnected(OcppBridgeClient client, Backend backend, int expected) throws Exception {
        long end = System.nanoTime() + 7000000000L;
        while (backend.connections.get() < expected || !client.isConnected()) {
            if (System.nanoTime() >= end) fail("No connection " + expected);
            Thread.sleep(10L);
        }
    }

    private static final class Station implements OcppBridgeClient.Station {
        public void remoteStart(String tag, int connector) {}
        public void remoteStop(int connector) {}
        public boolean sessionActive(int connector) { return false; }
        public int connectorForTransactionId(int id) { return 0; }
    }

    private static final class Backend implements Closeable {
        final SSLServerSocket listener;
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger transactions = new AtomicInteger();
        final java.util.List<Socket> sockets = java.util.Collections.synchronizedList(new java.util.ArrayList<Socket>());
        volatile Throwable failure;
        Backend(File keyFile) throws Exception {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            InputStream in = new FileInputStream(keyFile);
            try { keys.load(in, "test-password".toCharArray()); } finally { in.close(); }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, "test-password".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(km.getKeyManagers(), null, null);
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(
                0, 4, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(new Runnable() {
                public void run() {
                    while (!listener.isClosed()) try {
                        final Socket socket = listener.accept();
                        sockets.add(socket);
                        final int number = connections.incrementAndGet();
                        Thread handler = new Thread(new Runnable() {
                            public void run() {
                                try { serve(socket, number); }
                                catch (Exception e) { if (!listener.isClosed() && !socket.isClosed()
                                    && !(e instanceof EOFException)) failure = e; }
                            }
                        });
                        handler.setDaemon(true); handler.start();
                    } catch (Exception e) { if (!listener.isClosed()) failure = e; }
                }
            });
            acceptor.setDaemon(true); acceptor.start();
        }
        int port() { return listener.getLocalPort(); }
        public void close() throws IOException {
            listener.close();
            synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
        }
        void serve(Socket socket, int number) throws Exception {
            InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            while (!header.toString("UTF-8").endsWith("\r\n\r\n")) {
                int b = in.read(); if (b < 0) throw new EOFException(); header.write(b);
                if (header.size() > 16384) throw new IOException("oversized request");
            }
            String key = null;
            for (String line : header.toString("UTF-8").split("\r\n"))
                if (line.startsWith("Sec-WebSocket-Key:")) key = line.substring(line.indexOf(':')+1).trim();
            // Reuse only the client's Base64 encoder, not its WebSocket parser.
            java.lang.reflect.Method base64 = Class.forName("de.rothner.qc45.OcppBridgeClient$WebSocket")
                .getDeclaredMethod("base64", byte[].class);
            base64.setAccessible(true);
            String accept = (String)base64.invoke(null, (Object)MessageDigest.getInstance("SHA-1")
                .digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\nSec-WebSocket-Protocol: ocpp1.6\r\n\r\n").getBytes("UTF-8"));
            out.flush();
            DataInputStream data = new DataInputStream(in);
            for (;;) {
                int op = data.readUnsignedByte();
                if ((op & 15) == 8) return;
                int length = data.readUnsignedByte() & 127;
                if (length == 126) length = data.readUnsignedShort();
                if (length == 127) throw new IOException("unexpected long frame");
                byte[] mask = new byte[4]; data.readFully(mask);
                byte[] bytes = new byte[length]; data.readFully(bytes);
                for (int i=0; i<length; i++) bytes[i] ^= mask[i%4];
                String request = new String(bytes, "UTF-8");
                if (request.contains("\"StartTransaction\"")) transactions.incrementAndGet();
                if (number == 1) continue;
                String uid = request.split("\"")[1];
                byte[] response = ("[3,\""+uid+"\",{\"currentTime\":\"2026-11-01T10:30:00Z\"}]").getBytes("UTF-8");
                out.write(0x81); out.write(response.length); out.write(response); out.flush();
            }
        }
    }
}
