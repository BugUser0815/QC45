package de.rothner.qc45;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/** OCPP 1.5 SOAP -> OCPP 1.6 JSON bridge. */
public final class Ocpp15BridgeServer {
    private static final String OCPP15_NS = "urn://Ocpp/Cs/2012/06/";
    private static final String SOAP_NS = "http://www.w3.org/2003/05/soap-envelope";

    private final String bindAddress;
    private final int port;
    private final String path;
    private final int heartbeatInterval;
    private final int timeoutMs;
    private final OcppBridgeClient upstream;
    private final ReflectionQC45 station;
    private final Map<Integer,Integer> activeTransactions = new HashMap<Integer,Integer>();
    private final Map<Integer,String> lastForwardedStatus = new HashMap<Integer,String>();
    private HttpServer server;
    private ExecutorService executor;

    public Ocpp15BridgeServer(String bindAddress, int port, String path, int heartbeatInterval,
                              int timeoutMs, OcppBridgeClient upstream,
                              ReflectionQC45 station) {
        if (bindAddress == null || bindAddress.trim().length() == 0
                || port < 1 || port > 65535 || heartbeatInterval <= 0
                || timeoutMs <= 0 || upstream == null || station == null) {
            throw new IllegalArgumentException("invalid OCPP15 bridge configuration");
        }
        this.bindAddress = bindAddress.trim();
        this.port = port;
        this.path = normalizePath(path);
        this.heartbeatInterval = heartbeatInterval;
        this.timeoutMs = timeoutMs;
        this.upstream = upstream;
        this.station = station;
    }

