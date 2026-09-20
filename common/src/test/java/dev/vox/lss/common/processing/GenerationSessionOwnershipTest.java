package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** WI-2: real admission and lossless mailbox; only platform IO/extraction is replaced. */
public class GenerationSessionOwnershipTest {
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
        protected boolean submitDiskRead(UUID id,RequestRegistration registration,String dim,int x,int z,long order,long stamp){submitted.incrementAndGet();return true;}
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
        reader.registerPlayer(id,old.registration()); Processor processor=new Processor(players,reader);
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
            var oldOutcome=new TickSnapshot.GenerationReadyData(id,old.registration(),7,9,DIM,
                    new LoadedColumnData(7,9,new byte[]{1,2},2),LSSConstants.epochSeconds(),ticket.get().submissionOrder());
            processor.postSnapshot(snapshot(id),List.of(oldOutcome));
            // Production quit/re-register ordering; lossless mailbox still owns oldOutcome.
            players.remove(id);processor.notifyPlayerRemoved(id,old.registration());reader.removePlayerResults(id);
            State fresh=new State(id);players.put(id,fresh);reader.registerPlayer(id,fresh.registration());
            processor.postSnapshot(snapshot(id),List.of());
            int before=processor.routeCyclesForTest();release.countDown();
            await(()->processor.routeCyclesForTest()>=before+2);
            assertAll(
                ()->assertFalse(processor.delivered.contains(fresh),"old buffered generation must not be delivered to replacement state"),
                ()->assertFalse(fresh.hasDiskReadDone(7,9),"teardown must not erase old generation taint then mark replacement done")
            );
        }finally{release.countDown();processor.shutdown();reader.shutdown();}
    }

    static void cycle(Processor processor, UUID id) throws Exception {
        int before = processor.routeCyclesForTest();
        processor.postSnapshot(snapshot(id), List.of());
        await(() -> processor.routeCyclesForTest() > before);
    }

    static OffThreadProcessor.GenerationTicketRequest admit(Processor processor, Reader reader,
                                                             State state) throws Exception {
        state.ask();
        cycle(processor, state.getPlayerUUID());
        reader.getPlayerQueue(state.getPlayerUUID()).add(ChunkReadResult.notFoundAuthoritative(
                state.getPlayerUUID(), 7, 9, DIM, 1));
        cycle(processor, state.getPlayerUUID());
        var ticket = processor.pollGenerationTicketRequest();
        assertNotNull(ticket, "real miss must admit generation");
        assertSame(state.registration(), ticket.registration());
        return ticket;
    }

    /** An old removal/outcome must not consume a replacement's same-position pending or taint. */
    @Test
    void retiredSuccessAndFailuresPreserveFreshGenerationTracking() throws Exception {
        for (int flavor = 0; flavor < 4; flavor++) {
            UUID id = UUID.randomUUID();
            Reader reader = new Reader();
            Map<UUID, State> players = new ConcurrentHashMap<>();
            State old = new State(id);
            players.put(id, old);
            reader.registerPlayer(id, old.registration());
            Processor processor = new Processor(players, reader);
            var deposits = new AtomicInteger();
            processor.attachStore(new dev.vox.lss.common.store.LodStoreService() {
                final dev.vox.lss.common.store.LodStoreDiagnostics diag =
                        new dev.vox.lss.common.store.LodStoreDiagnostics();
                public dev.vox.lss.common.store.LodStoreMode mode() { return dev.vox.lss.common.store.LodStoreMode.FULL; }
                public StoreHit get(String dimension, long packed) { return null; }
                public boolean deposit(String d, long p, byte[] b, long ts, long acq) { deposits.incrementAndGet(); return true; }
                public void invalidate(String d, long[] p) {}
                public void delete(String d, long p) {}
                public dev.vox.lss.common.store.LodStoreDiagnostics diagnostics() { return diag; }
                public void shutdown() {}
            });
            try {
                processor.start();
                var oldTicket = admit(processor, reader, old);
                old.registration().retire();
                reader.removePlayerResults(id, old.registration());
                State fresh = new State(id);
                players.put(id, fresh);
                reader.registerPlayer(id, fresh.registration());
                var freshTicket = admit(processor, reader, fresh);
                // Delay the OLD removal until fresh work exists. Its scope must be identity,
                // not UUID; otherwise it deletes fresh generation tracking and dirty taint.
                processor.invalidateTimestamps(DIM, new long[]{PositionUtil.packPosition(7,9)});
                processor.notifyPlayerRemoved(id, old.registration());
                cycle(processor, id);
                LoadedColumnData oldData = flavor == 0 ? new LoadedColumnData(7,9,new byte[]{1},1)
                        : flavor == 1 ? new LoadedColumnData(7,9,new byte[0],0) : null;
                var oldOutcome = new TickSnapshot.GenerationReadyData(id, old.registration(),7,9,DIM,
                        oldData,LSSConstants.epochSeconds(),oldTicket.submissionOrder(),flavor == 3,false);
                processor.postSnapshot(snapshot(id), List.of(oldOutcome));
                cycle(processor, id);
                assertEquals(1, fresh.getHeldGenSlots(), "old outcome must not remove fresh pending, flavor=" + flavor);
                assertTrue(processor.delivered.isEmpty(), "no obsolete bytes delivered");
                assertEquals(0, deposits.get(), "no obsolete store deposit");
                assertFalse(fresh.hasDiskReadDone(7,9));
                List<Byte> answers = new ArrayList<>();
                processor.drainSendActions((state, types, positions, count) -> {
                    for (int i=0;i<count;i++) answers.add(types[i]);
                });
                assertTrue(answers.isEmpty(), "old failure must not produce a fresh-session response");
                var freshOutcome = new TickSnapshot.GenerationReadyData(id, fresh.registration(),7,9,DIM,
                        new LoadedColumnData(7,9,new byte[]{2},1),LSSConstants.epochSeconds(),freshTicket.submissionOrder());
                processor.postSnapshot(snapshot(id), List.of(freshOutcome));
                await(() -> processor.delivered.contains(fresh));
                cycle(processor,id);
                assertEquals(0, fresh.getHeldGenSlots(), "fresh outcome frees its own pending");
                assertFalse(fresh.hasDiskReadDone(7,9), "fresh outcome's dirty taint survived old removal/outcome");
                assertEquals(0, deposits.get(), "tainted fresh bytes must not enter the store");
            } finally { processor.shutdown(); reader.shutdown(); }
        }
    }
}
