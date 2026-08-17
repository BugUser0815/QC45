package de.rothner.qc45;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Small OCPP 1.6 JSON client with an embedded RFC6455 WSS implementation.
 * No third-party runtime dependencies.
 */
public final class OcppClient extends Thread {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ReflectionQC45 station;
    private final URI uri;
    private final String username;
    private final String password;
    private final String chargePointSerial;
    private final String defaultIdTag;

    private final Map<Integer, Integer> transactions = new HashMap<Integer, Integer>();
    private final Map<Integer, String> idTags = new HashMap<Integer, String>();
    private final Map<String, Pending> pending = new HashMap<String, Pending>();
    private final int[] previousPower = new int[] { 0, 0, 0, 0 };
    private final String[] previousStatus = new String[] { "", "", "", "" };

    private volatile boolean running = true;
    private volatile WebSocket ws;
    private volatile long heartbeatMs = 60000L;
    private long lastHeartbeat;
    private long lastMeterValues;

    public OcppClient(ReflectionQC45 station, String url, String username, String password,
                      String chargePointSerial, String defaultIdTag) throws Exception {
        super("qc45-ocpp16");
        this.station = station;
        this.uri = new URI(url);
        this.username = username;
        this.password = password;
        this.chargePointSerial = chargePointSerial;
        this.defaultIdTag = defaultIdTag;
        setDaemon(true);
    }

    public void shutdown() {
        running = false;
        WebSocket current = ws;
        if (current != null) current.close();
    }

    public void run() {
        long retry = 2000L;
        while (running) {
            try {
                ws = new WebSocket(uri, username, password);
                ws.connect();
                System.out.println("[QC45] OCPP connected: " + uri);
                retry = 2000L;

                sendBootNotification();
                sendInitialStatuses();
                lastHeartbeat = System.currentTimeMillis();
                lastMeterValues = 0L;

                while (running && ws.isOpen()) {
                    String message = ws.readText(500);
                    if (message != null) onMessage(message);

                    long now = System.currentTimeMillis();
                    pollStation(now);

                    if (now - lastHeartbeat >= heartbeatMs) {
                        call("Heartbeat", "{}", new Pending("heartbeat", 0));
                        lastHeartbeat = now;
                    }
                }
            } catch (Throwable e) {
                if (running) {
                    System.err.println("[QC45] OCPP connection error: " + e);
                    try { Thread.sleep(retry); } catch (InterruptedException ignored) {}
                    retry = Math.min(30000L, retry * 2L);
                }
            } finally {
                WebSocket current = ws;
                if (current != null) current.close();
                ws = null;
            }
        }
    }

    private void sendBootNotification() throws IOException {
        String payload = "{" +
            "\"chargePointVendor\":\"EFACEC\"," +
            "\"chargePointModel\":\"QC45\"," +
            "\"chargePointSerialNumber\":\"" + json(chargePointSerial) + "\"," +
            "\"firmwareVersion\":\"evcsd-efacec-v3_build_76\"" +
            "}";
        call("BootNotification", payload, new Pending("boot", 0));
    }

    private void sendInitialStatuses() throws Exception {
        for (int connector = 1; connector <= 3; connector++) {
            String status = station.powerKw(connector) > 0 ? "Charging" : "Available";
            previousPower[connector] = station.powerKw(connector);
            previousStatus[connector] = status;
            statusNotification(connector, status);
        }
    }

    private void pollStation(long now) throws Exception {
        for (int connector = 1; connector <= 3; connector++) {
            int power = station.powerKw(connector);
            boolean wasCharging = previousPower[connector] > 0;
            boolean charging = power > 0;

            if (!wasCharging && charging) {
                String idTag = idTags.get(Integer.valueOf(connector));
                if (idTag == null || idTag.length() == 0) idTag = station.idTag(connector);
                if (idTag == null || idTag.length() == 0) idTag = defaultIdTag;
                idTags.put(Integer.valueOf(connector), idTag);

                statusNotification(connector, "Charging");
                startTransaction(connector, idTag);
            } else if (wasCharging && !charging) {
                stopTransaction(connector);
                statusNotification(connector, "Available");
            } else {
                String status = charging ? "Charging" : "Available";
                if (!status.equals(previousStatus[connector])) statusNotification(connector, status);
            }

            previousPower[connector] = power;
        }

        if (now - lastMeterValues >= 10000L) {
            for (int connector = 1; connector <= 3; connector++) {
                if (previousPower[connector] > 0) meterValues(connector);
            }
            lastMeterValues = now;
        }
    }

