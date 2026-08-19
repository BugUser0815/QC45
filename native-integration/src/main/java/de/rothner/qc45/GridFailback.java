package de.rothner.qc45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

/**
 * Independent grid-current failback.
 *
 * Stage 1: if any phase stays above reduceA long enough, force QC45 DC/AC budgets down.
 * Stage 2: if any phase stays above tripA long enough, or exceeds instantTripA, stop all connectors and latch.
 * KSEM communication loss sets DC/AC to 0 kW while keeping transactions alive.
 *
 * A hard-trip latch can be reset locally with a deliberate emergency-stop cycle:
 * after the trip the emergency stop must first become active and then inactive.
 */
public final class GridFailback extends Thread {
    private final ReflectionQC45 station;
    private final KsemClient meter;
    private final double reduceA;
    private final long reduceDelayMs;
    private final double tripA;
    private final long tripDelayMs;
    private final double instantTripA;
    private final int reduceDcKw;
    private final int reduceAcKw;
    private final int intervalMs;
    private final boolean tripOnMeterFailure;
    private final long meterFailureMs;
    private final EmergencyStopProbe emergencyStopProbe = new EmergencyStopProbe();

    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile boolean meterPaused;
    private boolean emergencySeenWhileTripped;
    private Boolean lastEmergencyState;
    private String lastBooleanSnapshot;
    private long reduceSince;
    private long tripSince;
    private long lastGoodRead;
    private long lastLog;
    private long lastEmergencyProbeLog;
    private int goodReadsAfterMeterPause;

    public GridFailback(ReflectionQC45 station,KsemClient meter,double reduceA,long reduceDelayMs,
                        double tripA,long tripDelayMs,double instantTripA,int reduceDcKw,int reduceAcKw,
                        int intervalMs,boolean tripOnMeterFailure,long meterFailureMs) {
        super("QC45-Grid-Failback");
        setDaemon(true);
        this.station=station;this.meter=meter;this.reduceA=reduceA;this.reduceDelayMs=reduceDelayMs;
        this.tripA=tripA;this.tripDelayMs=tripDelayMs;this.instantTripA=instantTripA;
        this.reduceDcKw=reduceDcKw;this.reduceAcKw=reduceAcKw;this.intervalMs=intervalMs;
        this.tripOnMeterFailure=tripOnMeterFailure;this.meterFailureMs=meterFailureMs;
    }

    public boolean isTripped(){return tripped;}
    public boolean isMeterPaused(){return meterPaused;}
    public void shutdown(){running=false;interrupt();}

    public void run(){
        lastGoodRead=System.currentTimeMillis();
        System.out.println("[QC45] GridFailback started emergency-reset=press-and-release boolean-probe=v3-epo");
        while(running){
            long now=System.currentTimeMillis();
            try{
                KsemClient.Currents c=meter.readCurrents();
                lastGoodRead=now;
                double max=c.max();
                if(now-lastLog>=5000L||max>=reduceA||tripped||meterPaused){
                    System.out.println("[QC45] Grid L1="+one(c.l1)+"A L2="+one(c.l2)+"A L3="+one(c.l3)
                        +"A max="+one(max)+"A"+(tripped?" TRIPPED":meterPaused?" METER-PAUSED":""));
                    lastLog=now;
                }
                if(tripped){
                    evaluateEmergencyReset(now);
                }else if(meterPaused){
                    if(max<reduceA){goodReadsAfterMeterPause++;if(goodReadsAfterMeterPause>=5)clearMeterPause();}
                    else goodReadsAfterMeterPause=0;
                }else evaluate(now,max);
            }catch(Throwable e){
                goodReadsAfterMeterPause=0;
                if(now-lastLog>=5000L){System.err.println("[QC45] GridFailback KSEM read failed: "+e);lastLog=now;}
                if(!tripped&&!meterPaused&&tripOnMeterFailure&&now-lastGoodRead>=meterFailureMs)
                    pauseForMeterFailure(now-lastGoodRead);
                if(tripped){
                    try{evaluateEmergencyReset(now);}catch(Throwable probeError){
                        if(now-lastEmergencyProbeLog>=5000L){
                            System.err.println("[QC45] GRID FAILBACK RESET-PROBE failed: "+probeError);
                            lastEmergencyProbeLog=now;
                        }
                    }
                }
            }
            try{Thread.sleep(intervalMs);}catch(InterruptedException e){if(!running)break;}
        }
        System.out.println("[QC45] GridFailback stopped");
    }

