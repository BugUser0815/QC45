package de.rothner.qc45;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated observations from one EVAcharge PLC interface; never an OCPP replacement. */
final class AutoChargeServer extends Thread {
    private final InetAddress bind, peer;
    private final int port;
    private final byte[] key;
    final FleetAutoCharge policy;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile ServerSocket listener;
    private volatile Socket client;

    static AutoChargeServer configured(Properties properties, final ReflectionQC45 station,
                                       final OcppBridgeClient backend) throws Exception {
        String bind = required(properties, "autocharge.bindAddress");
        String peer = required(properties, "autocharge.allowedPeer");
        int port = Integer.parseInt(properties.getProperty("autocharge.port", "15119"));
        int timeout = Integer.parseInt(properties.getProperty("autocharge.authorizeTimeoutMs", "10000"));
        if (timeout < 1000 || timeout > 10000) throw new IllegalArgumentException("AutoCharge Authorize timeout must be 1000..10000 ms");
        final int authorizeTimeout = timeout;
        byte[] key = readKey(new File(required(properties, "autocharge.keyFile")));
        FleetAutoCharge policy = new FleetAutoCharge(new FleetAutoCharge.Backend() {
            public String authorize(String idTag) throws Exception {
                return backend.call("Authorize", "{\"idTag\":\"" + idTag + "\"}", authorizeTimeout);
            }
        }, new FleetAutoCharge.Station() {
            public boolean available() throws Exception {
                return !station.sessionActive(1) && !station.isRemoteSession(1)
                    && !station.sessionActive(2) && !station.isRemoteSession(2)
                    && station.idTag(2).length() == 0 && station.isCcsCharge(2)
                    && !station.emergencyStopPressed();
            }
            public void start(String idTag) throws Exception { station.remoteStart(idTag, 2); }
            public void stopIfOwned(String idTag) throws Exception {
                station.stopAutoChargeIfOwned(idTag);
            }
        }, new FleetAutoCharge.Clock() {
            public long monotonicMillis() { return System.nanoTime() / 1000000L; }
            public long wallMillis() { return System.currentTimeMillis(); }
        }, 3000L, 15000L);
        return new AutoChargeServer(InetAddress.getByName(bind), port,
            InetAddress.getByName(peer), key, policy);
    }

    AutoChargeServer(InetAddress bind, int port, InetAddress peer, byte[] key, FleetAutoCharge policy) {
        super("qc45-autocharge-receiver");
        if (bind == null || peer == null || peer.isAnyLocalAddress() || peer.isMulticastAddress()
                || port < 1 || port > 65535 || key == null || key.length != 32 || policy == null) {
            throw new IllegalArgumentException("invalid AutoCharge receiver configuration");
        }
        this.bind = bind; this.peer = peer; this.port = port; this.key = key.clone(); this.policy = policy;
        setDaemon(true);
        worker = new Thread(new Runnable() {
            public void run() {
                try {
                    while (running) {
                        try { AutoChargeServer.this.policy.watchdog(); AutoChargeServer.this.policy.authorizeCurrent(); }
                        catch (Exception e) { if (running) System.err.println("[QC45] AutoCharge automatic action failed: " + e); }
                        try { Thread.sleep(100L); } catch (InterruptedException ignored) {}
                    }
                } finally {
                    AutoChargeServer.this.policy.shutdown();
                    try { AutoChargeServer.this.policy.watchdog(); }
                    catch (Exception e) { System.err.println("[QC45] AutoCharge pending-start cleanup failed: " + e); }
                }
            }
        }, "qc45-autocharge-authorization");
        worker.setDaemon(true);
    }

    synchronized void open() throws Exception {
        ServerSocket socket = new ServerSocket();
        try { socket.bind(new InetSocketAddress(bind, port), 1); }
        catch (Exception e) { socket.close(); throw e; }
        listener = socket;
        worker.start();
        start();
        System.out.println("[QC45] AutoCharge fleet receiver started; OCPP authorization and native transaction reporting required");
    }

    public void run() {
        while (running) {
            Object source = new Object();
            Socket socket = null;
            try {
                socket = listener.accept();
                client = socket;
                if (!peer.equals(socket.getInetAddress())) throw new java.io.IOException("unexpected observation peer");
                socket.setSoTimeout(4000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] nonce = new byte[32];
                new SecureRandom().nextBytes(nonce);
                String challenge = hex(nonce);
                write(output, "QC45-AUTOCHARGE/1 " + challenge);
                String expected = "AUTH " + signature(key, challenge);
                if (!MessageDigest.isEqual(expected.getBytes("US-ASCII"), line(input).getBytes("US-ASCII"))) {
                    throw new java.io.IOException("observation authentication failed");
                }
                write(output, "OK");
                while (running) {
                    String[] fields = line(input).split(" ", -1);
                    if (fields.length == 1 && "IDLE".equals(fields[0])) policy.end(source, null);
                    else if (fields.length == 2 && "END".equals(fields[0])) policy.end(source, fields[1]);
                    else if (fields.length == 3 && "VEHICLE".equals(fields[0])) policy.observe(source, fields[1], fields[2]);
                    else throw new java.io.IOException("invalid observation message");
                }
            } catch (Exception e) {
                if (running) System.err.println("[QC45] AutoCharge observation link closed: " + e);
            } finally {
                policy.end(source, null);
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                client = null;
            }
        }
    }

    void shutdown() {
        running = false;
        policy.shutdown();
        interrupt(); worker.interrupt();
        try { if (listener != null) listener.close(); } catch (Exception ignored) {}
        try { if (client != null) client.close(); } catch (Exception ignored) {}
        try { join(1000L); worker.join(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    boolean healthy() { return running && isAlive() && worker.isAlive(); }

    static String signature(byte[] key, String nonce) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return hex(mac.doFinal(("QC45-AUTOCHARGE/1\n" + nonce).getBytes("US-ASCII")));
    }
    static String line(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') return new String(bytes.toByteArray(), "US-ASCII");
            if (value < 32 || value > 126 || bytes.size() >= 160) throw new java.io.IOException("invalid observation line");
            bytes.write(value);
        }
        throw new java.io.EOFException("observation link ended");
    }
    private static void write(OutputStream out, String value) throws Exception { out.write((value + "\n").getBytes("US-ASCII")); out.flush(); }
    static byte[] readKey(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            int value;
            while ((value = in.read()) >= 0) { if (bytes.size() >= 66) throw new IllegalArgumentException("AutoCharge key size"); bytes.write(value); }
        } finally { in.close(); }
        String text = new String(bytes.toByteArray(), "US-ASCII").trim();
        if (!text.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("AutoCharge key file must contain 64 hex characters");
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte)Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return key;
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name, "").trim();
        if (value.length() == 0) throw new IllegalArgumentException("Missing property: " + name);
        return value;
    }
}