    private void statusNotification(int connector, String status) throws IOException {
        previousStatus[connector] = status;
        String payload = "{" +
            "\"connectorId\":" + connector + "," +
            "\"errorCode\":\"NoError\"," +
            "\"status\":\"" + status + "\"," +
            "\"timestamp\":\"" + timestamp() + "\"" +
            "}";
        call("StatusNotification", payload, null);
    }

    private void startTransaction(int connector, String idTag) throws Exception {
        long meter = station.energyRaw(connector);
        String payload = "{" +
            "\"connectorId\":" + connector + "," +
            "\"idTag\":\"" + json(idTag) + "\"," +
            "\"meterStart\":" + meter + "," +
            "\"timestamp\":\"" + timestamp() + "\"" +
            "}";
        call("StartTransaction", payload, new Pending("start", connector));
    }

    private void stopTransaction(int connector) throws Exception {
        Integer tx;
        synchronized (transactions) {
            tx = transactions.remove(Integer.valueOf(connector));
        }
        if (tx == null) return;

        long meter = station.energyRaw(connector);
        String idTag = idTags.remove(Integer.valueOf(connector));
        String payload = "{" +
            "\"transactionId\":" + tx.intValue() + "," +
            (idTag == null ? "" : "\"idTag\":\"" + json(idTag) + "\",") +
            "\"meterStop\":" + meter + "," +
            "\"timestamp\":\"" + timestamp() + "\"," +
            "\"reason\":\"Local\"" +
            "}";
        call("StopTransaction", payload, new Pending("stop", connector));
    }

    private void meterValues(int connector) throws Exception {
        Integer tx;
        synchronized (transactions) { tx = transactions.get(Integer.valueOf(connector)); }
        if (tx == null) return;

        int powerW = station.powerKw(connector) * 1000;
        long energy = station.energyRaw(connector);
        String payload = "{" +
            "\"connectorId\":" + connector + "," +
            "\"transactionId\":" + tx.intValue() + "," +
            "\"meterValue\":[{" +
                "\"timestamp\":\"" + timestamp() + "\"," +
                "\"sampledValue\":[" +
                    "{\"value\":\"" + powerW + "\",\"measurand\":\"Power.Active.Import\",\"unit\":\"W\"}," +
                    "{\"value\":\"" + energy + "\",\"measurand\":\"Energy.Active.Import.Register\",\"unit\":\"Wh\"}" +
                "]" +
            "}]}";
        call("MeterValues", payload, null);
    }

    private void onMessage(String message) throws Exception {
        int type = firstInt(message);
        if (type == 2) onCall(message);
        else if (type == 3) onCallResult(message);
        else if (type == 4) onCallError(message);
    }

    private void onCall(String message) throws Exception {
        String uid = arrayString(message, 1);
        String action = arrayString(message, 2);
        String payload = arrayObject(message, 3);

        if ("RemoteStartTransaction".equals(action)) {
            int connector = fieldInt(payload, "connectorId", 0);
            String idTag = fieldString(payload, "idTag", defaultIdTag);
            if (connector < 1 || connector > 3) connector = 2;
            try {
                station.remoteStart(idTag, connector);
                idTags.put(Integer.valueOf(connector), idTag);
                callResult(uid, "{\"status\":\"Accepted\"}");
            } catch (Throwable e) {
                System.err.println("[QC45] RemoteStart failed: " + e);
                callResult(uid, "{\"status\":\"Rejected\"}");
            }
            return;
        }

        if ("RemoteStopTransaction".equals(action)) {
            int transactionId = fieldInt(payload, "transactionId", -1);
            int connector = connectorForTransaction(transactionId);
            if (connector > 0) {
                try {
                    station.remoteStop(connector);
                    callResult(uid, "{\"status\":\"Accepted\"}");
                } catch (Throwable e) {
                    System.err.println("[QC45] RemoteStop failed: " + e);
                    callResult(uid, "{\"status\":\"Rejected\"}");
                }
            } else {
                callResult(uid, "{\"status\":\"Rejected\"}");
            }
            return;
        }

        if ("TriggerMessage".equals(action)) {
            callResult(uid, "{\"status\":\"NotImplemented\"}");
            return;
        }

        callError(uid, "NotSupported", "Unsupported action: " + action);
    }

