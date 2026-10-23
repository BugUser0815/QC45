package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Properties;

/** More defensive raw-stream hook for old Java 7 EVCSD/RXTX installations. */
public final class CcsRawTracerV2 {
    private static final String PREFIX = "[QC45] CCS-RAW2 ";

    private static final Object LIVE_LOCK = new Object();
    private static int liveSocPct;
    private static int livePowerW;
    private static long liveLastRxMs;
    private static long liveLastChargingMs;
    private static long liveSessionStartMs;
    private static long liveLastIntegrateMs;
    private static double liveEnergyWh;
    private static Object txOwner;
    private static Field txField;
    private static OutputStream txOriginal;
    private static TraceOutputStream txWrapper;
    private static Object rxOwner;
    private static Field rxField;
    private static TraceInputStream rxWrapper;
    private static Thread installer;
    private static String lastAttachmentStatus;
    private static long lastAttachmentLogMs;

    interface RuntimeAccess {
        Object central() throws Exception;
        CcsSerialDiscovery.Reader[] readers() throws Exception;
    }

    static final RuntimeAccess RUNTIME = new RuntimeAccess() {
        public Object central() throws Exception {
            Class<?> type = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
            return type.getMethod("getCurrentModule").invoke(null);
        }
        public CcsSerialDiscovery.Reader[] readers() {
            return CcsSerialDiscovery.readers();
        }
    };

    private CcsRawTracerV2() {}

