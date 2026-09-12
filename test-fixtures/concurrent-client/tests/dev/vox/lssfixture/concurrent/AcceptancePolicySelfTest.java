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
        require(decide(current,1,true,120,130)==AcceptancePolicy.Decision.OBSERVE); // Controlled initial memory-source probe remains exact.
        require(decide(current,0,false,120,130)==AcceptancePolicy.Decision.OBSERVE);
        require(decide(current,0,true,120,130)==AcceptancePolicy.Decision.COMMIT);
        require(decide(current,0,true,90,130)==AcceptancePolicy.Decision.OBSERVE); // Actual pre-apply wire.
        require(decide(current,0,true,220,230)==AcceptancePolicy.Decision.IGNORE); // B2820: expired same gold.
        require(decide(current,0,true,120,200)==AcceptancePolicy.Decision.IGNORE); // End is exclusive.
        require(decide(new AcceptancePolicy.Facts(0,80,0,0,0),0,true,120,130)==AcceptancePolicy.Decision.AWAIT_APPLICATION);
        require(decide(new AcceptancePolicy.Facts(0,80,100,0,150),0,true,120,160)==AcceptancePolicy.Decision.IGNORE);
        require(AcceptancePolicy.decide(current,false,true,120,130,1,false)==AcceptancePolicy.Decision.IGNORE);
        require(AcceptancePolicy.decide(current,true,false,120,130,1,false)==AcceptancePolicy.Decision.IGNORE);
        var measured=new AcceptancePolicy.Facts(0,80,100,200,0,true);
        for(int source:new int[]{0,1,3}) {
            require(decide(measured,source,true,120,130)==AcceptancePolicy.Decision.COMMIT);
            require(decide(measured,source,false,120,130)==AcceptancePolicy.Decision.OBSERVE);
            require(decide(measured,source,true,90,130)==AcceptancePolicy.Decision.OBSERVE);
            require(decide(measured,source,true,120,200)==AcceptancePolicy.Decision.IGNORE);
            require(AcceptancePolicy.decide(measured,true,false,120,130,source,true)==AcceptancePolicy.Decision.IGNORE);
        }
        for(int source:new int[]{-1,2,4})require(decide(measured,source,true,120,130)==AcceptancePolicy.Decision.OBSERVE);
        for(int expected:new int[]{0,1,2,3})for(int source:new int[]{0,1,2,3})
            require(decide(new AcceptancePolicy.Facts(expected,80,100,200,0),source,true,120,130)
                    ==(source==expected?AcceptancePolicy.Decision.COMMIT:AcceptancePolicy.Decision.OBSERVE));
        // Real decoded predecessor/pre-apply/wrong-route observations cannot earn target credit.
        AtomicInteger commits=new AtomicInteger();
        for(int i=0;i<8;i++)require(decide(measured,1,false,120,130)==AcceptancePolicy.Decision.OBSERVE);
        require(decide(measured,1,true,90,130)==AcceptancePolicy.Decision.OBSERVE);
        require(commits.get()==0);
        if(decide(measured,1,true,140,150)==AcceptancePolicy.Decision.COMMIT)commits.incrementAndGet();
        require(commits.get()==1);
        require(decide(measured,1,true,140,139)==AcceptancePolicy.Decision.FAILURE);
        // Refreshing the oracle after a hold rejects old data even when the later revision repeats its block.
        var beforeHold=new AcceptancePolicy.Facts(0,80,100,0,0);
        require(decide(beforeHold,0,true,120,130)==AcceptancePolicy.Decision.COMMIT);
        require(decide(current,0,true,120,230)==AcceptancePolicy.Decision.IGNORE);
        // A lagging immutable snapshot alone cannot certify current revision. The unchanged full-journal
        // checker must reject this optimistic match once the real end=200 is observed after collection.
        require(decide(beforeHold,0,true,220,230)==AcceptancePolicy.Decision.COMMIT);
        System.out.println("AcceptancePolicy: source/block, original interval, pre-apply, unknown application, authority, passive observation and deferred lifetime controls passed; stale-snapshot boundary remains final-checker gated");
    }
}
