package de.rothner.qc45;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import javax.net.ssl.*;

/** OCPP 1.6 JSON/WSS endpoint used by the legacy OCPP 1.5 SOAP bridge. */
public final class OcppBridgeClient extends Thread {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final ReflectionQC45 station;
    private final URI uri;
    private final String username, password, caFile;
    private final boolean insecure;
    private final File transactionMapFile;
    private final Map<String, Pending> pending = new HashMap<String, Pending>();
    private final Map<Integer,Integer> txConnector = new HashMap<Integer,Integer>();
    private volatile boolean running = true;
    private volatile WebSocket ws;

    public OcppBridgeClient(ReflectionQC45 station, String url, String username, String password,
                            String caFile, boolean insecure,
                            String transactionMapPath) throws Exception {
        super("qc45-ocpp-bridge");
        if (station == null || url == null || username == null || password == null
                || transactionMapPath == null || transactionMapPath.trim().length() == 0) {
            throw new IllegalArgumentException("invalid OCPP bridge configuration");
        }
        this.station = station;
        this.uri = new URI(url);
        if (!"wss".equalsIgnoreCase(this.uri.getScheme()) || this.uri.getHost() == null) {
            throw new IllegalArgumentException("OCPP bridge URL must be an absolute wss:// URL");
        }
        this.username = username;
        this.password = password;
        this.caFile = caFile == null ? "" : caFile.trim();
        this.insecure = insecure;
        this.transactionMapFile = new File(transactionMapPath.trim());
        loadTransactionMap();
        setDaemon(true);
    }

    public void shutdown() {
        running = false;
        interrupt();
        WebSocket s = ws;
        if (s != null) s.close();
        synchronized (pending) {
            for (Pending p : pending.values()) p.fail("bridge stopped");
            pending.clear();
        }
    }

    public boolean isConnected() { WebSocket s = ws; return s != null && s.isOpen(); }

    public void run() {
        long retry = 2000L;
        while (running) {
            try {
                ws = new WebSocket(uri, username, password, caFile, insecure);
                ws.connect();
                System.out.println("[QC45] OCPP bridge connected: " + uri);
                retry = 2000L;
                while (running && ws.isOpen()) {
                    String msg = ws.readText(1000);
                    if (msg != null) onMessage(msg);
                }
            } catch (Throwable e) {
                if (running) {
                    System.err.println("[QC45] OCPP bridge connection error: " + e);
                    synchronized (pending) {
                        for (Pending p : pending.values()) p.fail(String.valueOf(e));
                        pending.clear();
                    }
                    try { Thread.sleep(retry); } catch (InterruptedException ignored) {}
                    retry = Math.min(30000L, retry * 2L);
                }
            } finally {
                WebSocket s = ws; if (s != null) s.close(); ws = null;
            }
        }
    }

    /** Forward an OCPP CALL and wait for CALLRESULT/CALLERROR. */
    public String call(String action, String payload, int timeoutMs) throws Exception {
        WebSocket s = ws;
        if (s == null || !s.isOpen()) throw new IOException("OCPP backend not connected");
        String uid = UUID.randomUUID().toString();
        Pending p = new Pending();
        synchronized (pending) { pending.put(uid, p); }
        try {
            s.sendText("[2,\"" + uid + "\",\"" + json(action) + "\"," + payload + "]");
            return p.await(timeoutMs);
        } finally {
            synchronized (pending) { pending.remove(uid); }
        }
    }

    public void rememberTransaction(int transactionId, int connector) {
        if (transactionId < 0 || connector < 1 || connector > 3) return;
        synchronized (txConnector) {
            txConnector.put(Integer.valueOf(transactionId), Integer.valueOf(connector));
            persistTransactionMapBestEffort();
        }
    }

    public void forgetTransaction(int transactionId) {
        synchronized (txConnector) {
            txConnector.remove(Integer.valueOf(transactionId));
            persistTransactionMapBestEffort();
        }
    }

