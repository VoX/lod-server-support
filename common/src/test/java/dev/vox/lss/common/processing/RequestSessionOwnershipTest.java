package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** WI-2: real-worker admission/retirement regressions; original review methods were RED. */
public class RequestSessionOwnershipTest {
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
        void submit(UUID player, RequestRegistration registration, long order, long timestamp) {
            submitRead(player, registration, 7, 9, DIM, order, timestamp, () -> {
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
        protected boolean submitDiskRead(UUID p, RequestRegistration registration, String d, int x, int z, long order, long ts) { reader.submit(p,registration,order,ts); return true; }
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
            var old = new RequestRegistration();
            reader.registerPlayer(uuid, old); reader.submit(uuid,old,1,0);
            assertTrue(reader.entered.await(30,TimeUnit.SECONDS));
            var oldQueue=reader.getPlayerQueue(uuid);
            reader.removePlayerResults(uuid); reader.registerPlayer(uuid);
            assertNotSame(oldQueue,reader.getPlayerQueue(uuid));
            reader.release.countDown();
            // A second result acts as an ordered completion fence on the single reader worker.
            UUID fence=UUID.randomUUID(); var fenceRegistration = new RequestRegistration();
            reader.registerPlayer(fence, fenceRegistration); reader.submit(fence,fenceRegistration,2,0);
            await(()->!reader.getPlayerQueue(fence).isEmpty());
            assertTrue(reader.getPlayerQueue(uuid).isEmpty(),
                    "read captured for removed session must not be appended to replacement queue");
        } finally { reader.release.countDown(); reader.shutdown(); }
    }
    @Test
    void reconnectMustNotEraseAnOldReadsDirtyTaintAndSealItsPreEditBytes() throws Exception {
        Reader reader=new Reader(); UUID uuid=UUID.randomUUID();
        Map<UUID,State> players=new ConcurrentHashMap<>(); State oldState=new State(uuid); players.put(uuid,oldState);
        reader.registerPlayer(uuid, oldState.registration()); Processor p=new Processor(players,reader);
        try {
            p.start(); oldState.ask(0); p.postSnapshot(snap(uuid),List.of());
            assertTrue(reader.entered.await(30,TimeUnit.SECONDS));
            // A real save overtook the old read; production marks its dedup group stale.
            p.invalidateTimestamps(DIM,new long[]{POS}); cycle(p,uuid);
            // Exact service teardown/register ordering, same dimension on reconnect.
            players.remove(uuid); p.notifyPlayerRemoved(uuid, oldState.registration()); reader.removePlayerResults(uuid);
            State fresh=new State(uuid); players.put(uuid,fresh); reader.registerPlayer(uuid, fresh.registration());
            cycle(p,uuid); // removes old group and consumes its dirty taint
            reader.release.countDown();
            UUID fence=UUID.randomUUID(); var fenceRegistration = new RequestRegistration();
            reader.registerPlayer(fence, fenceRegistration); reader.submit(fence,fenceRegistration,2,0);
            await(()->!reader.getPlayerQueue(fence).isEmpty());
            // Warm client's declaration is real, with pre-edit data. It must resolve honestly.
            // First inspect the retired completion before admitting fresh work: a valid
            // second read may complete immediately and legitimately set its own done-bit.
            cycle(p,uuid);
            assertFalse(fresh.hasDiskReadDone(7,9));
            fresh.ask(LSSConstants.epochSeconds()-60); cycle(p,uuid);
            List<Byte> responses=new ArrayList<>();
            p.drainSendActions((s,t,positions,n)->{for(int i=0;i<n;i++)responses.add(t[i]);});
            assertAll(
                () -> assertFalse(responses.contains(LSSConstants.RESPONSE_UP_TO_DATE),
                    "pre-edit bytes from removed session must not seal a replacement client's warm request as up_to_date"),
                () -> assertFalse(p.delivered.contains(1),
                    "pre-edit byte1 from removed session must not be delivered into replacement session")
            );
            await(() -> p.delivered.contains(3) || !reader.getPlayerQueue(uuid).isEmpty());
            cycle(p,uuid);
            assertTrue(p.delivered.contains(3), "replacement request converges on its own read");
        } finally { reader.release.countDown(); p.shutdown(); reader.shutdown(); }
    }

    /** Every terminal result, including an all-air success or an exception, owns its sink. */
    @Test
    void terminalReadFlavorsCannotCrossRetirement() throws Exception {
        for (int flavor = 0; flavor < 5; flavor++) {
            final int terminal = flavor;
            class TerminalReader extends AbstractChunkDiskReader {
                TerminalReader() { super(1); }
                void submit(UUID id, RequestRegistration registration, ReadOperation operation) {
                    submitRead(id, registration, 7, 9, DIM, 1, 0, operation);
                }
            }
            var reader = new TerminalReader();
            UUID id = UUID.randomUUID();
            var old = new RequestRegistration();
            var fresh = new RequestRegistration();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                reader.registerPlayer(id, old);
                reader.submit(id, old, () -> {
                    entered.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                    return switch (terminal) {
                        case 0 -> new byte[]{1};
                        case 1 -> new byte[0];
                        case 2 -> null;
                        case 3 -> throw new IllegalStateException("injected read failure");
                        default -> throw new TimeoutException("injected timeout");
                    };
                });
                assertTrue(entered.await(30, TimeUnit.SECONDS));
                reader.removePlayerResults(id, old);
                reader.registerPlayer(id, fresh);
                release.countDown();
                reader.submit(id, fresh, () -> new byte[]{9});
                await(() -> reader.getDiag().getCompletedCount() == 2
                        && !reader.getPlayerQueue(id).isEmpty());
                var queue = reader.getPlayerQueue(id);
                assertEquals(1, queue.size(), "only fresh result, terminal=" + terminal);
                assertArrayEquals(new byte[]{9}, queue.remove().sectionBytes());
                assertEquals(2, reader.getDiag().getSubmittedCount(), "real read bookkeeping balances");
            } finally { release.countDown(); reader.shutdown(); }
        }
    }

