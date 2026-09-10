package dev.vox.lssfixture.concurrent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure policy/lifetime controls; no mocked native proof or invented application time. */
public final class AcceptancePolicySelfTest {
    private static void require(boolean value){if(!value)throw new AssertionError();}
    private static AcceptancePolicy.Decision decide(AcceptancePolicy.Facts facts,int source,boolean block,long received,long resolved){
        return AcceptancePolicy.decide(facts,true,true,received,resolved,source,block);
    }
    public static void main(String[] args){
        var current=new AcceptancePolicy.Facts(0,80,100,200,0);
        require(decide(current,1,true,120,130)==AcceptancePolicy.Decision.RETRY); // D257: disk source, correct block.
        require(decide(current,0,false,120,130)==AcceptancePolicy.Decision.RETRY);
        require(decide(current,0,true,120,130)==AcceptancePolicy.Decision.COMMIT);
        require(decide(current,0,true,90,130)==AcceptancePolicy.Decision.RETRY); // Actual pre-apply wire.
        require(decide(current,0,true,220,230)==AcceptancePolicy.Decision.IGNORE); // B2820: expired same gold.
        require(decide(current,0,true,120,200)==AcceptancePolicy.Decision.IGNORE); // End is exclusive.
        require(decide(new AcceptancePolicy.Facts(0,80,0,0,0),0,true,120,130)==AcceptancePolicy.Decision.RETRY);
        require(decide(new AcceptancePolicy.Facts(0,80,100,0,150),0,true,120,160)==AcceptancePolicy.Decision.IGNORE);
        require(AcceptancePolicy.decide(current,false,true,120,130,1,false)==AcceptancePolicy.Decision.IGNORE);
        require(AcceptancePolicy.decide(current,true,false,120,130,1,false)==AcceptancePolicy.Decision.IGNORE);
        AtomicInteger reports=new AtomicInteger();var noTarget=new AcceptancePolicy.RetryOnce();
        require(!noTarget.finish(()->true,reports::incrementAndGet));require(reports.get()==0);
        var mismatch=new AcceptancePolicy.RetryOnce();mismatch.require();mismatch.require();
        require(mismatch.finish(()->true,reports::incrementAndGet));require(!mismatch.finish(()->true,reports::incrementAndGet));require(reports.get()==1);
        AtomicBoolean active=new AtomicBoolean(true);var deferred=new AcceptancePolicy.RetryOnce();deferred.require();
        active.set(false);require(!deferred.finish(active::get,reports::incrementAndGet));require(reports.get()==1);
        // Refreshing the oracle after a hold rejects old data even when the later revision repeats its block.
        var beforeHold=new AcceptancePolicy.Facts(0,80,100,0,0);
        require(decide(beforeHold,0,true,120,130)==AcceptancePolicy.Decision.COMMIT);
        require(decide(current,0,true,120,230)==AcceptancePolicy.Decision.IGNORE);
        // A lagging immutable snapshot alone cannot certify current revision. The unchanged full-journal
        // checker must reject this optimistic match once the real end=200 is observed after collection.
        require(decide(beforeHold,0,true,220,230)==AcceptancePolicy.Decision.COMMIT);
        System.out.println("AcceptancePolicy: source/block, original interval, pre-apply, unknown application, authority, once-only retry and deferred lifetime controls passed; stale-snapshot boundary remains final-checker gated");
    }
}
