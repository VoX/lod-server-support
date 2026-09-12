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