    @Test
    void parkedReadKeepsItsOriginalSinkAndReleasesItsPermit() throws Exception {
        class ParkReader extends AbstractChunkDiskReader {
            ParkReader() { super(2); configureReadGate(1); }
            void submit(UUID id, RequestRegistration registration, int x, ReadOperation op) {
                submitRead(id, registration, x, 9, DIM, x, 0, op);
            }
        }
        var reader = new ParkReader();
        UUID id = UUID.randomUUID();
        var old = new RequestRegistration();
        var fresh = new RequestRegistration();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            reader.registerPlayer(id, old);
            reader.submit(id, old, 1, () -> {
                entered.countDown();
                if (!release.await(30,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                return new byte[]{1};
            });
            assertTrue(entered.await(30,TimeUnit.SECONDS));
            reader.submit(id, old, 2, () -> new byte[]{2});
            await(() -> reader.getDiagnostics().contains("gate_parked=1"));
            reader.removePlayerResults(id, old);
            reader.registerPlayer(id, fresh);
            release.countDown();
            reader.submit(id, fresh, 3, () -> new byte[]{9});
            await(() -> reader.getDiag().getCompletedCount() == 3 && !reader.getPlayerQueue(id).isEmpty());
            assertEquals(1, reader.getPlayerQueue(id).size(), "retired parked result stayed detached");
            assertArrayEquals(new byte[]{9}, reader.getPlayerQueue(id).remove().sectionBytes());
            await(() -> reader.getDiagnostics().contains("gate_parked=0")
                    && reader.getDiagnostics().contains("read_gate=0/1"));
            assertTrue(reader.hasHeadroom(), "parked work did not leak pool capacity");
        } finally { release.countDown(); reader.shutdown(); }
    }
}
