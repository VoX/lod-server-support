package dev.vox.lss.paper;

import dev.vox.lss.common.PositionUtil;
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
        diskReader = new PaperChunkDiskReader(1);
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
        state.addRequest(3, 4, -1);

        service.tick();

        assertEquals(1, scheduledTasks.size(), "one probe task per player with pending requests");
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

        assertTrue(scheduledTasks.isEmpty(), "an empty request queue schedules no region task");
    }

    @Test
    void syncProbingIsUnchangedWhenRegionizedProbingIsOff() {
        service.setRegionizedProbing(false);
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        state.addRequest(3, 4, -1);

        service.tick();

        assertTrue(scheduledTasks.isEmpty(), "sync mode never touches the region scheduler");
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertTrue(probes.containsKey(PositionUtil.packPosition(3, 4)),
                "sync mode serves the probe in the SAME tick's snapshot");
    }

    // ---- RP-002: one-tick merge and single consumption ----

    @Test
    void publishedBatchIsMergedIntoTheNextSnapshotAndConsumedOnce() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        state.addRequest(5, 7, -1);

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
        state.addRequest(1, 1, -1);

        service.tick();                    // task 1 snapshots {(1,1)}
        state.addRequest(2, 2, -1);
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
        state.addRequest(5, 7, -1);

        service.tick();
        assertNull(state.pollIncomingRequest(),
                "fresh arrivals are parked for one tick, not routable from the queue");

        scheduledTasks.get(0).run();
        service.tick();

        var released = state.pollIncomingRequest();
        assertNotNull(released,
                "the held batch is released the tick its probe results are consumed");
        assertEquals(5, released.cx());
        assertEquals(7, released.cz());
        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes, "request and probe result must meet in the same snapshot");
        assertTrue(probes.containsKey(PositionUtil.packPosition(5, 7)));
    }

    @Test
    void aLateProbeTaskNeverDelaysTheRelease() {
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        state.addRequest(5, 7, -1);

        service.tick();                    // held; the scheduled task never runs ("region lagged")
        service.tick();

        assertNotNull(state.pollIncomingRequest(),
                "release is unconditional — a late probe only misses, it never delays routing");
    }

    @Test
    void heldBatchDiesWithTheRemovedPlayer() {
        var uuid = UUID.randomUUID();
        var overworld = level(Level.OVERWORLD);
        var player = playerIn(uuid, overworld);
        var state = service.registerPlayer(player, 1);
        state.addRequest(6, 6, -1);

        service.tick();                    // held
        when(player.isRemoved()).thenReturn(true);
        service.tick();                    // lifecycle removes the player, dropping the batch

        var rejoined = playerIn(uuid, overworld);
        var freshState = service.registerPlayer(rejoined, 1);
        service.tick();

        assertNull(freshState.pollIncomingRequest(),
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
        state.addRequest(1, 1, -1);
        state.addRequest(2, 2, -1);

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
        state.addRequest(9, 9, -1);

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
        state.addRequest(6, 6, -1);

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
        state.addRequest(3, 3, 0);
        state.addRequest(4, 4, -1);
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
        state.addRequest(3, 3, -1);
        state.addRequest(4, 4, -1);

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
        for (int i = 0; i < 600; i++) {
            state.addRequest(i, -i, -1);
        }

        service.tick();
        scheduledTasks.get(0).run();

        assertEquals(512, checks.get(),
                "the region task is bounded by the same 512-position cap as sync probing");
    }
}
