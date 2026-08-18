package de.rothner.qc45;

/**
 * Independent grid-current failback.
 *
 * Stage 1: if any phase stays above reduceA long enough, force QC45 DC/AC budgets down.
 * Stage 2: if any phase stays above tripA long enough, or exceeds instantTripA, stop all connectors and latch.
 * KSEM communication loss is handled differently: set DC and AC limits to 0 kW,
 * keep the charging transactions alive, and automatically resume control after
 * stable meter readings return.
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
    private volatile boolean running=true,tripped,meterPaused;
    private long reduceSince,tripSince,lastGoodRead,lastLog;
    private int goodReadsAfterMeterPause;

    public GridFailback(ReflectionQC45 station,KsemClient meter,double reduceA,long reduceDelayMs,double tripA,long tripDelayMs,double instantTripA,int reduceDcKw,int reduceAcKw,int intervalMs,boolean tripOnMeterFailure,long meterFailureMs){
        super("QC45-Grid-Failback");setDaemon(true);this.station=station;this.meter=meter;this.reduceA=reduceA;this.reduceDelayMs=reduceDelayMs;this.tripA=tripA;this.tripDelayMs=tripDelayMs;this.instantTripA=instantTripA;this.reduceDcKw=reduceDcKw;this.reduceAcKw=reduceAcKw;this.intervalMs=intervalMs;this.tripOnMeterFailure=tripOnMeterFailure;this.meterFailureMs=meterFailureMs;
    }
    public boolean isTripped(){return tripped;}
    public boolean isMeterPaused(){return meterPaused;}
    public void shutdown(){running=false;interrupt();}
    public void run(){lastGoodRead=System.currentTimeMillis();System.out.println("[QC45] GridFailback started");while(running){long now=System.currentTimeMillis();try{KsemClient.Currents c=meter.readCurrents();lastGoodRead=now;double max=c.max();if(now-lastLog>=5000L||max>=reduceA||tripped||meterPaused){System.out.println("[QC45] Grid L1="+one(c.l1)+"A L2="+one(c.l2)+"A L3="+one(c.l3)+"A max="+one(max)+"A"+(tripped?" TRIPPED":meterPaused?" METER-PAUSED":""));lastLog=now;}if(tripped){}else if(meterPaused){if(max<reduceA){goodReadsAfterMeterPause++;if(goodReadsAfterMeterPause>=5)clearMeterPause();}else goodReadsAfterMeterPause=0;}else evaluate(now,max);}catch(Throwable e){goodReadsAfterMeterPause=0;if(now-lastLog>=5000L){System.err.println("[QC45] GridFailback KSEM read failed: "+e);lastLog=now;}if(!tripped&&!meterPaused&&tripOnMeterFailure&&now-lastGoodRead>=meterFailureMs)pauseForMeterFailure(now-lastGoodRead);}try{Thread.sleep(intervalMs);}catch(InterruptedException e){if(!running)break;}}System.out.println("[QC45] GridFailback stopped");}
    private void evaluate(long now,double max)throws Exception{if(max>=instantTripA){hardTrip("instant phase current "+one(max)+"A >= "+one(instantTripA)+"A");return;}if(max>=tripA){if(tripSince==0L)tripSince=now;if(now-tripSince>=tripDelayMs){hardTrip("phase current "+one(max)+"A >= "+one(tripA)+"A for "+(now-tripSince)+"ms");return;}}else tripSince=0L;if(max>=reduceA){if(reduceSince==0L)reduceSince=now;if(now-reduceSince>=reduceDelayMs)forceMinimum();}else reduceSince=0L;}
    private void forceMinimum()throws Exception{station.setDcBudgetKw(reduceDcKw);station.setAcBudgetKw(reduceAcKw);}
    private synchronized void pauseForMeterFailure(long outageMs){if(tripped||meterPaused)return;meterPaused=true;goodReadsAfterMeterPause=0;System.err.println("[QC45] GRID FAILBACK METER PAUSE: KSEM communication lost for "+outageMs+"ms -> DC=0kW AC=0kW, transactions remain active");try{station.setDcBudgetKw(0);}catch(Throwable e){System.err.println("[QC45] meter-pause DC=0 failed: "+e);}try{station.setAcBudgetKw(0);}catch(Throwable e){System.err.println("[QC45] meter-pause AC=0 failed: "+e);}}
    private synchronized void clearMeterPause(){if(!meterPaused||tripped)return;meterPaused=false;goodReadsAfterMeterPause=0;reduceSince=0L;tripSince=0L;System.out.println("[QC45] GRID FAILBACK METER RECOVERED: KSEM stable, LoadManager may ramp charging again");}
    private synchronized void hardTrip(String reason){if(tripped)return;tripped=true;meterPaused=false;System.err.println("[QC45] GRID FAILBACK TRIP: "+reason+" [latched]");enforceHardTripOnce();}
    private void enforceHardTripOnce(){try{station.setDcBudgetKw(reduceDcKw);}catch(Throwable e){System.err.println("[QC45] failback DC reduction failed: "+e);}try{station.setAcBudgetKw(reduceAcKw);}catch(Throwable e){System.err.println("[QC45] failback AC reduction failed: "+e);}for(int connector=1;connector<=3;connector++){try{station.remoteStop(connector);}catch(Throwable e){}}}
    private static String one(double value){return String.format(java.util.Locale.US,"%.1f",Double.valueOf(value));}
}
