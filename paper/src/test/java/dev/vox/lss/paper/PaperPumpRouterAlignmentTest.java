package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.processing.*;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Paper pump, processor and router; native serialization/disk completion are injected
 * distinct markers. A passing current-marker assertion is NOT native serialization proof. */
class PaperPumpRouterAlignmentTest {
    private static final byte[] CURRENT = {7, 7, 7};
    private static final byte[] OLD_DISK = {1, 1, 1};
    private static final int X = 5, Z = 7;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "bounded ordering latch"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private record Delivery(byte source, byte[] bytes) {}

    private static final class Processor extends PaperOffThreadProcessor {
        final AtomicReference<Runnable> beforeRoute = new AtomicReference<>();
        final AtomicReference<Runnable> beforePost = new AtomicReference<>();
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        final CountDownLatch diskSubmitted = new CountDownLatch(1);
        final CountDownLatch routeDecided = new CountDownLatch(1);
        final PaperChunkDiskReader reader;
        volatile TickSnapshot lastPosted;
        boolean holdTargetDisk;
        boolean headerFreshResult;
        final AtomicReference<ChunkReadResult> heldTargetDisk = new AtomicReference<>();
        Processor(Map<UUID, PaperPlayerRequestState> players, PaperChunkDiskReader reader) {
            super(players, reader, false, null, 1, 0);
            this.reader = reader;
        }
        @Override protected void beforeRouteHook() {
            var action = beforeRoute.getAndSet(null);
            if (action != null) action.run();
        }
        @Override public void postSnapshot(TickSnapshot snapshot,
                List<TickSnapshot.GenerationReadyData> ready) {
            var action = beforePost.getAndSet(null);
            if (action != null) action.run();
            lastPosted = snapshot;
            super.postSnapshot(snapshot, ready);
        }
        @Override protected boolean submitDiskRead(UUID uuid, RequestRegistration registration,
                String dimension, int x, int z, long order, long clientTimestamp) {
            // Complete the admitted read through the ACTUAL per-player result queue. The
            // timestamp is current; bytes model a persisted pre-edit snapshot, not corruption.
            var result = new ChunkReadResult(uuid, x, z,
                    OLD_DISK.clone(), dimension, OLD_DISK.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    LSSConstants.epochSeconds(), false, false, false, order);
            if (headerFreshResult) result = ChunkReadResult.headerFresh(uuid, x, z,
                    dimension, order, clientTimestamp - 100);
            if (holdTargetDisk && x == X && z == Z) heldTargetDisk.set(result);
            else reader.getPlayerQueue(uuid).add(result);
            diskSubmitted.countDown(); routeDecided.countDown();
            return true;
        }
        @Override protected boolean buildAndEnqueueColumnPayload(PaperPlayerRequestState state,
                int x, int z, String dimension, long timestamp, long order,
                ColumnBytes bytes, int estimated, byte source) {
            boolean accepted = super.buildAndEnqueueColumnPayload(state, x, z, dimension,
                    timestamp, order, bytes, estimated, source);
            if (accepted) { deliveries.add(new Delivery(source, bytes.raw().clone())); routeDecided.countDown(); }
            return accepted;
        }
        Delivery delivered() throws InterruptedException {
            var result = deliveries.poll(10, TimeUnit.SECONDS);
            assertNotNull(result, "actual payload-build/enqueue must complete; timeout is setup failure");
            return result;
        }
    }

