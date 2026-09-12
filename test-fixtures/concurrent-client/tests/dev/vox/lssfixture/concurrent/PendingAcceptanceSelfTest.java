package dev.vox.lssfixture.concurrent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual held-entry state controls; no fabricated native acceptance proof. */
public final class PendingAcceptanceSelfTest {
    private static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        AtomicInteger evaluated=new AtomicInteger(),released=new AtomicInteger(),failed=new AtomicInteger();
        long originalDeadline=80+120_000_000_000L;
        PendingAcceptance waiting=new PendingAcceptance(()->true,()->evaluated.incrementAndGet()>1,()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(!waiting.poll(2_000_000_000L,false));require(released.get()==0&&failed.get()==0); // No invented1s deadline.
        require(waiting.poll(3_000_000_000L,false));waiting.cancel();waiting.reject();
        require(evaluated.get()==2&&released.get()==1&&failed.get()==0);

        released.set(0);failed.set(0);evaluated.set(0);
        PendingAcceptance timeout=new PendingAcceptance(()->true,()->{evaluated.incrementAndGet();return false;},()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(timeout.poll(originalDeadline+1,false));timeout.reject();timeout.cancel();
        require(evaluated.get()==1&&released.get()==1&&failed.get()==1);

        released.set(0);failed.set(0);evaluated.set(0);
        PendingAcceptance stall=new PendingAcceptance(()->true,()->{evaluated.incrementAndGet();return true;},()->Long.MAX_VALUE,released::incrementAndGet,failed::incrementAndGet);
        require(!stall.poll(20_000_000_000L,true));require(evaluated.get()==0&&released.get()==0&&failed.get()==0);
        require(stall.poll(20_000_000_001L,false));require(released.get()==1&&failed.get()==0);

        released.set(0);failed.set(0);
        AtomicBoolean active=new AtomicBoolean(true);
        PendingAcceptance retired=new PendingAcceptance(active::get,()->{throw new AssertionError("must not evaluate retired work");},()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        active.set(false);require(retired.poll(originalDeadline+1,false));retired.cancel();
        require(released.get()==1&&failed.get()==0); // In-flight retirement outranks timeout without queue cancellation.

        released.set(0);failed.set(0);
        AtomicBoolean originalCommittedElsewhere=new AtomicBoolean(false);
        PendingAcceptance superseded=new PendingAcceptance(()->!originalCommittedElsewhere.get(),()->false,()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(!superseded.poll(100,false));originalCommittedElsewhere.set(true);
        require(superseded.poll(originalDeadline+1,false));require(released.get()==1&&failed.get()==0);

        released.set(0);failed.set(0);
        PendingAcceptance error=new PendingAcceptance(()->true,()->{throw new IllegalStateException("original interval ended");},()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(error.poll(101,false));error.cancel();require(released.get()==1&&failed.get()==1);

        released.set(0);failed.set(0);
        PendingAcceptance overflow=new PendingAcceptance(()->true,()->true,()->Long.MAX_VALUE,released::incrementAndGet,failed::incrementAndGet);
        overflow.reject();overflow.reject();require(released.get()==1&&failed.get()==1);

        // Earliest original obligation is independently committed; only the later unknown remains.
        released.set(0);failed.set(0);
        final long[] outstandingDeadline={100};
        PendingAcceptance later=new PendingAcceptance(()->true,()->false,()->outstandingDeadline[0],released::incrementAndGet,failed::incrementAndGet);
        outstandingDeadline[0]=300;
        require(!later.poll(200,false));require(failed.get()==0&&released.get()==0);
        require(later.poll(301,false));require(failed.get()==1&&released.get()==1);

        // Unknown resolved elsewhere cannot skip evaluating a remaining known mismatch.
        released.set(0);failed.set(0);evaluated.set(0);
        PendingAcceptance mixed=new PendingAcceptance(()->true,()->{evaluated.incrementAndGet();return true;},()->Long.MAX_VALUE,released::incrementAndGet,failed::incrementAndGet);
        require(mixed.poll(originalDeadline+1,false));require(evaluated.get()==1&&released.get()==1&&failed.get()==0);

        AtomicInteger reports=new AtomicInteger();
        final AcceptancePolicy.Facts[] facts={new AcceptancePolicy.Facts(0,80,0,200,0,true)};
        PendingAcceptance delayed=new PendingAcceptance(()->true,()->{
            var decision=AcceptancePolicy.decide(facts[0],true,true,120,130,1,true);
            if(decision==AcceptancePolicy.Decision.AWAIT_APPLICATION)return false;
            if(decision==AcceptancePolicy.Decision.FAILURE)throw new AssertionError("invalid clock");
            require(decision==AcceptancePolicy.Decision.COMMIT);return true;
        },()->originalDeadline,()->{},()->{throw new AssertionError();});
        require(!delayed.poll(101,false));facts[0]=new AcceptancePolicy.Facts(0,80,100,200,0,true);
        require(delayed.poll(102,false));require(reports.get()==0);
        require(AcceptancePolicy.decide(new AcceptancePolicy.Facts(0,80,125,200,0,true),true,true,120,130,1,true)==AcceptancePolicy.Decision.OBSERVE);
        require(AcceptancePolicy.decide(facts[0],true,true,120,200,1,true)==AcceptancePolicy.Decision.IGNORE);
        System.out.println("PendingAcceptance: delayed original fact, actual preapply, original deadline, stall, retirement/supersession priority, error and rejection once passed");
    }
}
