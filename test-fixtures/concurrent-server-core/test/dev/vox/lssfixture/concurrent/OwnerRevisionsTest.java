package dev.vox.lssfixture.concurrent;
import java.util.concurrent.atomic.AtomicInteger;
public final class OwnerRevisionsTest {
    public static void main(String[] args){
        var ledger=new OwnerRevisions();var target=new SourceWorkload.Target("RigSubjectA",12,0,64,0,"diamond_block");var writes=new AtomicInteger();
        var first=new SourceWorkload.Mutation("first","minecraft:overworld","run:overworld:1",1,null);
        var result=ledger.mutate(target,first,"native-region-7",()->{writes.incrementAndGet();return System.nanoTime();});
        if(result.revision()!=1||result.predecessor()!=null||!result.ownerRegion().equals("native-region-7"))throw new AssertionError();
        try{ledger.mutate(target,first,"native-region-7",()->{writes.incrementAndGet();return System.nanoTime();});throw new AssertionError("duplicate owner revision accepted");}catch(IllegalStateException expected){}
        if(writes.get()!=1)throw new AssertionError("rejected revision mutated cell");
        var second=new SourceWorkload.Mutation("second","minecraft:overworld","run:overworld:1",2,"first");
        result=ledger.mutate(target,second,"native-region-7",()->{writes.incrementAndGet();return System.nanoTime();});
        if(result.revision()!=2||!result.predecessor().equals("first")||writes.get()!=2)throw new AssertionError();
        var other=new SourceWorkload.Target("RigSubjectA",13,0,64,0,"diamond_block");
        if(ledger.mutate(other,first,"native-region-7",System::nanoTime).revision()!=1)throw new AssertionError("distinct cell shared revision");
        System.out.println("OwnerRevisions: native mutation order, predecessor, no-write rejection and distinct cells passed");
    }
}
