package dev.vox.lssfixture.concurrent;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One bounded fixture hold. The caller owns the queue permit until release. */
final class PendingAcceptance {
    private final BooleanSupplier needed,evaluate;
    private final LongSupplier deadline;
    private final Runnable release,failure;
    private boolean finished;
    PendingAcceptance(BooleanSupplier needed,BooleanSupplier evaluate,LongSupplier deadline,Runnable release,Runnable failure) {
        this.needed=needed;this.evaluate=evaluate;this.deadline=deadline;this.release=release;this.failure=failure;
    }
    /** True means settled. False is requeued once for a later journal poll. */
    synchronized boolean poll(long now,boolean stalled) {
        if(finished)return true;
        try {
            // Native retirement or an independently committed original obligation outranks timeout.
            if(!needed.getAsBoolean()){finish();return true;}
            if(stalled){if(now>deadline.getAsLong()){fail();return true;}return false;}
            if(evaluate.getAsBoolean()){finish();return true;}
            if(now>deadline.getAsLong()){fail();return true;}
            return false;
        }catch(Throwable error){fail();return true;}
    }
    synchronized void cancel(){finish();}
    synchronized void reject(){fail();}
    private void fail(){if(finished)return;finished=true;try{failure.run();}finally{release.run();}}
    private void finish(){if(!finished){finished=true;try{release.run();}catch(Throwable error){failure.run();}}}


    /** One sparse receipt-triggered fault. No timer renewal or new demand. */
    static final class SparseFault {
        private OracleJournal.SparseArm arm;
        private java.util.Map<String,Object> trigger;
        private long until;
        private boolean retired;
        synchronized void arm(OracleJournal.SparseArm value){
            if(value==null)return;
            if(arm!=null&&!arm.equals(value))throw new IllegalStateException("sparse fault rearmed");
            if(arm==null)arm=value;
        }
        synchronized boolean eligible(String connection,String target,long applied,long arrived,long now,long wire){
            return !retired&&arm!=null&&trigger==null&&arm.connection().equals(connection)&&arm.target().equals(target)
                    &&applied>0&&arrived>=applied&&wire>0&&now>=arm.armed()&&now<arm.deadline();
        }
        synchronized java.util.Map<String,Object> begin(String connection,String target,long applied,long arrived,long now,long body,long wire){
            if(body<=0||!eligible(connection,target,applied,arrived,now,wire))return null;
            var value=new java.util.HashMap<String,Object>(arm.fields());
            value.put("body_id",body);value.put("wire_capture_id",wire);value.put("start_ns",now);
            until=Math.addExact(now,arm.duration());value.put("end_ns",until);trigger=java.util.Map.copyOf(value);return trigger;
        }
        synchronized boolean stalled(long now){return !retired&&trigger!=null&&now<until;}
        synchronized boolean expired(long now){return !retired&&arm!=null&&trigger==null&&now>=arm.deadline();}
        synchronized void retire(){retired=true;}
        synchronized OracleJournal.SparseArm arm(){return arm;}
        synchronized java.util.Map<String,Object> trigger(){return trigger;}
    }

    /** Shared bounded admission; keeps no delivery objects after the call. */
    static final class Admission {
        enum Result { ADMITTED, REFUSED, REFUSAL_FAILED }
        private final Semaphore slots;
        private final AtomicLong refused=new AtomicLong(),reportErrors=new AtomicLong(),releaseErrors=new AtomicLong();
        Admission(Semaphore slots){this.slots=slots;}

        /** The caller already holds a deferred receipt lease. Refusal reports before releasing it.
         * The receipt itself suppresses duplicate reports and reports after native retirement. */
        Result acquireOrRefuse(Runnable report,Runnable release){
            if(slots.tryAcquire())return Result.ADMITTED;
            refused.incrementAndGet();
            boolean failed=false;
            try{report.run();}
            catch(Throwable failure){reportErrors.incrementAndGet();failed=true;}
            finally{
                try{release.run();}
                catch(Throwable failure){releaseErrors.incrementAndGet();failed=true;}
            }
            return failed?Result.REFUSAL_FAILED:Result.REFUSED;
        }
        long refusals(){return refused.get();}
        long reportErrors(){return reportErrors.get();}
        long releaseErrors(){return releaseErrors.get();}
    }
}