    public synchronized void start() throws Exception {
        if (server != null) return;
        InetAddress address = InetAddress.getByName(bindAddress);
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException("OCPP15 bridge must bind to a loopback address: " + bindAddress);
        }
        HttpServer s = null;
        ExecutorService workers = null;
        try {
            s = HttpServer.create(new InetSocketAddress(address, port), 16);
            s.createContext(path, new Handler());
            workers = Executors.newFixedThreadPool(4);
            s.setExecutor(workers);
            s.start();
            executor = workers;
            server = s;
        } catch (Exception e) {
            if (s != null) try { s.stop(0); } catch (Throwable ignored) {}
            if (workers != null) workers.shutdownNow();
            throw e;
        }
        System.out.println("[QC45] OCPP15 bridge listening on http://" + bindAddress + ":" + port + path);
    }

    public synchronized void shutdown() {
        HttpServer s = server;
        server = null;
        if (s != null) s.stop(0);
        ExecutorService workers = executor;
        executor = null;
        if (workers != null) workers.shutdownNow();
        System.out.println("[QC45] OCPP15 bridge stopped");
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
                System.out.println("[QC45] OCPP15 SOAP RX op=" + operation + " Content-Type=" + header(exchange, "Content-Type"));
                String response = dispatch(operation, request);
                byte[] bytes = response.getBytes("UTF-8");
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/soap+xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                try { out.write(bytes); } finally { out.close(); }
            } catch (Throwable e) {
                System.err.println("[QC45] OCPP15 bridge request failed: " + e);
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
                + value("currentTime", fieldString(result, "currentTime", utcNow())) + "</heartbeatResponse>");
        }
        if ("authorize".equals(op)) {
            String idTag = elementText(xml, "idTag").trim();
            if (idTag.length() == 0) throw new IllegalArgumentException("Authorize request has no idTag");
            String result = upstream.call("Authorize", "{" + q("idTag", idTag) + "}", timeoutMs);
            return soapEnvelope("<authorizeResponse xmlns=\"" + OCPP15_NS + "\">" + idTagInfoXml(result, "Invalid") + "</authorizeResponse>");
        }
        if ("statusNotification".equals(op)) {
            int connector = intText(xml, "connectorId", 0);
            String incoming = elementText(xml, "status");
            if ("Occupied".equalsIgnoreCase(incoming)) incoming = "Preparing";
            String status = translatedStatus(connector, incoming);
            String json = "{" + n("connectorId", connector) + ","
                + q("status", status.length() == 0 ? "Unavailable" : status) + ","
                + q("errorCode", emptyDefault(elementText(xml, "errorCode"), "NoError"))
                + opt(xml, "info") + opt(xml, "timestamp") + opt(xml, "vendorId") + opt(xml, "vendorErrorCode") + "}";
            upstream.call("StatusNotification", json, timeoutMs);
            rememberStatus(connector, status);
            if (!status.equalsIgnoreCase(incoming)) {
                System.out.println("[QC45] OCPP status corrected connector=" + connector + " " + incoming + " -> " + status);
            }
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
            int tx = fieldInt(result, "transactionId", -1);
            String authorization = nestedIdTagField(result, "status", "Invalid");
            if ("Accepted".equalsIgnoreCase(authorization) && tx >= 0) {
                upstream.rememberTransaction(tx, connector);
                synchronized (activeTransactions) {
                    activeTransactions.put(Integer.valueOf(tx), Integer.valueOf(connector));
                }
                sendDerivedStatusBestEffort(connector);
            }
            return soapEnvelope("<startTransactionResponse xmlns=\"" + OCPP15_NS + "\">"
                + value("transactionId", String.valueOf(tx)) + idTagInfoXml(result, "Accepted")
                + "</startTransactionResponse>");
        }
        if ("meterValues".equals(op)) {
            int connector = intText(xml, "connectorId", 0);
            upstream.call("MeterValues", meterValuesJson(xml), timeoutMs);
            sendDerivedStatusBestEffort(connector);
            return emptyResponse("meterValuesResponse");
        }
        if ("stopTransaction".equals(op)) {
            int tx = intText(xml, "transactionId", 0);
            String ts = elementText(xml, "timestamp"); if (ts.length() == 0) ts = utcNow();
            String json = "{" + n("transactionId", tx) + "," + n("meterStop", longText(xml, "meterStop", 0)) + "," + q("timestamp", ts);
            String id = elementText(xml, "idTag"); if (id.length() > 0) json += "," + q("idTag", id);
            String reason = elementText(xml, "reason"); if (reason.length() > 0) json += "," + q("reason", reason);
            json += "}";
            String result = upstream.call("StopTransaction", json, timeoutMs);
            Integer connector;
            synchronized (activeTransactions) { connector = activeTransactions.remove(Integer.valueOf(tx)); }
            upstream.forgetTransaction(tx);
            if (connector != null && connector.intValue() > 0) sendFinishingBestEffort(connector.intValue());
            if (result.indexOf("idTagInfo") >= 0) {
                return soapEnvelope("<stopTransactionResponse xmlns=\"" + OCPP15_NS + "\">" + idTagInfoXml(result, "Accepted") + "</stopTransactionResponse>");
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

    private String translatedStatus(int connector, String incoming) {
        if (connector <= 0 || !isNormalChargeStatus(incoming)) return incoming;
        try {
            if (station.powerKw(connector) > 0) return "Charging";
            if (!hasActiveTransaction(connector) && !liveTransactionActive(connector)) return incoming;
            if (station.limitKw(connector) <= 0) return "SuspendedEVSE";
            return "SuspendedEV";
        } catch (Throwable e) {
            System.err.println("[QC45] OCPP status derivation failed connector=" + connector + ": " + e);
            return incoming;
        }
    }

    private boolean hasActiveTransaction(int connector) {
        synchronized (activeTransactions) {
            for (Integer value : activeTransactions.values()) {
                if (value != null && value.intValue() == connector) return true;
            }
        }
        return false;
    }

    private boolean liveTransactionActive(int connector) {
        try {
            Method method = ReflectionQC45.class.getDeclaredMethod("satellite", Integer.TYPE);
            method.setAccessible(true);
            Object sat = method.invoke(station, Integer.valueOf(connector));
            Object tx = sat.getClass().getMethod("getActiveTransaction").invoke(sat);
            if (tx != null) return true;
        } catch (Throwable ignored) {}
        try { return station.idTag(connector).length() > 0; }
        catch (Throwable ignored) { return false; }
    }

    private void sendDerivedStatusBestEffort(int connector) {
        if (connector <= 0) return;
        try {
            String status;
            if (station.powerKw(connector) > 0) status = "Charging";
            else if (hasActiveTransaction(connector) || liveTransactionActive(connector)) {
                status = station.limitKw(connector) <= 0 ? "SuspendedEVSE" : "SuspendedEV";
            } else return;
            synchronized (lastForwardedStatus) {
                String previous = lastForwardedStatus.get(Integer.valueOf(connector));
                if (status.equals(previous)) return;
            }
            sendStatus(connector, status);
            System.out.println("[QC45] OCPP derived status connector=" + connector + " status=" + status);
        } catch (Throwable e) {
            System.err.println("[QC45] OCPP derived status failed connector=" + connector + ": " + e);
        }
    }

    private void sendStatus(int connector, String status) throws Exception {
        String json = "{" + n("connectorId", connector) + ","
            + q("status", status) + "," + q("errorCode", "NoError") + "," + q("timestamp", utcNow()) + "}";
        upstream.call("StatusNotification", json, timeoutMs);
        rememberStatus(connector, status);
    }

    private void rememberStatus(int connector, String status) {
        synchronized (lastForwardedStatus) {
            lastForwardedStatus.put(Integer.valueOf(connector), status);
        }
    }

    private static boolean isNormalChargeStatus(String status) {
        return "Available".equalsIgnoreCase(status)
            || "Preparing".equalsIgnoreCase(status)
            || "Charging".equalsIgnoreCase(status)
            || "SuspendedEV".equalsIgnoreCase(status)
            || "SuspendedEVSE".equalsIgnoreCase(status)
            || "Finishing".equalsIgnoreCase(status)
            || status == null || status.length() == 0;
    }

    private void sendFinishingBestEffort(int connector) {
        try { sendStatus(connector, "Finishing"); }
        catch (Throwable e) { System.err.println("[QC45] OCPP Finishing notification failed connector=" + connector + ": " + e); }
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

    static String meterValuesJson(String xml) throws Exception {
        Document document = parseXml(xml);
        Element request = findElement(document.getDocumentElement(), "meterValuesRequest");
        if (request == null) request = findElement(document.getDocumentElement(), "meterValues");
        if (request == null) throw new IllegalArgumentException("MeterValues request element missing");

        int connector = parseInt(childText(request, "connectorId"), 0);
        int tx = parseInt(childText(request, "transactionId"), -1);
        List<Element> groups = directChildren(request, "values", "meterValue");
        groups = expandMeterGroups(groups);
        if (groups.isEmpty()) throw new IllegalArgumentException("MeterValues request has no meter groups");

        StringBuilder out = new StringBuilder("{\"connectorId\":").append(connector);
        if (tx >= 0) out.append(",\"transactionId\":").append(tx);
        out.append(",\"meterValue\":[");

        int writtenGroups = 0;
        for (int i = 0; i < groups.size(); i++) {
            Element group = groups.get(i);
            List<Element> samples = directChildren(group, "values", "sampledValue", "value");
            samples = retainSampleElements(samples);
            if (samples.isEmpty()) continue;
            if (writtenGroups++ > 0) out.append(',');
            String timestamp = childText(group, "timestamp");
            if (timestamp.length() == 0) timestamp = utcNow();
            out.append("{\"timestamp\":\"").append(json(timestamp))
                .append("\",\"sampledValue\":[");

            for (int j = 0; j < samples.size(); j++) {
                if (j > 0) out.append(',');
                Element sample = samples.get(j);
                String value = sampleValue(sample);
                out.append("{\"value\":\"").append(json(value)).append('"');
                appendOptionalJson(out, sample, "context");
                appendOptionalJson(out, sample, "format");
                appendOptionalJson(out, sample, "measurand");
                appendOptionalJson(out, sample, "phase");
                appendOptionalJson(out, sample, "location");
                appendOptionalJson(out, sample, "unit");
                out.append('}');
            }
            out.append("]}");
        }
        if (writtenGroups == 0) throw new IllegalArgumentException("MeterValues request has no sampled values");
        return out.append("]}").toString();
    }

    private static List<Element> expandMeterGroups(List<Element> candidates) {
        List<Element> groups = new ArrayList<Element>();
        for (int i = 0; i < candidates.size(); i++) {
            Element candidate = candidates.get(i);
            if (childText(candidate, "timestamp").length() > 0) {
                groups.add(candidate);
                continue;
            }
            List<Element> nested = directChildren(candidate, "values", "meterValue");
            for (int j = 0; j < nested.size(); j++) {
                if (childText(nested.get(j), "timestamp").length() > 0) groups.add(nested.get(j));
            }
        }
        return groups;
    }

    private static List<Element> retainSampleElements(List<Element> candidates) {
        List<Element> samples = new ArrayList<Element>();
        for (int i = 0; i < candidates.size(); i++) {
            Element candidate = candidates.get(i);
            String local = localName(candidate);
            if ("value".equals(local) && directElementChildren(candidate).isEmpty()) {
                samples.add(candidate);
            } else if (childText(candidate, "value").length() > 0) {
                samples.add(candidate);
            }
        }
        return samples;
    }

    private static String sampleValue(Element sample) {
        if ("value".equals(localName(sample)) && directElementChildren(sample).isEmpty()) {
            String value = sample.getTextContent();
            return value == null ? "0" : value.trim();
        }
        String value = childText(sample, "value");
        return value.length() == 0 ? "0" : value;
    }

    private static void appendOptionalJson(StringBuilder out, Element sample, String name) {
        String value = childText(sample, name);
        if (value.length() > 0) out.append(",\"").append(json(name)).append("\":\"")
            .append(json(value)).append('"');
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e) throws SAXParseException { throw e; }
            public void error(SAXParseException e) throws SAXParseException { throw e; }
            public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static void setFeature(DocumentBuilderFactory factory, String name,
                                   boolean value) throws Exception {
        factory.setFeature(name, value);
    }

    private static Element findElement(Element root, String name) {
        if (name.equals(localName(root))) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element found = findElement((Element)node, name);
            if (found != null) return found;
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String... names) {
        List<Element> result = new ArrayList<Element>();
        List<Element> children = directElementChildren(parent);
        for (int i = 0; i < children.size(); i++) {
            String local = localName(children.get(i));
            for (int j = 0; j < names.length; j++) {
                if (names[j].equals(local)) {
                    result.add(children.get(i));
                    break;
                }
            }
        }
        return result;
    }

    private static List<Element> directElementChildren(Element parent) {
        List<Element> result = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) result.add((Element)node);
        }
        return result;
    }

    private static String childText(Element parent, String name) {
        List<Element> children = directElementChildren(parent);
        for (int i = 0; i < children.size(); i++) {
            Element child = children.get(i);
            if (!name.equals(localName(child))) continue;
            String value = child.getTextContent();
            return value == null ? "" : value.trim();
        }
        return "";
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null) return local;
        String name = element.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static int parseInt(String value, int fallback) {
        try { return value.length() == 0 ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
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

    private static String first(String xml,String... names){for(int i=0;i<names.length;i++){String v=elementText(xml,names[i]);if(v.length()>0)return v;}return"";}
    private static int intText(String x,String n,int d){try{String v=elementText(x,n);return v.length()==0?d:Integer.parseInt(v);}catch(Exception e){return d;}}
    private static long longText(String x,String n,long d){try{String v=elementText(x,n);return v.length()==0?d:Long.parseLong(v);}catch(Exception e){return d;}}
    private static String q(String k,String v){return "\""+json(k)+"\":\""+json(v)+"\"";}
    private static String n(String k,long v){return "\""+json(k)+"\":"+v;}
    private static String opt(String x,String n){String v=elementText(x,n);return v.length()==0?"":","+q(n,v);}
    private static String optionalNumber(String x,String n){String v=elementText(x,n);if(v.length()==0)return"";try{return",\""+json(n)+"\":"+Long.parseLong(v);}catch(NumberFormatException e){return"";}}
    private static String emptyDefault(String v,String d){return v==null||v.length()==0?d:v;}
    private static int fieldInt(String j,String f,int d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*(-?\\d+)").matcher(j);return m.find()?Integer.parseInt(m.group(1)):d;}
    private static String fieldString(String j,String f,String d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(j);return m.find()?m.group(1).replace("\\\"","\"").replace("\\\\","\\"):d;}
    private static String value(String n,String v){return "<"+n+">"+xml(v)+"</"+n+">";}
    private static String xml(String v){return v==null?"":v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static String unxml(String v){return v.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&");}
    private static String json(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    private static String readUtf8(InputStream in)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[4096];int n;while((n=in.read(b))>=0){if(o.size()+n>1048576)throw new IllegalArgumentException("SOAP request exceeds 1 MiB");o.write(b,0,n);}return new String(o.toByteArray(),"UTF-8");}
    private static String normalizePath(String v){String p=v==null||v.trim().length()==0?"/QC45":v.trim();return p.charAt(0)=='/'?p:"/"+p;}
    private static String header(HttpExchange e,String n){String v=e.getRequestHeaders().getFirst(n);return v==null?"":v;}
    private static String utcNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
}