    private void onMessage(String msg) throws Exception {
        int type = firstInt(msg);
        if (type == 3 || type == 4) {
            String uid = arrayString(msg, 1);
            Pending p;
            synchronized (pending) { p = pending.get(uid); }
            if (p != null) {
                if (type == 3) p.complete(arrayObject(msg, 2));
                else p.fail("CALLERROR " + msg);
            }
            return;
        }
        if (type != 2) return;

        String uid = arrayString(msg, 1);
        String action = arrayString(msg, 2);
        String payload = arrayObject(msg, 3);
        System.out.println("[QC45] OCPP bridge backend CALL " + action);

        if ("RemoteStartTransaction".equals(action)) {
            int connector = fieldInt(payload, "connectorId", 0);
            if (connector < 1 || connector > 3) connector = 2;
            String idTag = fieldString(payload, "idTag", "REMOTE");
            try {
                station.remoteStart(idTag, connector);
                sendResult(uid, "{\"status\":\"Accepted\"}");
            } catch (Throwable e) {
                System.err.println("[QC45] bridge RemoteStart failed: " + e);
                sendResult(uid, "{\"status\":\"Rejected\"}");
            }
            return;
        }
        if ("RemoteStopTransaction".equals(action)) {
            int tx = fieldInt(payload, "transactionId", -1);
            int connector = connectorForTransaction(tx);
            if (connector > 0) {
                try {
                    station.remoteStop(connector);
                    forgetTransaction(tx);
                    sendResult(uid, "{\"status\":\"Accepted\"}");
                }
                catch (Throwable e) { System.err.println("[QC45] bridge RemoteStop failed: " + e); sendResult(uid, "{\"status\":\"Rejected\"}"); }
            } else sendResult(uid, "{\"status\":\"Rejected\"}");
            return;
        }
        if ("Reset".equals(action)) { sendResult(uid, "{\"status\":\"Rejected\"}"); return; }
        if ("UnlockConnector".equals(action)) { sendResult(uid, "{\"status\":\"NotSupported\"}"); return; }
        sendError(uid, "NotSupported", "Unsupported action: " + action);
    }

    private int connectorForTransaction(int transactionId) {
        if (transactionId < 0) return 0;
        int reflected = station.connectorForTransactionId(transactionId);
        if (reflected > 0) {
            rememberTransaction(transactionId, reflected);
            System.out.println("[QC45] recovered transaction mapping tx=" + transactionId
                + " connector=" + reflected + " from live EVCSD transaction");
            return reflected;
        }
        synchronized (txConnector) {
            Integer connector = txConnector.get(Integer.valueOf(transactionId));
            if (connector != null) {
                try {
                    if (station.sessionActive(connector.intValue())) return connector.intValue();
                } catch (Throwable ignored) {
                    // Keep the conservative persisted mapping when live state
                    // cannot be observed; remoteStop itself still validates.
                    return connector.intValue();
                }
                txConnector.remove(Integer.valueOf(transactionId));
                persistTransactionMapBestEffort();
            }
        }
        int sole = station.soleActiveConnector();
        if (sole > 0) {
            System.out.println("[QC45] recovered transaction mapping tx=" + transactionId
                + " connector=" + sole + " from sole active session");
            return sole;
        }
        return 0;
    }

