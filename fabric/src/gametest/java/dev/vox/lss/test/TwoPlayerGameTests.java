package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.PlayerRequestState;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * First multi-player executions of the shared pipeline on a dedicated server: cross-player
 * disk-read dedup, bandwidth fairness between a busy and an idle player, the
 * vanilla-client-coexistence contract, and the dirty broadcaster's per-player fan-out loop
 * (which no other test runs with more than one registered player).
 *
 * <p>Each test constructs its OWN {@code RequestProcessingService} (the live singleton must
 * stay player-free for {@code LSSGameTests}) and uses far negative chunk bands (−176..−210
 * relative to the mock spawn) disjoint from every other gametest class. All waits are
 * bounded {@code succeedWhen} polls on counters — never wall-clock sleeps.
 */
public class TwoPlayerGameTests {

    private static final int DEDUP_CHUNK_OFFSET = 180;
    private static final int VANILLA_CHUNK_OFFSET = 190;
    private static final int FANOUT_CHUNK_OFFSET = 200;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /**
     * SP-025: two real players with fully overlapping request sets through the live service
     * tick. The second player's requests must ATTACH to the first's in-flight dedup groups
     * — the disk reader sees exactly K submissions for 2×K requests — and the fan-out must
     * converge BOTH players (each receives every column) with zero held slots at rest. Both
     * batches are queued before the first tick, so both route in the same processing cycle,
     * strictly before any result can drain — the attach window is structural, not timed.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void overlappingRequestsFromTwoPlayersDedupeDiskReadsAndBothConverge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mockA = placeMockServerPlayer(helper);
        var mockB = placeMockServerPlayer(helper);
        int pcx = mockA.getBlockX() >> 4;
        int pcz = mockA.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();

        var positions = new long[3];
        var chunkPositions = new ChunkPos[3];
        for (int i = 0; i < 3; i++) {
            chunkPositions[i] = new ChunkPos(pcx - DEDUP_CHUNK_OFFSET, pcz + i);
            positions[i] = PositionUtil.packPosition(chunkPositions[i].x(), chunkPositions[i].z());
            chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPositions[i], 0);
            level.getChunk(chunkPositions[i].x(), chunkPositions[i].z());
        }
        // Release after generation: the serves must come from DISK (a loaded chunk
        // probe-serves and never engages the dedup tracker).
        helper.runAfterDelay(4, () -> {
            for (var pos : chunkPositions) {
                chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, pos, 0);
            }
        });

        var service = new RequestProcessingService(server);
        var stateA = service.registerPlayer(mockA, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var stateB = service.registerPlayer(mockB, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 6, "waiting for the ticket release");
            if (step.get() == 0) {
                for (var pos : chunkPositions) {
                    helper.assertTrue(chunkSource.getChunkNow(pos.x(), pos.z()) == null,
                            "waiting for the dedup chunks to unload");
                }
                level.save(null, true, false);
                // Both batches queued BEFORE the first tick: one routing cycle sees both.
                service.handleBatchRequest(mockA, new BatchChunkRequestC2SPayload(
                        positions, new long[]{-1L, -1L, -1L}, 3));
                service.handleBatchRequest(mockB, new BatchChunkRequestC2SPayload(
                        positions, new long[]{-1L, -1L, -1L}, 3));
                helper.assertTrue(stateA.getTotalRequestsReceived() == 3
                                && stateB.getTotalRequestsReceived() == 3,
                        "premise: all six requests must pass the distance guard");
                step.set(1);
                helper.assertTrue(false, "requests queued, awaiting dedup convergence");
            }
            service.tick();
            var diskDiag = service.getDiskReader().getDiag();
            helper.assertTrue(stateA.getTotalSectionsSent() == 3 && stateB.getTotalSectionsSent() == 3,
                    "waiting for BOTH players to receive all three columns (fan-out delivery), A="
                            + stateA.getTotalSectionsSent() + " B=" + stateB.getTotalSectionsSent());
            helper.assertTrue(diskDiag.getSubmittedCount() == 3,
                    "six overlapping requests must submit exactly THREE disk reads (the second "
                            + "player attaches to the in-flight dedup groups), got "
                            + diskDiag.getSubmittedCount());
            helper.assertTrue(diskDiag.getSuccessfulReadCount() == 3,
                    "all three deduped reads must resolve with content");
            helper.assertTrue(stateA.getHeldSyncSlots() == 0 && stateA.getHeldGenSlots() == 0
                            && stateB.getHeldSyncSlots() == 0 && stateB.getHeldGenSlots() == 0,
                    "every slot must be free once both players converged");
            service.shutdown();
            playerList.remove(mockA);
            playerList.remove(mockB);
        });
    }

    /**
     * SP-062: bandwidth fairness with one busy and one idle handshaked player, composed
     * exactly as the service composes it (one shared allocation per round, both players
     * flushed against it). Pins: the idle player never spends; each round's busy spend is
     * bounded by the 250 ms burst window of its fair share (alloc/4 + one payload
     * overshoot); a send that empties the global bucket yields an observable zero-token
     * round (sampled sub-millisecond, before any refill) in which nothing flushes; and the
     * bucket recovers on later ticks. Bounds, never exact splits — refills are wall-time
     * driven and the loop only ever waits on tick-spaced counter observations.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 600)
    public void bandwidthFairnessBoundsBusySpendAndIdleDilutionRecovers(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var busyMock = placeMockServerPlayer(helper);
        var idleMock = placeMockServerPlayer(helper);
        long globalCap = 65536;
        int payloadBytes = 8192;
        var limiter = new SharedBandwidthLimiter(globalCap);
        var busy = new PlayerRequestState(busyMock, 200, 16);
        var idle = new PlayerRequestState(idleMock, 200, 16);
        busy.markHandshakeComplete();
        idle.markHandshakeComplete();
        var filler = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 1, 1, 1, true);
        for (int i = 0; i < 40; i++) {
            busy.addReadyPayload(new QueuedPayload<>(filler, payloadBytes, i,
                    PositionUtil.packPosition(1_020_000 + i, 99)));
        }
        var diag = new TickDiagnostics();
        var step = new AtomicInteger();
        var rounds = new AtomicInteger();
        var busyAtZero = new AtomicLong(-1);

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 2,
                    "waiting one tick so token buckets have elapsed time to refill from");
            if (step.get() == 0) {
                helper.assertTrue(rounds.incrementAndGet() < 100,
                        "the busy backlog must exhaust the global bucket within 100 tick-rounds");
                long alloc = limiter.getPerPlayerAllocation(2);
                long busyBefore = busy.getTotalBytesSent();
                long idleBefore = idle.getTotalBytesSent();
                busy.flushSendQueue(alloc, limiter, diag, p -> {});
                idle.flushSendQueue(alloc, limiter, diag, p -> {});
                long busyDelta = busy.getTotalBytesSent() - busyBefore;
                helper.assertTrue(idle.getTotalBytesSent() == idleBefore,
                        "an idle player must never spend tokens");
                helper.assertTrue(busyDelta <= alloc / 4 + payloadBytes,
                        "per-round busy spend must stay within the burst window of its fair "
                                + "share (alloc/4 + one payload overshoot): alloc=" + alloc
                                + " spent=" + busyDelta);
                // Sub-millisecond re-sample: no refill can run, so a send that emptied the
                // global bucket reads as a true zero-token state.
                if (limiter.getPerPlayerAllocation(2) == 0) {
                    long beforeZeroRound = busy.getTotalBytesSent();
                    busy.flushSendQueue(0, limiter, diag, p -> {});
                    helper.assertTrue(busy.getTotalBytesSent() == beforeZeroRound,
                            "a zero-token round must flush nothing");
                    busyAtZero.set(busy.getTotalBytesSent());
                    step.set(1);
                }
                helper.assertTrue(false, "round " + rounds.get() + " done, bucket not yet exhausted");
            }
            // Recovery: later ticks refill the global bucket and sends resume.
            long alloc = limiter.getPerPlayerAllocation(2);
            busy.flushSendQueue(alloc, limiter, diag, p -> {});
            helper.assertTrue(busy.getTotalBytesSent() > busyAtZero.get(),
                    "waiting for the refilled bucket to admit a post-exhaustion send");
            helper.assertTrue(idle.getTotalBytesSent() == 0,
                    "the idle player must end the test having spent nothing");
            helper.assertTrue(limiter.getTotalBytesSent() == busy.getTotalBytesSent(),
                    "global accounting identity: every counted byte must be the busy player's");
            playerList.remove(busyMock);
            playerList.remove(idleMock);
        });
    }

    /**
     * FP-014: a connected player that never handshakes (a vanilla client) is structurally
     * invisible to LSS — no state, no disk-reader queue, no send target (every LSS send
     * path iterates registered states) — and its batch-request frames are no-ops that leak
     * into nobody's queue, while a registered player's pipeline flows normally beside it.
     * Scoped pin: with no per-frame sender seam in the service (owned elsewhere), absence
     * of a player state IS the zero-LSS-frames guarantee, asserted alongside the
     * byte-accounting identity (every LSS byte belongs to the registered player).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 600)
    public void vanillaPlayerWithoutHandshakeStaysInvisibleWhilePipelineFlows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var vanilla = placeMockServerPlayer(helper);
        var registered = placeMockServerPlayer(helper);
        int pcx = registered.getBlockX() >> 4;
        int pcz = registered.getBlockZ() >> 4;
        var chunkPos = new ChunkPos(pcx - VANILLA_CHUNK_OFFSET, pcz + 6);
        long packed = PositionUtil.packPosition(chunkPos.x(), chunkPos.z());
        var chunkSource = level.getChunkSource();
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x(), chunkPos.z());

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(registered, LSSConstants.CAPABILITY_VOXEL_COLUMNS);

        // The vanilla player's frames are silent no-ops: no state created, nothing queued.
        service.handleBatchRequest(vanilla, new BatchChunkRequestC2SPayload(
                new long[]{packed}, new long[]{-1L}, 1));
        helper.assertTrue(!service.getPlayers().containsKey(vanilla.getUUID()),
                "a never-handshaked player's batch request must not create state");
        helper.assertTrue(service.getDiskReader().getPlayerQueue(vanilla.getUUID()) == null,
                "a never-handshaked player must have no disk-reader queue");
        helper.assertTrue(state.getTotalRequestsReceived() == 0,
                "the vanilla player's request must not leak into the registered player's queue");

        GameTestSeeding.seedRequest(state, packed, -1L);
        helper.succeedWhen(() -> {
            service.tick();
            helper.assertTrue(state.getTotalSectionsSent() == 1,
                    "waiting for the registered player's serve (pipeline must flow beside the "
                            + "vanilla player)");
            helper.assertTrue(!service.getPlayers().containsKey(vanilla.getUUID())
                            && service.getDiskReader().getPlayerQueue(vanilla.getUUID()) == null,
                    "the vanilla player must remain invisible after pipeline activity");
            helper.assertTrue(service.getBandwidthLimiter().getTotalBytesSent()
                            == state.getTotalBytesSent(),
                    "every LSS byte must be attributed to the registered player — there is no "
                            + "state through which the vanilla player could be sent anything");
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
            service.shutdown();
            playerList.remove(vanilla);
            playerList.remove(registered);
        });
    }

    /**
     * FP-053: the dirty propagation race with TWO registered holders — the broadcaster's
     * per-player fan-out loop's first multi-player execution. Player B's edit lands, A's
     * probe re-serve (the honest ts&le;0 re-resolution) delivers post-edit bytes BETWEEN
     * the edit and the save, and the save must still mark dirty against the LIVE filter
     * (probe serves never seed — a seed here would hash the save equal and swallow the
     * broadcast). The mark is then forwarded to this test's own service, whose broadcaster
     * fires after exactly intervalTicks manual ticks and must clear BOTH holders' done-bits
     * and invalidate the stamp — observable as both re-requests re-serving instead of
     * resolving up-to-date.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void editedColumnPropagatesToBothHoldersThroughBroadcastFanout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var liveService = LSSServerNetworking.getRequestService();
        helper.assertTrue(liveService != null, "live service required (the save hook feeds it)");
        var mockA = placeMockServerPlayer(helper);
        var mockB = placeMockServerPlayer(helper);
        int pcx = mockA.getBlockX() >> 4;
        int pcz = mockA.getBlockZ() >> 4;
        var chunkPos = new ChunkPos(pcx - FANOUT_CHUNK_OFFSET, pcz + 4);
        helper.assertTrue(FANOUT_CHUNK_OFFSET <= LSSServerConfig.CONFIG.lodDistanceChunks,
                "premise: the column must be inside the broadcaster's RAW lodDistance range");
        long packed = PositionUtil.packPosition(chunkPos.x(), chunkPos.z());
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var chunkSource = level.getChunkSource();
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x(), chunkPos.z());
        var editPos = new BlockPos(chunkPos.x() * 16 + 4, -61, chunkPos.z() * 16 + 4);

        var service = new RequestProcessingService(server);
        var stateA = service.registerPlayer(mockA, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var stateB = service.registerPlayer(mockB, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 2, "waiting for generation light to settle");
            switch (step.get()) {
                case 0 -> {
                    if (stateA.getTotalRequestsReceived() == 0) {
                        GameTestSeeding.seedRequest(stateA, packed, -1L);
                        GameTestSeeding.seedRequest(stateB, packed, -1L);
                    }
                    service.tick();
                    helper.assertTrue(stateA.getTotalSectionsSent() == 1
                                    && stateB.getTotalSectionsSent() == 1,
                            "waiting for both holders' initial probe serves to flush");
                    // Baseline the LIVE filter pre-edit (an earlier save's state).
                    var chunk = level.getChunk(chunkPos.x(), chunkPos.z());
                    var liveFilter = liveService.getDirtyContentFilter();
                    liveFilter.contentChanged(level, chunk, dim);
                    helper.assertTrue(!liveFilter.contentChanged(level, chunk, dim),
                            "premise: live filter baselined pre-edit");
                    // B's edit; A's re-ask is issued (and retried) in step 1 — the probe re-serve
                    // must land between edit and save.
                    var edit = level.getBlockState(editPos).is(Blocks.STONE)
                            ? Blocks.COBBLESTONE : Blocks.STONE;
                    level.setBlock(editPos, edit.defaultBlockState(), 3);
                    step.set(1);
                    helper.assertTrue(false, "edit placed, awaiting A's post-edit probe re-serve");
                }
                case 1 -> {
                    // A's ts<=0 re-ask re-resolves the flushed position via the loaded-chunk
                    // probe. That probe needs the chunk resident at snapshot-build time and the
                    // send pipeline clear; on slow (2-core) CI either can miss, and the design
                    // expects the client to RETRY (the one-shot re-ask was a documented flake).
                    // Keep the chunk resident and re-issue until the re-serve lands — but only
                    // when no re-ask is in flight, so A re-serves EXACTLY once (step 2 asserts A==3).
                    level.getChunk(chunkPos.x(), chunkPos.z());
                    if (stateA.getTotalSectionsSent() < 2
                            && GameTestSeeding.noDeclarationOutstanding(stateA)
                            && !stateA.hasEnqueuedColumn(packed)
                            && !stateA.hasPendingRequest(chunkPos.x(), chunkPos.z())) {
                        GameTestSeeding.seedRequest(stateA, packed, -1L);
                    }
                    service.tick();
                    helper.assertTrue(stateA.getTotalSectionsSent() == 2,
                            "waiting for A's post-edit re-serve (a ts<=0 re-ask of a flushed "
                                    + "position re-resolves)");
                    // A second toggle staged INSIDE this atomic drain-save-drain callback:
                    // a concurrent test's level.save may already have absorbed the first
                    // edit into the live filter, but this fresh edit differs from every
                    // possible baseline (grass/stone/cobble are pairwise distinct), so the
                    // save below marks deterministically. The probe-race no-seed DIRECTION
                    // is pinned single-player by probeServesLoadedChunkFromMemory...
                    var edit2 = level.getBlockState(editPos).is(Blocks.STONE)
                            ? Blocks.COBBLESTONE : Blocks.STONE;
                    level.setBlock(editPos, edit2.defaultBlockState(), 3);
                    var liveTracker = liveService.getDirtyTracker();
                    liveTracker.drainDirty(dim);
                    level.save(null, true, false);
                    long[] dirty = liveTracker.drainDirty(dim);
                    helper.assertTrue(containsPosition(dirty, packed),
                            "the save after A's mid-window probe re-serve must mark the edited "
                                    + "column dirty (save hook -> live filter -> live tracker)");
                    // Forward the mark to this test's own service and fire ITS broadcaster:
                    // intervalTicks manual ticks guarantee at least one broadcast pass.
                    service.getDirtyTracker().markDirty(dim, chunkPos.x(), chunkPos.z());
                    int intervalTicks = LSSServerConfig.CONFIG.dirtyBroadcastIntervalSeconds
                            * LSSConstants.TICKS_PER_SECOND;
                    for (int i = 0; i < intervalTicks; i++) {
                        service.tick();
                    }
                    step.set(2);
                    helper.assertTrue(false, "broadcast fired, awaiting both re-serves");
                }
                case 2 -> {
                    // Both holders re-request with their stored stamps: only a delivered
                    // fan-out (done-bit cleared + timestamp invalidated, per player) re-serves.
                    // Stamp 1L (not epochSeconds()+N): still >0 so the request claimsData and
                    // proves the done-bit fan-out, but a fresh re-serve re-stamps the cache at
                    // cycleNow (a large epoch second) which can never satisfy resolvedFromTimestamp's
                    // cachedTs <= clientTimestamp(1). A future stamp made this racy on 2-core CI —
                    // whichever holder routed first re-stamped, and the second then short-circuited
                    // to up_to_date off cachedTs(now) <= now+10000, freezing its section count.
                    //
                    // The re-asks are issued HERE and retried like a real client (mirrors step 1):
                    // a one-shot ask could route before the broadcaster's dirty-clear mailbox
                    // event applied on the processing thread and terminally resolve up_to_date
                    // off the stale done-bit (the documented 2-core CI flake, "A=3 B=1"). If the
                    // fan-out clear never arrives at all, every re-ask keeps bouncing up_to_date
                    // and the counts stay frozen — the regression this test pins still fails at
                    // maxTicks. The step-1 guards keep at most one ask in flight per holder, so
                    // each holder re-serves EXACTLY once.
                    level.getChunk(chunkPos.x(), chunkPos.z());
                    if (stateA.getTotalSectionsSent() < 3
                            && GameTestSeeding.noDeclarationOutstanding(stateA)
                            && !stateA.hasEnqueuedColumn(packed)
                            && !stateA.hasPendingRequest(chunkPos.x(), chunkPos.z())) {
                        GameTestSeeding.seedRequest(stateA, packed, 1L);
                    }
                    if (stateB.getTotalSectionsSent() < 2
                            && GameTestSeeding.noDeclarationOutstanding(stateB)
                            && !stateB.hasEnqueuedColumn(packed)
                            && !stateB.hasPendingRequest(chunkPos.x(), chunkPos.z())) {
                        GameTestSeeding.seedRequest(stateB, packed, 1L);
                    }
                    service.tick();
                    helper.assertTrue(stateA.getTotalSectionsSent() == 3
                                    && stateB.getTotalSectionsSent() == 2,
                            "BOTH holders must be re-served after the broadcast fan-out "
                                    + "(an undelivered clear resolves the re-request up-to-date "
                                    + "off the stale done-bit): A=" + stateA.getTotalSectionsSent()
                                    + " B=" + stateB.getTotalSectionsSent());
                    chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                    service.shutdown();
                    playerList.remove(mockA);
                    playerList.remove(mockB);
                }
                default -> helper.fail("unexpected fan-out step " + step.get());
            }
        });
    }

    private static boolean containsPosition(long[] positions, long packed) {
        if (positions == null) return false;
        for (long p : positions) {
            if (p == packed) return true;
        }
        return false;
    }
}