    private void evaluate(long now,double max)throws Exception{
        if(max>=instantTripA){hardTrip("instant phase current "+one(max)+"A >= "+one(instantTripA)+"A");return;}
        if(max>=tripA){
            if(tripSince==0L)tripSince=now;
            if(now-tripSince>=tripDelayMs){hardTrip("phase current "+one(max)+"A >= "+one(tripA)+"A for "+(now-tripSince)+"ms");return;}
        }else tripSince=0L;
        if(max>=reduceA){if(reduceSince==0L)reduceSince=now;if(now-reduceSince>=reduceDelayMs)forceMinimum();}
        else reduceSince=0L;
    }

    private void forceMinimum()throws Exception{station.setDcBudgetKw(reduceDcKw);station.setAcBudgetKw(reduceAcKw);}

    private synchronized void pauseForMeterFailure(long outageMs){
        if(tripped||meterPaused)return;
        meterPaused=true;goodReadsAfterMeterPause=0;
        System.err.println("[QC45] GRID FAILBACK METER PAUSE: KSEM communication lost for "+outageMs
            +"ms -> DC=0kW AC=0kW, transactions remain active");
        try{station.setDcBudgetKw(0);}catch(Throwable e){System.err.println("[QC45] meter-pause DC=0 failed: "+e);}
        try{station.setAcBudgetKw(0);}catch(Throwable e){System.err.println("[QC45] meter-pause AC=0 failed: "+e);}
    }

    private synchronized void clearMeterPause(){
        if(!meterPaused||tripped)return;
        meterPaused=false;goodReadsAfterMeterPause=0;reduceSince=0L;tripSince=0L;
        System.out.println("[QC45] GRID FAILBACK METER RECOVERED: KSEM stable, LoadManager may ramp charging again");
    }

    private synchronized void hardTrip(String reason){
        if(tripped)return;
        tripped=true;meterPaused=false;emergencySeenWhileTripped=false;lastEmergencyState=null;
        lastBooleanSnapshot=null;lastEmergencyProbeLog=0L;
        System.err.println("[QC45] GRID FAILBACK TRIP: "+reason
            +" [latched; reset requires emergency-stop press + release]");
        enforceHardTripOnce();
    }

    private void evaluateEmergencyReset(long now)throws Exception{
        EmergencyStopProbe.Result result=emergencyStopProbe.read();

        if(lastBooleanSnapshot==null||!lastBooleanSnapshot.equals(result.booleanSnapshot)){
            System.out.println("[QC45] GRID FAILBACK EVCSD-BOOLEAN-CHANGE "+result.booleanSnapshot);
            lastBooleanSnapshot=result.booleanSnapshot;
        }

        if(lastEmergencyState==null||lastEmergencyState.booleanValue()!=result.active){
            System.out.println("[QC45] GRID FAILBACK RESET-PROBE emergencyActive="+result.active
                +" candidates="+result.emergencyDescription);
            lastEmergencyState=Boolean.valueOf(result.active);lastEmergencyProbeLog=now;
        }else if(now-lastEmergencyProbeLog>=5000L){
            System.out.println("[QC45] GRID FAILBACK RESET-PROBE emergencyActive="+result.active
                +" armed="+emergencySeenWhileTripped+" candidates="+result.emergencyDescription);
            lastEmergencyProbeLog=now;
        }

        if(result.active){
            if(!emergencySeenWhileTripped){
                emergencySeenWhileTripped=true;
                System.out.println("[QC45] GRID FAILBACK RESET ARMED: EPO pressed; release it to clear trip latch");
            }
            return;
        }
        if(emergencySeenWhileTripped)clearHardTripAfterEmergencyCycle();
    }

    private synchronized void clearHardTripAfterEmergencyCycle(){
        if(!tripped||!emergencySeenWhileTripped)return;
        tripped=false;emergencySeenWhileTripped=false;lastEmergencyState=Boolean.FALSE;
        reduceSince=0L;tripSince=0L;goodReadsAfterMeterPause=0;
        System.out.println("[QC45] GRID FAILBACK RESET: EPO press/release cycle completed; latch cleared");
    }

    private void enforceHardTripOnce(){
        try{station.setDcBudgetKw(reduceDcKw);}catch(Throwable e){System.err.println("[QC45] failback DC reduction failed: "+e);}
        try{station.setAcBudgetKw(reduceAcKw);}catch(Throwable e){System.err.println("[QC45] failback AC reduction failed: "+e);}
        for(int connector=1;connector<=3;connector++){try{station.remoteStop(connector);}catch(Throwable e){}}
    }

    private static String one(double value){return String.format(java.util.Locale.US,"%.1f",Double.valueOf(value));}

