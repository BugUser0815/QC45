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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OCPP 1.5 SOAP -> OCPP 1.6 JSON bridge for the legacy EVCSD client. */
public final class Ocpp15LoopbackServer {
    private static final String NS = "urn://Ocpp/Cs/2012/06/";
    private static final String WSA = "http://www.w3.org/2005/08/addressing";

    private final String bindAddress;
    private final int port;
    private final String path;
    private final int heartbeatInterval;
    private final int timeoutMs;
    private final OcppBridgeClient backend;
    private HttpServer server;

    public Ocpp15LoopbackServer(String bindAddress, int port, String path, int heartbeatInterval,
                                int timeoutMs, OcppBridgeClient backend) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.path = normalizePath(path);
        this.heartbeatInterval = heartbeatInterval;
        this.timeoutMs = timeoutMs;
        this.backend = backend;
    }

    public synchronized void start() throws Exception {
        if (server != null) return;
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 16);
        s.createContext(path, new Handler());
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        server = s;
        System.out.println("[QC45] OCPP15->16 bridge listening on http://" + bindAddress + ":" + port + path);
    }

    public synchronized void shutdown() {
        HttpServer s = server; server = null;
        if (s != null) s.stop(0);
        System.out.println("[QC45] OCPP15->16 bridge stopped");
    }

    private final class Handler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                String request = readUtf8(exchange.getRequestBody());
                String op = operation(request);
                String reqId = elementText(request, "MessageID");
                String reqAction = elementText(request, "Action");
                if (op.length() == 0) throw new IllegalArgumentException("Unknown OCPP 1.5 SOAP operation");

                String action16 = action16(op);
                String json = requestJson(op, request);
                System.out.println("[QC45] BRIDGE 1.5->1.6 " + op + " => " + action16 + " " + json);

                String result = backend.call(action16, json, timeoutMs);
                System.out.println("[QC45] BRIDGE 1.6->1.5 " + action16 + " result=" + result);

                String body = responseBody(op, result, request);
                String respAction = responseAction(op, reqAction);
                String response = envelope(body, respAction, reqId, "urn:uuid:" + UUID.randomUUID().toString());
                if ("authorize".equals(op)) {
                    System.out.println("[QC45] OCPP15 Authorize SOAP response=" + response);
                }
                byte[] bytes = response.getBytes("UTF-8");
                Headers h = exchange.getResponseHeaders();
                h.set("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + respAction + "\"");
                h.set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody(); try { out.write(bytes); } finally { out.close(); }
            } catch (Throwable e) {
                System.err.println("[QC45] OCPP15->16 bridge request failed: " + e);
                e.printStackTrace();
                try { exchange.sendResponseHeaders(500, -1); } catch (Throwable ignored) {}
            } finally { try { exchange.close(); } catch (Throwable ignored) {} }
        }
    }

    private String requestJson(String op, String xml) {
        if ("bootNotification".equals(op)) {
            return "{" + q("chargePointVendor", first(xml,"chargePointVendor","chargePointManufacturer","vendor")) + ","
                + q("chargePointModel", first(xml,"chargePointModel","model"))
                + opt(xml,"chargePointSerialNumber") + opt(xml,"chargeBoxSerialNumber") + opt(xml,"firmwareVersion") + "}";
        }
        if ("heartbeat".equals(op)) return "{}";
        if ("authorize".equals(op)) return "{" + q("idTag", elementText(xml,"idTag")) + "}";
        if ("startTransaction".equals(op)) {
            int connector = intText(xml,"connectorId",1);
            return "{" + n("connectorId",connector) + "," + q("idTag",elementText(xml,"idTag")) + ","
                + n("meterStart",longText(xml,"meterStart",0)) + "," + q("timestamp",elementText(xml,"timestamp")) + optionalNumber(xml,"reservationId") + "}";
        }
        if ("stopTransaction".equals(op)) {
            String j = "{" + n("transactionId",intText(xml,"transactionId",-1)) + "," + n("meterStop",longText(xml,"meterStop",0)) + "," + q("timestamp",elementText(xml,"timestamp"));
            String id=elementText(xml,"idTag"); if(id.length()>0) j += ","+q("idTag",id);
            String reason=elementText(xml,"reason"); if(reason.length()>0) j += ","+q("reason",reason);
            return j+"}";
        }
        if ("statusNotification".equals(op)) {
            return "{" + n("connectorId",intText(xml,"connectorId",0)) + "," + q("errorCode",emptyDefault(elementText(xml,"errorCode"),"NoError")) + ","
                + q("status",elementText(xml,"status")) + optionalString(xml,"timestamp") + optionalString(xml,"vendorId") + optionalString(xml,"vendorErrorCode") + "}";
        }
        if ("meterValues".equals(op)) return meterValuesJson(xml);
        if ("firmwareStatusNotification".equals(op)) return "{" + q("status",elementText(xml,"status")) + "}";
        if ("diagnosticsStatusNotification".equals(op)) return "{" + q("status",elementText(xml,"status")) + "}";
        if ("dataTransfer".equals(op)) {
            String j="{"+q("vendorId",elementText(xml,"vendorId")); String mid=elementText(xml,"messageId"), data=elementText(xml,"data");
            if(mid.length()>0)j+=","+q("messageId",mid); if(data.length()>0)j+=","+q("data",data); return j+"}";
        }
        throw new IllegalArgumentException("Unsupported OCPP15 operation: " + op);
    }

    private String meterValuesJson(String xml) {
        int connector = intText(xml,"connectorId",0);
        int tx = intText(xml,"transactionId",-1);
        String timestamp = elementText(xml,"timestamp");
        String value = first(xml,"value","meterValue");
        String measurand = first(xml,"measurand");
        String unit = first(xml,"unit");
        if (value.length()==0) value="0";
        String sampled="{\"value\":\""+json(value)+"\"";
        if(measurand.length()>0)sampled+=",\"measurand\":\""+json(measurand)+"\"";
        if(unit.length()>0)sampled+=",\"unit\":\""+json(unit)+"\"";
        sampled+="}";
        String j="{\"connectorId\":"+connector;
        if(tx>=0)j+=",\"transactionId\":"+tx;
        j+=",\"meterValue\":[{\"timestamp\":\""+json(timestamp)+"\",\"sampledValue\":["+sampled+"]}]}";
        return j;
    }

    private String responseBody(String op, String result, String request) {
        if ("bootNotification".equals(op)) return tag("bootNotificationResponse",
            value("currentTime",fieldString(result,"currentTime","")) + value("heartbeatInterval",String.valueOf(fieldInt(result,"interval",heartbeatInterval))) + value("status",fieldString(result,"status","Rejected")));
        if ("heartbeat".equals(op)) return tag("heartbeatResponse",value("currentTime",fieldString(result,"currentTime","")));
        if ("authorize".equals(op)) return tag("authorizeResponse",idTagInfo(result));
        if ("startTransaction".equals(op)) {
            int tx=fieldInt(result,"transactionId",-1); int connector=intText(request,"connectorId",1); if(tx>=0) backend.rememberTransaction(tx,connector);
            return tag("startTransactionResponse",value("transactionId",String.valueOf(tx))+idTagInfo(result));
        }
        if ("stopTransaction".equals(op)) return tag("stopTransactionResponse",idTagInfo(result));
        if ("statusNotification".equals(op)) return tag("statusNotificationResponse","");
        if ("meterValues".equals(op)) return tag("meterValuesResponse","");
        if ("firmwareStatusNotification".equals(op)) return tag("firmwareStatusNotificationResponse","");
        if ("diagnosticsStatusNotification".equals(op)) return tag("diagnosticsStatusNotificationResponse","");
        if ("dataTransfer".equals(op)) { String b=value("status",fieldString(result,"status","Rejected")); String d=fieldString(result,"data",""); if(d.length()>0)b+=value("data",d); return tag("dataTransferResponse",b); }
        return tag(op+"Response","");
    }

    /**
     * EFACEC treats a near-current expiryDate as already expired. ChargePoint
     * currently returns Accepted together with an expiry timestamp at roughly
     * the authorization instant, so the bridge deliberately forwards only the
     * authorization status to the legacy OCPP 1.5 client.
     */
    private static String idTagInfo(String json) {
        String status=fieldString(json,"status","Accepted");
        return "<idTagInfo><status>" + escape(status) + "</status></idTagInfo>";
    }

    private static String action16(String op){ if("bootNotification".equals(op))return"BootNotification";if("heartbeat".equals(op))return"Heartbeat";if("authorize".equals(op))return"Authorize";if("startTransaction".equals(op))return"StartTransaction";if("stopTransaction".equals(op))return"StopTransaction";if("statusNotification".equals(op))return"StatusNotification";if("meterValues".equals(op))return"MeterValues";if("firmwareStatusNotification".equals(op))return"FirmwareStatusNotification";if("diagnosticsStatusNotification".equals(op))return"DiagnosticsStatusNotification";if("dataTransfer".equals(op))return"DataTransfer";return op;}
    private static String operation(String xml){String[] a={"bootNotification","heartbeat","authorize","startTransaction","stopTransaction","statusNotification","meterValues","firmwareStatusNotification","diagnosticsStatusNotification","dataTransfer"};for(String op:a)if(xml.indexOf("<"+op)>=0||xml.indexOf(":"+op)>=0)return op;return"";}
    private static String responseAction(String op,String req){if(req!=null&&req.length()>0){if(req.endsWith("Request"))return req.substring(0,req.length()-7)+"Response";if(!req.endsWith("Response"))return req+"Response";return req;}return NS+Character.toUpperCase(op.charAt(0))+op.substring(1)+"Response";}

    private static String elementText(String xml,String n){Pattern p=Pattern.compile("(?is)<(?:[A-Za-z0-9_.-]+:)?"+Pattern.quote(n)+"(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?"+Pattern.quote(n)+">");Matcher m=p.matcher(xml);if(!m.find())return"";return unescapeXml(m.group(1).replaceAll("<[^>]+>","").trim());}
    private static String first(String xml,String... names){for(String n:names){String v=elementText(xml,n);if(v.length()>0)return v;}return"";}
    private static int intText(String x,String n,int d){try{String v=elementText(x,n);return v.length()==0?d:Integer.parseInt(v);}catch(Exception e){return d;}}
    private static long longText(String x,String n,long d){try{String v=elementText(x,n);return v.length()==0?d:Long.parseLong(v);}catch(Exception e){return d;}}
    private static String q(String k,String v){return"\""+json(k)+"\":\""+json(v)+"\"";}
    private static String n(String k,long v){return"\""+json(k)+"\":"+v;}
    private static String opt(String x,String n){String v=elementText(x,n);return v.length()==0?"":","+q(n,v);}
    private static String optionalString(String x,String n){return opt(x,n);}
    private static String optionalNumber(String x,String n){String v=elementText(x,n);return v.length()==0?"":",\""+json(n)+"\":"+v;}
    private static String emptyDefault(String v,String d){return v.length()==0?d:v;}
    private static int fieldInt(String j,String f,int d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*(-?\\d+)").matcher(j);return m.find()?Integer.parseInt(m.group(1)):d;}
    private static String fieldString(String j,String f,String d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(j);return m.find()?m.group(1).replace("\\\"","\"").replace("\\\\","\\"):d;}

    private static String envelope(String body,String action,String relates,String id){String h="<s:Header><wsa:Action s:mustUnderstand=\"1\">"+escape(action)+"</wsa:Action><wsa:MessageID>"+escape(id)+"</wsa:MessageID>"+(relates.length()>0?"<wsa:RelatesTo>"+escape(relates)+"</wsa:RelatesTo>":"")+"</s:Header>";return"<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:wsa=\""+WSA+"\" xmlns:cs=\""+NS+"\">"+h+"<s:Body>"+body+"</s:Body></s:Envelope>";}
    private static String tag(String n,String c){return"<cs:"+n+">"+c+"</cs:"+n+">";}
    private static String value(String n,String v){return"<"+n+">"+escape(v)+"</"+n+">";}
    private static String escape(String v){return v==null?"":v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static String unescapeXml(String v){return v.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&");}
    private static String json(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    private static String readUtf8(InputStream in)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[4096];int n;while((n=in.read(b))>=0)o.write(b,0,n);return new String(o.toByteArray(),"UTF-8");}
    private static String normalizePath(String v){String p=v==null||v.trim().length()==0?"/QC45":v.trim();return p.charAt(0)=='/'?p:"/"+p;}
}
