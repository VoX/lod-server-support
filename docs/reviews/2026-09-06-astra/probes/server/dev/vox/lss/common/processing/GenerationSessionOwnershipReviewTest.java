package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** External regression: real admission and mailbox, with only platform IO/extraction replaced. */
public class GenerationSessionOwnershipReviewTest {
    static final String DIM="minecraft:overworld";
    static final class State extends AbstractPlayerRequestState<Object> {
        State(UUID id) { super(id,4,4); setRegisteredDimension(DIM);
            setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS); markHandshakeComplete(); }
        public String getPlayerName() { return "review-generation"; }
        void ask() { offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{new IncomingRequest(7,9,0)})); }
    }
    static final class Reader extends AbstractChunkDiskReader { Reader(){super(1);} }
    static final class Processor extends OffThreadProcessor<State> {
        final AtomicInteger submitted=new AtomicInteger();
        final AtomicReference<Runnable> hook=new AtomicReference<>();
        final ConcurrentLinkedQueue<State> delivered=new ConcurrentLinkedQueue<>();
        Processor(Map<UUID,State> players, Reader reader){super(players,reader,true,null,1,0);}
        protected boolean submitDiskRead(UUID id,String dim,int x,int z,long order,long stamp){submitted.incrementAndGet();return true;}
        protected boolean buildAndEnqueueColumnPayload(State state,int x,int z,String dim,long stamp,long order,ColumnBytes bytes,int estimated,byte source){delivered.add(state);return true;}
        protected void beforeRouteHook(){Runnable r=hook.getAndSet(null);if(r!=null)r.run();}
    }
    static TickSnapshot snapshot(UUID id){return new TickSnapshot(Map.of(id,DIM),Map.of(),0,false);}
    static void await(BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)fail("review premise timed out");Thread.sleep(1);}
    }
    @Test
    void bufferedGenerationMustNotLoseDirtyTaintAndSealReplacementSession()throws Exception{
        UUID id=UUID.randomUUID(); Reader reader=new Reader();
        Map<UUID,State> players=new ConcurrentHashMap<>(); State old=new State(id);players.put(id,old);
        reader.registerPlayer(id); Processor processor=new Processor(players,reader);
        CountDownLatch paused=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            processor.start(); old.ask();processor.postSnapshot(snapshot(id),List.of());
            await(()->processor.submitted.get()==1);
            reader.getPlayerQueue(id).add(ChunkReadResult.notFoundAuthoritative(id,7,9,DIM,1L));
            processor.postSnapshot(snapshot(id),List.of());
            AtomicReference<OffThreadProcessor.GenerationTicketRequest> ticket=new AtomicReference<>();
            await(()->{var next=processor.pollGenerationTicketRequest();if(next!=null)ticket.set(next);return ticket.get()!=null;});
            assertEquals(1,old.getHeldGenSlots(),"real disk miss admitted generation for the old session");
            processor.hook.set(()->{
                paused.countDown();
                try{if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException("release timed out");}
                catch(InterruptedException e){throw new RuntimeException(e);}
            });
            // Generation serialized before an edit. Apply the edit's real dirty taint,
            // then suspend this processing cycle while pump ticks continue publishing.
            processor.invalidateTimestamps(DIM,new long[]{PositionUtil.packPosition(7,9)});
            processor.postSnapshot(snapshot(id),List.of());
            assertTrue(paused.await(30,TimeUnit.SECONDS),"processor reached the controlled delay");
            var oldOutcome=new TickSnapshot.GenerationReadyData(id,7,9,DIM,
                    new LoadedColumnData(7,9,new byte[]{1,2},2),LSSConstants.epochSeconds(),ticket.get().submissionOrder());
            processor.postSnapshot(snapshot(id),List.of(oldOutcome));
            // Production quit/re-register ordering; lossless mailbox still owns oldOutcome.
            players.remove(id);processor.notifyPlayerRemoved(id);reader.removePlayerResults(id);
            State fresh=new State(id);players.put(id,fresh);reader.registerPlayer(id);
            processor.postSnapshot(snapshot(id),List.of());
            int before=processor.routeCyclesForTest();release.countDown();
            await(()->processor.routeCyclesForTest()>=before+2);
            assertAll(
                ()->assertFalse(processor.delivered.contains(fresh),"old buffered generation must not be delivered to replacement state"),
                ()->assertFalse(fresh.hasDiskReadDone(7,9),"teardown must not erase old generation taint then mark replacement done")
            );
        }finally{release.countDown();processor.shutdown();reader.shutdown();}
    }
}
