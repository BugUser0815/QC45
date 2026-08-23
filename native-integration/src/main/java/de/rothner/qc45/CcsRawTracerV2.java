package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;
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

    private CcsRawTracerV2() {}

    public static void installFromDefaultConfig() throws Exception {
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
        install(integer(p, "evcsd.ccsRawTrace.repeatMs", 1000), logging);
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

    private static void install(long repeatMs, boolean logging) throws Exception {
        Class<?> centralType = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object central = centralType.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable");

        Field communicationsField = findField(central.getClass(), "communications");
        if (communicationsField == null) throw new NoSuchFieldException("CentralModule.communications");
        communicationsField.setAccessible(true);
        Object channel = communicationsField.get(central);
        if (channel == null) throw new IllegalStateException("CentralModule.communications is null");

        String port = stringField(channel, "portName", "physical-master");
        String serializer = objectClassField(channel, "pSerializer");
        int tx = hookTx(channel, port, serializer, repeatMs, logging);
        int rx = hookRx(channel, port, serializer, repeatMs, logging);

        System.out.println(PREFIX + "installed txStreams=" + tx + " rxStreams=" + rx
            + " port=" + port + " serializer=" + serializer + " repeatMs=" + repeatMs
            + " logging=" + logging + " liveTelemetry=true");
    }

    private static int hookTx(Object channel, String port, String serializer, long repeatMs, boolean logging) {
        try {
            Field outField = findField(channel.getClass(), "out");
            Object outValue = null;
            if (outField != null) {
                outField.setAccessible(true);
                outValue = outField.get(channel);
            }
            if (logging) {
                System.out.println(PREFIX + "TX inspect outField=" + (outField != null)
                    + " outValue=" + className(outValue)
                    + " loaded=" + fieldValue(channel, "loaded")
                    + " portObject=" + className(fieldObject(channel, "port")));
            }

            if (outValue instanceof OutputStream && !(outValue instanceof TraceOutputStream)) {
                outField.set(channel, new TraceOutputStream((OutputStream)outValue, port, serializer, repeatMs, logging));
                if (logging) System.out.println(PREFIX + "TX hooked field=out");
                return 1;
            }
        } catch (Throwable e) {
            System.err.println(PREFIX + "TX hook failed: " + e);
        }
        return 0;
    }

    private static int hookRx(Object channel, String port, String serializer, long repeatMs, boolean logging) {
        try {
            Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
            Field targetField = findField(Thread.class, "target");
            if (targetField != null) targetField.setAccessible(true);

            Object byOwner = null;
            Object byName = null;
            StringBuilder seen = new StringBuilder();
            for (Thread thread : threads.keySet()) {
                if (thread == null) continue;
                String name = thread.getName();
                if (name == null || name.indexOf("SerialReader") < 0) continue;
                if (seen.length() > 0) seen.append(',');
                seen.append(name);

                Object target = null;
                try { if (targetField != null) target = targetField.get(thread); } catch (Throwable ignored) {}
                if (target == null || target.getClass().getName().indexOf("SerialReader") < 0) continue;

                Field ownerField = findField(target.getClass(), "owner");
                if (ownerField != null) {
                    ownerField.setAccessible(true);
                    Object owner = ownerField.get(target);
                    if (owner == channel) byOwner = target;
                }
                if (name.indexOf(port) >= 0) byName = target;
            }

            Object reader = byOwner != null ? byOwner : byName;
            if (logging) {
                System.out.println(PREFIX + "RX inspect threads=" + seen
                    + " matchedByOwner=" + (byOwner != null) + " matchedByName=" + (byName != null)
                    + " target=" + className(reader));
            }
            if (reader == null) return 0;

            Field inField = findField(reader.getClass(), "in");
            if (inField == null) return 0;
            inField.setAccessible(true);
            Object inValue = inField.get(reader);
            if (logging) System.out.println(PREFIX + "RX input=" + className(inValue));
            if (inValue instanceof InputStream && !(inValue instanceof TraceInputStream)) {
                inField.set(reader, new TraceInputStream((InputStream)inValue, port, serializer, repeatMs, logging));
                if (logging) System.out.println(PREFIX + "RX hooked field=in");
                return 1;
            }
        } catch (Throwable e) {
            System.err.println(PREFIX + "RX hook failed: " + e);
        }
        return 0;
    }

    private static void observeLiveRx(byte[] b, int o, int l) {
        if (b == null || l <= 0) return;
        int end = o + l;
        for (int p = o; p < end; p++) {
            if ((b[p] & 0xff) != 0x63) continue;
            if (end - p < 12) continue;

            int flags = b[p + 1] & 0xff;
            int soc = b[p + 2] & 0xff;
            int voltage = (b[p + 9] & 0xff) | ((b[p + 10] & 0xff) << 8);
            int current = b[p + 11] & 0xff;

            // Reject obvious false 0x63 matches in an outer/master frame.
            if (soc > 100 || voltage < 100 || voltage > 1000 || current > 250) continue;

            boolean charging = (flags & 0x10) != 0;
            int powerW = voltage * current;
            long now = System.currentTimeMillis();

            synchronized (LIVE_LOCK) {
                integrateTo(now);

                boolean newSession = charging &&
                    (liveSessionStartMs <= 0L || liveLastChargingMs <= 0L || now - liveLastChargingMs > 10000L);
                if (newSession) {
                    liveSessionStartMs = now;
                    liveEnergyWh = 0.0d;
                    liveLastIntegrateMs = now;
                }

                liveSocPct = soc;
                livePowerW = charging ? powerW : 0;
                liveLastRxMs = now;
                if (charging) liveLastChargingMs = now;
                liveLastIntegrateMs = now;
            }
            return;
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
        public int read() throws IOException { int v=in.read(); if(v>=0){byte[] x={(byte)v};state.observe(x,0,1);} return v; }
        public int read(byte[] b) throws IOException { int n=in.read(b); if(n>0)state.observe(b,0,n); return n; }
        public int read(byte[] b,int o,int l) throws IOException { int n=in.read(b,o,l); if(n>0)state.observe(b,o,n); return n; }
    }

    private static final class TraceState {
        private final String dir, port, serializer;
        private final long repeatMs;
        private final boolean logging;
        private String last="";
        private long lastMs;
        TraceState(String d,String p,String s,long r,boolean log){dir=d;port=p;serializer=s;repeatMs=Math.max(100L,r);logging=log;}
        synchronized void observe(byte[] b,int o,int l){
            if(b==null||l<=0)return;
            if("RX".equals(dir)) observeLiveRx(b,o,l);
            if(!logging)return;
            String h=hex(b,o,l);
            long now=System.currentTimeMillis();
            boolean relevant=h.indexOf("63")>=0;
            if(!relevant)return;
            if(h.equals(last)&&now-lastMs<repeatMs)return;
            last=h;lastMs=now;
            System.out.println(PREFIX+dir+" t="+now+" port="+port+" serializer="+serializer+" bytes="+l+" raw="+h);
        }
    }

    private static Object fieldObject(Object owner,String name){try{Field f=findField(owner.getClass(),name);if(f==null)return null;f.setAccessible(true);return f.get(owner);}catch(Throwable e){return null;}}
    private static String fieldValue(Object owner,String name){Object v=fieldObject(owner,name);return String.valueOf(v);}
    private static String objectClassField(Object owner,String name){return className(fieldObject(owner,name));}
    private static String className(Object o){return o==null?"null":o.getClass().getName();}
    private static String stringField(Object owner,String name,String fallback){Object v=fieldObject(owner,name);return v==null?fallback:String.valueOf(v);}
    private static Field findField(Class<?> type,String name){Class<?> t=type;while(t!=null){try{return t.getDeclaredField(name);}catch(NoSuchFieldException e){t=t.getSuperclass();}}return null;}
    private static String hex(byte[] b,int o,int l){StringBuilder s=new StringBuilder(l*3);for(int i=o;i<o+l;i++){if(s.length()>0)s.append(' ');int v=b[i]&255;s.append("0123456789ABCDEF".charAt((v>>>4)&15)).append("0123456789ABCDEF".charAt(v&15));}return s.toString();}
    private static int integer(Properties p,String k,int f){String v=p.getProperty(k);return v==null||v.trim().length()==0?f:Integer.parseInt(v.trim());}
    private static boolean bool(Properties p,String k,boolean f){String v=p.getProperty(k);return v==null||v.trim().length()==0?f:Boolean.parseBoolean(v.trim());}
}
