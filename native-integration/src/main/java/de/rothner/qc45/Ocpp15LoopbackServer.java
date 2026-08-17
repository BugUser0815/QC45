package de.rothner.qc45;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal OCPP 1.5 SOAP loopback central system for the legacy EVCSD client.
 *
 * Purpose: keep the old QC45 state machine online while the real backend
 * connection is handled independently by OcppClient (OCPP 1.6 JSON/WSS).
 * Nothing received here is forwarded to ChargePoint.
 */
public final class Ocpp15LoopbackServer {
    private static final String NS = "urn://Ocpp/Cs/2012/06/";

    private final String bindAddress;
    private final int port;
    private final String path;
    private final int heartbeatInterval;
    private final AtomicInteger transactionIds = new AtomicInteger(100000);
    private HttpServer server;

    public Ocpp15LoopbackServer(String bindAddress, int port, String path, int heartbeatInterval) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.path = normalizePath(path);
        this.heartbeatInterval = heartbeatInterval;
    }

    public synchronized void start() throws Exception {
        if (server != null) return;
        InetAddress address = InetAddress.getByName(bindAddress);
        HttpServer s = HttpServer.create(new InetSocketAddress(address, port), 16);
        s.createContext(path, new Handler());
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        server = s;
        System.out.println("[QC45] OCPP15 loopback listening on http://" + bindAddress + ":" + port + path);
    }

    public synchronized void shutdown() {
        HttpServer s = server;
        server = null;
        if (s != null) s.stop(0);
        System.out.println("[QC45] OCPP15 loopback stopped");
    }

    private final class Handler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                String request = readUtf8(exchange.getRequestBody());
                String operation = operation(request);
                String body = responseBody(operation);
                String response = envelope(body);
                byte[] bytes = response.getBytes("UTF-8");

                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/soap+xml; charset=utf-8");
                headers.set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }

                if (operation.length() == 0) {
                    System.err.println("[QC45] OCPP15 loopback: unknown SOAP operation");
                }
            } catch (Throwable e) {
                System.err.println("[QC45] OCPP15 loopback request failed: " + e);
                try { exchange.sendResponseHeaders(500, -1); } catch (Throwable ignored) {}
            } finally {
                try { exchange.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private String responseBody(String op) {
        if ("bootNotification".equals(op)) {
            return tag("bootNotificationResponse",
                tagValue("currentTime", now())
                + tagValue("heartbeatInterval", String.valueOf(heartbeatInterval))
                + tagValue("status", "Accepted"));
        }
        if ("heartbeat".equals(op)) {
            return tag("heartbeatResponse", tagValue("currentTime", now()));
        }
        if ("authorize".equals(op)) {
            return tag("authorizeResponse", idTagInfo());
        }
        if ("startTransaction".equals(op)) {
            int tx = transactionIds.incrementAndGet();
            return tag("startTransactionResponse",
                tagValue("transactionId", String.valueOf(tx)) + idTagInfo());
        }
        if ("stopTransaction".equals(op)) {
            return tag("stopTransactionResponse", idTagInfo());
        }
        if ("statusNotification".equals(op)) {
            return tag("statusNotificationResponse", "");
        }
        if ("meterValues".equals(op)) {
            return tag("meterValuesResponse", "");
        }
        if ("firmwareStatusNotification".equals(op)) {
            return tag("firmwareStatusNotificationResponse", "");
        }
        if ("diagnosticsStatusNotification".equals(op)) {
            return tag("diagnosticsStatusNotificationResponse", "");
        }
        if ("dataTransfer".equals(op)) {
            return tag("dataTransferResponse", tagValue("status", "Accepted"));
        }

        // Unknown request: return an empty response element derived from its operation
        // when possible. This is intentionally lenient for vendor-specific messages.
        if (op.length() > 0) return tag(op + "Response", "");
        return tag("heartbeatResponse", tagValue("currentTime", now()));
    }

    private static String operation(String xml) {
        String[] operations = new String[] {
            "bootNotification", "heartbeat", "authorize", "startTransaction",
            "stopTransaction", "statusNotification", "meterValues",
            "firmwareStatusNotification", "diagnosticsStatusNotification", "dataTransfer"
        };
        for (int i = 0; i < operations.length; i++) {
            String op = operations[i];
            if (containsElement(xml, op)) return op;
        }

        // Best-effort extraction of the first element inside SOAP Body.
        int body = indexOfIgnoreCase(xml, ":Body");
        if (body < 0) body = indexOfIgnoreCase(xml, "<Body");
        if (body >= 0) {
            int gt = xml.indexOf('>', body);
            int lt = gt < 0 ? -1 : xml.indexOf('<', gt + 1);
            if (lt >= 0 && lt + 1 < xml.length()) {
                int start = lt + 1;
                if (xml.charAt(start) == '/') return "";
                int end = start;
                while (end < xml.length()) {
                    char c = xml.charAt(end);
                    if (c == '>' || c == '/' || Character.isWhitespace(c)) break;
                    end++;
                }
                String name = xml.substring(start, end);
                int colon = name.indexOf(':');
                return colon >= 0 ? name.substring(colon + 1) : name;
            }
        }
        return "";
    }

    private static boolean containsElement(String xml, String localName) {
        return xml.indexOf("<" + localName) >= 0
            || xml.indexOf(":" + localName) >= 0;
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase(Locale.US).indexOf(needle.toLowerCase(Locale.US));
    }

    private static String idTagInfo() {
        return "<idTagInfo><status>Accepted</status></idTagInfo>";
    }

    private static String envelope(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:cs=\"" + NS + "\">"
            + "<s:Body>" + body + "</s:Body></s:Envelope>";
    }

    private static String tag(String name, String content) {
        return "<cs:" + name + ">" + content + "</cs:" + name + ">";
    }

    private static String tagValue(String name, String value) {
        return "<" + name + ">" + escape(value) + "</" + name + ">";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String readUtf8(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String normalizePath(String value) {
        String p = value == null || value.trim().length() == 0 ? "/QC45" : value.trim();
        return p.charAt(0) == '/' ? p : "/" + p;
    }
}
