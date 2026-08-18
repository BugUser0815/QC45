package de.rothner.qc45;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Authenticated HTTPS JSON API for the pinned iOS monitor app. */
public final class RemoteApiServer {
    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final OcppBridgeClient ocpp;
    private final GridFailback failback;
    private final String bind;
    private final int port;
    private final String token;
    private final String keyStoreFile;
    private final String keyStoreType;
    private final String keyStorePassword;
    private final String keyPassword;
    private final String certificateAlias;
    private HttpsServer server;

    public RemoteApiServer(ReflectionQC45 station, KsemClient meter, OcppBridgeClient ocpp,
                           GridFailback failback, String bind, int port, String token,
                           String keyStoreFile, String keyStoreType, String keyStorePassword,
                           String keyPassword, String certificateAlias) {
        this.station = station;
        this.meter = meter;
        this.ocpp = ocpp;
        this.failback = failback;
        this.bind = bind;
        this.port = port;
        this.token = token == null ? "" : token.trim();
        this.keyStoreFile = keyStoreFile == null ? "" : keyStoreFile.trim();
        this.keyStoreType = keyStoreType == null || keyStoreType.trim().length() == 0 ? "JKS" : keyStoreType.trim();
        this.keyStorePassword = keyStorePassword == null ? "" : keyStorePassword;
        this.keyPassword = keyPassword == null || keyPassword.length() == 0 ? this.keyStorePassword : keyPassword;
        this.certificateAlias = certificateAlias == null || certificateAlias.trim().length() == 0 ? "qc45-api" : certificateAlias.trim();
    }

    public synchronized void start() throws Exception {
        if (server != null) return;
        if (token.length() < 32) throw new IllegalArgumentException("remoteapi.token must contain at least 32 characters");
        if (keyStoreFile.length() == 0) throw new IllegalArgumentException("remoteapi.tls.keyStore is required");
        if (keyStorePassword.length() == 0) throw new IllegalArgumentException("remoteapi.tls.keyStorePassword is required");

        final KeyStore ks = KeyStore.getInstance(keyStoreType);
        InputStream in = new FileInputStream(keyStoreFile);
        try { ks.load(in, keyStorePassword.toCharArray()); } finally { in.close(); }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPassword.toCharArray());
        final SSLContext ssl = SSLContext.getInstance("TLSv1.2");
        ssl.init(kmf.getKeyManagers(), null, null);

        HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 16);
        s.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            public void configure(HttpsParameters params) {
                SSLParameters p = getSSLContext().getDefaultSSLParameters();
                p.setProtocols(new String[] { "TLSv1.2" });
                params.setSSLParameters(p);
            }
        });
        s.createContext("/api/status", new Handler("status"));
        s.createContext("/api/start", new Handler("start"));
        s.createContext("/api/stop", new Handler("stop"));
        s.createContext("/api/health", new Handler("health"));
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        server = s;

        Certificate cert = ks.getCertificate(certificateAlias);
        if (cert == null) throw new IllegalStateException("Certificate alias not found in keystore: " + certificateAlias);
        String pin = sha256Hex(cert.getEncoded());
        System.out.println("[QC45] Remote API listening on https://" + bind + ":" + port + "/api TLSv1.2");
        System.out.println("[QC45] Remote API certificate SHA-256 pin=" + pin);
    }

    public synchronized void shutdown() {
        HttpsServer s = server; server = null;
        if (s != null) s.stop(0);
        System.out.println("[QC45] Remote API stopped");
    }

    private final class Handler implements HttpHandler {
        private final String operation;
        Handler(String operation) { this.operation = operation; }

        public void handle(HttpExchange e) {
            try {
                if (!(e instanceof HttpsExchange)) { json(e, 426, "{\"error\":\"https_required\"}"); return; }
                if (!authorized(e)) { json(e, 401, "{\"error\":\"unauthorized\"}"); return; }
                if ("health".equals(operation)) { json(e, 200, "{\"ok\":true}"); return; }
                if ("status".equals(operation)) {
                    if (!"GET".equalsIgnoreCase(e.getRequestMethod())) { json(e,405,"{\"error\":\"method_not_allowed\"}"); return; }
                    json(e, 200, statusJson()); return;
                }
                if (!"POST".equalsIgnoreCase(e.getRequestMethod())) { json(e,405,"{\"error\":\"method_not_allowed\"}"); return; }
                Map<String,String> p = params(e);
                int connector = integer(p.get("connector"), 0);
                if (connector < 1 || connector > 3) { json(e,400,"{\"error\":\"invalid_connector\"}"); return; }
                if ("start".equals(operation)) {
                    String idTag = p.get("idTag");
                    if (idTag == null || idTag.trim().length() == 0) { json(e,400,"{\"error\":\"missing_idTag\"}"); return; }
                    station.remoteStart(idTag.trim(), connector);
                    json(e,200,"{\"ok\":true,\"connector\":"+connector+"}"); return;
                }
                station.remoteStop(connector);
                json(e,200,"{\"ok\":true,\"connector\":"+connector+"}");
            } catch (Throwable ex) {
                System.err.println("[QC45] Remote API " + operation + " failed: " + ex);
                try { json(e,500,"{\"error\":\""+j(String.valueOf(ex))+"\"}"); } catch(Throwable ignored) {}
            } finally { try { e.close(); } catch(Throwable ignored) {} }
        }
    }

    private String statusJson() throws Exception {
        StringBuilder b = new StringBuilder();
        b.append('{');
        b.append("\"online\":true,");
        b.append("\"ocppConnected\":").append(ocpp != null && ocpp.isConnected()).append(',');
        b.append("\"remoteStarted\":").append(station.remoteStarted()).append(',');
        b.append("\"failbackTripped\":").append(failback != null && failback.isTripped()).append(',');
        b.append("\"meterPaused\":").append(failback != null && failback.isMeterPaused()).append(',');
        b.append("\"stationPowerKw\":").append(station.stationPowerKw()).append(',');

        try {
            KsemClient.Currents c = meter.readCurrents();
            b.append("\"grid\":{\"online\":true,\"l1\":").append(one(c.l1))
             .append(",\"l2\":").append(one(c.l2)).append(",\"l3\":").append(one(c.l3))
             .append(",\"max\":").append(one(c.max())).append("},");
        } catch (Throwable ex) {
            b.append("\"grid\":{\"online\":false,\"l1\":0,\"l2\":0,\"l3\":0,\"max\":0},");
        }

        b.append("\"connectors\":[");
        for (int c=1;c<=3;c++) {
            if (c>1) b.append(',');
            int power = station.powerKw(c);
            String tag = station.idTag(c);
            b.append('{')
             .append("\"id\":").append(c).append(',')
             .append("\"name\":\"").append(c==1?"CHAdeMO":c==2?"CCS":"Type 2").append("\",")
             .append("\"powerKw\":").append(power).append(',')
             .append("\"limitKw\":").append(station.limitKw(c)).append(',')
             .append("\"energyRaw\":").append(station.energyRaw(c)).append(',')
             .append("\"active\":").append(power>0 || tag.length()>0).append(',')
             .append("\"idTag\":\"").append(j(tag)).append("\"")
             .append('}');
        }
        b.append("]}");
        return b.toString();
    }

    private boolean authorized(HttpExchange e) throws Exception {
        String auth = e.getRequestHeaders().getFirst("Authorization");
        if (auth == null) return false;
        byte[] a = auth.getBytes("UTF-8");
        byte[] b = ("Bearer " + token).getBytes("UTF-8");
        return MessageDigest.isEqual(a, b);
    }

    private static Map<String,String> params(HttpExchange e) throws Exception {
        Map<String,String> out = new LinkedHashMap<String,String>();
        parseQuery(out, e.getRequestURI().getRawQuery());
        String ct=e.getRequestHeaders().getFirst("Content-Type");
        if (ct != null && ct.toLowerCase().indexOf("application/x-www-form-urlencoded") >= 0) parseQuery(out, read(e.getRequestBody()));
        return out;
    }

    private static void parseQuery(Map<String,String> out,String q)throws Exception{
        if(q==null||q.length()==0)return; String[] parts=q.split("&");
        for(int i=0;i<parts.length;i++){int p=parts[i].indexOf('=');String k=p<0?parts[i]:parts[i].substring(0,p);String v=p<0?"":parts[i].substring(p+1);out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}
    }
    private static int integer(String v,int d){try{return v==null?d:Integer.parseInt(v);}catch(Exception e){return d;}}
    private static String read(InputStream in)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]buf=new byte[1024];int n;while((n=in.read(buf))>=0)o.write(buf,0,n);return new String(o.toByteArray(),"UTF-8");}
    private static void json(HttpExchange e,int code,String body)throws Exception{byte[]data=body.getBytes("UTF-8");Headers h=e.getResponseHeaders();h.set("Content-Type","application/json; charset=utf-8");h.set("Cache-Control","no-store");h.set("X-Content-Type-Options","nosniff");e.sendResponseHeaders(code,data.length);OutputStream out=e.getResponseBody();try{out.write(data);}finally{out.close();}}
    private static String j(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    private static String one(double d){return String.format(java.util.Locale.US,"%.3f",Double.valueOf(d));}
    private static String sha256Hex(byte[] data)throws Exception{byte[]d=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder b=new StringBuilder();for(int i=0;i<d.length;i++){int v=d[i]&255;if(v<16)b.append('0');b.append(Integer.toHexString(v));}return b.toString().toUpperCase(java.util.Locale.US);}
}
