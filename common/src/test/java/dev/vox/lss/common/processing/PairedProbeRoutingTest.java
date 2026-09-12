package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real processor/router validity branches, injected serializer/disk bytes; no native claim. */
class PairedProbeRoutingTest {
    private static final String DIM = "minecraft:overworld";
    private static final long KEY = PositionUtil.packPosition(5, 7);
    private static final class State extends AbstractPlayerRequestState<Object> {
        volatile boolean queueBlocked;
        State(UUID id) { super(id, 4, 4); markHandshakeComplete(); setCapabilities(1); setRegisteredDimension(DIM); requireProbeHandoff(); }
        @Override public String getPlayerName() { return "paired-routing"; }
        @Override public int getSendQueueSize() { return queueBlocked ? 1 : super.getSendQueueSize(); }
    }
    private static final class Reader extends AbstractChunkDiskReader { Reader() { super(1); } }
    private record Delivery(byte source, byte[] bytes) {}
    private static final class Processor extends OffThreadProcessor<State> {
        final Reader reader;
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        final CountDownLatch disk = new CountDownLatch(1);
        final AtomicReference<Runnable> beforeRoute = new AtomicReference<>();
        Processor(Map<UUID, State> players, Reader reader) { super(players, reader, false, null, 1, 0); this.reader = reader; }
        @Override protected void beforeRouteHook() { var action = beforeRoute.getAndSet(null); if (action != null) action.run(); }
        @Override protected boolean submitDiskRead(UUID id, RequestRegistration registration, String dim,
                int x, int z, long order, long clientTimestamp) {
            reader.getPlayerQueue(id, registration).add(new ChunkReadResult(id, x, z, new byte[]{9}, dim,
                    1 + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, LSSConstants.epochSeconds(), false, false, false, order));
            disk.countDown(); return true;
        }
        @Override protected boolean buildAndEnqueueColumnPayload(State state, int x, int z, String dim,
                long timestamp, long order, ColumnBytes bytes, int estimated, byte source) {
            deliveries.add(new Delivery(source, bytes.raw().clone())); return true;
        }
    }
    private static final class Rig implements AutoCloseable {
        final UUID id = UUID.randomUUID();
        final State state = new State(id);
        final Reader reader = new Reader();
        final Processor processor = new Processor(Map.of(id, state), reader);
        boolean started;
        Rig() { reader.registerPlayer(id, state.registration()); }
        LoadedColumnData captured(int marker) {
            return processor.captureLoadedProbe(DIM, KEY, state.registration()).bind(new LoadedColumnData(5, 7, new byte[]{(byte)marker}, 1));
        }
        void release(LoadedColumnData probe) {
            var batch = new IncomingBatch(new IncomingRequest[]{new IncomingRequest(5, 7, -1)});
            state.offerIncomingBatch(batch); long generation = state.offerGeneration();
            assertSame(batch, state.takeFreshIncomingBatchForProbe());
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>(); probes.put(KEY, probe);
            assertTrue(state.republishHeldBatch(batch, generation, probes));
        }
        TickSnapshot snapshot(LoadedColumnData probe, int queueLimit) {
            var map = new Long2ObjectOpenHashMap<LoadedColumnData>(); if (probe != null) map.put(KEY, probe);
            return new TickSnapshot(Map.of(id, DIM), Map.<UUID, Long2ObjectMap<LoadedColumnData>>of(id, map), queueLimit, false);
        }
        void start(TickSnapshot snapshot, List<TickSnapshot.GenerationReadyData> ready) {
            processor.postSnapshot(snapshot, ready); started = true; processor.start();
        }
        Delivery delivered() throws InterruptedException {
            var d = processor.deliveries.poll(10, TimeUnit.SECONDS); assertNotNull(d, "bounded real payload completion"); return d;
        }
        Delivery diskDelivered() throws InterruptedException {
            assertTrue(processor.disk.await(10, TimeUnit.SECONDS), "bounded real disk admission");
            processor.postSnapshot(snapshot(null, 0), List.of()); return delivered();
        }
        TickSnapshot.GenerationReadyData transientOutcome() {
            return new TickSnapshot.GenerationReadyData(id, state.registration(), 5, 7, DIM, null, LSSConstants.epochSeconds(), 99, true);
        }
        @Override public void close() {
            try { if (!started) { started = true; processor.start(); } processor.shutdown(); }
            finally { reader.shutdown(); }
        }
    }
    @Test void validPairRemainsUsableWhenSnapshotProbeWasInvalidated() throws Exception {
        try (var r = new Rig()) {
            var staleSnapshot = r.captured(1);
            r.processor.invalidateTimestamps(DIM, new long[]{KEY}, null);
            r.release(r.captured(7)); r.start(r.snapshot(staleSnapshot, 0), List.of());
            var d = r.delivered(); assertEquals(0, d.source()); assertArrayEquals(new byte[]{7}, d.bytes());
            assertEquals(1, r.processor.disk.getCount());
        }
    }
    @Test void invalidatedPairCannotServeLoadedData() throws Exception {
        try (var r = new Rig()) {
            var stalePair = r.captured(1);
            r.processor.invalidateTimestamps(DIM, new long[]{KEY}, null);
            r.release(stalePair); r.start(r.snapshot(null, 0), List.of());
            var d = r.diskDelivered(); assertEquals(1, d.source()); assertArrayEquals(new byte[]{9}, d.bytes());
        }
    }
    @Test void validNewerSnapshotIsPreferredOverValidPairedProbe() throws Exception {
        try (var r = new Rig()) {
            r.release(r.captured(3)); var newer = r.captured(7);
            r.start(r.snapshot(newer, 0), List.of());
            var d = r.delivered(); assertEquals(0, d.source()); assertArrayEquals(new byte[]{7}, d.bytes());
        }
    }
    @Test void realGenerationOutcomeExcludesPendingReleasedProbe() throws Exception {
        try (var r = new Rig()) {
            r.release(r.captured(7)); r.start(r.snapshot(null, 0), List.of(r.transientOutcome()));
            var d = r.diskDelivered(); assertEquals(1, d.source()); assertArrayEquals(new byte[]{9}, d.bytes());
        }
    }
    @Test void realGenerationOutcomeExcludesProbeRetainedByPreviousRouterPass() throws Exception {
        try (var r = new Rig()) {
            r.release(r.captured(7)); r.state.queueBlocked = true;
            var restoredBacklog = new AtomicInteger(-1);
            var pairCleared = new AtomicBoolean();
            r.processor.beforeRoute.set(() -> {
                r.processor.beforeRoute.set(() -> {
                    restoredBacklog.set(r.state.getBacklogSize());
                    pairCleared.set(r.state.pairedLoadedProbe(KEY) == null);
                    r.state.queueBlocked = false;
                });
                // Next cycle is already queued; first cycle must finish its queue-full
                // retain/restore before this actual generation event and second route hook.
                r.processor.postSnapshot(r.snapshot(null, 0), List.of(r.transientOutcome()));
            });
            r.start(r.snapshot(null, 1), List.of());
            var d = r.diskDelivered();
            assertEquals(1, restoredBacklog.get()); assertTrue(pairCleared.get());
            assertEquals(1, d.source()); assertArrayEquals(new byte[]{9}, d.bytes());
        }
    }
}
