package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** External review regressions; no repository changes or game server required. */
public class SessionOwnershipReviewTest {
    static final String DIM = "minecraft:overworld";
    static final long POS = PositionUtil.packPosition(7, 9);
    static final class State extends AbstractPlayerRequestState<Object> {
        State(UUID uuid) {
            super(uuid, 4, 4);
            setRegisteredDimension(DIM);
            setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            markHandshakeComplete();
        }
        public String getPlayerName() { return "review"; }
        void ask(long stamp) { offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{new IncomingRequest(7,9,stamp)})); }
    }
    static final class Reader extends AbstractChunkDiskReader {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        Reader() { super(1); }
        void submit(UUID player, long order, long timestamp) {
            submitRead(player, 7, 9, DIM, order, timestamp, () -> {
                int n = reads.incrementAndGet();
                if (n == 1) {
                    entered.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("review barrier timed out");
                }
                return new byte[]{(byte)n}; // read 1 captured pre-edit bytes; read 2 reads the saved edit
            });
        }
    }
    static final class Processor extends OffThreadProcessor<State> {
        final Reader reader;
        final ConcurrentLinkedQueue<Integer> delivered = new ConcurrentLinkedQueue<>();
        final AtomicInteger cycles = new AtomicInteger();
        Processor(Map<UUID,State> players, Reader reader) { super(players, reader, true, null, 1, 0); this.reader=reader; }
        protected boolean submitDiskRead(UUID p, String d, int x, int z, long order, long ts) { reader.submit(p,order,ts); return true; }
        protected boolean buildAndEnqueueColumnPayload(State state,int x,int z,String d,long ts,long order,ColumnBytes bytes,int estimated,byte source) {
            delivered.add((int)bytes.raw()[0]); return true;
        }
        protected void beforeRouteHook() { cycles.incrementAndGet(); }
    }
    static void await(BooleanSupplier ok) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (!ok.getAsBoolean()) { if(System.nanoTime()>end) fail("review barrier timed out"); Thread.sleep(1); }
    }
    static TickSnapshot snap(UUID player) { return new TickSnapshot(Map.of(player,DIM),Map.of(),0,false); }
    static void cycle(Processor p,UUID player) throws Exception {
        int before=p.routeCyclesForTest(); p.postSnapshot(snap(player),List.of()); await(()->p.routeCyclesForTest()>before);
    }
    @Test
    void oldReadMustNotEnterAReRegisteredPlayersQueue() throws Exception {
        Reader reader=new Reader(); UUID uuid=UUID.randomUUID();
        try {
            reader.registerPlayer(uuid); reader.submit(uuid,1,0);
            assertTrue(reader.entered.await(30,TimeUnit.SECONDS));
            var oldQueue=reader.getPlayerQueue(uuid);
            reader.removePlayerResults(uuid); reader.registerPlayer(uuid);
            assertNotSame(oldQueue,reader.getPlayerQueue(uuid));
            reader.release.countDown();
            // A second result acts as an ordered completion fence on the single reader worker.
            UUID fence=UUID.randomUUID(); reader.registerPlayer(fence); reader.submit(fence,2,0);
            await(()->!reader.getPlayerQueue(fence).isEmpty());
            assertTrue(reader.getPlayerQueue(uuid).isEmpty(),
                    "read captured for removed session must not be appended to replacement queue");
        } finally { reader.release.countDown(); reader.shutdown(); }
    }
    @Test
    void reconnectMustNotEraseAnOldReadsDirtyTaintAndSealItsPreEditBytes() throws Exception {
        Reader reader=new Reader(); UUID uuid=UUID.randomUUID();
        Map<UUID,State> players=new ConcurrentHashMap<>(); State oldState=new State(uuid); players.put(uuid,oldState);
        reader.registerPlayer(uuid); Processor p=new Processor(players,reader);
        try {
            p.start(); oldState.ask(0); p.postSnapshot(snap(uuid),List.of());
            assertTrue(reader.entered.await(30,TimeUnit.SECONDS));
            // A real save overtook the old read; production marks its dedup group stale.
            p.invalidateTimestamps(DIM,new long[]{POS}); cycle(p,uuid);
            // Exact service teardown/register ordering, same dimension on reconnect.
            players.remove(uuid); p.notifyPlayerRemoved(uuid); reader.removePlayerResults(uuid);
            State fresh=new State(uuid); players.put(uuid,fresh); reader.registerPlayer(uuid);
            cycle(p,uuid); // removes old group and consumes its dirty taint
            reader.release.countDown();
            UUID fence=UUID.randomUUID(); reader.registerPlayer(fence); reader.submit(fence,2,0);
            await(()->!reader.getPlayerQueue(fence).isEmpty());
            // Warm client's declaration is real, with pre-edit data. It must resolve honestly.
            fresh.ask(LSSConstants.epochSeconds()-60); cycle(p,uuid);
            List<Byte> responses=new ArrayList<>();
            p.drainSendActions((s,t,positions,n)->{for(int i=0;i<n;i++)responses.add(t[i]);});
            assertAll(
                () -> assertFalse(fresh.hasDiskReadDone(7,9),
                    "old read's taint was discarded at teardown; it must not mark replacement state done"),
                () -> assertFalse(responses.contains(LSSConstants.RESPONSE_UP_TO_DATE),
                    "pre-edit bytes from removed session must not seal a replacement client's warm request as up_to_date"),
                () -> assertFalse(p.delivered.contains(1),
                    "pre-edit byte1 from removed session must not be delivered into replacement session")
            );
        } finally { reader.release.countDown(); p.shutdown(); reader.shutdown(); }
    }
}
