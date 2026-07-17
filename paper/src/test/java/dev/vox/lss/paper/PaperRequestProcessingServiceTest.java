package dev.vox.lss.paper;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.IncomingBatch;
import dev.vox.lss.common.processing.IncomingRequest;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * First direct tests of {@link PaperRequestProcessingService} — the hand-copied twin of
 * Fabric's RequestProcessingService, previously exercised only by live soak runs. Built
 * on the collaborator-injected {@code Wiring} constructor: recording subclasses of the
 * processor / generation service / broadcaster observe exactly the calls the service
 * glue makes, and the column-sender / probe seams replace the only NMS sends. Pins the
 * batch-request gate, handshake lifecycle (reuse, dimension change, respawn swap vs
 * removal), the a9bee8d flush-drop {@code clearDiskReadDone} wiring, the generation
 * ticket drain triage, the enabled=false freeze, probe budget accounting, and shutdown
 * failure isolation.
 */
class PaperRequestProcessingServiceTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- recording collaborators ----

    static class RecordingProcessor extends PaperOffThreadProcessor {
        final List<TickSnapshot> snapshots = new ArrayList<>();
        final List<List<TickSnapshot.GenerationReadyData>> postedGenerationReady = new ArrayList<>();
        final List<UUID> removals = new ArrayList<>();
        record DirtyClear(UUID playerUuid, long[] positions) {}
        final List<DirtyClear> dirtyClears = new ArrayList<>();
        record GenFailure(UUID playerUuid, int cx, int cz, String dimension, long submissionOrder,
                          boolean transientFailure) {}
        final List<GenFailure> genFailures = new ArrayList<>();
        final ArrayDeque<OffThreadProcessor.GenerationTicketRequest> ticketQueue = new ArrayDeque<>();
        final AtomicInteger sendActionDrains = new AtomicInteger();
        record Invalidation(String dimension, long[] positions) {}
        final List<Invalidation> invalidations = new ArrayList<>();
        boolean throwOnShutdown = false;
        boolean shutdownCalled = false;
        boolean invalidationsSeenBeforeShutdown = false;

        RecordingProcessor(Map<UUID, PaperPlayerRequestState> players, PaperChunkDiskReader diskReader) {
            // Never start()ed: the recording overrides observe the main-thread glue only.
            super(players, diskReader, true, null, 32);
        }

        @Override
        public void postSnapshot(TickSnapshot snapshot, List<TickSnapshot.GenerationReadyData> generationReady) {
            snapshots.add(snapshot);
            postedGenerationReady.add(generationReady);
        }

        @Override
        public void notifyPlayerRemoved(UUID uuid) {
            removals.add(uuid);
        }

        @Override
        public void clearDiskReadDone(UUID playerUuid, long[] positions) {
            dirtyClears.add(new DirtyClear(playerUuid, positions));
        }

        @Override
        public void feedGenerationFailure(UUID playerUuid, int cx, int cz, String dimension,
                                          long submissionOrder, boolean transientFailure) {
            genFailures.add(new GenFailure(playerUuid, cx, cz, dimension, submissionOrder, transientFailure));
        }

        @Override
        public OffThreadProcessor.GenerationTicketRequest pollGenerationTicketRequest() {
            return ticketQueue.poll();
        }

        @Override
        public void drainSendActions(OffThreadProcessor.BatchSender<PaperPlayerRequestState> sender) {
            sendActionDrains.incrementAndGet();
        }

        @Override
        public void invalidateTimestamps(String dimension, long[] positions) {
            invalidations.add(new Invalidation(dimension, positions));
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
            invalidationsSeenBeforeShutdown = !invalidations.isEmpty();
            if (throwOnShutdown) throw new IllegalStateException("processor shutdown boom");
        }
    }

    static class RecordingGenService extends PaperChunkGenerationService {
        record Submitted(UUID playerUuid, int cx, int cz, long submissionOrder) {}
        final List<Submitted> submitted = new ArrayList<>();
        final List<UUID> removedPlayers = new ArrayList<>();
        List<TickSnapshot.GenerationReadyData> nextTick = List.of();
        boolean accept = true;
        boolean shutdownCalled = false;

        RecordingGenService(PaperConfig config) {
            super(config, null); // plugin only used by the (never-invoked) real launchAsyncLoad
        }

        @Override
        public boolean submitGeneration(UUID playerUuid, ServerLevel level, int cx, int cz, long submissionOrder) {
            submitted.add(new Submitted(playerUuid, cx, cz, submissionOrder));
            return accept;
        }

        @Override
        public List<TickSnapshot.GenerationReadyData> tick() {
            var out = nextTick;
            nextTick = List.of();
            return out;
        }

        @Override
        public void removePlayer(UUID playerUuid) {
            removedPlayers.add(playerUuid);
            super.removePlayer(playerUuid);
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
            super.shutdown();
        }
    }

    static class RecordingBroadcaster extends PaperDirtyColumnBroadcaster {
        int ticks = 0;

        RecordingBroadcaster(MinecraftServer server, Map<UUID, PaperPlayerRequestState> players,
                             DirtyColumnTracker tracker, PaperOffThreadProcessor processor) {
            super(server, players, tracker, processor);
        }

        @Override
        void tick(PaperConfig config) {
            ticks++;
        }
    }

    // ---- rig ----

    private Map<UUID, PaperPlayerRequestState> players;
    private PaperChunkDiskReader diskReader;
    private RecordingProcessor processor;
    private RecordingGenService genService;
    private RecordingBroadcaster broadcaster;
    private MinecraftServer server;
    private PlayerList playerList;
    private PaperConfig config;
    private PaperRequestProcessingService service;

    @BeforeEach
    void buildRig() {
        config = new PaperConfig();
        config.validate();
        players = new ConcurrentHashMap<>();
        diskReader = new PaperChunkDiskReader(1, false);
        processor = new RecordingProcessor(players, diskReader);
        genService = new RecordingGenService(config);
        server = mock(MinecraftServer.class);
        playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        var tracker = new DirtyColumnTracker();
        broadcaster = new RecordingBroadcaster(server, players, tracker, processor);
        service = new PaperRequestProcessingService(server, config,
                new PaperRequestProcessingService.Wiring(
                        players, diskReader, genService, processor, tracker, broadcaster));
        // Default probe: "nothing is loaded". The production default would dereference the
        // mocked level's chunk source; probe-specific tests inject their own recorder.
        service.setLoadedColumnProbe((level, cx, cz) -> null);
    }

    @AfterEach
    void teardownReader() {
        diskReader.shutdown();
    }

    private static ServerLevel level(ResourceKey<Level> key) {
        var l = mock(ServerLevel.class);
        when(l.dimension()).thenReturn(key);
        return l;
    }

    /** Mocked player at chunk (0,0) (getBlockX/Z default 0), not removed. */
    private static ServerPlayer playerIn(UUID uuid, ServerLevel level) {
        var p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.level()).thenReturn(level);
        // The lifecycle pass stamps the player chunk each tick (ring origin for the
        // generation order-spread gate)
        when(p.chunkPosition()).thenReturn(new ChunkPos(0, 0));
        // getPlayerName() is dereferenced by the flush-failure log path
        when(p.getName()).thenReturn(Component.literal("p-" + uuid.toString().substring(0, 8)));
        return p;
    }

    private static PaperPayloadHandler.DecodedBatchChunkRequest batchOf(long[] positions, long[] timestamps) {
        return new PaperPayloadHandler.DecodedBatchChunkRequest(positions, timestamps, positions.length);
    }

    /** Declare a complete want-set batch straight into a state's mailbox (v17 ingress shape).
     *  Each call REPLACES the previous declaration — that is the protocol, not a test shortcut. */
    private static void offer(PaperPlayerRequestState state, IncomingRequest... reqs) {
        state.offerIncomingBatch(new IncomingBatch(reqs));
    }

    /** The un-taken mailbox contents (empty once the processing thread has taken the batch). */
    private static List<IncomingRequest> pendingBatch(PaperPlayerRequestState state) {
        var b = state.peekIncomingBatch();
        return b == null ? List.of() : List.of(b.requests());
    }

    /** Stand in for the processing thread having APPLIED a want-set to the backlog — that is
     *  what publishes it. The main-thread probe reads the mailbox FIRST and falls back to the
     *  published set; publishing (with an empty mailbox) is how these tests exercise the
     *  fallback arm, which is the one that carries a want-set across the ~19 ticks per second
     *  on which no batch arrives. These tests drive the pump directly with no processing
     *  thread behind it, so they publish the same way replaceBacklogWith does.
     *  {@link #freshMailboxBatchIsProbedOnItsArrivalTickWithNothingPublished} pins the other arm. */
    private static void publish(PaperPlayerRequestState state, IncomingRequest... reqs) {
        state.publishWantSet(new IncomingBatch(reqs));
    }

    /** PlayerBandwidthTracker refills only after >=1ms elapsed; give the fresh bucket a window. */
    private static void awaitBandwidthWindow() throws InterruptedException {
        Thread.sleep(10);
    }

    // ---- PP-001: batch-request distance guard ----

    @Test
    void batchRequestGateDropsBeyondLodPlusBufferAndKeepsClientTimestamps() {
        config.lodDistanceChunks = 16; // gate at 16 + 32 = 48 Chebyshev
        var player = playerIn(UUID.randomUUID(), level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);

        service.handleBatchRequest(player, batchOf(
                new long[]{
                        PositionUtil.packPosition(48, -48),  // exactly at the gate: accepted
                        PositionUtil.packPosition(49, 0),    // one past: dropped
                        PositionUtil.packPosition(0, -49)},  // negative side: dropped
                new long[]{77L, 1L, 2L}));

        assertEquals(1, state.getTotalRequestsReceived(), "out-of-range positions never reach the mailbox");
        var accepted = pendingBatch(state);
        assertEquals(1, accepted.size(), "only the in-range position is declared: " + accepted);
        assertEquals(48, accepted.get(0).cx());
        assertEquals(-48, accepted.get(0).cz());
        assertEquals(77L, accepted.get(0).clientTimestamp(),
                "the accepted request carries ITS OWN clientTimestamp");
        // The two gated entries were RECEIVED on the wire but never routed. Nothing answers
        // them, so they must be booked as range_filtered or the request-conservation law
        // cannot close over the batch.
        assertEquals(2, state.drainPendingRangeFiltered(),
                "both out-of-range entries are counted range_filtered exactly once");
        assertEquals(0, state.drainPendingRangeFiltered(), "the drain is destructive");
    }

    // ---- PP-002: unregistered / pre-handshake batch requests are silent no-ops ----

    @Test
    void batchRequestWithoutRegistrationOrHandshakeIsSilentlyDropped() {
        var level = level(Level.OVERWORLD);
        var batch = batchOf(new long[]{PositionUtil.packPosition(1, 1)}, new long[]{-1L});

        // state == null: the post-/reload shape (client still sends, plugin map is fresh)
        var stranger = playerIn(UUID.randomUUID(), level);
        assertDoesNotThrow(() -> service.handleBatchRequest(stranger, batch));
        assertTrue(players.isEmpty());

        // state present but handshake never completed: also dropped, no queue growth
        var uuid = UUID.randomUUID();
        var p = playerIn(uuid, level);
        var bare = new PaperPlayerRequestState(p, 4, 4);
        players.put(uuid, bare);
        service.handleBatchRequest(p, batch);
        assertEquals(0, bare.getTotalRequestsReceived());
        assertNull(bare.peekIncomingBatch(), "no batch is offered before the handshake completes");
    }

    // ---- PP-003: re-handshake reuses the state ----

    @Test
    void reHandshakeReusesStateUpdatesCapabilitiesAndKeepsPendingWork() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));

        var first = service.registerPlayer(player, 1);
        offer(first, new IncomingRequest(3, 4, -1L));
        var queueBefore = diskReader.getPlayerQueue(uuid);
        assertNotNull(queueBefore, "registration creates the disk-reader result queue");

        var second = service.registerPlayer(player, 0);
        assertSame(first, second, "computeIfAbsent reuses the existing state");
        assertEquals(0, second.getCapabilities(), "capabilities are updated by the re-handshake");
        assertTrue(second.hasCompletedHandshake());
        assertEquals(1, second.getTotalRequestsReceived(), "counters survive the re-handshake");
        var pending = pendingBatch(second);
        assertEquals(1, pending.size(), "the declared want-set survives the re-handshake");
        assertEquals(3, pending.get(0).cx());
        assertSame(queueBefore, diskReader.getPlayerQueue(uuid),
                "disk-reader registration is idempotent (results are not torn down)");
    }

    // ---- PP-004: dimension change re-registers ----

    @Test
    void checkDimensionChangeIsTrueExactlyOncePerFlip() {
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(UUID.randomUUID(), overworld);
        var state = new PaperPlayerRequestState(player, 4, 4);

        var end = level(Level.END);  // build the mock BEFORE opening the when() stub (no nested stubbing)
        assertFalse(state.checkDimensionChange(), "construction captures the join dimension");
        when(player.level()).thenReturn(end);
        assertTrue(state.checkDimensionChange(), "first check after the flip reports it");
        assertFalse(state.checkDimensionChange(), "the flip is consumed — no repeat re-registration");
        when(player.level()).thenReturn(overworld);
        assertTrue(state.checkDimensionChange(), "the round trip back is a fresh flip");
        assertFalse(state.checkDimensionChange());
    }

    @Test
    void dimensionChangeTickReRegistersAFreshStateWithPreservedCapabilities() {
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        var old = service.registerPlayer(player, 1);
        offer(old, new IncomingRequest(1, 1, -1L));
        long packed = PositionUtil.packPosition(1, 1);
        old.addReadyPayload(new QueuedPayload<>(new byte[]{1}, 1, 1L, packed));

        var end = level(Level.END);  // build the mock BEFORE opening the when() stub (no nested stubbing)
        when(player.level()).thenReturn(end);
        service.tick();

        var fresh = service.getPlayers().get(uuid);
        assertNotNull(fresh, "the player is re-registered, not dropped");
        assertNotSame(old, fresh, "a dimension change discards the old state entirely");
        assertEquals(1, fresh.getCapabilities(), "capabilities survive the re-registration");
        assertTrue(fresh.hasCompletedHandshake());
        assertEquals(List.of(uuid), processor.removals,
                "the processing thread is told exactly once (dedup-group teardown)");
        // Every carrier of cross-dimension work must be empty on the fresh state: the mailbox
        // (declared but un-taken), the backlog (taken but un-admitted), and the send pipeline.
        // A want declared for the OLD dimension must never route under the new one.
        assertNull(fresh.peekIncomingBatch(), "fresh mailbox");
        assertNull(fresh.peekWantSet(), "nothing published for the probe");
        assertEquals(0, fresh.getBacklogSize(), "fresh backlog");
        assertFalse(fresh.hasEnqueuedColumn(packed), "fresh send pipeline");
        assertEquals(0, fresh.getSendQueueSize());

        // No second flip: the next tick must NOT re-register again
        service.tick();
        assertSame(fresh, service.getPlayers().get(uuid));
        assertEquals(1, processor.removals.size());
    }

    // ---- PP-005: removed-vs-respawn branches ----

    @Test
    void removedPlayerStillOnThePlayerListSwapsTheHandleAndKeepsState() {
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(2, 2, -1L));

        when(player.isRemoved()).thenReturn(true);
        var respawned = playerIn(uuid, overworld); // genuinely different instance, same UUID
        when(playerList.getPlayer(uuid)).thenReturn(respawned);
        service.tick();

        assertSame(state, service.getPlayers().get(uuid), "respawn keeps the state");
        assertSame(respawned, state.getPlayer(), "the player handle is swapped to the new instance");
        assertEquals(1, pendingBatch(state).size(), "pending work survives the respawn swap");
        assertTrue(processor.removals.isEmpty(), "no teardown for a respawn");
    }

    @Test
    void removedPlayerAbsentFromThePlayerListIsFullyTornDown() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        service.registerPlayer(player, 1);
        assertNotNull(diskReader.getPlayerQueue(uuid));

        when(player.isRemoved()).thenReturn(true);
        when(playerList.getPlayer(uuid)).thenReturn(null);
        service.tick();

        assertTrue(service.getPlayers().isEmpty(), "state removed");
        assertEquals(List.of(uuid), processor.removals, "processing thread notified");
        assertNull(diskReader.getPlayerQueue(uuid), "disk-reader results torn down");
        assertEquals(List.of(uuid), genService.removedPlayers, "generation service pruned");
    }

    // ---- PP-006: flush-drop wiring clears done-bits (the Paper half of a9bee8d) ----

    @Test
    void senderFailureRoutesExactlyTheDroppedPositionsToClearDiskReadDone() throws Exception {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        long p1 = PositionUtil.packPosition(10, 11);
        long p2 = PositionUtil.packPosition(12, 13);
        state.addReadyPayload(new QueuedPayload<>(new byte[]{1}, 8, 1L, p1));
        state.addReadyPayload(new QueuedPayload<>(new byte[]{2}, 8, 2L, p2));
        service.setColumnPayloadSender((s, data) -> { throw new IllegalStateException("wire down"); });

        awaitBandwidthWindow();
        service.tick();

        assertEquals(1, processor.dirtyClears.size(), "one clear batch for the dropped flush");
        var clear = processor.dirtyClears.get(0);
        assertEquals(uuid, clear.playerUuid());
        long[] got = clear.positions().clone();
        Arrays.sort(got);
        assertArrayEquals(new long[]{p1, p2}, got,
                "exactly the dropped positions reach clearDiskReadDone — deleting the wiring "
                        + "turns every lost delivery into a permanent up-to-date hole");
        assertFalse(state.hasEnqueuedColumn(p1));
        assertFalse(state.hasEnqueuedColumn(p2));
    }

    @Test
    void successfulFlushSendsPayloadBytesAndClearsNothing() throws Exception {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        state.addReadyPayload(new QueuedPayload<>(new byte[]{7, 7}, 8, 1L, PositionUtil.packPosition(1, 0)));
        var sent = new ArrayList<byte[]>();
        service.setColumnPayloadSender((s, data) -> sent.add(data));

        awaitBandwidthWindow();
        service.tick();

        assertEquals(1, sent.size());
        assertArrayEquals(new byte[]{7, 7}, sent.get(0));
        assertTrue(processor.dirtyClears.isEmpty(), "a clean flush must not clear done-bits");
    }

    // ---- PP-007: generation ticket drain triage ----

    @Test
    void staleDimensionTicketIsDroppedWithoutSubmittingOrFeedingFailure() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        service.registerPlayer(player, 1);
        processor.ticketQueue.add(new OffThreadProcessor.GenerationTicketRequest(
                uuid, 5, 5, "minecraft:the_end", 9L));

        service.tick();

        assertTrue(genService.submitted.isEmpty(), "stale-dimension ticket never reaches generation");
        assertTrue(processor.genFailures.isEmpty(),
                "and feeds no failure — the admitting state died with the dimension change");
    }

    @Test
    void capacityRejectedTicketFeedsAFailureSoTheSlotUnwinds() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        service.registerPlayer(player, 1);
        genService.accept = false;
        processor.ticketQueue.add(new OffThreadProcessor.GenerationTicketRequest(
                uuid, 5, 5, "minecraft:overworld", 9L));

        service.tick();

        assertEquals(List.of(new RecordingGenService.Submitted(uuid, 5, 5, 9L)), genService.submitted);
        assertEquals(List.of(new RecordingProcessor.GenFailure(uuid, 5, 5, "minecraft:overworld", 9L, true)),
                processor.genFailures,
                "a bounced submit must feed a TRANSIENT failure (capacity is momentary — a "
                        + "permanent NOT_GENERATED would blank the position for the session) "
                        + "or the pending slot leaks forever");
    }

    @Test
    void removedPlayerTicketFeedsFailureWithoutSubmitting() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        service.registerPlayer(player, 1);
        when(player.isRemoved()).thenReturn(true);
        when(playerList.getPlayer(uuid)).thenReturn(player); // respawn-swap keeps the same (removed) handle
        processor.ticketQueue.add(new OffThreadProcessor.GenerationTicketRequest(
                uuid, 6, 6, "minecraft:overworld", 11L));

        service.tick();

        assertTrue(genService.submitted.isEmpty(), "isRemoved short-circuits before submitGeneration");
        assertEquals(List.of(new RecordingProcessor.GenFailure(uuid, 6, 6, "minecraft:overworld", 11L, true)),
                processor.genFailures);
    }

    // ---- PP-008: enabled=false tick is a full freeze ----

    @Test
    void tickWithEnabledFalseDoesNothingAndResumesWhenFlipped() {
        config.enabled = false;
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        // There WOULD be work at BOTH ingress stages: a declared want-set in the mailbox and
        // an applied one the probe would walk.
        offer(state, new IncomingRequest(1, 1, -1L));
        publish(state, new IncomingRequest(1, 1, -1L));
        processor.ticketQueue.add(new OffThreadProcessor.GenerationTicketRequest(
                uuid, 5, 5, "minecraft:overworld", 9L));

        service.tick();

        assertTrue(processor.snapshots.isEmpty(), "no snapshot posted");
        assertEquals(0, processor.sendActionDrains.get(), "no send-action drain");
        assertEquals(0, broadcaster.ticks, "broadcaster never ticks");
        assertEquals(1, processor.ticketQueue.size(), "ticket queue untouched");

        // The same rig does work once enabled — proves the recorders are live, not vacuous
        config.enabled = true;
        service.tick();
        assertEquals(1, processor.snapshots.size());
        assertEquals(1, processor.sendActionDrains.get());
        assertEquals(1, broadcaster.ticks);
        assertTrue(processor.ticketQueue.isEmpty());
    }

    // ---- PP-009: shutdown failure isolation ----

    @Test
    void throwingProcessorShutdownStillShutsDownDiskReaderAndGenerationAndClearsPlayers() {
        var uuid = UUID.randomUUID();
        service.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)), 1);
        processor.throwOnShutdown = true;

        assertDoesNotThrow(service::shutdown);

        assertTrue(processor.shutdownCalled);
        assertTrue(service.getPlayers().isEmpty(), "players cleared despite the processor throw");
        assertNull(diskReader.getPlayerQueue(uuid), "disk reader shut down (result queues cleared)");
        assertTrue(genService.shutdownCalled, "generation shutdown not skipped");
    }

    // ---- PP-010: probe budget, dedupe, generation-ready skip ----

    /**
     * The probe reads the MAILBOX before the published want-set. A batch that arrived since
     * the last routing cycle must be probed on its ARRIVAL tick — nothing is published yet,
     * because publishing is what the processing thread does when it APPLIES the batch, which
     * happens after this snapshot is built.
     *
     * <p>Without the mailbox arm a freshly declared position is never probed on its first
     * routing cycle, and a want-set that fits under the per-player slot cap has no second
     * cycle: it admits everything at once, the backlog drains, the want-set unpublishes.
     * That is the converged steady state and every single-position dirty-broadcast
     * re-request — i.e. the edited-loaded-column case, which would then disk-read
     * pre-edit bytes instead of serving live. Folia's hold-release makes the same alignment
     * deterministic; this is the sync path's equivalent, and only this test fails if it is
     * "simplified" back to peekWantSet() alone.
     */
    @Test
    void freshMailboxBatchIsProbedOnItsArrivalTickWithNothingPublished() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);

        long packed = PositionUtil.packPosition(1000, 0);
        // Ingress only — exactly what handleBatchRequest does. Nothing applied, nothing published.
        state.offerIncomingBatch(new IncomingBatch(
                new IncomingRequest[]{new IncomingRequest(1000, 0, -1L)}));
        assertNull(state.peekWantSet(),
                "premise: the processing thread has not applied the batch, so nothing is published");

        var probedPositions = new ArrayList<Long>();
        service.setLoadedColumnProbe((lvl, cx, cz) -> {
            probedPositions.add(PositionUtil.packPosition(cx, cz));
            return new LoadedColumnData(cx, cz, new byte[]{1}, 10);
        });

        service.tick();

        assertEquals(List.of(packed), probedPositions,
                "the freshly arrived batch must be probed on its arrival tick");
        var probes = processor.snapshots.get(0).loadedChunkProbes().get(uuid);
        assertNotNull(probes, "the arrival tick's snapshot must carry the probe the router "
                + "needs to resolve this very batch in-memory");
        assertNotNull(probes.get(packed));
    }

    @Test
    void probeBudgetCountsNullChunkAttemptsButNotDuplicateSkips() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);

        // Order matters (the want-set is declared closest-first and probed in that order):
        // success A, duplicate of A, 511 unloaded, one overflow.
        long packedA = PositionUtil.packPosition(1000, 0);
        var wants = new ArrayList<IncomingRequest>();
        wants.add(new IncomingRequest(1000, 0, -1L));
        wants.add(new IncomingRequest(1000, 0, 50L)); // duplicate: containsKey skip, must NOT charge the budget
        for (int i = 0; i < 511; i++) wants.add(new IncomingRequest(i, 1, -1L)); // unloaded: null probes DO charge it
        wants.add(new IncomingRequest(2000, 0, -1L)); // past the 512 budget: never probed
        publish(state, wants.toArray(new IncomingRequest[0]));

        var probedPositions = new ArrayList<Long>();
        service.setLoadedColumnProbe((lvl, cx, cz) -> {
            probedPositions.add(PositionUtil.packPosition(cx, cz));
            return cx == 1000 ? new LoadedColumnData(cx, cz, new byte[]{1}, 10) : null;
        });

        service.tick();

        assertEquals(512, probedPositions.size(),
                "budget is 512 ATTEMPTS: the success + all 511 null-chunk probes (a duplicate charging "
                        + "the budget would cut this to 511; a null probe not charging it would reach 513)");
        assertEquals(1, probedPositions.stream().filter(p -> p == packedA).count(),
                "the duplicate position is probed exactly once");
        assertTrue(probedPositions.contains(PositionUtil.packPosition(510, 1)),
                "the last in-budget unloaded position was attempted");
        assertFalse(probedPositions.contains(PositionUtil.packPosition(2000, 0)),
                "the position past the budget was not attempted this tick");

        var probes = processor.snapshots.get(0).loadedChunkProbes().get(uuid);
        assertNotNull(probes);
        assertEquals(1, probes.size(), "only the loaded column is snapshotted");
        assertNotNull(probes.get(packedA));
    }

    @Test
    void probeSkipsSameTickGenerationCompletions() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        publish(state, new IncomingRequest(7, 7, 0L), new IncomingRequest(8, 8, -1L));
        genService.nextTick = List.of(new TickSnapshot.GenerationReadyData(
                uuid, 7, 7, "minecraft:overworld", null, 0L, 1L));

        var probedPositions = new ArrayList<Long>();
        service.setLoadedColumnProbe((lvl, cx, cz) -> {
            probedPositions.add(PositionUtil.packPosition(cx, cz));
            return null;
        });

        service.tick();

        assertEquals(List.of(PositionUtil.packPosition(8, 8)), probedPositions,
                "a same-tick generation completion is never probed — probing it would double-serve");
        assertEquals(1, processor.postedGenerationReady.get(0).size(),
                "the generation outcome still reaches the snapshot post");
    }

    /**
     * Served-head filter: a position whose column payload already sits in the send pipeline
     * (enqueuedColumns) is skipped by the probe without charging the budget. Under backlog
     * retention the published want-set re-lists served positions every tick until the next
     * 1 Hz declaration — without the filter the probe re-serializes that head 20×/s per
     * player for a whole second.
     */
    @Test
    void probeSkipsPositionsWithAPayloadAlreadyInTheSendPipeline() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);

        long served = PositionUtil.packPosition(5, 5);
        publish(state, new IncomingRequest(5, 5, -1L), new IncomingRequest(6, 6, -1L));
        // The served head: its payload is staged for send but not flushed yet (the probe
        // runs before flushSendQueues within a tick).
        state.addReadyPayload(new QueuedPayload<>(new byte[]{1}, 8, 1L, served));
        assertTrue(state.hasEnqueuedColumn(served), "premise: payload is in the pipeline");

        var probedPositions = new ArrayList<Long>();
        service.setLoadedColumnProbe((lvl, cx, cz) -> {
            probedPositions.add(PositionUtil.packPosition(cx, cz));
            return null;
        });

        service.tick();

        assertEquals(List.of(PositionUtil.packPosition(6, 6)), probedPositions,
                "the in-pipeline position must be skipped (the router resolves it as a "
                        + "duplicate — the probe is guaranteed-unused)");
    }

    /**
     * The lifecycle pass rotates its starting player each tick. ConcurrentHashMap iteration
     * order is stable, so without rotation the players unlucky enough to iterate last get
     * zero probe coverage on EVERY tick once the global budget exhausts mid-pass — permanent
     * starvation, not fair sharing.
     */
    @Test
    void probePassRotatesItsStartingPlayerAcrossTicks() {
        var uuidA = UUID.randomUUID();
        var uuidB = UUID.randomUUID();
        var stateA = service.registerPlayer(playerIn(uuidA, level(Level.OVERWORLD)), 1);
        var stateB = service.registerPlayer(playerIn(uuidB, level(Level.OVERWORLD)), 1);
        long pA = PositionUtil.packPosition(1, 1);
        long pB = PositionUtil.packPosition(2, 2);
        publish(stateA, new IncomingRequest(1, 1, -1L));
        publish(stateB, new IncomingRequest(2, 2, -1L));

        var probedPositions = new ArrayList<Long>();
        service.setLoadedColumnProbe((lvl, cx, cz) -> {
            probedPositions.add(PositionUtil.packPosition(cx, cz));
            return null;
        });

        service.tick();
        service.tick();

        assertEquals(4, probedPositions.size(), "both players probed on both ticks");
        long tick1First = probedPositions.get(0);
        long tick1Second = probedPositions.get(1);
        assertTrue((tick1First == pA && tick1Second == pB) || (tick1First == pB && tick1Second == pA),
                "tick 1 probes each player once");
        assertEquals(List.of(tick1Second, tick1First), probedPositions.subList(2, 4),
                "tick 2 must start from the OTHER player — a fixed iteration order would "
                        + "starve whoever trails once the global budget exhausts mid-pass");
    }

    // ---- PP-038 (service leg): shutdown with queued work; late disk submits are inert ----

    @Test
    void shutdownWithQueuedWorkIsCleanAndLateDiskSubmitsAreInert() {
        var uuid = UUID.randomUUID();
        var level = level(Level.OVERWORLD);
        var player = playerIn(uuid, level);
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(1, 1, -1L));
        processor.ticketQueue.add(new OffThreadProcessor.GenerationTicketRequest(
                uuid, 5, 5, "minecraft:overworld", 9L));

        assertDoesNotThrow(service::shutdown);
        assertTrue(service.getPlayers().isEmpty());

        // A disk read racing the shutdown (async callback shape) must be a no-op:
        // no result queue exists and the submit must not throw into the caller.
        diskReader.setReadOverride((cx, cz) -> CompletableFuture.completedFuture(Optional.empty()));
        assertDoesNotThrow(() -> diskReader.submitReadDirect(uuid, "minecraft:overworld", level, 1, 1, 1L));
        assertNull(diskReader.getPlayerQueue(uuid));
        assertEquals(0, diskReader.getDiag().getSubmittedCount(),
                "post-shutdown submits are rejected before they are counted");
    }

    // ---- PP-012: shutdown freezes subsequent ticks (runtime plugin-manager disable) ----

    @Test
    void shutdownStopsSubsequentTicks() {
        service.shutdown();
        service.tick();
        assertEquals(0, broadcaster.ticks, "a post-shutdown tick must be a no-op");
        assertTrue(processor.snapshots.isEmpty(), "no snapshot may be posted after shutdown");
    }

    @Test
    void mailboxEventsAfterShutdownAreNotApplied() {
        // The overlapped-disable tick must not register into mid-teardown collaborators
        // (players.clear() vs registerPlayer, a shut-down disk reader): shuttingDown is
        // checked BEFORE the mailbox drain.
        var overworld = level(Level.OVERWORLD);
        service.shutdown();
        service.enqueueRegister(playerIn(UUID.randomUUID(), overworld), 1);
        service.tick();
        assertTrue(service.getPlayers().isEmpty(),
                "a register racing shutdown must not apply into torn-down collaborators");
    }

    // ---- PP-013: shutdown drains pending dirty marks into cache invalidations ----

    @Test
    void shutdownDrainsPendingDirtyMarksIntoInvalidationsBeforeProcessorShutdown() {
        // Marks accumulated since the last broadcast interval would otherwise die with the
        // tracker while the final cache save persists their pre-edit stamps — false
        // up_to_date for edited columns across the restart.
        service.getDirtyTracker().markDirty("minecraft:overworld", 3, 4);
        service.getDirtyTracker().markDirty("minecraft:the_end", 7, 8);
        service.shutdown();
        assertEquals(2, processor.invalidations.size(), "every dirty dimension must invalidate");
        assertTrue(processor.invalidationsSeenBeforeShutdown,
                "invalidations must be enqueued BEFORE the processor shutdown that saves the cache");
        assertEquals(0, service.getDirtyTracker().pendingCount(), "the tracker must be drained");
    }

    // ---- PP-011: lifecycle mailbox (Folia region-thread ingress → pump-owned apply) ----

    @Test
    void enqueuedRegisterAppliesAtNextTick() {
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(UUID.randomUUID(), overworld);
        service.enqueueRegister(player, 1);
        assertTrue(service.getPlayers().isEmpty(), "mailbox must not apply before tick");
        service.tick();
        assertEquals(1, service.getPlayers().size());
        assertTrue(service.getPlayers().get(player.getUUID()).hasCompletedHandshake());
    }

    @Test
    void enqueuedRemoveAppliesAtNextTick() {
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(UUID.randomUUID(), overworld);
        service.registerPlayer(player, 1);
        service.enqueueRemove(player.getUUID());
        assertEquals(1, service.getPlayers().size(), "mailbox must not apply before tick");
        service.tick();
        assertTrue(service.getPlayers().isEmpty());
    }

    @Test
    void kickThenRejoinSameUuidPreservesArrivalOrder() {
        var overworld = level(Level.OVERWORLD);
        var uuid = UUID.randomUUID();
        var first = playerIn(uuid, overworld);
        service.registerPlayer(first, 1);
        // Quit and re-handshake land in the mailbox before the pump runs (Folia region threads).
        service.enqueueRemove(uuid);
        var rejoined = playerIn(uuid, overworld);
        service.enqueueRegister(rejoined, 1);
        service.tick();
        assertEquals(1, service.getPlayers().size());
        assertSame(rejoined, service.getPlayers().get(uuid).getPlayer(),
                "remove must apply before the re-register that followed it");
    }

    @Test
    void mailboxDrainsEvenWhenServiceDisabled() {
        // A disabled server still sees quits (onPlayerQuit enqueues unconditionally); the
        // drain must run BEFORE the enabled guard or the queue grows for the whole run.
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(UUID.randomUUID(), overworld);
        service.registerPlayer(player, 1);
        config.enabled = false;
        service.enqueueRemove(player.getUUID());
        service.tick();
        assertTrue(service.getPlayers().isEmpty(),
                "enqueued remove must apply even with enabled=false");
    }

    @Test
    void enqueuesFromForeignThreadsAreVisibleToThePump() throws Exception {
        // The mailbox's whole job is cross-thread handoff (Folia region threads → pump).
        var overworld = level(Level.OVERWORLD);
        int n = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < n; i++) {
                var player = playerIn(UUID.randomUUID(), overworld);
                futures.add(pool.submit(() -> service.enqueueRegister(player, 1)));
            }
            for (var f : futures) f.get();
        } finally {
            pool.shutdown();
        }
        service.tick();
        assertEquals(n, service.getPlayers().size(), "all foreign-thread registers must apply");
    }
}
