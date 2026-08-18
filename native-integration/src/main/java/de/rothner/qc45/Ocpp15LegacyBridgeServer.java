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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCPP 1.5 SOAP -> OCPP 1.6 JSON bridge matching the original working
 * qc45_bridge.py SOAP behaviour byte-for-byte in structure:
 * - SOAP 1.2 envelope only
 * - no WS-Addressing headers
 * - OCPP namespace as default namespace on the response operation root
 * - child fields unqualified
 * - Content-Type only: application/soap+xml; charset=utf-8
 */
public final class Ocpp15LegacyBridgeServer {
    private static final String OCPP15_NS = "urn://Ocpp/Cs/2012/06/";
    private static final String SOAP_NS = "http://www.w3.org/2003/05/soap-envelope";

    private final String bindAddress;
    private final int port;
    private final String path;
    private final int heartbeatInterval;
    private final int timeoutMs;
    private final OcppBridgeClient upstream;
    private HttpServer server;

    public Ocpp15LegacyBridgeServer(String bindAddress, int port, String path, int heartbeatInterval,
                                    int timeoutMs, OcppBridgeClient upstream) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.path = normalizePath(path);
        this.heartbeatInterval = heartbeatInterval;
        this.timeoutMs = timeoutMs;
        this.upstream = upstream;
    }

    public synchronized void start() throws Exception {
        if (server != null) return;
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 16);
        s.createContext(path, new Handler());
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        server = s;
        System.out.println("[QC45] OCPP15 legacy bridge listening on http://" + bindAddress + ":" + port + path);
    }

    public synchronized void shutdown() {
        HttpServer s = server;
        server = null;
        if (s != null) s.stop(0);
        System.out.println("[QC45] OCPP15 legacy bridge stopped");
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
                if (operation.length() == 0) throw new IllegalArgumentException("Unsupported OCPP15 request");

                System.out.println("[QC45] LEGACY SOAP RX op=" + operation + " Content-Type="
                    + header(exchange, "Content-Type"));
                if ("authorize".equals(operation)) System.out.println("[QC45] LEGACY Authorize SOAP RX=" + request);

                String response = dispatch(operation, request);
                if ("authorize".equals(operation)) System.out.println("[QC45] LEGACY Authorize SOAP TX=" + response);

                byte[] bytes = response.getBytes("UTF-8");
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/soap+xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                try { out.write(bytes); } finally { out.close(); }
            } catch (Throwable e) {
                System.err.println("[QC45] OCPP15 legacy bridge request failed: " + e);
                e.printStackTrace();
                try { exchange.sendResponseHeaders(502, -1); } catch (Throwable ignored) {}
            } finally {
                try { exchange.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private String dispatch(String op, String xml) throws Exception {
        if ("bootNotification".equals(op)) {
            String vendor = first(xml, "chargePointVendor", "chargePointManufacturer", "vendor");
            if (vendor.length() == 0) vendor = "EFACEC";
            String model = first(xml, "chargePointModel", "model");
            if (model.length() == 0) model = "Efapower-EV QC45";
            String json = "{" + q("chargePointVendor", vendor) + "," + q("chargePointModel", model)
                + opt(xml, "chargePointSerialNumber") + opt(xml, "chargeBoxSerialNumber") + opt(xml, "firmwareVersion")
                + opt(xml, "iccid") + opt(xml, "imsi") + opt(xml, "meterType") + opt(xml, "meterSerialNumber") + "}";
            String result = upstream.call("BootNotification", json, timeoutMs);
            return soapEnvelope("<bootNotificationResponse xmlns=\"" + OCPP15_NS + "\">"
                + value("status", fieldString(result, "status", "Rejected"))
                + value("currentTime", fieldString(result, "currentTime", utcNow()))
                + value("heartbeatInterval", String.valueOf(fieldInt(result, "interval", heartbeatInterval)))
                + "</bootNotificationResponse>");
        }
        if ("heartbeat".equals(op)) {
            String result = upstream.call("Heartbeat", "{}", timeoutMs);
            return soapEnvelope("<heartbeatResponse xmlns=\"" + OCPP15_NS + "\">"
                + value("currentTime", fieldString(result, "currentTime", utcNow()))
                + "</heartbeatResponse>");
        }
        if ("authorize".equals(op)) {
            String idTag = elementText(xml, "idTag").trim();
            if (idTag.length() == 0) throw new IllegalArgumentException("Authorize request has no idTag");
            String result = upstream.call("Authorize", "{" + q("idTag", idTag) + "}", timeoutMs);
            return soapEnvelope("<authorizeResponse xmlns=\"" + OCPP15_NS + "\">"
                + idTagInfoXml(result, "Invalid") + "</authorizeResponse>");
        }
        if ("statusNotification".equals(op)) {
            String status = elementText(xml, "status");
            if ("Occupied".equals(status)) status = "Preparing";
            String json = "{" + n("connectorId", intText(xml, "connectorId", 0)) + ","
                + q("status", status.length() == 0 ? "Unavailable" : status) + ","
                + q("errorCode", emptyDefault(elementText(xml, "errorCode"), "NoError"))
                + opt(xml, "info") + opt(xml, "timestamp") + opt(xml, "vendorId") + opt(xml, "vendorErrorCode") + "}";
            upstream.call("StatusNotification", json, timeoutMs);
            return emptyResponse("statusNotificationResponse");
        }
        if ("startTransaction".equals(op)) {
            int connector = intText(xml, "connectorId", 1);
            String idTag = elementText(xml, "idTag").trim();
            if (idTag.length() == 0) throw new IllegalArgumentException("StartTransaction missing idTag");
            String ts = elementText(xml, "timestamp"); if (ts.length() == 0) ts = utcNow();
            String json = "{" + n("connectorId", connector) + "," + q("idTag", idTag) + ","
                + q("timestamp", ts) + "," + n("meterStart", longText(xml, "meterStart", 0))
                + optionalNumber(xml, "reservationId") + "}";
            String result = upstream.call("StartTransaction", json, timeoutMs);
            int tx = fieldInt(result, "transactionId", 0);
            upstream.rememberTransaction(tx, connector);
            return soapEnvelope("<startTransactionResponse xmlns=\"" + OCPP15_NS + "\">"
                + value("transactionId", String.valueOf(tx)) + idTagInfoXml(result, "Accepted")
                + "</startTransactionResponse>");
        }
        if ("meterValues".equals(op)) {
            upstream.call("MeterValues", meterValuesJson(xml), timeoutMs);
            return emptyResponse("meterValuesResponse");
        }
        if ("stopTransaction".equals(op)) {
            String ts = elementText(xml, "timestamp"); if (ts.length() == 0) ts = utcNow();
            String json = "{" + n("transactionId", intText(xml, "transactionId", 0)) + ","
                + n("meterStop", longText(xml, "meterStop", 0)) + "," + q("timestamp", ts);
            String id = elementText(xml, "idTag"); if (id.length() > 0) json += "," + q("idTag", id);
            String reason = elementText(xml, "reason"); if (reason.length() > 0) json += "," + q("reason", reason);
            json += "}";
            String result = upstream.call("StopTransaction", json, timeoutMs);
            if (result.indexOf("idTagInfo") >= 0) {
                return soapEnvelope("<stopTransactionResponse xmlns=\"" + OCPP15_NS + "\">"
                    + idTagInfoXml(result, "Accepted") + "</stopTransactionResponse>");
            }
            return emptyResponse("stopTransactionResponse");
        }
        if ("firmwareStatusNotification".equals(op)) {
            upstream.call("FirmwareStatusNotification", "{" + q("status", emptyDefault(elementText(xml, "status"), "Installed")) + "}", timeoutMs);
            return emptyResponse("firmwareStatusNotificationResponse");
        }
        if ("diagnosticsStatusNotification".equals(op)) {
            upstream.call("DiagnosticsStatusNotification", "{" + q("status", emptyDefault(elementText(xml, "status"), "Idle")) + "}", timeoutMs);
            return emptyResponse("diagnosticsStatusNotificationResponse");
        }
        if ("dataTransfer".equals(op)) {
            String json = "{" + q("vendorId", emptyDefault(elementText(xml, "vendorId"), "EFACEC"));
            String mid = elementText(xml, "messageId"); if (mid.length() > 0) json += "," + q("messageId", mid);
            String data = elementText(xml, "data"); if (data.length() > 0) json += "," + q("data", data);
            json += "}";
            String result = upstream.call("DataTransfer", json, timeoutMs);
            String body = value("status", fieldString(result, "status", "UnknownVendorId"));
            String d = fieldString(result, "data", ""); if (d.length() > 0) body += value("data", d);
            return soapEnvelope("<dataTransferResponse xmlns=\"" + OCPP15_NS + "\">" + body + "</dataTransferResponse>");
        }
        throw new IllegalArgumentException("Unsupported OCPP15 operation: " + op);
    }

    private static String soapEnvelope(String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\">\n"
            + " <soap:Body>\n  " + inner + "\n </soap:Body>\n</soap:Envelope>";
    }

    private static String emptyResponse(String name) {
        return soapEnvelope("<" + name + " xmlns=\"" + OCPP15_NS + "\"></" + name + ">");
    }

    private static String idTagInfoXml(String json, String fallback) {
        String status = nestedIdTagField(json, "status", fallback);
        StringBuilder b = new StringBuilder("<idTagInfo><status>").append(xml(status)).append("</status>");
        String expiry = nestedIdTagField(json, "expiryDate", "");
        if (expiry.length() > 0) b.append("<expiryDate>").append(xml(expiry)).append("</expiryDate>");
        String parent = nestedIdTagField(json, "parentIdTag", "");
        if (parent.length() > 0) b.append("<parentIdTag>").append(xml(parent)).append("</parentIdTag>");
        return b.append("</idTagInfo>").toString();
    }

    private static String nestedIdTagField(String json, String field, String fallback) {
        int p = json.indexOf("\"idTagInfo\"");
        String source = p >= 0 ? json.substring(p) : json;
        return fieldString(source, field, fallback);
    }

    private static String meterValuesJson(String xml) {
        int connector = intText(xml, "connectorId", 0);
        int tx = intText(xml, "transactionId", -1);
        String timestamp = elementText(xml, "timestamp"); if (timestamp.length() == 0) timestamp = utcNow();
        String value = first(xml, "value", "meterValue"); if (value.length() == 0) value = "0";
        String sampled = "{\"value\":\"" + json(value) + "\"";
        String measurand = elementText(xml, "measurand"); if (measurand.length() > 0) sampled += ",\"measurand\":\"" + json(measurand) + "\"";
        String unit = elementText(xml, "unit"); if (unit.length() > 0) sampled += ",\"unit\":\"" + json(unit) + "\"";
        sampled += "}";
        String out = "{\"connectorId\":" + connector;
        if (tx >= 0) out += ",\"transactionId\":" + tx;
        return out + ",\"meterValue\":[{\"timestamp\":\"" + json(timestamp) + "\",\"sampledValue\":[" + sampled + "]}]}";
    }

    private static String operation(String xml) {
        String[] names = {"bootNotification", "heartbeat", "statusNotification", "authorize", "startTransaction", "meterValues", "stopTransaction", "firmwareStatusNotification", "diagnosticsStatusNotification", "dataTransfer"};
        for (int i = 0; i < names.length; i++) if (xml.indexOf("<" + names[i]) >= 0 || xml.indexOf(":" + names[i]) >= 0) return names[i];
        return "";
    }

    private static String elementText(String xml, String name) {
        Pattern p = Pattern.compile("(?is)<(?:[A-Za-z0-9_.-]+:)?" + Pattern.quote(name) + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?" + Pattern.quote(name) + ">");
        Matcher m = p.matcher(xml);
        if (!m.find()) return "";
        return unxml(m.group(1).replaceAll("<[^>]+>", "").trim());
    }

    private static String first(String xml, String... names) { for (int i=0;i<names.length;i++){String v=elementText(xml,names[i]);if(v.length()>0)return v;} return ""; }
    private static int intText(String x,String n,int d){try{String v=elementText(x,n);return v.length()==0?d:Integer.parseInt(v);}catch(Exception e){return d;}}
    private static long longText(String x,String n,long d){try{String v=elementText(x,n);return v.length()==0?d:Long.parseLong(v);}catch(Exception e){return d;}}
    private static String q(String k,String v){return "\""+json(k)+"\":\""+json(v)+"\"";}
    private static String n(String k,long v){return "\""+json(k)+"\":"+v;}
    private static String opt(String x,String n){String v=elementText(x,n);return v.length()==0?"":","+q(n,v);}
    private static String optionalNumber(String x,String n){String v=elementText(x,n);return v.length()==0?"":",\""+json(n)+"\":"+v;}
    private static String emptyDefault(String v,String d){return v==null||v.length()==0?d:v;}
    private static int fieldInt(String j,String f,int d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*(-?\\d+)").matcher(j);return m.find()?Integer.parseInt(m.group(1)):d;}
    private static String fieldString(String j,String f,String d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(j);return m.find()?m.group(1).replace("\\\"","\"").replace("\\\\","\\"):d;}
    private static String value(String n,String v){return "<"+n+">"+xml(v)+"</"+n+">";}
    private static String xml(String v){return v==null?"":v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static String unxml(String v){return v.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&");}
    private static String json(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    private static String readUtf8(InputStream in)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[4096];int n;while((n=in.read(b))>=0)o.write(b,0,n);return new String(o.toByteArray(),"UTF-8");}
    private static String normalizePath(String v){String p=v==null||v.trim().length()==0?"/QC45":v.trim();return p.charAt(0)=='/'?p:"/"+p;}
    private static String header(HttpExchange e,String n){String v=e.getRequestHeaders().getFirst(n);return v==null?"":v;}
    private static String utcNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
}