    private static final class Rig implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final Map<UUID, PaperPlayerRequestState> players = new ConcurrentHashMap<>();
        final PaperChunkDiskReader reader = new PaperChunkDiskReader(1, false);
        final Processor processor = new Processor(players, reader);
        final List<Runnable> ownerTasks = new ArrayList<>();
        final List<byte[]> capturedFrames = new ArrayList<>();
        final PaperRequestProcessingService service;
        final PaperPlayerRequestState state;
        final CountDownLatch resumeRoute = new CountDownLatch(1);
        boolean started;
        Rig() { this(true); }
        Rig(boolean initialOffer) {
            var config = new PaperConfig(); config.validate();
            var server = mock(MinecraftServer.class);
            when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
            var level = mock(ServerLevel.class); when(level.dimension()).thenReturn(Level.OVERWORLD);
            var player = mock(ServerPlayer.class);
            when(player.getUUID()).thenReturn(uuid); when(player.level()).thenReturn(level);
            when(player.chunkPosition()).thenReturn(new ChunkPos(0, 0));
            when(player.getName()).thenReturn(Component.literal("alignment-control"));
            var tracker = new DirtyColumnTracker();
            service = new PaperRequestProcessingService(server, config,
                    new PaperRequestProcessingService.Wiring(players, reader,
                            new PaperRequestProcessingServiceTest.RecordingGenService(config), processor,
                            tracker, new PaperRequestProcessingServiceTest.RecordingBroadcaster(
                                    server, players, tracker, processor)));
            service.setColumnPayloadSender((s, data) -> capturedFrames.add(data.clone())); // no mocked native network
            service.setRegionizedProbing(true);
            service.setRegionTaskScheduler((p, task) -> ownerTasks.add(task));
            service.setRegionOwnershipCheck((l, x, z) -> true);
            service.setLoadedColumnProbe((l, x, z) ->
                    new LoadedColumnData(x, z, CURRENT.clone(), CURRENT.length));
            state = service.registerPlayer(player, 1);
            if (initialOffer) state.offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{new IncomingRequest(X, Z, -1)}));
        }
        void start() { started = true; processor.start(); }
        void holdAndCompleteProbe() {
            service.tick();
            assertNull(state.peekIncomingBatch(), "pump actually holds fresh declaration");
            assertEquals(1, ownerTasks.size()); ownerTasks.get(0).run();
        }
        void assertCurrentSnapshot() {
            var probes = processor.lastPosted.loadedChunkProbes().get(uuid);
            assertNotNull(probes); assertTrue(probes.containsKey(dev.vox.lss.common.PositionUtil.packPosition(X, Z)));
        }
        @Override public void close() {
            resumeRoute.countDown();
            if (!started) start();
            processor.shutdown(); reader.shutdown();
        }
    }

    @Test void availableProbePostedBeforeWorkerDeliversCurrentPayload() throws Exception {
        try (var r = new Rig()) {
            r.holdAndCompleteProbe(); r.service.tick(); r.assertCurrentSnapshot(); r.start();
            var actual = r.processor.delivered();
            assertEquals(0, actual.source()); assertArrayEquals(CURRENT, actual.bytes());
            assertEquals(1, r.processor.diskSubmitted.getCount(), "positive control must not admit disk");
        }
    }

    @Test void availableProbeMustSurviveReleaseBeforeSnapshotPublication() throws Exception {
        try (var r = new Rig()) {
            var paused = new CountDownLatch(1);
            r.processor.beforeRoute.set(() -> { paused.countDown(); await(r.resumeRoute); });
            r.service.tick(); r.start(); await(paused); // worker owns first tick's empty snapshot
            assertEquals(1, r.ownerTasks.size()); r.ownerTasks.get(0).run();
            r.processor.beforePost.set(() -> {
                assertNotNull(r.state.peekIncomingBatch(), "second pump already released held request");
                r.resumeRoute.countDown(); await(r.processor.routeDecided);
            });
            r.service.tick(); r.assertCurrentSnapshot(); // ready probe posted only after adverse routing
            var actual = r.processor.delivered();
            assertArrayEquals(CURRENT, actual.bytes(), "available owner probe must align with its released declaration");
            assertEquals(0, actual.source());
        }
    }

    @Test void availableProbeMustSurviveSubsequentPumpOverwrite() throws Exception {
        try (var r = new Rig()) {
            r.holdAndCompleteProbe(); r.service.tick(); r.assertCurrentSnapshot();
            r.service.tick(); // current baseline re-holds the released mailbox declaration
            r.service.tick(); // its new callback is absent: re-release with empty snapshot
            assertTrue(r.processor.lastPosted.loadedChunkProbes().isEmpty());
            assertNotNull(r.state.peekIncomingBatch()); r.start(); await(r.processor.routeDecided);
            // Wake real worker to consume the injected disk completion; no extra pump/clear.
            r.processor.postSnapshot(r.processor.lastPosted, List.of());
            var actual = r.processor.delivered();
            assertArrayEquals(CURRENT, actual.bytes(), "previously ready probe must survive pending snapshot replacement");
            assertEquals(0, actual.source());
        }
    }

    @Test void absentOwnerCallbackReleasesAndDeliversDiskFallback() throws Exception {
        try (var r = new Rig()) {
            r.service.tick(); r.service.tick(); // no scheduled owner callback executed
            assertNotNull(r.state.peekIncomingBatch()); r.start(); await(r.processor.routeDecided);
            r.processor.postSnapshot(r.processor.lastPosted, List.of());
            var actual = r.processor.delivered();
            assertEquals(1, actual.source()); assertArrayEquals(OLD_DISK, actual.bytes());
        }
    }

    @Test void freshOfferAfterPumpInspectionWaitsForBoundedHoldAndReadyRelease() throws Exception {
        try (var r = new Rig(false)) {
            var firstPaused = new CountDownLatch(1);
            var secondPaused = new CountDownLatch(1);
            var resumeSecond = new CountDownLatch(1);
            var fresh = new IncomingBatch(new IncomingRequest[]{new IncomingRequest(X, Z, -1)});
            try {
                r.processor.beforeRoute.set(() -> { firstPaused.countDown(); await(r.resumeRoute); });
                // The real pump has inspected this player with no declaration. Ingress
                // arrives at the existing post boundary, before its empty snapshot posts.
                r.processor.beforePost.set(() -> r.state.offerIncomingBatch(fresh));
                r.service.tick();
                assertTrue(r.ownerTasks.isEmpty(), "offer arrived after the player's pump pass");
                assertSame(fresh, r.state.peekIncomingBatch());
                r.start(); await(firstPaused);
                r.processor.beforeRoute.set(() -> { secondPaused.countDown(); await(resumeSecond); });
                // Queue one more old empty snapshot. Entering its route hook proves the
                // previous cycle actually routed and completed; no timed negative wait.
                r.processor.postSnapshot(r.processor.lastPosted, List.of());
                r.resumeRoute.countDown(); await(secondPaused);
                assertSame(fresh, r.state.peekIncomingBatch(),
                        "Folia ingress must remain pump-owned until its bounded hold/release");
                assertEquals(1, r.processor.diskSubmitted.getCount(),
                        "completed pre-hold routing cycle must not admit this fresh declaration");
                assertTrue(r.processor.deliveries.isEmpty());
                r.service.tick(); // next actual pump takes and holds fresh ingress
                assertNull(r.state.peekIncomingBatch());
                assertEquals(1, r.ownerTasks.size()); r.ownerTasks.get(0).run();
                r.service.tick(); // following actual pump releases with ready probes
                r.assertCurrentSnapshot();
                assertNotNull(r.state.peekIncomingBatch());
                resumeSecond.countDown();
                var actual = r.processor.delivered();
                assertArrayEquals(CURRENT, actual.bytes()); assertEquals(0, actual.source());
                assertEquals(1, r.processor.diskSubmitted.getCount());
            } finally {
                r.resumeRoute.countDown(); resumeSecond.countDown();
                r.processor.beforeRoute.set(null); r.processor.beforePost.set(null);
            }
        }
    }
    /** Observe actual encoded send callbacks, not the earlier build/enqueue seam. */
    private static Delivery sentFrame(byte[] frame) {
        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(frame));
        try {
            assertEquals(X, buf.readInt()); assertEquals(Z, buf.readInt());
            assertEquals("minecraft:overworld", buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH));
            assertTrue(buf.readLong() > 0);
            byte source = buf.readByte();
            assertEquals(0, buf.readByte(), "test recipient requests raw columns");
            byte[] body = buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE);
            assertEquals(0, buf.readableBytes());
            return new Delivery(source, body);
        } finally { buf.release(); }
    }

    /** Two route hooks prove one complete worker route/result cycle, without sleeping. */
    private static void completeWorkerCycle(Rig r) {
        var first = new CountDownLatch(1); var second = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        try {
            r.processor.beforeRoute.set(() -> { first.countDown(); await(releaseFirst); });
            r.processor.postSnapshot(r.processor.lastPosted, List.of()); await(first);
            r.processor.beforeRoute.set(() -> { second.countDown(); await(releaseSecond); });
            r.processor.postSnapshot(r.processor.lastPosted, List.of());
            releaseFirst.countDown(); await(second);
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown();
        }
    }

    private static void lateCallbackAfterFallback(boolean flushDiskFirst) throws Exception {
        lateCallbackAfterFallback(flushDiskFirst, CURRENT);
    }

    private static void lateCallbackAfterFallback(boolean flushDiskFirst, byte[] current) throws Exception {
        try (var r = new Rig()) {
            r.service.tick(); r.service.tick(); // preserve unconditional one-tick release
            assertEquals(1, r.ownerTasks.size(), "one original owner task is still delayed");
            r.start(); await(r.processor.diskSubmitted);
            r.processor.postSnapshot(r.processor.lastPosted, List.of());
            var built = r.processor.delivered();
            assertEquals(1, built.source()); assertArrayEquals(OLD_DISK, built.bytes());
            assertTrue(r.state.hasEnqueuedColumn(dev.vox.lss.common.PositionUtil.packPosition(X, Z)));
            assertTrue(r.capturedFrames.isEmpty(), "disk built but has not reached the actual sender");
            if (flushDiskFirst) {
                // Real token bucket starts at zero and skips sub-millisecond refills.
                // Await the actual send while withholding every owner callback.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (r.capturedFrames.isEmpty() && System.nanoTime() < deadline) {
                    r.service.tick();
                    if (r.capturedFrames.isEmpty()) Thread.sleep(1);
                }
                assertEquals(1, r.capturedFrames.size(), "fallback must actually send before late callback");
                assertArrayEquals(OLD_DISK, sentFrame(r.capturedFrames.get(0)).bytes());
            }
            r.service.setLoadedColumnProbe((level, x, z) ->
                    new LoadedColumnData(x, z, current.clone(), current.length));
            r.ownerTasks.get(0).run(); // real scheduled callback, no second client declaration/dirty
            // Each iteration completes real worker work and the real pump's send drain.
            // Fixed finite cycles also expose duplicate corrections; no timed negative wait.
            for (int i = 0; i < 8; i++) {
                r.service.tick(); completeWorkerCycle(r);
            }
            r.service.tick();
            assertEquals(2, r.capturedFrames.size(),
                    "late original owner probe must correct disk exactly once without a new declaration");
            var first = sentFrame(r.capturedFrames.get(0));
            var second = sentFrame(r.capturedFrames.get(1));
            assertEquals(1, first.source()); assertArrayEquals(OLD_DISK, first.bytes());
            assertEquals(0, second.source()); assertArrayEquals(current.length == 0 ? new byte[]{0, 0} : current, second.bytes(),
                    "actual sender order must be OLD_DISK then CURRENT, never stale last");
        }
    }

    @Test void lateOwnerCallbackMustDeliverCurrentAfterHeaderFreshResolution() throws Exception {
        lateCallbackAfterHeaderFresh(CURRENT);
    }

    @Test void lateAllAirCallbackClearsAfterHeaderFreshResolutionExactlyOnce() throws Exception {
        lateCallbackAfterHeaderFresh(new byte[0]);
    }

    private static void lateCallbackAfterHeaderFresh(byte[] current) throws Exception {
        try (var r = new Rig(false)) {
            r.service.setLoadedColumnProbe((level, x, z) ->
                    new LoadedColumnData(x, z, current.clone(), current.length));
            // Inject the existing reader's header-fresh result, not fabricated column bytes.
            // The client already has old content; the owner callback observes current content.
            r.processor.headerFreshResult = true;
            r.state.offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{
                    new IncomingRequest(X, Z, LSSConstants.epochSeconds())}));
            r.service.tick(); r.service.tick(); // original owner callback remains delayed
            assertEquals(1, r.ownerTasks.size());
            r.start(); await(r.processor.diskSubmitted);
            completeWorkerCycle(r); // actual result queue -> delivery -> response action
            var responses = new ArrayList<Long>();
            r.processor.drainSendActions((state, types, positions, count) -> {
                assertSame(r.state, state);
                for (int i = 0; i < count; i++) {
                    assertEquals(LSSConstants.RESPONSE_UP_TO_DATE, types[i]);
                    responses.add(positions[i]);
                }
            });
            assertEquals(List.of(dev.vox.lss.common.PositionUtil.packPosition(X, Z)), responses,
                    "setup must actually resolve the admitted read as header-fresh up_to_date");
            assertTrue(r.state.hasDiskReadDone(X, Z));
            assertTrue(r.capturedFrames.isEmpty(), "header-fresh answer has no column body");
            r.ownerTasks.get(0).run(); // original capture, no new declaration, edit or reset
            for (int i = 0; i < 8; i++) {
                r.service.tick(); completeWorkerCycle(r);
            }
            // Respect the real token bucket's sub-millisecond refill floor.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (r.capturedFrames.isEmpty() && System.nanoTime() < deadline) {
                r.service.tick();
                if (r.capturedFrames.isEmpty()) Thread.sleep(1);
            }
            assertEquals(1, r.capturedFrames.size(),
                    "late current owner capture must deliver a body after header-fresh resolution");
            var frame = sentFrame(r.capturedFrames.get(0));
            assertEquals(0, frame.source());
            assertArrayEquals(current.length == 0 ? new byte[]{0, 0} : current, frame.bytes());
            r.ownerTasks.get(0).run(); // repeated callback cannot renew the consumed opportunity
            for (int i = 0; i < 4; i++) { r.service.tick(); completeWorkerCycle(r); }
            r.service.tick(); assertEquals(1, r.capturedFrames.size(), "one correction only");
        }
    }

    @Test void lateOwnerCallbackCorrectsAlreadySentDiskWithoutNewDeclaration() throws Exception {
        lateCallbackAfterFallback(true);
    }

    @Test void lateOwnerCallbackCannotOvertakeQueuedDiskAtActualSend() throws Exception {
        lateCallbackAfterFallback(false);
    }

    @Test void lateAllAirProbeClearsAlreadyDeliveredDiskForOriginalNoDataRequest() throws Exception {
        lateCallbackAfterFallback(true, new byte[0]);
    }

    @Test void targetBeyondProbePrefixMustConvergeAfterItsPublishedWantProbe() throws Exception {
        try (var r = new Rig(false)) {
            var requests = new ArrayList<IncomingRequest>();
            for (int x = -16; x < 16; x++) for (int z = -12; z <= 12; z++) {
                if (x != X || z != Z) requests.add(new IncomingRequest(x, z, -1));
            }
            requests.add(new IncomingRequest(X, Z, -1));
            assertEquals(800, requests.size());
            var targetProbes = new java.util.concurrent.atomic.AtomicInteger();
            r.service.setLoadedColumnProbe((level, x, z) -> {
                if (x == X && z == Z) targetProbes.incrementAndGet();
                return new LoadedColumnData(x, z, CURRENT.clone(), CURRENT.length);
            });
            java.util.function.Supplier<List<Delivery>> targetFrames = () -> {
                var found = new ArrayList<Delivery>();
                for (byte[] frame : r.capturedFrames) {
                    var coordinates = java.nio.ByteBuffer.wrap(frame);
                    if (coordinates.getInt() == X && coordinates.getInt() == Z) found.add(sentFrame(frame));
                }
                return found;
            };
            r.state.offerIncomingBatch(new IncomingBatch(requests.toArray(IncomingRequest[]::new)));
            r.holdAndCompleteProbe(); // prompt original callback, unchanged 512-position cap
            assertEquals(0, targetProbes.get(), "target lies outside original selected prefix");
            r.service.tick(); r.start();
            // Pump/worker barriers drive real admission/results/sends. Published-want
            // tasks are deliberately retained until the target's stale disk body sends.
            for (int cycle = 0; cycle < 64 && targetFrames.get().isEmpty(); cycle++) {
                completeWorkerCycle(r); r.service.tick();
            }
            var fallback = targetFrames.get();
            assertEquals(1, fallback.size(), "setup must deliver the actual target disk frame");
            assertEquals(1, fallback.get(0).source());
            assertArrayEquals(OLD_DISK, fallback.get(0).bytes());
            assertEquals(0, targetProbes.get(), "no published-want callback has run before fallback");
            int nextTask = 1; // original task already completed promptly above
            for (int cycle = 0; cycle < 8; cycle++) {
                while (nextTask < r.ownerTasks.size()) r.ownerTasks.get(nextTask++).run();
                r.service.tick(); completeWorkerCycle(r);
            }
            r.service.tick();
            assertTrue(targetProbes.get() > 0, "actual published-want callback must observe target CURRENT");
            var actual = targetFrames.get();
            assertArrayEquals(CURRENT, actual.get(actual.size() - 1).bytes(),
                    "actual target sender must converge from old disk to current loaded bytes beyond probe prefix");
        }
    }
    private void frontierReplacementAfterAdmittedCallback(boolean exclude, boolean empty) throws Exception {
        try (var r = new Rig(false)) {
            var requests = new ArrayList<IncomingRequest>();
            for (int x = -16; x < 16; x++) for (int z = -12; z <= 12; z++) {
                if (x != X || z != Z) requests.add(new IncomingRequest(x, z, -1));
            }
            requests.add(new IncomingRequest(X, Z, -1));
            assertEquals(800, requests.size());
            var targetProbes = new java.util.concurrent.atomic.AtomicInteger();
            r.service.setLoadedColumnProbe((level, x, z) -> {
                if (x == X && z == Z) targetProbes.incrementAndGet();
                return new LoadedColumnData(x, z, CURRENT.clone(), CURRENT.length);
            });
            java.util.function.Supplier<List<Delivery>> targetFrames = () -> {
                var found = new ArrayList<Delivery>();
                for (byte[] frame : r.capturedFrames) {
                    var coordinates = java.nio.ByteBuffer.wrap(frame);
                    if (coordinates.getInt() == X && coordinates.getInt() == Z) found.add(sentFrame(frame));
                }
                return found;
            };
            r.processor.holdTargetDisk = true;
            var batch = new IncomingBatch(requests.toArray(IncomingRequest[]::new));
            r.state.offerIncomingBatch(batch);
            r.holdAndCompleteProbe();
            assertEquals(0, targetProbes.get(), "target outside original 512");
            r.service.tick(); r.start();
            for (int cycle = 0; cycle < 64 && r.processor.heldTargetDisk.get() == null; cycle++) {
                completeWorkerCycle(r); r.service.tick();
            }
            assertNotNull(r.processor.heldTargetDisk.get(), "actual target disk admission is pending");
            int nextTask = 1;
            for (int cycle = 0; cycle < 8 && targetProbes.get() == 0; cycle++) {
                r.service.tick(); completeWorkerCycle(r);
                while (nextTask < r.ownerTasks.size()) r.ownerTasks.get(nextTask++).run();
            }
            assertTrue(targetProbes.get() > 0, "claimed owner callback actually serialized CURRENT");
            assertTrue(targetFrames.get().isEmpty(), "disk result still held, no target sender yet");
            var next = empty ? new IncomingBatch(new IncomingRequest[0])
                    : exclude ? new IncomingBatch(new IncomingRequest[]{new IncomingRequest(-15, -11, -1)}) : batch;
            r.state.offerIncomingBatch(next); // replaces backlog, admitted target still completes
            r.reader.getPlayerQueue(r.uuid).add(r.processor.heldTargetDisk.getAndSet(null));
            for (int cycle = 0; cycle < 64; cycle++) {
                r.service.tick(); completeWorkerCycle(r);
                while (nextTask < r.ownerTasks.size()) r.ownerTasks.get(nextTask++).run();
            }
            r.service.tick();
            var actual = targetFrames.get();
            assertFalse(actual.isEmpty(), "actual target sender must receive the held disk result");
            assertEquals(1, actual.get(0).source());
            assertArrayEquals(OLD_DISK, actual.get(0).bytes());
            assertArrayEquals(CURRENT, actual.get(actual.size() - 1).bytes(),
                    "overlapping declaration must preserve the pending disk attempt's current correction");
        }
    }

    @Test void overlappingDeclarationMustRetainCompletedProbeForPendingDisk() throws Exception {
        frontierReplacementAfterAdmittedCallback(false, false);
    }
    @Test void excludingFrontierMustNotCancelAdmittedCorrection() throws Exception {
        frontierReplacementAfterAdmittedCallback(true, false);
    }
    @Test void emptyBackpressureClearMustNotCancelAdmittedCorrection() throws Exception {
        frontierReplacementAfterAdmittedCallback(false, true);
    }

}
