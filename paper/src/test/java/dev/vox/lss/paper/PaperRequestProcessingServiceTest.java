package dev.vox.lss.paper;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.IncomingBatch;
import dev.vox.lss.common.processing.IncomingRequest;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.common.LSSConstants;
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
            super(players, diskReader, true, null, 32, 0);  // memo off: pins the ttl=0 read path
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
        public void drainSendActions(OffThreadProcessor.BatchSender<PaperPlayerRequestState> sender,
                                     OffThreadProcessor.StampsSink<PaperPlayerRequestState> sink) {
            // The service's tick calls the 2-arg drain (stamped up_to_date); this rig
            // only counts drains, so both arities record identically.
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

    @Test
    void batchRequestGateUsesPerWorldLodOverrideByDimensionId() {
        config.lodDistanceChunks = 16;
        config.lodDistanceChunksByWorld.put("minecraft:the_nether", 8); // gate at 8 + 32 = 40
        var player = playerIn(UUID.randomUUID(), level(Level.NETHER));
        var state = service.registerPlayer(player, 1);

        service.handleBatchRequest(player, batchOf(
                new long[]{
                        PositionUtil.packPosition(40, -40),  // exactly at the override gate: accepted
                        PositionUtil.packPosition(41, 0),    // one past: dropped
                        PositionUtil.packPosition(0, -41)}, // negative side: dropped
                new long[]{77L, 1L, 2L}));

        assertEquals(1, state.getTotalRequestsReceived(),
                "the nether override shrinks the gate to 40, not the default 48");
        var accepted = pendingBatch(state);
        assertEquals(1, accepted.size());
        assertEquals(40, accepted.get(0).cx());
        assertEquals(-40, accepted.get(0).cz());
        assertEquals(2, state.drainPendingRangeFiltered());
    }

    @Test
    void unregisteredBatchSendsRateLimitedReattachPromptMidHandshakeDoesNot() {
        // R2-3: after a plugin /reload the fresh service has an empty player map while
        // every connected LSS client keeps declaring at 1 Hz — proof of an orphaned
        // session (vanilla clients never speak the channel; a live client cannot declare
        // before its deferred-reply registration ran). The service prompts a re-attach
        // (the v16-dialect config — a modern client's downgrade guard answers with a
        // fresh handshake; a genuine v16 client parses it harmlessly), rate-limited.
        var level = level(Level.OVERWORLD);
        var batch = batchOf(new long[]{PositionUtil.packPosition(1, 1)}, new long[]{-1L});
        var prompted = new ArrayList<UUID>();
        service.reattachPromptSender = p -> prompted.add(p.getUUID());

        var stranger = playerIn(UUID.randomUUID(), level);
        service.handleBatchRequest(stranger, batch);
        service.handleBatchRequest(stranger, batch); // 1 Hz re-declaration inside the window
        assertEquals(1, prompted.size(), "one prompt per rate-limit window, not one per batch");
        assertEquals(stranger.getUUID(), prompted.get(0));
        assertTrue(players.isEmpty(), "the prompt never registers anything itself");

        // state present but handshake never completed: a HEALTHY client whose deferred
        // reply is in flight — the batch is dropped and NO prompt fires (state == null
        // strictly; prompting here would race the real registration).
        var uuid = UUID.randomUUID();
        var p = playerIn(uuid, level);
        var bare = new PaperPlayerRequestState(p, 4, 4);
        players.put(uuid, bare);
        service.handleBatchRequest(p, batch);
        assertEquals(0, bare.getTotalRequestsReceived());
        assertNull(bare.peekIncomingBatch(), "no batch is offered before the handshake completes");
        assertEquals(1, prompted.size(), "a mid-handshake state never prompts");
    }

    @Test
    void removalStampsThePromptGraceSoTheRemoveRegisterWindowNeverPrompts() {
        // The Folia dimension-change shape: removePlayer → (a 1 Hz batch races the gap) →
        // registerPlayer. removePlayer STAMPS the prompt rate-limit map, so the racing
        // state==null batch is the EXPECTED remove→register window, not an orphan — a
        // healthy mid-stream client must never be prompted (a prompt is a v16-dialect
        // config: at best a redundant re-handshake + manager rebuild, and pre-fix it could
        // land between live columns). A genuine /reload orphan is unaffected: the reload
        // rebuilds the service, so its map is fresh and prompts immediately.
        var level = level(Level.OVERWORLD);
        var batch = batchOf(new long[]{PositionUtil.packPosition(1, 1)}, new long[]{-1L});
        var prompted = new ArrayList<UUID>();
        service.reattachPromptSender = p -> prompted.add(p.getUUID());

        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level);
        service.registerPlayer(player, 1);
        service.removePlayer(uuid);

        service.handleBatchRequest(player, batch); // races in before the re-registration
        assertTrue(prompted.isEmpty(),
                "a state==null batch inside the post-removal grace must not prompt");

        // A dimension hop EXTENDS the bound (the old sweep RESET it): a second removal
        // re-stamps, and the window stays closed.
        service.registerPlayer(player, 1);
        service.removePlayer(uuid);
        service.handleBatchRequest(player, batch);
        assertTrue(prompted.isEmpty(), "re-removal re-arms the grace, never resets it open");
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

    @Test
    void dimensionChangeRePushesSessionConfigWithNewWorldDistance() {
        config.lodDistanceChunks = 512;
        config.lodDistanceChunksByWorld.put("minecraft:the_nether", 64);
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT);
        service.registerPlayer(player, 1);

        var sent = new ArrayList<Integer>();
        service.setSessionConfigSender((p, cfg, enabled) ->
                sent.add(PaperWorldLod.distance(cfg, p)));

        var nether = level(Level.NETHER);
        when(player.level()).thenReturn(nether);
        service.tick();

        assertEquals(List.of(64), sent,
                "a CURRENT-dialect dimension change must re-push SessionConfig with the new distance");
    }

    @Test
    void dimensionChangeDoesNotRePushWhenTheDistanceIsUnchanged() {
        // No overrides: both worlds resolve to the default, so the re-push must be
        // SKIPPED — the client rebuilds its manager on any SessionConfig, so an
        // unconditional push would tax every portal even with the feature off (the client
        // rebuilds its whole request manager on any SessionConfig).
        config.lodDistanceChunks = 512;
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT);
        service.registerPlayer(player, 1);

        var sent = new ArrayList<Integer>();
        service.setSessionConfigSender((p, cfg, enabled) ->
                sent.add(PaperWorldLod.distance(cfg, p)));

        var end = level(Level.END);
        when(player.level()).thenReturn(end);
        service.tick();

        assertTrue(sent.isEmpty(),
                "an equal-distance dimension change must NOT re-push SessionConfig");
        assertNotNull(service.getPlayers().get(uuid),
                "the dimension change is still processed (re-registration) — only the push is gated");
    }

    @Test
    void dimensionChangeDoesNotRePushWhenBothWorldsAreOverriddenToTheSameDistance() {
        // Configured-but-equal (not just empty map): two worlds explicitly overridden to
        // the same value must still skip the push — a "push whenever overrides exist" bug
        // would fail here.
        config.lodDistanceChunks = 512;
        config.lodDistanceChunksByWorld.put("minecraft:the_nether", 96);
        config.lodDistanceChunksByWorld.put("minecraft:the_end", 96);
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.NETHER));
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT);
        service.registerPlayer(player, 1);
        var sent = new ArrayList<Integer>();
        service.setSessionConfigSender((p, cfg, enabled) -> sent.add(PaperWorldLod.distance(cfg, p)));

        var end = level(Level.END);  // build the mock BEFORE the when() stub (no nested stubbing)
        when(player.level()).thenReturn(end);
        service.tick();

        assertTrue(sent.isEmpty(), "nether(96) → end(96): equal distance, no re-push");
    }

    @Test
    void dimensionChangeDoesNotRePushToALegacyDialectSession() {
        // The push is v20-shaped; a legacy (v18) session must never receive it even when
        // the distance genuinely differs — it picks the new world's distance up on rejoin.
        config.lodDistanceChunks = 512;
        config.lodDistanceChunksByWorld.put("minecraft:the_nether", 64);
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        service.registerPlayer(player, 1);
        var sent = new ArrayList<Integer>();
        service.setSessionConfigSender((p, cfg, enabled) -> sent.add(PaperWorldLod.distance(cfg, p)));

        var nether = level(Level.NETHER);  // build the mock BEFORE the when() stub (no nested stubbing)
        when(player.level()).thenReturn(nether);
        service.tick();

        assertTrue(sent.isEmpty(), "a legacy-dialect session gets no mid-session v20 push");
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
        assertDoesNotThrow(() -> diskReader.submitReadDirect(uuid, "minecraft:overworld", level, 1, 1, 1L, 0L));
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
    void testWiredShutdownDoesNotRetractAForeignMaskManager() {
        // The test-wiring ctor never publishes the x-ray mask manager (xrayMasks stays
        // null), so its shutdown's guarded retract must leave a live production manager
        // alone — the invariant behind PaperXrayMaskManager.deactivate(owner).
        var foreign = PaperXrayMaskManager.activate(new PaperConfig());
        try {
            service.shutdown();
            assertSame(foreign, PaperXrayMaskManager.current(),
                    "a service that never published must not retract the live manager");
        } finally {
            PaperXrayMaskManager.deactivate(foreign);
        }
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
    void deferredHandshakeReplyRunsOnlyAfterTheRegistrationApplies() {
        // The Folia pre-registration fix: the SessionConfig reply is handed to
        // enqueueRegister and must fire on the pump AFTER the player state exists —
        // never before the drain — so the client's first want-set always has a mailbox.
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(UUID.randomUUID(), overworld);
        var stateExistedAtReply = new java.util.concurrent.atomic.AtomicBoolean();
        var replied = new java.util.concurrent.atomic.AtomicBoolean();
        service.enqueueRegister(player, 1, () -> {
            stateExistedAtReply.set(service.getPlayers().containsKey(player.getUUID()));
            replied.set(true);
        });
        assertFalse(replied.get(), "the reply must not run before the drain");
        service.tick();
        assertTrue(replied.get(), "the drain must run the deferred reply");
        assertTrue(stateExistedAtReply.get(),
                "the reply must observe the registered state — reply-before-register re-opens "
                        + "the pre-registration first-declaration drop");
    }

    @Test
    void dialectFlipRunsOnThePumpBeforeRegistrationAndBeforeTheReply() {
        // The wire-dialect flip rides enqueueRegister's PRE-register hook, and both halves
        // of that placement are load-bearing (round-3 review):
        //   * BEFORE registerPlayer, which derives wantsCompressedColumns from isV16().
        //     Flipping after it left a v16 -> current re-handshake uncompressed all session.
        //   * ON THE PUMP, not the calling thread. On Folia the handshake arrives on a
        //     REGION thread; a flip applied there takes effect at once while the
        //     SessionConfig that re-arms the client's decoder waits for this drain, so the
        //     rest of that tick's flush could ship new-dialect columns to a decoder armed
        //     for the old one — a malformed frame and a disconnect.
        // Fixing the first bullet is what originally opened the second, so pin the order.
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(UUID.randomUUID(), overworld);
        var order = new java.util.ArrayList<String>();
        var stateExistedAtFlip = new java.util.concurrent.atomic.AtomicBoolean(true);
        service.enqueueRegister(player, 1,
                () -> {
                    order.add("flip");
                    stateExistedAtFlip.set(service.getPlayers().containsKey(player.getUUID()));
                },
                () -> order.add("reply"));
        assertTrue(order.isEmpty(), "neither hook may run before the drain — both are pump work");
        service.tick();
        assertEquals(java.util.List.of("flip", "reply"), order,
                "the dialect flip must precede the reply, with registration between them");
        assertFalse(stateExistedAtFlip.get(),
                "the flip must run BEFORE registerPlayer publishes state, or the session's "
                        + "compression flag is derived from the stale dialect");
        assertTrue(service.getPlayers().containsKey(player.getUUID()),
                "registration still applies in the same drain");
    }

    @Test
    void deferredHandshakeReplyNeverFiresAfterShutdown() {
        // A register racing shutdown is discarded with its reply: sending SessionConfig
        // from a torn-down service would invite declarations nobody will ever drain.
        var overworld = level(Level.OVERWORLD);
        var replied = new java.util.concurrent.atomic.AtomicBoolean();
        service.shutdown();
        service.enqueueRegister(playerIn(UUID.randomUUID(), overworld), 1, () -> replied.set(true));
        service.tick();
        assertFalse(replied.get(), "no deferred reply may fire once the service is shut down");
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

    // ---- Compressed-column session flag (plan §2: the four-term AND at registration) ----

    /** A second service over the same rig collaborators with wire compression LIVE. */
    private PaperRequestProcessingService liveCompressionService() {
        return new PaperRequestProcessingService(server, config,
                new PaperRequestProcessingService.Wiring(
                        players, diskReader, genService, processor, new DirtyColumnTracker(),
                        broadcaster, null, null, true));
    }

    @Test
    void compressedFlagSetForCapableClientWhenLive() {
        var svc = liveCompressionService();
        var player = playerIn(UUID.randomUUID(), level(Level.OVERWORLD));
        var state = svc.registerPlayer(player,
                LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS);
        assertTrue(state.wantsCompressedColumns());
    }

    @Test
    void compressedFlagClearWithoutTheCapabilityBit() {
        var svc = liveCompressionService();
        var state = svc.registerPlayer(playerIn(UUID.randomUUID(), level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        assertFalse(state.wantsCompressedColumns());
    }

    @Test
    void compressedFlagClearWhenCompressionNotLive() {
        // The rig's default service wires wireCompressionLive=false (config off or the
        // server-side native probe failed — plan §0.11): the capability bit alone must
        // never enable framing.
        var state = service.registerPlayer(playerIn(UUID.randomUUID(), level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS);
        assertFalse(state.wantsCompressedColumns());
    }

    @Test
    void compressedFlagClearForAV16DialectSessionEvenWithTheBit() {
        // A v16 handshake maliciously setting 0x2 must never get a codec byte — its wire
        // layout has nowhere to carry one. The manager is marked before registration on
        // the real handshake path; mirror that ordering here.
        var svc = liveCompressionService();
        var uuid = UUID.randomUUID();
        // The dialect TRACKER is the egress/derivation source of truth now; the real
        // dialectFlip marks both it and the manager's ingress session before register.
        svc.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V16);
        svc.getV16CompatManager().onHandshake(uuid);
        var state = svc.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS);
        assertFalse(state.wantsCompressedColumns());
    }

    // ---- v18 compat rung (docs/planning/v18-compat-design.md §2.3/§2.5) ----

    @Test
    void compressedFlagClearForAV18DialectSessionEvenWithTheBit() {
        // The v18 twin of the pin above: a real v0.8.x client hardcodes caps=1, so this
        // guards the HOSTILE caps=3 shape — the v18 layout has nowhere to carry a codec
        // byte either. The tracker is marked before registration (the dialectFlip runs
        // in the drain's beforeRegister); mirror that ordering here.
        var svc = liveCompressionService();
        var uuid = UUID.randomUUID();
        svc.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        var state = svc.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS);
        assertFalse(state.wantsCompressedColumns());
    }

    @Test
    void v18MembershipSurvivesTheDimensionChangeCycleAndKeepsTheFlagClear() {
        // The load-bearing lifecycle property (v18-compat §2.3, plan-review F6): the
        // dimension-change remove+register cycle calls removePlayer DIRECTLY (not the
        // mailbox Remove), so the membership must survive it and the re-derivation must
        // stay false — losing it here would re-grow the codec byte mid-session and
        // hard-kick the client on its next column.
        var svc = liveCompressionService();
        var uuid = UUID.randomUUID();
        svc.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        svc.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS);

        svc.removePlayer(uuid); // the dimension-change half: direct, NOT the mailbox
        assertTrue(svc.getDialectTracker().isV18(uuid),
                "removePlayer must not shed the v18 identity (dim changes reuse it)");

        var state = svc.registerPlayer(playerIn(uuid, level(Level.END)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS);
        assertFalse(state.wantsCompressedColumns(),
                "the re-registration must re-derive false through the surviving membership");
    }

    @Test
    void quitOriginatedMailboxRemoveDropsV18Membership() {
        // The quit-race leak guard (v18-compat §2.3, plan-review F2): a quit's direct
        // onDisconnect can run BEFORE a deferred Register's dialectFlip marked the
        // membership — the mailbox Remove draining AFTER the register must drop it, or
        // the entry (and the diag count) leaks forever. Sequence exactly the race:
        // enqueueRegister(mark) then enqueueRemove then the direct onDisconnect (no-op,
        // too early), then drain.
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        service.enqueueRegister(player, LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                () -> service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V18), () -> {});
        service.getDialectTracker().onDisconnect(uuid); // the quit's direct drop: too early
        service.enqueueRemove(uuid);
        service.tick(); // drain: Register (marks) then Remove (must drop the mark)
        assertFalse(service.getDialectTracker().isV18(uuid),
                "the mailbox Remove must drop membership the direct disconnect missed");
        assertEquals(0, service.getDialectTracker().sessionCount(dev.vox.lss.common.HandshakeGate.WireDialect.V18));
    }

    /** 2026-08-05 review H2: the lifecycle SWEEP (entity removed, no PlayerList entry —
     *  the quit event never fired) is a disconnect and must drop BOTH compat identities.
     *  The v18 half was closed by execution-review finding 2; the v16 twin was explicitly
     *  scoped out there and leaked the session (bounded memory + an inflated
     *  {@code V16Compat: clients=} diag count) until a same-UUID rejoin. */
    @Test
    void lifecycleSweepDropsBothCompatIdentities() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        service.getV16CompatManager().onHandshake(uuid);
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        service.registerPlayer(player, LSSConstants.CAPABILITY_VOXEL_COLUMNS);

        // The player's entity vanishes with no PlayerList entry and no quit event.
        when(player.isRemoved()).thenReturn(true);
        service.tick(); // the lifecycle pass sweeps the player out

        assertFalse(service.getDialectTracker().isV18(uuid),
                "the sweep drops v18 membership (execution-review finding 2)");
        assertFalse(service.getV16CompatManager().isV16(uuid),
                "…and the v16 session with it (review H2 — the leaked twin)");
    }

    // ---- C2 legacy egress routing (routeColumnFrame — XVER §4.2) ----
    //
    // The dialect ladder over a capturing sender: which header shape ships per dialect
    // and how a splice refusal contains. Bodies are translated at the ENQUEUE choke
    // point (PaperOffThreadProcessor.buildLegacyColumn — pinned with the corpus goldens
    // in PaperLegacyEgressTest), so this seam sees native-bodied frames and applies
    // only header shapes.

    private static byte[] ladderFrame(byte codec, byte[] body) {
        return PaperPayloadHandler.encodeVoxelColumnPreEncoded(3, 4, "minecraft:overworld",
                99L, LSSConstants.COLUMN_SOURCE_DISK, codec, body);
    }

    @Test
    void routeColumnFrameShipsCurrentSessionsVerbatim() {
        var state = service.registerPlayer(playerIn(UUID.randomUUID(), level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        byte[] frame = ladderFrame(LSSConstants.COLUMN_CODEC_RAW, new byte[] {0, 0});
        var sent = new ArrayList<byte[]>();
        service.routeColumnFrame(state, frame, sent::add);
        assertEquals(1, sent.size());
        assertSame(frame, sent.get(0), "verbatim — the same array, no re-encode");
    }

    @Test
    void routeColumnFrameShipsV19SessionsVerbatimAtTheCurrentHeader() {
        var uuid = UUID.randomUUID();
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V19);
        var state = service.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        byte[] frame = ladderFrame(LSSConstants.COLUMN_CODEC_RAW, new byte[] {0});
        var sent = new ArrayList<byte[]>();
        service.routeColumnFrame(state, frame, sent::add);
        assertEquals(1, sent.size());
        assertSame(frame, sent.get(0),
                "v19's header IS the current header and the body was translated at "
                        + "enqueue — nothing to rewrite at the flush seam");
    }

    @Test
    void routeColumnFrameComposesTheV18SpliceOnTheQueuedFrame() {
        var uuid = UUID.randomUUID();
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        var state = service.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        byte[] frame = ladderFrame(LSSConstants.COLUMN_CODEC_RAW, new byte[] {0});
        var sent = new ArrayList<byte[]>();
        service.routeColumnFrame(state, frame, sent::add);
        assertEquals(1, sent.size());
        assertArrayEquals(PaperPayloadHandler.rewriteColumnToV18(frame), sent.get(0),
                "the v18 egress strips exactly the codec byte");
    }

    @Test
    void routeColumnFrameComposesTheV16SpliceOnTheQueuedFrame() {
        var uuid = UUID.randomUUID();
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V16);
        service.getV16CompatManager().onHandshake(uuid);
        var state = service.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        byte[] frame = ladderFrame(LSSConstants.COLUMN_CODEC_RAW, new byte[] {0});
        var sent = new ArrayList<byte[]>();
        service.routeColumnFrame(state, frame, sent::add);
        assertEquals(1, sent.size());
        assertArrayEquals(PaperPayloadHandler.rewriteColumnToV16(frame), sent.get(0),
                "the v16 egress strips the source and codec bytes");
    }

    @Test
    void enqueueTranslatesLegacySessionBodiesThroughTheRealDialectRead() throws Exception {
        // Review MAJOR-1's discriminating pin: the REAL enqueue path — the tracker the
        // service attached, the dialect read on it, the translate branch, the queued
        // frame — driven end to end. The emitted frame must byte-equal a natively-built
        // frame (header preserved, body the frozen native golden); shipping the v20
        // body here is the C1-1 CRITICAL failure mode no other tier can see (the mock
        // client does not decode, and the soak lever is not in CI).
        var uuid = UUID.randomUUID();
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V19);
        var level = level(Level.OVERWORLD);
        when(level.registryAccess()).thenReturn(CorpusRegistryAccess.build());
        var state = service.registerPlayer(playerIn(uuid, level), LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        processor.updateDimensionContext("minecraft:overworld", level);

        byte[] v20 = PaperLegacyEgressTest.readCorpus("v20-corpus", "multi-section.bin");
        byte[] nativeGolden = PaperLegacyEgressTest.readCorpus("nbt-corpus", "multi-section.bin");
        boolean sent = processor.buildAndEnqueueColumnPayload(state, 3, 4, "minecraft:overworld",
                99L, 1L, dev.vox.lss.common.processing.ColumnBytes.ofRaw(null, v20),
                v20.length, LSSConstants.COLUMN_SOURCE_DISK);
        assertTrue(sent, "the legacy build must enqueue, not refuse");

        var sentFrames = new ArrayList<byte[]>();
        service.setColumnPayloadSender((s, data) -> sentFrames.add(data));
        awaitBandwidthWindow();
        service.tick();

        assertEquals(1, sentFrames.size(), "the queued legacy payload must flush");
        assertArrayEquals(PaperPayloadHandler.encodeVoxelColumnPreEncoded(3, 4,
                        "minecraft:overworld", 99L, LSSConstants.COLUMN_SOURCE_DISK,
                        LSSConstants.COLUMN_CODEC_RAW, nativeGolden), sentFrames.get(0),
                "the flushed frame must carry the TRANSLATED native body under the "
                        + "preserved header — a v20 body here is the C1-1 failure mode");
    }

    @Test
    void routeColumnFrameDropsWhenTheLegacySpliceRefusesTheFrame() {
        // The cross-dialect downgrade window: a codec-1 frame reaching a v18 session's
        // splice throws (nowhere to carry a codec) — contained as a warn-drop, never a
        // propagated exception (which would make flushSendQueue drop the whole queue).
        var uuid = UUID.randomUUID();
        service.getDialectTracker().onHandshake(uuid, dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        var state = service.registerPlayer(playerIn(uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var sent = new ArrayList<byte[]>();
        service.routeColumnFrame(state, ladderFrame(LSSConstants.COLUMN_CODEC_ZSTD, new byte[] {1, 2, 3}),
                sent::add);
        assertTrue(sent.isEmpty());
    }

    // ---- v0.11.0 stage C: runtime settings (/lsslod set) ----

    /**
     * THE SET-review ordering MAJOR, pinned: a registered-but-flip-pending player must
     * NOT be pushed a v20 SessionConfig. The re-push runs as a runtime task, and
     * runtime tasks drain AFTER the lifecycle mailbox — so the register's
     * beforeRegister dialect flip (the pump-only marking) is applied before the
     * enumeration. An implementation that drained runtime tasks first (or enumerated
     * from the command thread) would read the tracker's untracked-defaults-to-CURRENT
     * and push protocol-20 at a legacy client, killing its session until rejoin.
     */
    @Test
    void repushRuntimeTaskSkipsARegisteredButFlipPendingLegacyPlayer() {
        var sent = new ArrayList<UUID>();
        service.setSessionConfigSender((player, cfg, enabled) -> sent.add(player.getUUID()));

        var legacyUuid = UUID.randomUUID();
        var legacy = playerIn(legacyUuid, level(Level.OVERWORLD));
        // The handshake thread's shape: register enqueued with the dialect flip as
        // beforeRegister — BOTH still pending in the mailbox when the set arrives.
        service.enqueueRegister(legacy, LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                () -> service.getDialectTracker().onHandshake(legacyUuid,
                        dev.vox.lss.common.HandshakeGate.WireDialect.V18),
                () -> { });
        var results = new ArrayList<int[]>();
        service.enqueueRuntimeTask(() -> results.add(service.repushSessionConfig()));

        service.tick();

        assertEquals(1, results.size(), "the runtime task ran on the pump tick");
        assertEquals(0, results.get(0)[0], "the flip-pending legacy player must NOT be pushed");
        assertEquals(1, results.get(0)[1], "…it counts as a legacy skip instead");
        assertTrue(sent.isEmpty());
    }

    @Test
    void repushSendsToCurrentDialectSessionsAndSkipsLegacyOnes() {
        var sent = new ArrayList<UUID>();
        service.setSessionConfigSender((player, cfg, enabled) -> sent.add(player.getUUID()));

        var v20Uuid = UUID.randomUUID();
        service.registerPlayer(playerIn(v20Uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var v18Uuid = UUID.randomUUID();
        service.getDialectTracker().onHandshake(v18Uuid,
                dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        service.registerPlayer(playerIn(v18Uuid, level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);

        int[] counts = service.repushSessionConfig();
        assertEquals(1, counts[0], "exactly the CURRENT-dialect session is pushed");
        assertEquals(1, counts[1], "the v18 session is skipped (updates on rejoin)");
        assertEquals(List.of(v20Uuid), sent);
    }

    /** The tick-poll pass must reach EXISTING sessions (the old capture-at-registration
     *  split gave new joins the new cap while existing sessions kept the boot value). */
    @Test
    void runtimeGenPerPlayerCapReachesExistingSessionsOnTheNextTick() {
        var state = service.registerPlayer(playerIn(UUID.randomUUID(), level(Level.OVERWORLD)),
                LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        int boot = state.getGenSlotCap();
        config.generationConcurrencyLimitPerPlayer = boot + 5;
        service.tick();
        assertEquals(boot + 5, state.getGenSlotCap(),
                "the per-player cap must follow config on the next tick for EXISTING states");
    }

    /** The HANDLER-checked region-summary kill switch (P2 review I-m5): the plan's
     *  adversarial-m1 asked for a drop at the HANDLER, and this is the only pin
     *  distinguishing "handler-checked" from "advertisement-gated" — both the
     *  dedicated key and the master {@code enabled} must drop the request before
     *  the service's ingress counter can move. */
    @Test
    void regionSummaryKillSwitchDropsAtTheHandler() throws Exception {
        var tracker = new DirtyColumnTracker();
        var wired = new PaperRequestProcessingService(server, config,
                new PaperRequestProcessingService.Wiring(
                        players, diskReader, genService, processor, tracker, broadcaster,
                        null, null, false,
                        new dev.vox.lss.common.region.RegionStampTable(d -> null)));
        try {
            byte[] body = dev.vox.lss.common.region.RegionSummaryWire.encodeRequest(
                    new dev.vox.lss.common.region.RegionSummaryWire.Request(
                            "minecraft:overworld", 0, 0, 1));
            var uuid = UUID.randomUUID();
            config.enableRegionSummaries = false;
            wired.handleRegionSummaryRequest(uuid, body);
            assertEquals(0, wired.getRegionSummaries().diagnostics().getRequests(),
                    "enableRegionSummaries=false must drop at the handler");
            config.enableRegionSummaries = true;
            config.enabled = false;
            wired.handleRegionSummaryRequest(uuid, body);
            assertEquals(0, wired.getRegionSummaries().diagnostics().getRequests(),
                    "the master enabled=false gate must drop too");
            config.enabled = true;
            wired.handleRegionSummaryRequest(uuid, body);
            assertEquals(1, wired.getRegionSummaries().diagnostics().getRequests(),
                    "with both gates open the request reaches the service");
            // Legacy-never-eligible (stamped-up-to-date-plan.md §9.4, the pin the
            // 3-Opus fold found missing — dialectOf defaults CURRENT for unknown
            // UUIDs, so only an explicitly legacy-marked session exercises the guard):
            // a v18 session's request must neither admit NOR arm stamps eligibility.
            var legacy = UUID.randomUUID();
            wired.getDialectTracker().onHandshake(legacy,
                    dev.vox.lss.common.HandshakeGate.WireDialect.V18);
            wired.handleRegionSummaryRequest(legacy, body);
            assertEquals(1, wired.getRegionSummaries().diagnostics().getRequests(),
                    "a legacy-dialect request drops at the CURRENT-only guard");
            assertFalse(wired.getRegionSummaries().hasRequestedThisSession(legacy),
                    "and never becomes stamps-eligible");
        } finally {
            config.enabled = true;
            config.enableRegionSummaries = true;
            wired.shutdown();
        }
    }
}