    /** Read-only probe. It never calls commands such as send...Stop(). */
    private static final class EmergencyStopProbe{
        Result read()throws Exception{
            Class<?> centralClass=Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
            Object cm=centralClass.getMethod("getCurrentModule").invoke(null);
            if(cm==null)throw new IllegalStateException("CentralModule unavailable");

            ProbeAccumulator acc=new ProbeAccumulator();
            inspectObject("CentralModule",cm,acc);
            try{Object conf=centralClass.getMethod("getConf").invoke(cm);if(conf!=null)inspectObject("Configuration",conf,acc);}catch(Throwable ignored){}
            try{
                Object value=centralClass.getMethod("getSatellites").invoke(cm);
                if(value instanceof Object[]){
                    Object[] sats=(Object[])value;
                    for(int i=0;i<sats.length;i++){
                        if(sats[i]==null)continue;
                        int id=i;
                        try{id=((Number)sats[i].getClass().getMethod("getSatelliteId").invoke(sats[i])).intValue();}catch(Throwable ignored){}
                        inspectObject("Satellite"+id,sats[i],acc);
                    }
                }
            }catch(Throwable ignored){}

            String emergency=acc.emergency.length()==0?"none-found":acc.emergency.toString();
            String snapshot=acc.states.isEmpty()?"none":mapString(acc.states);
            return new Result(acc.active,emergency,snapshot);
        }

        private static void inspectObject(String label,Object target,ProbeAccumulator acc){
            Class<?> type=target.getClass();

            Method[] methods=type.getMethods();
            for(int i=0;i<methods.length;i++){
                Method m=methods[i];
                Class<?> rt=m.getReturnType();
                if(m.getParameterTypes().length!=0)continue;
                if(rt!=Boolean.TYPE&&rt!=Boolean.class)continue;
                if(!getterName(m.getName()))continue;
                try{
                    Object value=m.invoke(target);
                    addBoolean(acc,label+"."+m.getName()+"()",Boolean.TRUE.equals(value),candidateName(m.getName()));
                }catch(Throwable ignored){}
            }

            Class<?> t=type;
            while(t!=null){
                Field[] fields=t.getDeclaredFields();
                for(int i=0;i<fields.length;i++){
                    Field f=fields[i];
                    if(Modifier.isStatic(f.getModifiers()))continue;
                    if(f.getType()!=Boolean.TYPE&&f.getType()!=Boolean.class)continue;
                    try{
                        f.setAccessible(true);
                        Object value=f.get(target);
                        addBoolean(acc,label+"."+f.getName(),Boolean.TRUE.equals(value),candidateName(f.getName()));
                    }catch(Throwable ignored){}
                }
                t=t.getSuperclass();
            }
        }

        private static void addBoolean(ProbeAccumulator acc,String name,boolean value,boolean emergencyCandidate){
            acc.states.put(name,Boolean.valueOf(value));
            if(emergencyCandidate){
                if(acc.emergency.length()>0)acc.emergency.append(",");
                acc.emergency.append(name).append("=").append(value);
                if(value)acc.active=true;
            }
        }

        private static boolean getterName(String name){
            return name.startsWith("is")||name.startsWith("get")||name.startsWith("has")||name.startsWith("can");
        }

        private static boolean candidateName(String name){
            if(name==null)return false;
            String n=name.toLowerCase(java.util.Locale.US);
            return n.indexOf("emergency")>=0||n.indexOf("estop")>=0||n.indexOf("e_stop")>=0
                ||n.indexOf("notaus")>=0||n.indexOf("epo")>=0||n.indexOf("panic")>=0
                ||n.indexOf("stopbutton")>=0||n.indexOf("stop_button")>=0;
        }

        private static String mapString(Map<String,Boolean> states){
            StringBuilder b=new StringBuilder();
            for(Map.Entry<String,Boolean> e:states.entrySet()){
                if(b.length()>0)b.append(",");
                b.append(e.getKey()).append("=").append(e.getValue().booleanValue());
            }
            return b.toString();
        }

        static final class Result{
            final boolean active;final String emergencyDescription;final String booleanSnapshot;
            Result(boolean active,String emergencyDescription,String booleanSnapshot){
                this.active=active;this.emergencyDescription=emergencyDescription;this.booleanSnapshot=booleanSnapshot;
            }
        }
        private static final class ProbeAccumulator{
            boolean active;
            final StringBuilder emergency=new StringBuilder();
            final TreeMap<String,Boolean> states=new TreeMap<String,Boolean>();
        }
    }
}