    public static synchronized void installFromDefaultConfig() throws Exception {
        Properties p = new Properties();
        String explicit = System.getProperty("qc45.integration.config");
        File file = explicit == null || explicit.trim().length() == 0
            ? new File("/home/mobie/evcsd/qc45-integration.properties")
            : new File(explicit.trim());
        InputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); }

        // The RX hook is also the authoritative live telemetry source for the
        // local Modbus/UI path. Therefore install it regardless of whether raw
        // trace logging is enabled. The flag only controls diagnostic logging.
        boolean logging = bool(p, "evcsd.ccsRawTrace.enabled", true);
        CcsTraceSession.CURRENT.configure(bool(p, "evcsd.ccsRawTrace.onlyWhenConnected", true));
        startInstaller(RUNTIME, integer(p, "evcsd.ccsRawTrace.repeatMs", 1000), logging, 1000L);
    }

    public static synchronized void shutdown() {
        Thread current = installer;
        installer = null;
        if (current != null) current.interrupt();
        synchronized (CcsSerialDiscovery.STREAM_LOCK) {
            restoreTx();
            restoreRx();
        }
        lastAttachmentStatus = null;
        lastAttachmentLogMs = 0L;
        clearLiveTelemetry();
        CcsTraceSession.CURRENT.configure(true);
        System.out.println(PREFIX + "streams restored");
    }

    private static void clearLiveTelemetry() {
        CcsTraceSession.CURRENT.reset();
        synchronized (LIVE_LOCK) {
            liveSocPct = 0;
            livePowerW = 0;
            liveLastRxMs = 0L;
            liveLastChargingMs = 0L;
            liveSessionStartMs = 0L;
            liveLastIntegrateMs = 0L;
            liveEnergyWh = 0.0d;
        }
    }

    private static void restoreTx() {
        if (txWrapper != null) txWrapper.state.disable();
        try {
            if (txOwner != null && txField != null && txWrapper != null) {
                txField.setAccessible(true);
                if (txField.get(txOwner) == txWrapper) txField.set(txOwner, txOriginal);
            }
        } catch (Throwable e) {
            System.err.println(PREFIX + "TX restore failed: " + e);
        }
        txOwner = null; txField = null; txOriginal = null; txWrapper = null;
    }

    private static void restoreRx() {
        boolean detached = rxWrapper != null;
        if (detached) rxWrapper.state.disable();
        try {
            if (rxOwner != null && rxField != null && rxWrapper != null) {
                rxField.setAccessible(true);
                Object input = rxField.get(rxOwner);
                if (input == rxWrapper) rxField.set(rxOwner, rxWrapper.source());
                else CcsFullRxTracer.restoreRaw(input, rxWrapper, rxWrapper.source());
            }
        } catch (Throwable e) {
            System.err.println(PREFIX + "RX restore failed: " + e);
        }
        rxOwner = null; rxField = null; rxWrapper = null;
        if (detached) clearLiveTelemetry();
    }

    public static boolean hasFreshLiveTelemetry() {
        synchronized (LIVE_LOCK) {
            return liveLastRxMs > 0L && System.currentTimeMillis() - liveLastRxMs <= 5000L;
        }
    }

    public static int livePowerKw() {
        synchronized (LIVE_LOCK) {
            return Math.max(0, (livePowerW + 500) / 1000);
        }
    }

    public static int liveSocPct() {
        synchronized (LIVE_LOCK) {
            return Math.max(0, Math.min(100, liveSocPct));
        }
    }

    public static long liveElapsedSeconds() {
        synchronized (LIVE_LOCK) {
            if (liveSessionStartMs <= 0L) return 0L;
            long now = System.currentTimeMillis();
            long end = liveLastChargingMs > 0L && now - liveLastChargingMs > 10000L
                ? liveLastChargingMs : now;
            long delta = end - liveSessionStartMs;
            return delta <= 0L ? 0L : delta / 1000L;
        }
    }

    public static long liveEnergyWh() {
        synchronized (LIVE_LOCK) {
            integrateTo(System.currentTimeMillis());
            return Math.max(0L, Math.round(liveEnergyWh));
        }
    }

    static synchronized void startInstaller(final RuntimeAccess runtime, final long repeatMs,
                                             final boolean logging, final long retryMs) {
        if (installer != null) return;
        installer = new Thread(new Runnable() {
            public void run() {
                Thread self = Thread.currentThread();
                while (true) {
                    synchronized (CcsRawTracerV2.class) {
                        if (installer != self) return;
                        try {
                            synchronized (CcsSerialDiscovery.STREAM_LOCK) {
                                attachAvailable(runtime, repeatMs, logging);
                            }
                        } catch (Exception error) {
                            reportAttachment("waiting reason=" + error, false);
                        }
                    }
                    try { Thread.sleep(Math.max(10L, retryMs)); }
                    catch (InterruptedException ignored) {
                        synchronized (CcsRawTracerV2.class) {
                            if (installer != self) return;
                        }
                    }
                }
            }
        }, "qc45-ccs-raw-attach");
        installer.setDaemon(true);
        installer.start();
    }

    private static void attachAvailable(RuntimeAccess runtime, long repeatMs, boolean logging)
            throws Exception {
        Object central = runtime.central();
        if (central == null) throw new IllegalStateException("CentralModule unavailable");
        CcsSerialDiscovery.Reader[] readers = runtime.readers();
        CcsSerialDiscovery.Selection selection = CcsSerialDiscovery.select(central, readers);
        Object channel = selection.channel;
        Object reader = selection.reader == null ? null : selection.reader.inputOwner;
        String port = CcsSerialDiscovery.port(channel);
        String serializer = CcsSerialDiscovery.serializer(channel);

        // TX and RX can become available independently, or be replaced by EVCSD.
        if (txOwner != channel || !txAttached()) restoreTx();
        if (rxOwner != reader || !rxAttached()) restoreRx();
        String errors = "";
        try {
            if (channel != null && txWrapper == null) hookTx(channel, port, serializer, repeatMs, logging);
        } catch (Exception error) { errors += " txError=" + error; }
        try {
            if (reader != null && rxWrapper == null) hookRx(reader, port, serializer, repeatMs, logging);
        } catch (Exception error) { errors += " rxError=" + error; }

        boolean tx = txAttached();
        boolean rx = rxAttached();
        String status = (tx && rx ? "installed" : "waiting")
            + " txStreams=" + (tx ? 1 : 0) + " rxStreams=" + (rx ? 1 : 0)
            + " port=" + port + " serializer=" + serializer
            + " selection=" + selection.reason + " readers=" + readers.length
            + " loaded=" + CcsSerialDiscovery.value(channel, "loaded")
            + " logging=" + logging + " liveTelemetry=" + rx + errors;
        reportAttachment(status, tx && rx);
    }

    private static boolean txAttached() throws Exception {
        return txWrapper != null && txField.get(txOwner) == txWrapper;
    }

    private static boolean rxAttached() throws Exception {
        if (rxWrapper == null) return false;
        Object input = rxField.get(rxOwner);
        return input == rxWrapper || CcsFullRxTracer.wraps(input, rxWrapper);
    }

    private static void reportAttachment(String status, boolean ready) {
        long now = System.currentTimeMillis();
        if (!status.equals(lastAttachmentStatus) || (!ready && now - lastAttachmentLogMs >= 30000L)) {
            System.out.println(PREFIX + status);
            lastAttachmentStatus = status;
            lastAttachmentLogMs = now;
        }
    }

    private static void hookTx(Object channel, String port, String serializer, long repeatMs, boolean logging)
            throws Exception {
        Field outField = findField(channel.getClass(), "out");
        if (outField == null) return;
        outField.setAccessible(true);
        Object outValue = outField.get(channel);
        if (!(outValue instanceof OutputStream)) return;
        TraceOutputStream wrapper = new TraceOutputStream(
            (OutputStream)outValue, port, serializer, repeatMs, logging);
        outField.set(channel, wrapper);
        txOwner = channel;
        txField = outField;
        txOriginal = (OutputStream)outValue;
        txWrapper = wrapper;
        System.out.println(PREFIX + "TX hooked field=out port=" + port);
    }

    private static void hookRx(Object reader, String port, String serializer, long repeatMs, boolean logging)
            throws Exception {
        Field inField = findField(reader.getClass(), "in");
        if (inField == null) return;
        inField.setAccessible(true);
        Object inValue = inField.get(reader);
        if (!(inValue instanceof InputStream)) return;
        TraceInputStream wrapper = new TraceInputStream(
            (InputStream)inValue, port, serializer, repeatMs, logging);
        inField.set(reader, wrapper);
        rxOwner = reader;
        rxField = inField;
        rxWrapper = wrapper;
        System.out.println(PREFIX + "RX hooked field=in port=" + port);
    }

    static void restoreFull(Object stream, InputStream full, InputStream original) {
        if (stream instanceof TraceInputStream && ((TraceInputStream)stream).source() == full) {
            ((TraceInputStream)stream).replaceSource(original);
        }
    }

    static boolean wraps(Object stream, InputStream full) {
        return stream instanceof TraceInputStream && ((TraceInputStream)stream).source() == full;
    }

    static void observeLiveRx(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;
        // Connection gating and live telemetry share the validated outer-master
        // and inner QuickCharge CRC parser, including split serial reads.
        CcsTraceSession.CURRENT.observeRx(data, offset, length, System.currentTimeMillis());
    }

    static void observeValidatedStatus(byte[] status, long now) {
        int flags = status[1] & 255;
        int soc = status[2] & 255;
        int voltage = (status[9] & 255) | ((status[10] & 255) << 8);
        int current = status[11] & 255;
        if ((soc > 100 && soc != 255) || voltage > 1000 || current > 250
                || (voltage < 100 && current > 0)) return;
        boolean charging = (flags & 0x10) != 0;
        synchronized (LIVE_LOCK) {
            integrateTo(now);
            boolean newSession = charging && (liveSessionStartMs <= 0L
                || liveLastChargingMs <= 0L || now - liveLastChargingMs > 10000L);
            if (newSession) {
                liveSessionStartMs = now;
                liveEnergyWh = 0.0d;
                liveLastIntegrateMs = now;
            }
            liveSocPct = soc <= 100 ? soc : 0;
            livePowerW = charging ? voltage * current : 0;
            liveLastRxMs = now;
            if (charging) liveLastChargingMs = now;
            liveLastIntegrateMs = now;
        }
    }

    private static void integrateTo(long now) {
        if (liveLastIntegrateMs <= 0L) {
            liveLastIntegrateMs = now;
            return;
        }
        long dt = now - liveLastIntegrateMs;
        if (dt <= 0L) return;
        // Ignore long gaps (restart, debugger, stalled serial path) instead of
        // inventing energy for time where no live telemetry was observed.
        if (dt <= 5000L && livePowerW > 0 && liveLastChargingMs > 0L
                && now - liveLastChargingMs <= 10000L) {
            liveEnergyWh += ((double)livePowerW * (double)dt) / 3600000.0d;
        }
        liveLastIntegrateMs = now;
    }

    private static final class TraceOutputStream extends FilterOutputStream {
        private final TraceState state;
        TraceOutputStream(OutputStream out, String port, String serializer, long repeatMs, boolean logging) {
            super(out); state = new TraceState("TX", port, serializer, repeatMs, logging);
        }
        public void write(int b) throws IOException { out.write(b); byte[] x={(byte)b}; state.observe(x,0,1); }
        public void write(byte[] b) throws IOException { out.write(b); state.observe(b,0,b.length); }
        public void write(byte[] b,int o,int l) throws IOException { out.write(b,o,l); state.observe(b,o,l); }
    }

    private static final class TraceInputStream extends FilterInputStream {
        private final TraceState state;
        TraceInputStream(InputStream in, String port, String serializer, long repeatMs, boolean logging) {
            super(in); state = new TraceState("RX", port, serializer, repeatMs, logging);
        }
        InputStream source() { return in; }
        void replaceSource(InputStream source) { in = source; }
        public int read() throws IOException { int v=in.read(); if(v>=0){byte[] x={(byte)v};state.observe(x,0,1);} return v; }
        public int read(byte[] b) throws IOException { int n=in.read(b); if(n>0)state.observe(b,0,n); return n; }
        public int read(byte[] b,int o,int l) throws IOException { int n=in.read(b,o,l); if(n>0)state.observe(b,o,n); return n; }
    }

    private static final class TraceState {
        private final CcsTraceSession.LogBuffer logBuffer = new CcsTraceSession.LogBuffer();
        private final String dir, port, serializer;
        private final long repeatMs;
        private final boolean logging;
        private boolean active = true;
        private String last="";
        private long lastMs;
        TraceState(String d,String p,String s,long r,boolean log){dir=d;port=p;serializer=s;repeatMs=Math.max(100L,r);logging=log;}
        synchronized void disable() { active = false; logBuffer.clear(); }
        synchronized void observe(byte[] b,int o,int l){
            if(!active||b==null||l<=0)return;
            long now=System.currentTimeMillis();
            if("RX".equals(dir)) {
                observeLiveRx(b,o,l);
            }
            if(!logging)return;
            String h=hex(b,o,l);
            boolean relevant=h.indexOf("63")>=0;
            if(!relevant)return;
            if(h.equals(last)&&now-lastMs<repeatMs)return;
            last=h;lastMs=now;
            logBuffer.write(PREFIX+dir+" t="+now+" port="+port+" serializer="+serializer+" bytes="+l+" raw="+h,now);
        }
    }

    private static Field findField(Class<?> type,String name){Class<?> t=type;while(t!=null){try{return t.getDeclaredField(name);}catch(NoSuchFieldException e){t=t.getSuperclass();}}return null;}
    private static String hex(byte[] b,int o,int l){StringBuilder s=new StringBuilder(l*3);for(int i=o;i<o+l;i++){if(s.length()>0)s.append(' ');int v=b[i]&255;s.append("0123456789ABCDEF".charAt((v>>>4)&15)).append("0123456789ABCDEF".charAt(v&15));}return s.toString();}
    private static int integer(Properties p,String k,int f){String v=p.getProperty(k);return v==null||v.trim().length()==0?f:Integer.parseInt(v.trim());}
    private static boolean bool(Properties p,String k,boolean f){String v=p.getProperty(k);return v==null||v.trim().length()==0?f:Boolean.parseBoolean(v.trim());}
}

