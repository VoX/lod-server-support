package dev.vox.lssfixture.concurrent;
import java.util.*;
import java.util.function.LongSupplier;
/** Bounded fixture revision ledger updated together with the actual owner mutation. */
public final class OwnerRevisions {
    private record Cell(String dimension,String generation,int x,int z,int y){}
    private record Revision(String id,long value){}
    private final Map<Cell,Revision> cells=new HashMap<>();
    public synchronized SourceWorkload.Applied mutate(SourceWorkload.Target target,SourceWorkload.Mutation mutation,String ownerRegion,LongSupplier operation){
        Cell cell=new Cell(mutation.dimension(),mutation.worldGeneration(),target.x(),target.z(),target.y());
        Revision previous=cells.get(cell);long expected=previous==null?1:previous.value()+1;
        if(mutation.revision()!=expected||!Objects.equals(mutation.predecessor(),previous==null?null:previous.id()))throw new IllegalStateException("native owner revision/predecessor mismatch");
        if(previous==null&&cells.size()>=2048)throw new IllegalStateException("native owner cell ledger bound");
        if(ownerRegion==null||ownerRegion.isBlank())throw new IllegalStateException("native mutation owner absent");
        long before=System.nanoTime(),at=operation.getAsLong();
        if(at<before||at>System.nanoTime())throw new IllegalStateException("native mutation timestamp invalid");
        cells.put(cell,new Revision(mutation.id(),expected));
        return new SourceWorkload.Applied(at,expected,mutation.predecessor(),ownerRegion);
    }
}