    private void onCallResult(String message) throws Exception {
        String uid = arrayString(message, 1);
        String payload = arrayObject(message, 2);
        Pending p;
        synchronized (pending) { p = pending.remove(uid); }
        if (p == null) return;

        if ("boot".equals(p.kind)) {
            int interval = fieldInt(payload, "interval", 60);
            heartbeatMs = Math.max(10, interval) * 1000L;
            System.out.println("[QC45] BootNotification: " + fieldString(payload, "status", "unknown") + ", heartbeat=" + interval + "s");
        } else if ("start".equals(p.kind)) {
            int tx = fieldInt(payload, "transactionId", -1);
            String status = fieldString(payload, "status", fieldString(payload, "idTagInfo.status", "Accepted"));
            if (tx >= 0 && !"Blocked".equals(status) && !"Invalid".equals(status)) {
                synchronized (transactions) { transactions.put(Integer.valueOf(p.connector), Integer.valueOf(tx)); }
                System.out.println("[QC45] transaction " + tx + " on connector " + p.connector);
            }
        }
    }

    private void onCallError(String message) {
        String uid = arrayString(message, 1);
        Pending p;
        synchronized (pending) { p = pending.remove(uid); }
        System.err.println("[QC45] OCPP CALLERROR uid=" + uid + " pending=" + (p == null ? "none" : p.kind) + " " + message);
    }

    private int connectorForTransaction(int tx) {
        synchronized (transactions) {
            for (Map.Entry<Integer, Integer> e : transactions.entrySet()) {
                if (e.getValue().intValue() == tx) return e.getKey().intValue();
            }
        }
        return 0;
    }

    private String call(String action, String payload, Pending p) throws IOException {
        String uid = UUID.randomUUID().toString();
        if (p != null) synchronized (pending) { pending.put(uid, p); }
        ws.sendText("[2,\"" + uid + "\",\"" + action + "\"," + payload + "]");
        return uid;
    }

    private void callResult(String uid, String payload) throws IOException {
        ws.sendText("[3,\"" + json(uid) + "\"," + payload + "]");
    }

    private void callError(String uid, String code, String description) throws IOException {
        ws.sendText("[4,\"" + json(uid) + "\",\"" + json(code) + "\",\"" + json(description) + "\",{}]");
    }

    private static String timestamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static String json(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static int firstInt(String json) {
        Matcher m = Pattern.compile("^\\s*\\[\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String arrayString(String json, int index) {
        int pos = skip(json, 0, '[');
        int current = 0;
        while (pos >= 0 && pos < json.length()) {
            pos = skipWhitespace(json, pos);
            if (current == index) {
                if (json.charAt(pos) != '\"') return "";
                return parseString(json, pos)[0];
            }
            pos = skipValue(json, pos);
            pos = skipWhitespace(json, pos);
            if (pos < json.length() && json.charAt(pos) == ',') pos++;
            current++;
        }
        return "";
    }

    private static String arrayObject(String json, int index) {
        int pos = skip(json, 0, '[');
        int current = 0;
        while (pos >= 0 && pos < json.length()) {
            pos = skipWhitespace(json, pos);
            if (current == index) {
                int end = skipValue(json, pos);
                return json.substring(pos, end);
            }
            pos = skipValue(json, pos);
            pos = skipWhitespace(json, pos);
            if (pos < json.length() && json.charAt(pos) == ',') pos++;
            current++;
        }
        return "{}";
    }

    private static String fieldString(String json, String field, String fallback) {
        if (field.indexOf('.') >= 0) field = field.substring(field.lastIndexOf('.') + 1);
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json);
        return m.find() ? unescape(m.group(1)) : fallback;
    }

    private static int fieldInt(String json, String field, int fallback) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r");
    }

