package dev.vox.lss.paper;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.IncomingBatch;
import dev.vox.lss.common.processing.IncomingRequest;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regionized loaded-chunk probing — the Folia probe path of
 * {@link PaperRequestProcessingService}. On Folia the pump (global region thread) owns no
 * chunks, so a sync getChunkNow+serialize there races the owning region's writes; probing
 * is instead dispatched to each player's owning region via the EntityScheduler seam and the
 * published batch is merged into the NEXT tick's snapshot. These tests pin the hand-off
 * contract: sync probing fully skipped, one-tick merge, ownership-guarded reads,
 * dimension-change discard, departed-player sweep, the generation-outcome skip contract on
 * both the schedule and consume sides, the per-tick position cap, and merge-until-consumed.
 *
 * <p>The scheduler/ownership seams stand in for Folia's EntityScheduler and
 * {@code Bukkit.isOwnedByCurrentRegion}; running a captured task on the test thread models
 * the region thread executing between two pump ticks.
 */
class RegionProbeSchedulingTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- rig (mirrors PaperRequestProcessingServiceTest, plus the probe seams) ----

    private Map<UUID, PaperPlayerRequestState> players;
    private PaperChunkDiskReader diskReader;
    private PaperRequestProcessingServiceTest.RecordingProcessor processor;
    private PaperRequestProcessingServiceTest.RecordingGenService genService;
    private MinecraftServer server;
    private PlayerList playerList;
    private PaperConfig config;
    private PaperRequestProcessingService service;

    /** Tasks captured from the RegionTaskScheduler seam, in scheduling order. */
    private List<Runnable> scheduledTasks;

    @BeforeEach
    void buildRig() {
        config = new PaperConfig();
        config.validate();
        players = new ConcurrentHashMap<>();
        diskReader = new PaperChunkDiskReader(1, false);
        processor = new PaperRequestProcessingServiceTest.RecordingProcessor(players, diskReader);
        genService = new PaperRequestProcessingServiceTest.RecordingGenService(config);
        server = mock(MinecraftServer.class);
        playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        var tracker = new DirtyColumnTracker();
        var broadcaster = new PaperRequestProcessingServiceTest.RecordingBroadcaster(
                server, players, tracker, processor);
        service = new PaperRequestProcessingService(server, config,
                new PaperRequestProcessingService.Wiring(
                        players, diskReader, genService, processor, tracker, broadcaster));

        service.setRegionizedProbing(true);
        scheduledTasks = new ArrayList<>();
        service.setRegionTaskScheduler((player, task) -> scheduledTasks.add(task));
        service.setRegionOwnershipCheck((level, cx, cz) -> true);
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

    private static ServerPlayer playerIn(UUID uuid, ServerLevel level) {
        var p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.level()).thenReturn(level);
        when(p.chunkPosition()).thenReturn(new ChunkPos(0, 0)); // lifecycle stamps the gate's ring origin
        when(p.getName()).thenReturn(Component.literal("p-" + uuid.toString().substring(0, 8)));
        return p;
    }

    private static LoadedColumnData column(int cx, int cz) {
        return new LoadedColumnData(cx, cz, new byte[]{1, 2, 3}, 3);
    }

    /** The probe map the latest posted snapshot carries for the player, or null. */
    private Long2ObjectMap<LoadedColumnData> probesInLastSnapshot(UUID uuid) {
        var snapshot = processor.snapshots.get(processor.snapshots.size() - 1);
        return snapshot.loadedChunkProbes().get(uuid);
    }

    /** Declare a complete want-set into the MAILBOX — the source the Folia hold-release takes
     *  from. Each call REPLACES the previous declaration (latest-wins): a client that still
     *  wants an earlier position re-declares it, so multi-position seeds are ONE batch. */
    private static void offer(PaperPlayerRequestState state, IncomingRequest... reqs) {
        state.offerIncomingBatch(new IncomingBatch(reqs));
    }

    /** Stand in for the processing thread having APPLIED a want-set: what the SYNC (non-Folia)
     *  probe walks. The regionized path reads the mailbox instead — see {@link #offer}. */
    private static void publish(PaperPlayerRequestState state, IncomingRequest... reqs) {
        state.publishWantSet(new IncomingBatch(reqs));
    }

    /** True when a declaration is sitting in the mailbox (released or never held). */
    private static boolean hasPendingBatch(PaperPlayerRequestState state) {
        return state.peekIncomingBatch() != null;
    }

    // ---- RP-001: regionized mode schedules instead of probing synchronously ----

    @Test
    void regionizedTickSchedulesATaskAndNeverProbesOnThePump() {
        var probeCalls = new AtomicInteger();
        service.setLoadedColumnProbe((level, cx, cz) -> {
            probeCalls.incrementAndGet();
            return null;
        });
        var player = playerIn(UUID.randomUUID(), level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(3, 4, -1));

        service.tick();

        assertEquals(1, scheduledTasks.size(), "one probe task per player with a pending declaration");
        assertEquals(0, probeCalls.get(),
                "the pump must not probe synchronously in regionized mode (it owns no chunks on Folia)");
        assertTrue(processor.snapshots.get(0).loadedChunkProbes().isEmpty(),
                "nothing published yet, so the first snapshot carries no probes");
    }

    @Test
    void noTaskIsScheduledWithoutPendingRequests() {
        var player = playerIn(UUID.randomUUID(), level(Level.OVERWORLD));
        service.registerPlayer(player, 1);

        service.tick();

        assertTrue(scheduledTasks.isEmpty(),
                "a converged player declares nothing and must cost no region task");
    }

    @Test
    void syncProbingIsUnchangedWhenRegionizedProbingIsOff() {
        service.setRegionizedProbing(false);
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        // Sync mode walks the APPLIED want-set (no hold-release), so seed the published set.
        publish(state, new IncomingRequest(3, 4, -1));

        service.tick();

        assertTrue(scheduledTasks.isEmpty(), "sync mode never touches the region scheduler");
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertTrue(probes.containsKey(PositionUtil.packPosition(3, 4)),
                "sync mode serves the probe in the SAME tick's snapshot");
    }

    /** 2026-08-05 review P1, pump rung: a probe-suppressed want-set entry (just sent, or
     *  just answered up_to_date) must not be re-serialized by the sync probe pass while
     *  an unsuppressed sibling still probes. Reverting the {@code isProbeSuppressed}
     *  rung reds here. */
    @Test
    void syncProbeSkipsSuppressedPositionsAndStillProbesSiblings() {
        service.setRegionizedProbing(false);
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        long suppressed = PositionUtil.packPosition(3, 4);
        long sibling = PositionUtil.packPosition(3, 5);
        publish(state, new IncomingRequest(3, 4, -1), new IncomingRequest(3, 5, -1));
        state.stampProbeSuppress(suppressed);

        service.tick();

        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertFalse(probes.containsKey(suppressed),
                "a suppressed head must not re-serialize in the probe pass");
        assertTrue(probes.containsKey(sibling), "the unsuppressed sibling still probes");

        // The suppress mark dies with a dirty clear (the edited-column path): the next
        // pass probes it again.
        state.clearDiskReadDone(suppressed);
        service.tick();
        var probes2 = probesInLastSnapshot(uuid);
        assertNotNull(probes2);
        assertTrue(probes2.containsKey(suppressed),
                "an un-suppressed (dirty-cleared) position probes again immediately");
    }

    /** The Folia twin of the pin above: {@code snapshotProbePositions} — the held-batch
     *  snapshot the region task probes — honors the suppress mark too (plan review
     *  amendment 4). */
    @Test
    void regionProbeSnapshotSkipsSuppressedPositions() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        long suppressed = PositionUtil.packPosition(6, 7);
        long sibling = PositionUtil.packPosition(6, 8);
        offer(state, new IncomingRequest(6, 7, -1), new IncomingRequest(6, 8, -1));
        state.stampProbeSuppress(suppressed);

        service.tick();                    // schedules with the filtered snapshot
        assertEquals(1, scheduledTasks.size());
        scheduledTasks.get(0).run();       // "region thread" probes and publishes
        service.tick();                    // consumes into the next snapshot

        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertFalse(probes.containsKey(suppressed),
                "the region task must never have been handed the suppressed position");
        assertTrue(probes.containsKey(sibling), "the unsuppressed sibling rode the batch");
    }

    // ---- RP-002: one-tick merge and single consumption ----

    @Test
    void publishedBatchIsMergedIntoTheNextSnapshotAndConsumedOnce() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(5, 7, -1));

        service.tick();                    // schedules; snapshot 1 empty
        scheduledTasks.get(0).run();       // "region thread" probes and publishes
        service.tick();                    // consumes into snapshot 2

        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes, "the batch published between ticks lands in the next snapshot");
        assertTrue(probes.containsKey(PositionUtil.packPosition(5, 7)));

        service.tick();                    // snapshot 3: batch already consumed
        assertTrue(processor.snapshots.get(2).loadedChunkProbes().isEmpty(),
                "a consumed batch must not be served twice");
    }

    @Test
    void unconsumedPublishesMergePerPlayer() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(1, 1, -1));

        service.tick();                    // task 1 snapshots {(1,1)}
        // The client re-declares: (1,1) is still unsatisfied, so the newer want-set carries it
        // again alongside (2,2). This arrival supersedes the batch held for probing.
        offer(state, new IncomingRequest(1, 1, -1), new IncomingRequest(2, 2, -1));
        service.tick();                    // task 2 snapshots {(1,1),(2,2)}; nothing to consume

        // Task 1 serves (1,1); task 2's probe serves only (2,2) — its (1,1) read misses.
        service.setLoadedColumnProbe((level, cx, cz) -> cx == 1 ? column(cx, cz) : null);
        scheduledTasks.get(0).run();
        service.setLoadedColumnProbe((level, cx, cz) -> cx == 2 ? column(cx, cz) : null);
        scheduledTasks.get(1).run();

        service.tick();
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertTrue(probes.containsKey(PositionUtil.packPosition(1, 1))
                        && probes.containsKey(PositionUtil.packPosition(2, 2)),
                "publishes stacked before a consume merge instead of clobbering");
    }

    // ---- RP-008: the one-tick hold-release pipeline aligns requests with their probes ----

    @Test
    void freshRequestsAreHeldOneTickAndReleasedWithTheirProbeResults() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(5, 7, -1));

        service.tick();
        assertFalse(hasPendingBatch(state),
                "a fresh declaration is parked for one tick — invisible to routing, which "
                        + "takes batches only from the mailbox");

        scheduledTasks.get(0).run();
        service.tick();

        var released = state.peekIncomingBatch();
        assertNotNull(released,
                "the held batch is released back into the mailbox the tick its probe "
                        + "results are consumed");
        assertEquals(1, released.size());
        assertEquals(5, released.requests()[0].cx());
        assertEquals(7, released.requests()[0].cz());
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes, "request and probe result must meet in the same snapshot");
        assertTrue(probes.containsKey(PositionUtil.packPosition(5, 7)));
    }

    @Test
    void aLateProbeTaskNeverDelaysTheRelease() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(5, 7, -1));

        service.tick();                    // held; the scheduled task never runs ("region lagged")
        service.tick();

        assertTrue(hasPendingBatch(state),
                "release is unconditional — a late probe only misses, it never delays routing");
    }

    @Test
    void heldBatchLosesToANewerArrivalAndIsCountedSuperseded() {
        // The CAS pin. A client that re-declares during the hold tick has SUPERSEDED the held
        // batch: the newer want-set must win, the held one must die (never be resurrected —
        // that would resurrect wants the client has already dropped, e.g. after a teleport),
        // and the drop must be counted so the conservation ledger closes. Releasing before the
        // take is what makes this observable: taking first would empty the mailbox and let the
        // CAS succeed unconditionally, republishing the STALE batch while parking the fresh one.
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(1, 1, -1), new IncomingRequest(2, 2, -1));

        service.tick();                    // batch A held for probing
        assertFalse(hasPendingBatch(state));
        assertEquals(0, state.drainPendingSuperseded());

        // Batch B arrives while A is held: a disjoint want-set (the client moved on).
        offer(state, new IncomingRequest(9, 9, -1));
        service.tick();

        assertEquals(2, state.drainPendingSuperseded(),
                "the held batch lost the CAS: both of ITS entries are counted superseded");
        var pending = state.peekIncomingBatch();
        assertNull(pending, "the newer batch is now the one held for probing, not routable yet");
        assertEquals(2, scheduledTasks.size(), "the newer batch gets its own probe task");

        scheduledTasks.get(1).run();
        service.tick();

        var released = state.peekIncomingBatch();
        assertNotNull(released, "the newer batch releases on the following tick");
        assertEquals(1, released.size(), "only the newest declaration survives: " + released.size());
        assertEquals(9, released.requests()[0].cx(),
                "a superseded want must never be resurrected by the hold-release");
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes, "and it meets its own probe results");
        assertTrue(probes.containsKey(PositionUtil.packPosition(9, 9)));
    }

    @Test
    void heldBatchDiesWhenANewerBatchPassesThroughTheMailboxDuringTheHold() {
        // The pass-through pin (the resurrection bug the offer-generation guard closes —
        // docs/planning/v0.7.1-candidates.md #1): a newer batch that is offered AND taken
        // by the processing thread during the hold leaves the mailbox EMPTY, so the plain
        // CAS(null, held) found nothing and republished the STALE batch over the newer
        // declaration's already-applied backlog — resurrecting wants the client had
        // already dropped. The offer-generation guard (recorded before the hold's take)
        // refuses the republish.
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(1, 1, -1), new IncomingRequest(2, 2, -1));

        service.tick();                    // batch A held for probing
        assertFalse(hasPendingBatch(state));

        // Batch B passes THROUGH during the hold: offered, then taken — takeIncomingBatch
        // is exactly the routing-side consume the processing thread performs.
        offer(state, new IncomingRequest(9, 9, -1));
        assertEquals(9, state.takeIncomingBatch().requests()[0].cx());
        assertFalse(hasPendingBatch(state), "premise: the pass-through left the mailbox empty");

        service.tick();                    // the release tick

        assertFalse(hasPendingBatch(state),
                "the stale held batch must NOT be republished over B's already-applied "
                        + "backlog — latest-wins survives the pass-through shape");
        assertEquals(2, state.drainPendingSuperseded(),
                "the killed held batch is counted superseded (law A1)");
    }

    @Test
    void heldBatchDiesWithTheRemovedPlayer() {
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(6, 6, -1));

        service.tick();                    // held
        when(player.isRemoved()).thenReturn(true);
        service.tick();                    // lifecycle removes the player, dropping the batch

        var rejoined = playerIn(uuid, overworld);
        var freshState = service.registerPlayer(rejoined, 1);
        service.tick();

        assertFalse(hasPendingBatch(freshState),
                "a removed player's held batch must not resurrect into the rejoined state");
    }

    // ---- RP-003: the ownership guard bounds what the region task may read ----

    @Test
    void foreignRegionChunksAreNeitherProbedNorPublished() {
        var probedPositions = new ArrayList<Long>();
        service.setLoadedColumnProbe((level, cx, cz) -> {
            probedPositions.add(PositionUtil.packPosition(cx, cz));
            return column(cx, cz);
        });
        long owned = PositionUtil.packPosition(1, 1);
        service.setRegionOwnershipCheck((level, cx, cz) ->
                PositionUtil.packPosition(cx, cz) == owned);
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(1, 1, -1), new IncomingRequest(2, 2, -1));

        service.tick();
        scheduledTasks.get(0).run();
        service.tick();

        assertEquals(List.of(owned), probedPositions,
                "getChunkNow+serialize is only race-free for owned chunks; foreign positions must be skipped");
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertEquals(1, probes.size());
        assertTrue(probes.containsKey(owned));
    }

    // ---- RP-004: dimension-change discard ----

    @Test
    void batchPublishedUnderTheOldDimensionIsDiscardedNotServed() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(9, 9, -1));

        service.tick();                    // task captured against the overworld level
        var task = scheduledTasks.get(0);

        // The player hops dimensions; the lifecycle pass re-registers the state.
        var nether = level(Level.NETHER);
        when(player.level()).thenReturn(nether);
        service.tick();

        task.run();                        // late publish: overworld bytes under this uuid

        service.tick();
        assertTrue(processor.snapshots.get(2).loadedChunkProbes().isEmpty(),
                "an old-dimension batch must be discarded, never served under the new dimension");
        service.tick();
        assertTrue(processor.snapshots.get(3).loadedChunkProbes().isEmpty(),
                "the discarded batch is gone, not parked for a later tick");
    }

    // ---- RP-005: departed-player sweep ----

    @Test
    void latePublishForADepartedPlayerIsSweptNotServedOnRejoin() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(6, 6, -1));

        service.tick();                    // task captured
        var task = scheduledTasks.get(0);

        when(player.isRemoved()).thenReturn(true);   // playerList returns null: real disconnect
        service.tick();                    // lifecycle removes the player (and clears any batch)

        task.run();                        // late publish resurrects an entry for a gone player
        service.tick();                    // sweep: uuid no longer in the players map

        var rejoined = playerIn(uuid, overworld);    // same UUID, same dimension
        service.registerPlayer(rejoined, 1);
        service.tick();

        assertTrue(processor.snapshots.get(processor.snapshots.size() - 1)
                        .loadedChunkProbes().isEmpty(),
                "a swept batch must not survive to a rejoin of the same UUID");
    }

    // ---- RP-006: generation-outcome skip contract, both sides ----

    @Test
    void scheduleSnapshotSkipsPositionsWithAGenerationOutcomeThisTick() {
        var probedPositions = new ArrayList<Long>();
        service.setLoadedColumnProbe((level, cx, cz) -> {
            probedPositions.add(PositionUtil.packPosition(cx, cz));
            return null;
        });
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(3, 3, 0), new IncomingRequest(4, 4, -1));
        genService.nextTick = List.of(new TickSnapshot.GenerationReadyData(
                uuid, 3, 3, "minecraft:overworld", column(3, 3), 1L, 1L));

        service.tick();
        scheduledTasks.get(0).run();

        assertEquals(List.of(PositionUtil.packPosition(4, 4)), probedPositions,
                "a position with a generation outcome in this snapshot is excluded from the probe task");
    }

    @Test
    void consumeDropsPositionsWithAGenerationOutcomeInTheSameSnapshot() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        offer(state, new IncomingRequest(3, 3, -1), new IncomingRequest(4, 4, -1));

        service.tick();                    // task snapshots both positions
        scheduledTasks.get(0).run();       // batch = {(3,3), (4,4)}

        genService.nextTick = List.of(new TickSnapshot.GenerationReadyData(
                uuid, 3, 3, "minecraft:overworld", column(3, 3), 1L, 2L));
        service.tick();

        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertFalse(probes.containsKey(PositionUtil.packPosition(3, 3)),
                "a consumed probe must not shadow the same position's generation outcome");
        assertTrue(probes.containsKey(PositionUtil.packPosition(4, 4)));
    }

    // ---- RP-007: per-task position cap ----

    @Test
    void positionSnapshotCapsAtTheSharedPerTickProbeLimit() {
        var checks = new AtomicInteger();
        service.setRegionOwnershipCheck((level, cx, cz) -> {
            checks.incrementAndGet();
            return false;
        });
        var player = playerIn(UUID.randomUUID(), level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        var wants = new IncomingRequest[600];
        for (int i = 0; i < wants.length; i++) {
            wants[i] = new IncomingRequest(i, -i, -1);
        }
        offer(state, wants);

        service.tick();
        scheduledTasks.get(0).run();

        assertEquals(512, checks.get(),
                "the region task is bounded by the same 512-position cap as sync probing");
    }

    // ---- Folia review 2026-08-27: R1 (the published-want-set arm) + R9 (races, N>1) ----

    @Test
    void thePublishedWantSetArmProbesBetweenDeclarations() {
        // R1: before the second arm, an empty mailbox scheduled NOTHING — the probe
        // window advanced only at the client's declaration cadence, and every routing
        // cycle past the arrival tick ran with zero probe coverage (loaded chunks took
        // disk reads; on gen-disabled servers the loaded-but-never-saved park became
        // the steady state). The published want-set is exactly what the sync path's
        // second arm walks.
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        publish(state, new IncomingRequest(7, 8, -1)); // applied want-set, mailbox EMPTY

        service.tick();
        assertEquals(1, scheduledTasks.size(),
                "an empty mailbox with a live published want-set must still schedule "
                        + "the second-arm probe");
        scheduledTasks.get(0).run(); // "region thread" probes and publishes
        service.tick();
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes, "the second arm's results flow through the same consume path");
        assertTrue(probes.containsKey(PositionUtil.packPosition(7, 8)));
    }

    @Test
    void anArrivalTickSchedulesExactlyOneTask() {
        // The one-region-task-per-player-per-tick shape survives the second arm: a
        // fresh mailbox batch takes the held arm, and the published arm must NOT also
        // fire on the same tick.
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        publish(state, new IncomingRequest(1, 1, -1)); // an older applied set
        offer(state, new IncomingRequest(2, 2, -1));   // the fresh declaration

        service.tick();

        assertEquals(1, scheduledTasks.size(),
                "arrival tick: the held arm only — never a second task from the "
                        + "published arm");
    }

    @Test
    void aConvergedPlayerStillCostsNoRegionTask() {
        // The second arm must not tax convergence: no mailbox, no published set.
        var player = playerIn(UUID.randomUUID(), level(Level.OVERWORLD));
        service.registerPlayer(player, 1);
        service.tick();
        assertTrue(scheduledTasks.isEmpty());
    }

    @Test
    void aRegionTaskRacingTheTickNeverCorruptsTheHandoff() throws Exception {
        // R9: all pre-review cases ran the region task on the JUnit thread with the
        // pump idle — the one interleaving that cannot race. This runs the captured
        // task on a REAL thread concurrent with tick(): the regionProbeResults
        // compute-vs-remove handoff and the skipProbe reads must never throw or lose
        // the rig's single-threaded invariants. (Content assertions stay soft — which
        // tick consumes a racing publish is timing; the absence of exceptions and a
        // final-state drain are the pins.)
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var lvl = level(Level.OVERWORLD);
        var player = playerIn(uuid, lvl);
        var state = service.registerPlayer(player, 1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 500; i++) {
                offer(state, new IncomingRequest(i & 63, (i >> 6) & 63, -1));
                service.tick(); // schedules (held arm) or republishes
                if (!scheduledTasks.isEmpty()) {
                    var task = scheduledTasks.remove(scheduledTasks.size() - 1);
                    var f = pool.submit(task);   // the "region thread"
                    service.tick();              // races the consume/sweep
                    f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            service.tick(); // final drain — must not throw on any leftover publish
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void multiPlayerHoldIsolationAndDepartedSweep() {
        // R9: every pre-review case was single-player. Three players in mixed states:
        // A declares (held arm), B has only a published set (second arm), C is
        // converged. A departed B's late publish must be swept, never served.
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var lvl = level(Level.OVERWORLD);
        var uuidA = UUID.randomUUID();
        var uuidB = UUID.randomUUID();
        var stateA = service.registerPlayer(playerIn(uuidA, lvl), 1);
        var stateB = service.registerPlayer(playerIn(uuidB, lvl), 1);
        service.registerPlayer(playerIn(UUID.randomUUID(), lvl), 1); // C: converged

        offer(stateA, new IncomingRequest(1, 0, -1));
        publish(stateB, new IncomingRequest(2, 0, -1));
        service.tick();
        assertEquals(2, scheduledTasks.size(),
                "A's held arm + B's published arm; converged C costs nothing");

        // B departs BEFORE its task runs; the late publish must be swept, not served.
        service.removePlayer(uuidB);
        scheduledTasks.get(0).run();
        scheduledTasks.get(1).run();
        service.tick();
        for (var snap : processor.snapshots) {
            assertFalse(snap.loadedChunkProbes().containsKey(uuidB),
                    "a departed player's late region publish must never reach a snapshot");
        }
        boolean aServed = processor.snapshots.stream()
                .anyMatch(sn -> sn.loadedChunkProbes().containsKey(uuidA)
                        && sn.loadedChunkProbes().get(uuidA)
                                .containsKey(PositionUtil.packPosition(1, 0)));
        assertTrue(aServed, "the surviving player's probes still flow");
    }
}