    private void loadTransactionMap() {
        if (!transactionMapFile.isFile()) return;
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(transactionMapFile);
            properties.load(in);
            for (Map.Entry<Object,Object> entry : properties.entrySet()) {
                int tx = Integer.parseInt(String.valueOf(entry.getKey()));
                int connector = Integer.parseInt(String.valueOf(entry.getValue()));
                if (tx >= 0 && connector >= 1 && connector <= 3) {
                    txConnector.put(Integer.valueOf(tx), Integer.valueOf(connector));
                }
            }
            System.out.println("[QC45] loaded " + txConnector.size()
                + " persisted OCPP transaction mapping(s)");
        } catch (Throwable e) {
            System.err.println("[QC45] transaction mapping load failed: " + e);
            txConnector.clear();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** Caller holds txConnector. */
    private void persistTransactionMapBestEffort() {
        OutputStream out = null;
        File temp = new File(transactionMapFile.getPath() + ".tmp");
        try {
            File parent = transactionMapFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            Properties properties = new Properties();
            for (Map.Entry<Integer,Integer> entry : txConnector.entrySet()) {
                properties.setProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            out = new FileOutputStream(temp);
            properties.store(out, "QC45 active OCPP transaction -> connector mappings");
            out.close();
            out = null;
            try {
                java.nio.file.Files.move(temp.toPath(), transactionMapFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp.toPath(), transactionMapFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable e) {
            System.err.println("[QC45] transaction mapping persist failed: " + e);
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            if (temp.exists() && !temp.equals(transactionMapFile)) try { temp.delete(); } catch (Throwable ignored) {}
        }
    }

    private void sendResult(String uid, String payload) throws IOException { ws.sendText("[3,\""+json(uid)+"\","+payload+"]"); }
    private void sendError(String uid, String code, String text) throws IOException { ws.sendText("[4,\""+json(uid)+"\",\""+json(code)+"\",\""+json(text)+"\",{}]"); }

    private static final class Pending {
        private String result, error;
        synchronized void complete(String r) { result=r; notifyAll(); }
        synchronized void fail(String e) { error=e; notifyAll(); }
        synchronized String await(int timeoutMs) throws Exception {
            long end=System.currentTimeMillis()+timeoutMs;
            while(result==null && error==null) {
                long left=end-System.currentTimeMillis();
                if(left<=0) throw new IOException("OCPP backend response timeout");
                wait(left);
            }
            if(error!=null) throw new IOException(error);
            return result;
        }
    }

    private static int firstInt(String s) { Matcher m=Pattern.compile("^\\s*\\[\\s*(\\d+)").matcher(s); return m.find()?Integer.parseInt(m.group(1)):-1; }
    private static String arrayString(String s,int idx){ int p=skip(s,0,'['),cur=0; while(p>=0&&p<s.length()){p=ws(s,p); if(cur==idx){if(s.charAt(p)!='\"')return"";return parseString(s,p)[0];}p=skipValue(s,p);p=ws(s,p);if(p<s.length()&&s.charAt(p)==',')p++;cur++;}return""; }
    private static String arrayObject(String s,int idx){ int p=skip(s,0,'['),cur=0; while(p>=0&&p<s.length()){p=ws(s,p);if(cur==idx){int e=skipValue(s,p);return s.substring(p,e);}p=skipValue(s,p);p=ws(s,p);if(p<s.length()&&s.charAt(p)==',')p++;cur++;}return"{}"; }
    private static int fieldInt(String s,String f,int d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*(-?\\d+)").matcher(s);return m.find()?Integer.parseInt(m.group(1)):d;}
    private static String fieldString(String s,String f,String d){Matcher m=Pattern.compile("\\\""+Pattern.quote(f)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(s);return m.find()?m.group(1).replace("\\\"","\"").replace("\\\\","\\"):d;}
    private static int skip(String s,int p,char c){p=ws(s,p);return p<s.length()&&s.charAt(p)==c?p+1:-1;}
    private static int ws(String s,int p){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;return p;}
    private static int skipValue(String s,int p){p=ws(s,p);if(p>=s.length())return p;char c=s.charAt(p);if(c=='\"')return Integer.parseInt(parseString(s,p)[1]);if(c=='{'||c=='['){char o=c,x=c=='{'?'}':']';int d=0;boolean q=false,e=false;for(int i=p;i<s.length();i++){char z=s.charAt(i);if(q){if(e)e=false;else if(z=='\\')e=true;else if(z=='\"')q=false;}else{if(z=='\"')q=true;else if(z==o)d++;else if(z==x&&--d==0)return i+1;}}return s.length();}int i=p;while(i<s.length()&&s.charAt(i)!=','&&s.charAt(i)!=']'&&s.charAt(i)!='}')i++;return i;}
    private static String[] parseString(String s,int p){StringBuilder b=new StringBuilder();boolean e=false;for(int i=p+1;i<s.length();i++){char c=s.charAt(i);if(e){b.append(c=='n'?'\n':c=='r'?'\r':c);e=false;}else if(c=='\\')e=true;else if(c=='\"')return new String[]{b.toString(),String.valueOf(i+1)};else b.append(c);}return new String[]{b.toString(),String.valueOf(s.length())};}
    private static String json(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}

    private static final class WebSocket {
        private static final SecureRandom RANDOM=new SecureRandom();
        private final URI uri; private final String user,pass,caFile; private final boolean insecure;
        private SSLSocket socket; private InputStream in; private OutputStream out; private volatile boolean open;
        private ByteArrayOutputStream fragmented; private int fragmentedOpcode;
        WebSocket(URI u,String user,String pass,String caFile,boolean insecure){this.uri=u;this.user=user;this.pass=pass;this.caFile=caFile;this.insecure=insecure;}
        boolean isOpen(){return open;}
        void connect() throws Exception {
            if(!"wss".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("Only wss:// supported");
            String host=uri.getHost(); int port=uri.getPort()>0?uri.getPort():443;
            SSLSocketFactory f=sslFactory(caFile,insecure);
            socket=(SSLSocket)f.createSocket(host,port); socket.setEnabledProtocols(new String[]{"TLSv1.2"}); socket.setSoTimeout(1000);
            if(!insecure){SSLParameters p=socket.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(p);}
            socket.startHandshake();
            System.out.println("[QC45] OCPP bridge TLS " + socket.getSession().getProtocol()+" / "+socket.getSession().getCipherSuite());
            in=new BufferedInputStream(socket.getInputStream()); out=new BufferedOutputStream(socket.getOutputStream());
            byte[] kb=new byte[16];RANDOM.nextBytes(kb);String key=base64(kb);String path=uri.getRawPath();if(path==null||path.length()==0)path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
            String auth=base64((user+":"+pass).getBytes(UTF8));
            String req="GET "+path+" HTTP/1.1\r\nHost: "+host+":"+port+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: ocpp1.6\r\nAuthorization: Basic "+auth+"\r\n\r\n";
            out.write(req.getBytes(UTF8));out.flush();String h=readHeader(in);if(h.indexOf(" 101 ")<0)throw new IOException("WebSocket upgrade failed: "+firstLine(h));
            String accept=header(h,"Sec-WebSocket-Accept");String exp=base64(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF8)));if(!exp.equals(accept))throw new IOException("Invalid Sec-WebSocket-Accept");if(!"ocpp1.6".equalsIgnoreCase(header(h,"Sec-WebSocket-Protocol")))throw new IOException("Backend did not select ocpp1.6 subprotocol");open=true;
        }
        synchronized void sendText(String s)throws IOException{sendFrame(1,s.getBytes(UTF8));}
        String readText(int timeout)throws IOException{if(!open)return null;socket.setSoTimeout(timeout);try{while(open){Frame f=readFrame();if(f.op==8){close();return null;}if(f.op==9){sendFrame(10,f.data);continue;}if(f.op==10)continue;if(f.op==1){if(fragmented!=null)throw new IOException("new text frame during fragmented message");if(f.fin)return new String(f.data,UTF8);fragmented=new ByteArrayOutputStream();fragmentedOpcode=1;appendFragment(f.data);continue;}if(f.op==0){if(fragmented==null||fragmentedOpcode!=1)throw new IOException("unexpected continuation frame");appendFragment(f.data);if(f.fin){String value=new String(fragmented.toByteArray(),UTF8);fragmented=null;fragmentedOpcode=0;return value;}continue;}if(f.op==2)throw new IOException("binary WebSocket messages are unsupported");}}catch(java.net.SocketTimeoutException e){return null;}return null;}
        synchronized void close(){if(!open&&socket==null)return;try{if(open)sendFrame(8,new byte[0]);}catch(Throwable ignored){}open=false;try{if(socket!=null)socket.close();}catch(Throwable ignored){}}
        private void sendFrame(int op,byte[] data)throws IOException{if(!open&&op!=8)throw new IOException("closed");ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(0x80|op);int n=data.length;if(n<=125)b.write(0x80|n);else if(n<=65535){b.write(0x80|126);b.write((n>>>8)&255);b.write(n&255);}else{b.write(0x80|127);for(int i=7;i>=0;i--)b.write((int)(((long)n>>>(8*i))&255));}byte[] m=new byte[4];RANDOM.nextBytes(m);b.write(m);for(int i=0;i<n;i++)b.write(data[i]^m[i&3]);out.write(b.toByteArray());out.flush();}
        private void appendFragment(byte[] data)throws IOException{if(fragmented.size()+data.length>1024*1024)throw new IOException("fragmented message too large");fragmented.write(data,0,data.length);}
        private Frame readFrame()throws IOException{int a=in.read(),b=in.read();if(a<0||b<0)throw new EOFException();boolean fin=(a&128)!=0;if((a&112)!=0)throw new IOException("WebSocket RSV bits are unsupported");int op=a&15;if(op!=0&&op!=1&&op!=2&&op!=8&&op!=9&&op!=10)throw new IOException("invalid WebSocket opcode "+op);boolean masked=(b&128)!=0;if(masked)throw new IOException("server WebSocket frame must not be masked");long n=b&127;if(n==126)n=((long)readByte()<<8)|readByte();else if(n==127){int first=readByte();if((first&128)!=0)throw new IOException("invalid WebSocket length");n=first;for(int i=1;i<8;i++)n=(n<<8)|readByte();}if(n>1024*1024)throw new IOException("frame too large");if(op>=8&&(!fin||n>125))throw new IOException("invalid WebSocket control frame");byte[] d=new byte[(int)n];readFully(in,d);return new Frame(fin,op,d);}
        private int readByte()throws IOException{int b=in.read();if(b<0)throw new EOFException();return b&255;}
        private static void readFully(InputStream in,byte[] d)throws IOException{int p=0;while(p<d.length){int n=in.read(d,p,d.length-p);if(n<0)throw new EOFException();p+=n;}}
        private static String readHeader(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int s=0;while(b.size()<65536){int c=in.read();if(c<0)throw new EOFException();b.write(c);if(s==0&&c=='\r')s=1;else if(s==1&&c=='\n')s=2;else if(s==2&&c=='\r')s=3;else if(s==3&&c=='\n')return new String(b.toByteArray(),UTF8);else s=c=='\r'?1:0;}throw new IOException("header too large");}
        private static String header(String h,String n){Matcher m=Pattern.compile("(?im)^"+Pattern.quote(n)+":\\s*(.+?)\\s*$").matcher(h);return m.find()?m.group(1).trim():"";}
        private static String firstLine(String h){int p=h.indexOf("\r\n");return p<0?h:h.substring(0,p);}
        private static SSLSocketFactory sslFactory(String caFile,boolean insecure)throws Exception{if(insecure){TrustManager[] tm={new X509TrustManager(){public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}public void checkClientTrusted(java.security.cert.X509Certificate[] c,String a){}public void checkServerTrusted(java.security.cert.X509Certificate[] c,String a){}}};SSLContext c=SSLContext.getInstance("TLSv1.2");c.init(null,tm,new SecureRandom());System.err.println("[QC45] WARNING: OCPP bridge TLS certificate verification DISABLED");return c.getSocketFactory();}if(caFile!=null&&caFile.length()>0){CertificateFactory cf=CertificateFactory.getInstance("X.509");InputStream in=new FileInputStream(caFile);Collection<? extends Certificate> certs;try{certs=cf.generateCertificates(in);}finally{in.close();}KeyStore ks=KeyStore.getInstance(KeyStore.getDefaultType());ks.load(null,null);int i=0;for(Certificate cert:certs)ks.setCertificateEntry("ca"+(++i),cert);TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tmf.init(ks);SSLContext c=SSLContext.getInstance("TLSv1.2");c.init(null,tmf.getTrustManagers(),new SecureRandom());System.out.println("[QC45] OCPP bridge custom CA loaded: "+caFile+" ("+i+" certificate(s))");return c.getSocketFactory();}return (SSLSocketFactory)SSLSocketFactory.getDefault();}
        private static final char[] B64="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();private static String base64(byte[] d){StringBuilder s=new StringBuilder((d.length+2)/3*4);for(int i=0;i<d.length;i+=3){int a=d[i]&255,b=i+1<d.length?d[i+1]&255:0,c=i+2<d.length?d[i+2]&255:0;s.append(B64[a>>>2]);s.append(B64[((a&3)<<4)|(b>>>4)]);s.append(i+1<d.length?B64[((b&15)<<2)|(c>>>6)]:'=');s.append(i+2<d.length?B64[c&63]:'=');}return s.toString();}
        private static final class Frame{final boolean fin;final int op;final byte[] data;Frame(boolean f,int o,byte[]d){fin=f;op=o;data=d;}}
    }
}