    private static int skip(String s, int start, char expected) {
        int p = skipWhitespace(s, start);
        if (p >= s.length() || s.charAt(p) != expected) return -1;
        return p + 1;
    }

    private static int skipWhitespace(String s, int p) {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    private static int skipValue(String s, int p) {
        p = skipWhitespace(s, p);
        if (p >= s.length()) return p;
        char c = s.charAt(p);
        if (c == '\"') return Integer.parseInt(parseString(s, p)[1]);
        if (c == '{' || c == '[') {
            char open = c;
            char close = c == '{' ? '}' : ']';
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int i = p; i < s.length(); i++) {
                char x = s.charAt(i);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (x == '\\') escaped = true;
                    else if (x == '\"') quoted = false;
                } else {
                    if (x == '\"') quoted = true;
                    else if (x == open) depth++;
                    else if (x == close && --depth == 0) return i + 1;
                }
            }
            return s.length();
        }
        int i = p;
        while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != ']' && s.charAt(i) != '}') i++;
        return i;
    }

    private static String[] parseString(String s, int p) {
        StringBuilder b = new StringBuilder();
        boolean escaped = false;
        for (int i = p + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                if (c == 'n') b.append('\n');
                else if (c == 'r') b.append('\r');
                else b.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '\"') {
                return new String[] { b.toString(), String.valueOf(i + 1) };
            } else b.append(c);
        }
        return new String[] { b.toString(), String.valueOf(s.length()) };
    }

    private static final class Pending {
        final String kind;
        final int connector;
        Pending(String kind, int connector) { this.kind = kind; this.connector = connector; }
    }

    /** RFC6455 client, client-to-server frames are masked as required. */
    private static final class WebSocket {
        private static final SecureRandom RANDOM = new SecureRandom();
        private final URI uri;
        private final String username;
        private final String password;
        private SSLSocket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean open;

        WebSocket(URI uri, String username, String password) {
            this.uri = uri;
            this.username = username;
            this.password = password;
        }

        boolean isOpen() { return open; }

        void connect() throws Exception {
            if (!"wss".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Only wss:// is supported");
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
            socket.setSoTimeout(500);

            String[] supported = socket.getSupportedProtocols();
            System.out.println("[QC45] TLS supported protocols: " + join(supported));
            boolean tls12 = false;
            for (int i = 0; i < supported.length; i++) {
                if ("TLSv1.2".equals(supported[i])) {
                    tls12 = true;
                    break;
                }
            }
            if (!tls12) {
                throw new IOException("TLSv1.2 is not supported by this JVM. Supported: " + join(supported));
            }
            socket.setEnabledProtocols(new String[] { "TLSv1.2" });
            System.out.println("[QC45] TLS enabled protocols: " + join(socket.getEnabledProtocols()));

            socket.startHandshake();
            System.out.println("[QC45] TLS session: " + socket.getSession().getProtocol() + " / " + socket.getSession().getCipherSuite());
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            String key = base64(keyBytes);
            String path = uri.getRawPath();
            if (path == null || path.length() == 0) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

            String auth = base64((username + ":" + password).getBytes(UTF8));
            String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: ocpp1.6\r\n" +
                "Authorization: Basic " + auth + "\r\n\r\n";
            out.write(request.getBytes(UTF8));
            out.flush();

            String header = readHttpHeader(in);
            if (header.indexOf(" 101 ") < 0) throw new IOException("WebSocket upgrade failed: " + firstLine(header));
            String accept = headerValue(header, "Sec-WebSocket-Accept");
            String expected = base64(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF8)));
            if (!expected.equals(accept)) throw new IOException("Invalid Sec-WebSocket-Accept");
            open = true;
        }

        synchronized void sendText(String text) throws IOException {
            sendFrame(1, text.getBytes(UTF8));
        }

        String readText(int timeoutMs) throws IOException {
            if (!open) return null;
            socket.setSoTimeout(timeoutMs);
            try {
                while (open) {
                    Frame f = readFrame();
                    if (f == null) return null;
                    if (f.opcode == 1) return new String(f.payload, UTF8);
                    if (f.opcode == 8) { close(); return null; }
                    if (f.opcode == 9) sendFrame(10, f.payload);
                }
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
            return null;
        }

        synchronized void close() {
            if (!open && socket == null) return;
            try { if (open) sendFrame(8, new byte[0]); } catch (Throwable ignored) {}
            open = false;
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            if (!open && opcode != 8) throw new IOException("WebSocket closed");
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            int len = payload.length;
            if (len <= 125) frame.write(0x80 | len);
            else if (len <= 65535) {
                frame.write(0x80 | 126);
                frame.write((len >>> 8) & 0xff);
                frame.write(len & 0xff);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) frame.write((len >>> (8 * i)) & 0xff);
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            frame.write(mask, 0, mask.length);
            for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
            out.write(frame.toByteArray());
            out.flush();
        }

        private Frame readFrame() throws IOException {
            int b0 = in.read();
            if (b0 < 0) throw new EOFException();
            int b1 = in.read();
            if (b1 < 0) throw new EOFException();
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) length = ((long) readByte() << 8) | readByte();
            else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte();
            }
            if (length > 1024 * 1024) throw new IOException("WebSocket frame too large");
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask, 0, 4);
            }
            byte[] payload = new byte[(int) length];
            readFully(in, payload, 0, payload.length);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            return new Frame(opcode, payload);
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException();
            return b & 0xff;
        }

        private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
            int done = 0;
            while (done < len) {
                int n = in.read(b, off + done, len - done);
                if (n < 0) throw new EOFException();
                done += n;
            }
        }

        private static String readHttpHeader(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int state = 0;
            while (b.size() < 65536) {
                int c = in.read();
                if (c < 0) throw new EOFException();
                b.write(c);
                if (state == 0 && c == '\r') state = 1;
                else if (state == 1 && c == '\n') state = 2;
                else if (state == 2 && c == '\r') state = 3;
                else if (state == 3 && c == '\n') return new String(b.toByteArray(), UTF8);
                else state = c == '\r' ? 1 : 0;
            }
            throw new IOException("HTTP header too large");
        }

        private static String headerValue(String header, String name) {
            Pattern p = Pattern.compile("(?im)^" + Pattern.quote(name) + ":\\s*(.+?)\\s*$");
            Matcher m = p.matcher(header);
            return m.find() ? m.group(1).trim() : "";
        }

        private static String firstLine(String header) {
            int p = header.indexOf("\r\n");
            return p < 0 ? header : header.substring(0, p);
        }

        private static String join(String[] values) {
            if (values == null || values.length == 0) return "(none)";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) b.append(',');
                b.append(values[i]);
            }
            return b.toString();
        }

        private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        private static String base64(byte[] data) {
            StringBuilder s = new StringBuilder((data.length + 2) / 3 * 4);
            for (int i = 0; i < data.length; i += 3) {
                int a = data[i] & 0xff;
                int b = i + 1 < data.length ? data[i + 1] & 0xff : 0;
                int c = i + 2 < data.length ? data[i + 2] & 0xff : 0;
                s.append(B64[a >>> 2]);
                s.append(B64[((a & 3) << 4) | (b >>> 4)]);
                s.append(i + 1 < data.length ? B64[((b & 15) << 2) | (c >>> 6)] : '=');
                s.append(i + 2 < data.length ? B64[c & 63] : '=');
            }
            return s.toString();
        }

        private static final class Frame {
            final int opcode;
            final byte[] payload;
            Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
        }
    }
}
