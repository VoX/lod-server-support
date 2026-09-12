package dev.vox.lssfixture.concurrent;

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
}
