package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.PendingRequest;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.common.processing.SlotType;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.server.ServerReceiverGlue;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.PlayerRequestState;
import dev.vox.lss.networking.server.RequestProcessingService;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link RequestProcessingService} surface driven through real (mock) {@code ServerPlayer}s on a
 * dedicated server:
 *
 * <ul>
 *   <li><b>Batch-request distance guard</b> — boundary acceptance at exactly
 *       {@code lodDistanceChunks + LOD_DISTANCE_BUFFER}, rejection one chunk beyond on both axes
 *       and in the negative direction, exact coordinate/timestamp round-trip for
 *       negative-quadrant positions (a sign or off-by-one bug here makes LSS appear dead in
 *       negative-coordinate quadrants), and the unregistered-player no-op.</li>
 *   <li><b>Player removal / lifecycle</b> — {@code removePlayer} cleans every per-player
 *       structure (players map, disk-reader result queue, in-flight generation entry), the
 *       {@code computeIfAbsent} re-registration contract (same state while present, fresh state
 *       after removal, capability update in place), and the tick lifecycle's auto-remove
 *       polarity: a discarded-but-still-listed player (the death/respawn shape) must KEEP its
 *       session, only a delisted player is auto-removed.</li>
 *   <li><b>Probe serve + no-filter-seed</b> — a loaded chunk is served from memory via the probe
 *       path, and that serve must NOT seed the {@code DirtyContentFilter}: a probe can land
 *       between another player's edit and the chunk's cooldown save, and a seed would make that
 *       save hash equal — silencing the dirty broadcast every other client needs.</li>
 * </ul>
 *
 * <p>Each test constructs its OWN {@code RequestProcessingService} instead of using the live
 * singleton: tests in the same batch run concurrently, and {@code LSSGameTests} asserts the live
 * service's players map is empty and its global bandwidth total is zero — registering mock
 * players or flushing columns through the live instance would break them. An own instance also
 * makes ticking manual, so every lifecycle transition is asserted after exactly one
 * deterministic {@code tick()}. The mock players themselves are real: vanilla's
 * {@code makeMockServerPlayerInLevel} places them in the server's player list with an
 * embedded-channel connection, so {@code ServerPlayNetworking.send} genuinely delivers.
 */
public class ServiceLifecycleGameTests {

    /** Probe chunk sits in the negative quadrant relative to the mock player's spawn — far from
     *  the positive-offset chunks SerializerParityGameTests edits and from spawn-loaded chunks. */
    private static final int PROBE_CHUNK_OFFSET = 80;
    private static final int GEN_CHUNK_OFFSET = 120;
    // C2 legacy-egress delivery legs (bands disjoint from every other gametest class:
    // TwoPlayer 180-210, RegionFault 220, Command 240).
    private static final int V18_DELIVERY_CHUNK_OFFSET = 130;
    private static final int V19_DELIVERY_CHUNK_OFFSET = 140;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void outboundBufferGaugeResolvesThroughTheRealMixinsAndChannel(GameTestHelper helper) {
        // The ONLY place the outbound-buffer gauge runs end-to-end: both accessor mixins
        // applied, the listener->Connection->Channel hops, and OutboundBufferMath against a
        // live netty channel. Its failure mode is silent and terminal (one warning, then
        // NO_SIGNAL forever), and a dead gauge reads exactly like "no buffer is building" —
        // a false negative on the measurement that decides whether transport deference is
        // ever armed (elytra-wall investigation §8.3).
        var server = helper.getLevel().getServer();
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        try {
            var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            Gt.assertTrue(helper, state.getOutboundPendingBytes() == -1,
                    "premise: nothing sampled before the first flush");

            service.tick(); // flushSendQueues samples the probe once per player per tick

            long pending = state.getOutboundPendingBytes();
            Gt.assertTrue(helper, pending >= 0,
                    "the gauge must resolve through the real mixins + channel; -1 means the"
                            + " accessors did not apply and the instrument is dead, got " + pending);
            Gt.assertTrue(helper, state.getOutboundPendingHighWater() >= pending,
                    "high-water must track the sampled value");
        } finally {
            service.shutdown();
        }
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void batchRequestDistanceGuardBoundaryAndNegativeCoords(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var stranger = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        try {
            var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;
            Gt.assertTrue(helper, pcx - maxDist < 0 && pcz - maxDist < 0,
                    "premise: spawn-relative far positions must reach the negative quadrant");

            long boundary = PositionUtil.packPosition(pcx + maxDist, pcz);
            long beyondX = PositionUtil.packPosition(pcx + maxDist + 1, pcz);
            long negBoundary = PositionUtil.packPosition(pcx - maxDist, pcz - maxDist);
            long negBeyond = PositionUtil.packPosition(pcx - maxDist - 1, pcz);
            long beyondZ = PositionUtil.packPosition(pcx, pcz + maxDist + 1);

            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    new long[]{beyondX, boundary, negBeyond, negBoundary, beyondZ},
                    new long[]{11L, -1L, 11L, 12345L, 11L}, 5));

            // The service is never ticked, so the declared want-set in the mailbox is exactly
            // what the guard let through.
            Gt.assertTrue(helper, state.getTotalRequestsReceived() == 2,
                    "only the two boundary positions must pass the distance guard, got "
                            + state.getTotalRequestsReceived());
            var batch = state.peekIncomingBatch();
            Gt.assertTrue(helper, batch != null && batch.size() == 2,
                    "the declared want-set must hold exactly the two boundary positions, got "
                            + (batch == null ? "no batch" : batch.size() + " entries"));
            var first = batch.requests()[0];
            Gt.assertTrue(helper, first.cx() == pcx + maxDist && first.cz() == pcz,
                    "request at exactly lodDistance+buffer must be accepted, got ["
                            + first.cx() + ", " + first.cz() + "]");
            Gt.assertTrue(helper, first.clientTimestamp() == -1L,
                    "client timestamp must survive intact, got " + first.clientTimestamp());
            var second = batch.requests()[1];
            Gt.assertTrue(helper, second.cx() == pcx - maxDist && second.cz() == pcz - maxDist,
                    "negative-quadrant boundary coords must round-trip exactly (sign bug in "
                            + "packing or distance), got [" + second.cx() + ", " + second.cz() + "]");
            Gt.assertTrue(helper, second.clientTimestamp() == 12345L,
                    "negative-quadrant timestamp must survive intact, got " + second.clientTimestamp());
            // v17: the three beyond-distance entries are dropped AT INGRESS and counted, not
            // silently vanished — the range_filtered counter is the only record they existed.
            Gt.assertTrue(helper, state.drainPendingRangeFiltered() == 3,
                    "the three beyond-distance entries must be counted range_filtered at ingress");

            // Unregistered player: silent no-op — no state created, nothing queued anywhere.
            service.handleBatchRequest(stranger, new BatchChunkRequestC2SPayload(
                    new long[]{boundary}, new long[]{-1L}, 1));
            Gt.assertTrue(helper, !service.getPlayers().containsKey(stranger.getUUID()),
                    "a batch request from an unregistered player must not create state");
            Gt.assertTrue(helper, state.getTotalRequestsReceived() == 2,
                    "an unregistered player's request must not leak into another player's queue");
        } finally {
            service.shutdown();
            playerList.remove(mock);
            playerList.remove(stranger);
        }
        helper.succeed();
    }

    /**
     * SP-016: extreme client-supplied chunk coordinates (Integer.MIN/MAX on both axes) must be
     * gated without overflow. The Chebyshev distance widens to long and clamps to
     * Integer.MAX_VALUE; a regression to naive int subtraction would wrap an extreme coord under
     * the distance gate and admit a far-off position — a disk read for a chunk light-years away.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void batchRequestDistanceGuardGatesExtremeCoordsWithoutOverflow(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        try {
            var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;

            long[] positions = {
                    PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MAX_VALUE),
                    PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MIN_VALUE),
                    PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE),
                    PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE),
                    PositionUtil.packPosition(pcx, pcz), // the only in-range position
            };
            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    positions, new long[]{-1L, -1L, -1L, -1L, 42L}, 5));

            Gt.assertTrue(helper, state.getTotalRequestsReceived() == 1,
                    "only the in-range position passes; the four extremes are gated without "
                            + "overflow, got " + state.getTotalRequestsReceived());
            var batch = state.peekIncomingBatch();
            Gt.assertTrue(helper, batch != null && batch.size() == 1,
                    "the declared want-set must hold only the in-range request, got "
                            + (batch == null ? "no batch" : batch.size() + " entries"));
            var req = batch.requests()[0];
            Gt.assertTrue(helper, req.cx() == pcx && req.cz() == pcz,
                    "the surviving request must be the player's own chunk, got ["
                            + req.cx() + ", " + req.cz() + "]");
            Gt.assertTrue(helper, state.drainPendingRangeFiltered() == 4,
                    "the four extreme coords must be counted range_filtered at ingress, not "
                            + "slip under the gate");

            // No tick has run, so the gate alone must not have submitted any disk/gen work.
            Gt.assertTrue(helper, service.getDiskReader().getPendingResultCount() == 0,
                    "a gated batch must not submit a disk read for an extreme coord");
            var gen = service.getGenerationService();
            Gt.assertTrue(helper, gen == null || gen.getActiveCount() == 0,
                    "a gated batch must not submit generation for an extreme coord");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * FP-006: shutdown() must be idempotent and tear down every per-player structure. Server stop
     * can call it after a manual shutdown (e.g. an integrated server published to LAN then closed),
     * so a second call must be a harmless no-op rather than an exception or a double-free.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void serviceShutdownIsIdempotentAndClearsEveryPerPlayerStructure(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        try {
            service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            Gt.assertTrue(helper, service.getPlayers().containsKey(uuid), "premise: a player is registered");
            Gt.assertTrue(helper, service.getDiskReader().getPlayerQueue(uuid) != null,
                    "premise: the disk-reader queue exists");

            service.shutdown();
            Gt.assertTrue(helper, service.getPlayers().isEmpty(), "shutdown clears the players map");
            Gt.assertTrue(helper, service.getDiskReader().getPlayerQueue(uuid) == null,
                    "shutdown tears down the disk-reader result queue");
            var gen = service.getGenerationService();
            Gt.assertTrue(helper, gen == null || gen.getActiveCount() == 0,
                    "shutdown clears any active generation");

            // The second call (server-stop after a manual shutdown) must not throw or re-break.
            service.shutdown();
            Gt.assertTrue(helper, service.getPlayers().isEmpty(), "a second shutdown stays clean");
        } finally {
            playerList.remove(mock);
        }
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void removePlayerCleansAllStateAndLifecycleAutoRemovesOnlyDelistedPlayers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        try {
            // Registration creates every per-player structure.
            var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            Gt.assertTrue(helper, service.getPlayers().containsKey(uuid),
                    "registered player must appear in the players map");
            Gt.assertTrue(helper, state.hasCompletedHandshake(),
                    "registerPlayer must complete the handshake");
            Gt.assertTrue(helper, state.getCapabilities() == LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                    "capabilities from the handshake must be stored");
            Gt.assertTrue(helper, service.getDiskReader().getPlayerQueue(uuid) != null,
                    "registration must create the disk-reader result queue");

            // computeIfAbsent contract: re-registering an online UUID updates the existing
            // state in place (capability change on re-handshake) and never replaces it.
            var reRegistered = service.registerPlayer(mock, 0);
            Gt.assertTrue(helper, reRegistered == state,
                    "re-registering an online player must return the SAME state, not wipe it");
            Gt.assertTrue(helper, state.getCapabilities() == 0,
                    "re-registration must apply the new capabilities to the existing state");
            Gt.assertTrue(helper, state.hasCompletedHandshake(),
                    "re-registration must keep the handshake complete");

            // Seed an in-flight generation, then removePlayer must clean every structure.
            // No tick() runs between submit and remove, so the entry cannot complete first.
            var gen = service.getGenerationService();
            Gt.assertTrue(helper, gen != null,
                    "generation service expected (gametest config has enableChunkGeneration=true)");
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            Gt.assertTrue(helper, gen.submitGeneration(uuid, level, pcx - GEN_CHUNK_OFFSET, pcz + GEN_CHUNK_OFFSET, 1L),
                    "a fresh generation service must accept a submission");
            Gt.assertTrue(helper, gen.getActiveCount() == 1, "submission must be tracked as active");

            service.removePlayer(uuid);
            Gt.assertTrue(helper, !service.getPlayers().containsKey(uuid),
                    "removePlayer must drop the players-map entry");
            Gt.assertTrue(helper, service.getDiskReader().getPlayerQueue(uuid) == null,
                    "removePlayer must remove the disk-reader result queue");
            Gt.assertTrue(helper, gen.getActiveCount() == 0,
                    "removePlayer must release the player's in-flight generation entry");
            Gt.assertTrue(helper, gen.getTotalRemovedInFlight() == 1,
                    "the released in-flight generation must be booked as removed (or the "
                            + "submitted/completed accounting never re-balances after a kick)");

            // After removal the same UUID re-registers with a FRESH state.
            var fresh = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            Gt.assertTrue(helper, fresh != state,
                    "a removed UUID must re-register with a fresh state object");

            // Lifecycle polarity: discarded but still in the player list is the death/respawn
            // shape — the session must survive. Removing on isRemoved() alone would wipe every
            // player's LOD session on every death.
            mock.discard();
            Gt.assertTrue(helper, mock.isRemoved(), "premise: discard marks the entity removed");
            Gt.assertTrue(helper, playerList.getPlayer(uuid) != null,
                    "premise: discard must not delist the player");
            service.tick();
            service.tick();
            Gt.assertTrue(helper, service.getPlayers().containsKey(uuid),
                    "a discarded-but-listed player must keep its session (death/respawn must not "
                            + "wipe LOD state)");

            // Only a delisted player auto-removes — and without any disconnect event, since a
            // direct player-list removal never fires one.
            playerList.remove(mock);
            Gt.assertTrue(helper, playerList.getPlayer(uuid) == null, "premise: player delisted");
            service.tick();
            Gt.assertTrue(helper, !service.getPlayers().containsKey(uuid),
                    "one tick must auto-remove a delisted player (disconnect-event-less cleanup)");
            Gt.assertTrue(helper, service.getDiskReader().getPlayerQueue(uuid) == null,
                    "lifecycle auto-remove must run the same per-player cleanup as removePlayer");
        } finally {
            service.shutdown();
            if (playerList.getPlayer(uuid) != null) {
                playerList.remove(mock);
            }
        }
        helper.succeed();
    }

    /**
     * The confirmed-MAJOR no-seed rule, reproduced at its exact danger window: baseline save →
     * another player edits → probe serves the loaded chunk from memory → the chunk's save runs.
     * The save's content check must still see the edit; if the probe serve had seeded the filter
     * with the post-edit bytes, the save would hash equal and the dirty broadcast every other
     * client needs would be swallowed. (Generation serves DO seed — freshly generated content
     * cannot be stale-held by anyone; probe serves must not.)
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void probeServesLoadedChunkFromMemoryWithoutSeedingDirtyFilter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var liveService = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, liveService != null,
                "live RequestProcessingService must be active (save-hook leg depends on it)");
        // The save-hook leg asserts through the LIVE service, but this test's player
        // registers on its own service — arm the P3 never-registered skip gate (one-way;
        // no Tier 2 test pins the skip).
        liveService.armSaveHookForTest();

        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        int cx = pcx - PROBE_CHUNK_OFFSET;
        int cz = pcz - PROBE_CHUNK_OFFSET;
        int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;
        Gt.assertTrue(helper, PositionUtil.chebyshevDistance(cx, cz, pcx, pcz) <= maxDist,
                "premise: the probe chunk must be inside the request distance guard");
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        long packed = PositionUtil.packPosition(cx, cz);
        // Grass surface block of the default superflat preset.
        var editPos = new BlockPos(cx * 16 + 4, -61, cz * 16 + 4);

        // Keep the chunk loaded for the whole test so the serve must come from the probe path.
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);

        var service = new RequestProcessingService(server);
        var filter = service.getDirtyContentFilter();
        // Review-P3 latch wiring pin (three-lens round): registerPlayer must arm the
        // save-hook gate — the Tier 2 arming seams cannot notice a wiring regression.
        Gt.assertTrue(helper, !service.hasEverRegisteredPlayer(), "premise: fresh service, latch unarmed");
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        Gt.assertTrue(helper, service.hasEverRegisteredPlayer(),
                "registerPlayer must flip the save-hook latch");

        // Tick 2 (generation light settled): baseline the filter like an earlier save would,
        // then edit, then request — the probe will serve the post-edit bytes.
        helper.runAfterDelay(2, () -> {
            var chunk = level.getChunk(cx, cz);
            Gt.assertTrue(helper, filter.contentChanged(level, chunk, dim),
                    "first observation must baseline the virgin filter");
            Gt.assertTrue(helper, !filter.contentChanged(level, chunk, dim),
                    "identical content must stay quiet once baselined");
            // Toggle so the edit is a real content change even if a previous run (the gametest
            // world persists) already left stone at this position.
            var edit = level.getBlockState(editPos).is(Blocks.STONE) ? Blocks.COBBLESTONE : Blocks.STONE;
            level.setBlock(editPos, edit.defaultBlockState(), 3);
            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    new long[]{packed}, new long[]{-1L}, 1));
            Gt.assertTrue(helper, state.getTotalRequestsReceived() == 1,
                    "the in-range request must be accepted");
        });

        var step = new AtomicInteger();
        helper.succeedWhen(() -> {
            Gt.assertTrue(helper, helper.getTick() >= 4, "waiting for the baseline+edit setup");
            switch (step.get()) {
                case 0 -> {
                    // Manual tick: main thread probes the loaded chunk, processing thread
                    // serves it, the next manual tick's flush sends it to the mock player.
                    service.tick();
                    Gt.assertTrue(helper, state.getTotalSectionsSent() >= 1,
                            "waiting for the probe serve to flush");
                    Gt.assertTrue(helper, 
                            service.getOffThreadProcessor().getDiagnostics().getTotalInMemory() == 1,
                            "the serve must come from the in-memory probe, not disk");
                    var chunk = level.getChunk(cx, cz);
                    Gt.assertTrue(helper, filter.contentChanged(level, chunk, dim),
                            "a probe serve must NOT seed the dirty filter: the save after the "
                                    + "edit no longer sees a change, swallowing the dirty "
                                    + "broadcast other clients need");
                    Gt.assertTrue(helper, !filter.contentChanged(level, chunk, dim),
                            "the check above must have stored the new hash (filter is live)");
                    step.set(1);
                    Gt.assertTrue(helper, false, "no-seed verified, running the live save-hook leg");
                }
                case 1 -> {
                    // Live end-to-end: edit → real save → the position must surface in the live
                    // dirty tracker (ChunkSaveDataHook → DirtyContentFilter → DirtyColumnTracker).
                    // Drain, save, and re-drain in one callback: saves and the broadcaster only
                    // run on the main thread, so nothing can interleave and steal the mark.
                    var edit = level.getBlockState(editPos).is(Blocks.STONE)
                            ? Blocks.COBBLESTONE : Blocks.STONE;
                    level.setBlock(editPos, edit.defaultBlockState(), 3);
                    liveService.getDirtyTracker().drainDirty(dim);
                    level.save(null, true, false);
                    long[] dirty = liveService.getDirtyTracker().drainDirty(dim);
                    Gt.assertTrue(helper, containsPosition(dirty, packed),
                            "a save after a real edit must mark the column dirty end-to-end "
                                    + "(save hook -> content filter -> dirty tracker)");
                    chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                    service.shutdown();
                    server.getPlayerList().remove(mock);
                }
                default -> Gt.fail(helper, "unexpected probe test step " + step.get());
            }
        });
    }

    /**
     * SP-016: Integer.MIN/MAX chunk coordinates fuzzed through the live
     * {@code handleBatchRequest} distance guard. Every extreme must be gated without
     * overflow (a wrapped Chebyshev distance would read as "near" and admit them) and
     * nothing may reach the disk reader or the generation service. With zero accepted
     * requests nothing can ever submit, so the zero-submission asserts are race-free.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void extremeCoordinateRequestsAreGatedWithoutOverflowOrSubmission(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        try {
            var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            int min = Integer.MIN_VALUE;
            int max = Integer.MAX_VALUE;
            long[] extremes = {
                    PositionUtil.packPosition(min, min),
                    PositionUtil.packPosition(max, max),
                    PositionUtil.packPosition(min, max),
                    PositionUtil.packPosition(max, min),
                    PositionUtil.packPosition(min, 0),
                    PositionUtil.packPosition(0, max),
            };
            // Mixed timestamps: -1 (sync), 0 (generation), >0 (resync) — every route must be gated.
            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    extremes, new long[]{-1L, 0L, 12345L, -1L, 0L, 12345L}, extremes.length));

            Gt.assertTrue(helper, state.getTotalRequestsReceived() == 0,
                    "extreme coordinates must be dropped by the distance guard (an overflowed "
                            + "Chebyshev distance admits them), got "
                            + state.getTotalRequestsReceived() + " accepted");
            service.tick();
            service.tick();
            Gt.assertTrue(helper, state.getHeldSyncSlots() == 0 && state.getHeldGenSlots() == 0,
                    "gated extremes must never hold a slot");
            Gt.assertTrue(helper, service.getDiskReader().getDiag().getSubmittedCount() == 0,
                    "gated extremes must never reach the disk reader, got "
                            + service.getDiskReader().getDiag().getSubmittedCount() + " submits");
            Gt.assertTrue(helper, service.getGenerationService() != null
                            && service.getGenerationService().getTotalSubmitted() == 0,
                    "gated extremes must never reach the generation service");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * FP-005: a second {@code startServiceForLan} on a server whose service is already
     * running must be a no-op. Identity is the whole pin: constructing a replacement
     * {@code RequestProcessingService} is the only thing that call can do, and construction
     * is what spawns a second processing thread and disk-reader pool — so unchanged identity
     * is exactly "no second thread/pool, no host re-handshake".
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void startServiceForLanIsIdempotentOnRunningService(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var live = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, live != null, "premise: the dedicated gametest server runs the live service");

        LSSServerNetworking.startServiceForLan(server);
        LSSServerNetworking.startServiceForLan(server);

        Gt.assertTrue(helper, LSSServerNetworking.getRequestService() == live,
                "startServiceForLan must keep the already-running service instance — a "
                        + "replacement would orphan the live service's processing thread and "
                        + "disk pool and wipe every registered player");
        helper.succeed();
    }

    /**
     * FP-006: {@code RequestProcessingService.shutdown()} is idempotent and the FIRST call
     * releases everything — players map emptied, in-flight generation entries released,
     * per-player disk-reader queues dropped. A second shutdown must throw nothing (the
     * server-stopping path can race a test/LAN teardown into a double call).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void serviceShutdownIsIdempotentAndReleasesPlayersAndGeneration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        // Same-tick (synchronous body, nothing can interleave on the server thread): the
        // ctor must publish the x-ray mask manager BEFORE any serve can run — the one
        // production wiring the masked parity gametest's self-activation does not cover.
        Gt.assertTrue(helper, dev.vox.lss.networking.server.XrayMaskManager.current() != null,
                "service construction must publish the x-ray mask manager");
        try {
            service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            var gen = service.getGenerationService();
            Gt.assertTrue(helper, gen != null, "generation service expected (gametest config)");
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            Gt.assertTrue(helper, gen.submitGeneration(uuid, level, pcx - 132, pcz + 132, 1L),
                    "premise: an in-flight generation entry must exist at shutdown");
            Gt.assertTrue(helper, gen.getActiveCount() == 1, "premise: entry tracked as active");

            service.shutdown();
            Gt.assertTrue(helper, dev.vox.lss.networking.server.XrayMaskManager.current() == null,
                    "shutdown must retract the x-ray mask manager");
            Gt.assertTrue(helper, service.getPlayers().isEmpty(),
                    "the first shutdown must clear the players map");
            Gt.assertTrue(helper, gen.getActiveCount() == 0,
                    "the first shutdown must release every in-flight generation entry "
                            + "(a held entry keeps its chunk force-loaded forever)");
            Gt.assertTrue(helper, service.getDiskReader().getPlayerQueue(uuid) == null,
                    "the first shutdown must drop the per-player disk-reader result queue");

            try {
                service.shutdown();
            } catch (Throwable t) {
                Gt.fail(helper, "a second shutdown must be a quiet no-op, threw: " + t);
            }
        } finally {
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * FP-011: a duplicate handshake through the REAL receiver body must reuse the existing
     * state (pendings, done-bits, queued requests all survive) and re-send the session
     * config. A caps=0 re-handshake through the receiver replies but — honest divergence
     * from the catalog sketch — does NOT touch the existing registration (the NO_CONSUMER
     * arm returns before registerPlayer, so capabilities stay as handshaken). The
     * router-skip consequence is pinned via the service-level capability update
     * ({@code registerPlayer(player, 0)}, the dimension-change/re-register path): the
     * skipped player's queued request stays unconsumed and its pending slot is neither
     * leaked nor torn down until disconnect.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void reHandshakeReusesStateResendsConfigAndCapsZeroSkipsRouting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mockA = placeMockServerPlayer(helper);
        var mockB = placeMockServerPlayer(helper);
        var uuidA = mockA.getUUID();
        var service = new RequestProcessingService(server);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        LSSServerNetworking.SessionConfigResponder recorder = replies::add;
        int pcx = mockA.getBlockX() >> 4;
        int pcz = mockA.getBlockZ() >> 4;

        LSSServerNetworking.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                mockA, service, recorder);
        var state = service.getPlayers().get(uuidA);
        Gt.assertTrue(helper, state != null && replies.size() == 1,
                "premise: first handshake must register and reply");

        // Seed live work: a held pending slot, a done-bit, and a declared want-set.
        Gt.assertTrue(helper, state.tryAdmit(new PendingRequest(pcx - 148, pcz - 12,
                        SlotType.SYNC_ON_LOAD, 0L)),
                "premise: pending seeded");
        state.markDiskReadDone(pcx - 148, pcz - 13);
        GameTestSeeding.seedRequest(state, PositionUtil.packPosition(pcx - 149, pcz - 12), -1L);
        Gt.assertTrue(helper, state.getTotalRequestsReceived() == 1 && state.peekIncomingBatch() != null,
                "premise: want-set declared");

        // Duplicate handshake: same instance, work survives, config re-sent.
        LSSServerNetworking.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                mockA, service, recorder);
        Gt.assertTrue(helper, service.getPlayers().get(uuidA) == state,
                "a duplicate handshake must reuse the SAME state (a replacement wipes pendings)");
        Gt.assertTrue(helper, replies.size() == 2,
                "a duplicate handshake must re-send the session config, got " + replies.size());
        Gt.assertTrue(helper, state.getHeldSyncSlots() == 1
                        && state.hasPendingRequest(pcx - 148, pcz - 12)
                        && state.hasDiskReadDone(pcx - 148, pcz - 13)
                        && state.peekIncomingBatch() != null
                        && state.peekIncomingBatch().size() == 1
                        && state.getBacklogSize() == 0,
                "pendings, done-bits, and the undelivered want-set must survive a duplicate "
                        + "handshake (no cycle has run, so the batch is still in the mailbox)");

        // caps=0 through the receiver: reply-no-register leaves the registration untouched.
        LSSServerNetworking.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, 0), mockA, service, recorder);
        Gt.assertTrue(helper, replies.size() == 3,
                "a caps=0 re-handshake must still be answered with the session config");
        Gt.assertTrue(helper, service.getPlayers().get(uuidA) == state
                        && state.getCapabilities() == LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                "the NO_CONSUMER arm must return before registerPlayer: the existing "
                        + "registration (and its capabilities) stays untouched");

        // Service-level capability update (re-register path): now the router must skip A.
        service.registerPlayer(mockA, 0);
        Gt.assertTrue(helper, state.getCapabilities() == 0, "premise: capabilities updated in place");

        // Control player proves a routing cycle ran end-to-end while A was skipped.
        var chunkPos = new ChunkPos(pcx - 152, pcz - 16);
        level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x, chunkPos.z);
        var stateB = service.registerPlayer(mockB, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        GameTestSeeding.seedRequest(stateB, PositionUtil.packPosition(chunkPos.x, chunkPos.z), -1L);

        helper.succeedWhen(() -> {
            service.tick();
            Gt.assertTrue(helper, 
                    service.getOffThreadProcessor().getDiagnostics().getTotalInMemory() >= 1,
                    "waiting for the control player's probe serve (proves routing cycles ran)");
            Gt.assertTrue(helper, state.peekIncomingBatch() != null && state.getBacklogSize() == 0,
                    "a caps=0 player's declared want-set must stay unconsumed — the router "
                            + "skips the player wholesale, so the batch is never even taken "
                            + "from the mailbox into the backlog");
            Gt.assertTrue(helper, state.getHeldSyncSlots() == 1
                            && state.hasPendingRequest(pcx - 148, pcz - 12),
                    "a caps=0 player's pending slot must be neither leaked nor torn down "
                            + "until disconnect");

            // Disconnect is the cleanup boundary: a fresh registration starts clean.
            service.removePlayer(uuidA);
            var fresh = service.registerPlayer(mockA, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            Gt.assertTrue(helper, fresh != state && fresh.getHeldSyncSlots() == 0
                            && !fresh.hasPendingRequest(pcx - 148, pcz - 12),
                    "disconnect must be the boundary that releases the skipped player's pendings");

            level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
            service.shutdown();
            playerList.remove(mockA);
            playerList.remove(mockB);
        });
    }

    /**
     * FP-013: a crafted caps=0 handshake frame through the REAL Fabric receiver body — the
     * H-55 zombie-registration site. The session config must be sent (the client needs the
     * server's answer to settle its session) but the players map must stay empty: a
     * registration here would create a state the router skips forever while the lifecycle
     * pass ticks it every tick.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void capsZeroHandshakeRepliesWithoutRegistering(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        try {
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, 0),
                    mock, service, replies::add);
            Gt.assertTrue(helper, replies.size() == 1,
                    "a caps=0 handshake must be answered with exactly one session config, got "
                            + replies.size());
            Gt.assertTrue(helper, replies.get(0).protocolVersion() == LSSConstants.PROTOCOL_VERSION
                            && replies.get(0).enabled(),
                    "the reply must advertise the server's protocol version and effective enabled");
            Gt.assertTrue(helper, !service.getPlayers().containsKey(mock.getUUID()),
                    "a caps=0 client must NOT be registered (zombie state the router skips forever)");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * WP-035 (server direction): a crafted foreign-version handshake through the real
     * receiver body must produce ZERO reply frames — replying would kick the client,
     * because a mismatched client's SessionConfig codec has a different field layout on
     * the same channel id — and must leave the connection and player list untouched.
     * Protocol 17 is the mismatch specimen: it never shipped, and BOTH its neighbors now
     * have compat rungs (16 since v0.7.0, 18 since v0.9.1 — see
     * v18HandshakeRegistersNativelyAndEchoesProtocol18 below), so PROTOCOL_VERSION - 1
     * no longer mismatches on default config.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void foreignVersionHandshakeProducesNoReplyAndNoKick(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        try {
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(17, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, replies::add);
            Gt.assertTrue(helper, replies.isEmpty(),
                    "a version-mismatched handshake must produce zero reply frames (any reply "
                            + "decodes as a DecoderException on the old client and kicks it), got "
                            + replies.size());
            Gt.assertTrue(helper, !service.getPlayers().containsKey(uuid),
                    "a version-mismatched client must not be registered");
            Gt.assertTrue(helper, playerList.getPlayer(uuid) == mock && !mock.isRemoved()
                            && mock.connection != null,
                    "the mismatch path must leave the player connected and its connection untouched");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * v18 compat rung (docs/planning/v18-compat-design.md §2.1/§5): a crafted protocol-18
     * handshake through the PRODUCTION Fabric receiver body — this is the pin that a call
     * site silently left on the 5-arg gate overload cannot pass (review F5: that miss
     * compiles clean and drops v18 clients to the v16 fallback, the exact symptom the rung
     * removes). The reply must be exactly one CURRENT-layout SessionConfig echoing 18 (the
     * v0.8.x gate hard-requires its own version), the player must register with v18
     * membership, and the session must be forced codec-RAW. Since C2 the test also runs a
     * DELIVERY leg: a probe serve must translate at the enqueue choke point
     * ({@code buildAndEnqueueColumnPayload} → {@code fromV20}) and flush through the
     * {@code asV18()} splice — a translation failure there answers up_to_date and never
     * enqueues, so {@code getTotalSectionsSent()} reaching 1 plus the surviving done-bit
     * is the deliver-vs-contain distinguisher.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void v18HandshakeRegistersNativelyAndEchoesProtocol18(GameTestHelper helper) {
        legacyDialectHandshakeAndDelivery(helper, LSSConstants.V18_COMPAT_PROTOCOL_VERSION,
                V18_DELIVERY_CHUNK_OFFSET);
    }

    /** The v19 rung through the PRODUCTION receiver (protocol 20, XVER §4.2): a v0.9.x
     *  client registers natively with the V19 dialect, the reply echoes 19, and since C2
     *  a probe serve must translate at enqueue and ship at the CURRENT header — same
     *  sectionsSent + done-bit distinguisher as the v18 twin above. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void v19HandshakeRegistersNativelyAndEchoesProtocol19(GameTestHelper helper) {
        legacyDialectHandshakeAndDelivery(helper, LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                V19_DELIVERY_CHUNK_OFFSET);
    }

    /** The shared handshake + C2 delivery body of the two dialect rung tests above. */
    private static void legacyDialectHandshakeAndDelivery(GameTestHelper helper,
                                                          int announcedVersion, int chunkOffset) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        // caps=3 (the HOSTILE shape for v18 — a real v0.8.x client hardcodes caps=1): with
        // the zstd bit set, the v18 forced-RAW assertion actually exercises the dialect
        // term of the derivation instead of passing vacuously off the missing bit.
        LSSServerNetworking.handleHandshake(
                new HandshakeC2SPayload(announcedVersion,
                        LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                | LSSConstants.CAPABILITY_ZSTD_COLUMNS),
                mock, service, replies::add);
        Gt.assertTrue(helper, replies.size() == 1,
                "a v" + announcedVersion + " handshake on default config must be answered, got "
                        + replies.size());
        Gt.assertTrue(helper, replies.get(0).protocolVersion() == announcedVersion,
                "the reply must echo protocol " + announcedVersion + " — the legacy client "
                        + "disables itself on any other version, got "
                        + replies.get(0).protocolVersion());
        // The echo must carry the REAL config, not zeroes (the dialect-19 soak lever
        // caught a decode-side flavor of this — pin the encode side too).
        Gt.assertTrue(helper, replies.get(0).enabled(),
                "the legacy echo must carry the real enabled flag");
        Gt.assertTrue(helper, replies.get(0).lodDistanceChunks() == LSSServerConfig.CONFIG.lodDistanceChunks,
                "the legacy echo must carry the real LOD distance, got "
                        + replies.get(0).lodDistanceChunks());
        var state = service.getPlayers().get(uuid);
        Gt.assertTrue(helper, state != null,
                "a v" + announcedVersion + " handshake must register natively (not fall to "
                        + "the v16 shim)");
        boolean v18 = announcedVersion == LSSConstants.V18_COMPAT_PROTOCOL_VERSION;
        Gt.assertTrue(helper, v18 ? service.getDialectTracker().isV18(uuid)
                        : service.getDialectTracker().isV19(uuid),
                "the session must carry its dialect membership (the egress gates on it)");
        Gt.assertTrue(helper, !service.getV16CompatManager().isV16(uuid),
                "a v" + announcedVersion + " session is NOT a v16 compat session");
        if (v18) {
            Gt.assertTrue(helper, !state.wantsCompressedColumns(),
                    "a v18 session must be forced codec-RAW even when the handshake "
                            + "(hostilely) declares the zstd capability bit");
        }

        // ---- C2 delivery leg: one probe-servable column through the legacy egress ----
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        int cx = pcx - chunkOffset;
        int cz = pcz - chunkOffset;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        // Loaded for the whole test: the serve must come from the in-memory probe.
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);
        service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                new long[]{PositionUtil.packPosition(cx, cz)}, new long[]{-1L}, 1));
        Gt.assertTrue(helper, state.getTotalRequestsReceived() == 1,
                "premise: the delivery-leg request must pass the distance guard");

        helper.succeedWhen(() -> {
            service.tick();
            Gt.assertTrue(helper, state.getTotalSectionsSent() >= 1,
                    "waiting for the probe serve to flush through the legacy egress — a "
                            + "translation failure at enqueue answers up_to_date and never "
                            + "sends a section, so this wait times out on one");
            Gt.assertTrue(helper, state.hasDiskReadDone(cx, cz),
                    "the done-bit must SURVIVE the flush (no drop path fired)");
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * CG-028: the REGISTER reply's field wiring at the Fabric call site — lodDistance
     * distinct from the protocol version (int transposition) and generationEnabled opposed
     * to enabled (boolean transposition) across the 4-field frame. The global config is
     * mutated and restored within this single synchronous callback — gametest callbacks own
     * the main thread, so no other test (or the live service tick) can observe the window.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void sessionConfigReplyWiresConfigFieldsByNameNotPosition(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        var config = LSSServerConfig.CONFIG;
        Gt.assertTrue(helper, config.enabled, "premise: gametest config runs enabled");

        int prevLod = config.lodDistanceChunks;
        boolean prevGenEnabled = config.enableChunkGeneration;
        var replies = new ArrayList<SessionConfigS2CPayload>();
        try {
            config.lodDistanceChunks = 251;
            config.enableChunkGeneration = false;

            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, replies::add);
        } finally {
            config.lodDistanceChunks = prevLod;
            config.enableChunkGeneration = prevGenEnabled;
        }
        try {
            Gt.assertTrue(helper, replies.size() == 1, "premise: REGISTER handshake must reply once");
            var reply = replies.get(0);
            Gt.assertTrue(helper, reply.enabled(),
                    "effectiveEnabled must be true (config enabled + service present)");
            Gt.assertTrue(helper, reply.lodDistanceChunks() == 251,
                    "lodDistanceChunks must wire from CONFIG.lodDistanceChunks, got "
                            + reply.lodDistanceChunks());
            Gt.assertTrue(helper, !reply.generationEnabled(),
                    "generationEnabled must wire from CONFIG.enableChunkGeneration"
                            + " (the concurrency caps left the 4-field wire payload)");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * C5 (review MAJOR-2): a legacy handshake with a FORCED Via mismatch through the
     * PRODUCTION Fabric ladder — the seam overload carries the probe answer, so this
     * is the only executable Fabric coverage of the denial (no test JVM has real Via;
     * the wiring contract test pins only source shape). Must produce ZERO reply
     * frames (silence — the legacy client ladders read any reply as a live server)
     * and register nobody; the same frame through the no-signal path must register,
     * pinning fail-open in the same production ladder.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void viaMismatchedLegacyHandshakeIsDeniedSilentlyThroughTheProductionLadder(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        try {
            // Via positively reports MC protocol 763 against a native of 774.
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(19, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, replies::add, 763, 774);
            Gt.assertTrue(helper, replies.isEmpty(),
                    "a Via-mismatched v19 handshake must stay SILENT, got "
                            + replies.size() + " reply frame(s)");
            Gt.assertTrue(helper, !service.getPlayers().containsKey(mock.getUUID()),
                    "a Via-mismatched legacy client must never register");

            // Fail-open twin: the same frame with no Via signal registers normally.
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(19, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, replies::add,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 774);
            Gt.assertTrue(helper, !replies.isEmpty(),
                    "no Via signal must leave the v19 rung untouched (fail-open)");
            Gt.assertTrue(helper, service.getPlayers().containsKey(mock.getUUID()),
                    "the no-signal handshake must register");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * FP-015: {@code enabled=false} freezes the tick wholesale — no snapshot is ever
     * posted, so the processing thread cannot route anything — and events posted while
     * frozen (here a done-bit clear) apply on the FIRST resumed cycle, before routing.
     * The discriminator: with the clear applied first, the ts&gt;0 re-request probe-serves
     * (in_memory=1); a lost or late clear resolves it up-to-date off the stale done-bit
     * (in_memory=0). Exactly one post-enable tick precedes the wait, so the test also
     * pins "resumes within a tick". The global flip is confined to this synchronous
     * callback with a finally-restore.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void disabledTickFreezesPipelineAndFrozenEventsApplyOnFirstResumedCycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkPos = new ChunkPos(pcx - 140, pcz - 4);
        long packed = PositionUtil.packPosition(chunkPos.x, chunkPos.z);
        var chunkSource = level.getChunkSource();
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x, chunkPos.z);

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        // Stale done-bit + a ts>0 request: only the frozen-queued clear makes it re-serve.
        state.markDiskReadDone(chunkPos.x, chunkPos.z);
        GameTestSeeding.seedRequest(state, packed, 5L);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var config = LSSServerConfig.CONFIG;
        try {
            config.enabled = false;
            for (int i = 0; i < 3; i++) {
                service.tick();
            }
            Gt.assertTrue(helper, diag.getTotalRequestsRouted() == 0,
                    "a disabled tick must post no snapshot — nothing can route while frozen");
            Gt.assertTrue(helper, state.peekIncomingBatch() != null && state.getBacklogSize() == 0,
                    "the declared want-set must sit un-taken in the mailbox while frozen — a "
                            + "frozen tick posts no snapshot, so the processing thread never "
                            + "reaches takeIncomingBatch()");
            // Event posted while frozen: lossless, must apply before the first resumed routing.
            service.getOffThreadProcessor().clearDiskReadDone(uuid, new long[]{packed});
        } finally {
            config.enabled = true;
        }
        service.tick(); // exactly one resumed tick — the wait below never ticks again

        helper.succeedWhen(() -> {
            Gt.assertTrue(helper, diag.getTotalRequestsRouted() == 1,
                    "the first resumed tick must post the snapshot that routes the frozen request");
            Gt.assertTrue(helper, diag.getTotalInMemory() == 1,
                    "the frozen done-bit clear must apply BEFORE routing: the ts>0 re-request "
                            + "must probe-serve (a stale done-bit answers it up-to-date instead)");
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * FP-017: a real {@code PlayerList.respawn} produces a genuinely different
     * {@code ServerPlayer} instance under the same UUID; the lifecycle pass must swap the
     * state's player reference in place (same state object — pendings and done-bits
     * survive) and subsequent requests must resolve against the NEW reference (the probe
     * reads {@code player.level()} and the flush sends through the new connection).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void respawnSwapsPlayerReferenceKeepingPendingAndDoneState(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var service = new RequestProcessingService(server);

        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        Gt.assertTrue(helper, state.tryAdmit(new PendingRequest(pcx - 144, pcz - 8,
                        SlotType.SYNC_ON_LOAD, 0L)),
                "premise: pending seeded before the respawn");
        state.markDiskReadDone(pcx - 144, pcz - 9);

        var fresh = playerList.respawn(mock, true, Entity.RemovalReason.DISCARDED);
        Gt.assertTrue(helper, fresh != mock, "premise: respawn must produce a NEW ServerPlayer instance");
        Gt.assertTrue(helper, playerList.getPlayer(uuid) == fresh,
                "premise: the player list must hold the respawned instance");
        Gt.assertTrue(helper, mock.isRemoved(), "premise: the old instance is removed");

        service.tick();
        Gt.assertTrue(helper, service.getPlayers().get(uuid) == state,
                "the respawn swap must keep the SAME state object (a teardown would wipe the "
                        + "session on every death)");
        Gt.assertTrue(helper, state.getPlayer() == fresh,
                "the lifecycle pass must swap the state's player reference to the respawned "
                        + "instance");
        Gt.assertTrue(helper, state.getHeldSyncSlots() == 1
                        && state.hasPendingRequest(pcx - 144, pcz - 8)
                        && state.hasDiskReadDone(pcx - 144, pcz - 9),
                "pendings and done-bits must survive the reference swap");

        // A request must now resolve against the NEW reference end-to-end.
        var chunkPos = new ChunkPos(pcx - 144, pcz - 10);
        var chunkSource = level.getChunkSource();
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x, chunkPos.z);
        GameTestSeeding.seedRequest(state, PositionUtil.packPosition(chunkPos.x, chunkPos.z), -1L);

        helper.succeedWhen(() -> {
            service.tick();
            Gt.assertTrue(helper, state.getTotalSectionsSent() >= 1,
                    "waiting for the post-respawn request to serve through the new player "
                            + "reference (probe + flush both read state.getPlayer())");
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
            service.shutdown();
            var listed = playerList.getPlayer(uuid);
            if (listed != null) {
                playerList.remove(listed);
            }
        });
    }

    /**
     * FP-022: the 512-probes-per-tick budget. 512 queue entries ahead of two loaded
     * requests exhaust the budget (misses count too), so the loaded pair must NOT be
     * probe-served — the budget pushes them to the disk reader, which serves them from
     * their saved state. Budget gone: the pair probe-serves (in_memory=2, submits=0) and
     * the test fails. The 512 fillers are pre-seeded done-bits answered up-to-date with
     * zero slot pressure, so nothing rate-limits and no retry modeling is needed.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 600)
    public void probeBudgetPushesTrailingLoadedRequestsToDiskWithoutStarvation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();
        var posK1 = new ChunkPos(pcx - 156, pcz - 20);
        var posK2 = new ChunkPos(pcx - 156, pcz - 21);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, posK1, 0);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, posK2, 0);
        level.getChunk(posK1.x, posK1.z);
        level.getChunk(posK2.x, posK2.z);
        // The pair must exist on disk: the budget routes them to the disk reader.
        level.save(null, true, false);

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        // ONE declared want-set: 512 filler entries AHEAD of the loaded pair. Far synthetic
        // NOTE (M3): these far seeds sit outside the served-set sweep radius; safe only
        // because maxTicks < EVICTION_INTERVAL_CYCLES (1200) — a budget bump past 1200
        // would let the sweep delete them mid-test.
        // coords: never loaded (probe miss), pre-seeded done-bit + ts>0 resolves them
        // up-to-date before slot admission.
        var packed = new long[514];
        var stamps = new long[514];
        for (int i = 0; i < 512; i++) {
            state.markDiskReadDone(1_000_000 + i, 77);
            packed[i] = PositionUtil.packPosition(1_000_000 + i, 77);
            stamps[i] = 5L;
        }
        packed[512] = PositionUtil.packPosition(posK1.x, posK1.z);
        stamps[512] = -1L;
        packed[513] = PositionUtil.packPosition(posK2.x, posK2.z);
        stamps[513] = -1L;
        GameTestSeeding.seedRequests(state, packed, stamps);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var diskDiag = service.getDiskReader().getDiag();
        helper.succeedWhen(() -> {
            service.tick();
            Gt.assertTrue(helper, diskDiag.getSuccessfulReadCount() >= 2 && state.getTotalSectionsSent() >= 2,
                    "waiting for the disk-served pair to flush (budget remainder must still serve)");
            Gt.assertTrue(helper, diag.getTotalInMemory() == 0,
                    "the trailing loaded pair must NOT be probe-served: 512 queue entries ahead "
                            + "of it must exhaust the per-tick probe budget (misses count too)");
            Gt.assertTrue(helper, diskDiag.getSubmittedCount() == 2,
                    "exactly the budget-excluded pair must reach the disk reader, got "
                            + diskDiag.getSubmittedCount());
            Gt.assertTrue(helper, diag.getTotalRequestsRouted() == 514,
                    "every request must be routed exactly once, got " + diag.getTotalRequestsRouted());
            Gt.assertTrue(helper, state.getHeldSyncSlots() == 0 && state.getHeldGenSlots() == 0,
                    "all slots must be free at rest");
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, posK1, 0);
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, posK2, 0);
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * FP-024: the probe pass's containsKey dedup — duplicate queued positions serialize
     * once and, critically, do NOT consume probe budget. The geometry makes the guard
     * observable: C×5 + 510 misses + loaded D is exactly 512 probed entries WITH the
     * guard (D makes the cut and probe-serves); without it the duplicates spend the
     * budget and D falls to the disk reader (in_memory=1, submits=1).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 600)
    public void duplicateQueuedPositionsSerializeOncePerProbePass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();
        var posC = new ChunkPos(pcx - 164, pcz - 24);
        var posD = new ChunkPos(pcx - 164, pcz - 25);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, posC, 0);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, posD, 0);
        level.getChunk(posC.x, posC.z);
        level.getChunk(posD.x, posD.z);

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        long packedC = PositionUtil.packPosition(posC.x, posC.z);
        // ONE declared want-set, in probe order: C, four duplicate re-asks of C, 510 misses,
        // then loaded D. The duplicate re-asks carry ts>0 so their routing outcome
        // (up-to-date off the fresh done-bit) is independent of whether the main-thread flush
        // already sent C's payload.
        var packed = new long[516];
        var stamps = new long[516];
        packed[0] = packedC;
        stamps[0] = -1L;
        for (int i = 1; i <= 4; i++) {
            packed[i] = packedC;
            stamps[i] = 5L;
        }
        // NOTE (M3): far seeds outside the sweep radius — see the sweep note above
        // (safe while maxTicks < 1200).
        for (int i = 0; i < 510; i++) {
            state.markDiskReadDone(1_010_000 + i, 88);
            packed[5 + i] = PositionUtil.packPosition(1_010_000 + i, 88);
            stamps[5 + i] = 5L;
        }
        packed[515] = PositionUtil.packPosition(posD.x, posD.z);
        stamps[515] = -1L;
        GameTestSeeding.seedRequests(state, packed, stamps);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var diskDiag = service.getDiskReader().getDiag();
        helper.succeedWhen(() -> {
            service.tick();
            Gt.assertTrue(helper, diag.getTotalRequestsRouted() == 516 && state.getTotalSectionsSent() >= 2,
                    "waiting for all 516 requests to route and both columns to flush");
            Gt.assertTrue(helper, diag.getTotalInMemory() == 2,
                    "C and D must BOTH probe-serve: duplicate positions must not consume probe "
                            + "budget (a guard regression pushes D past the 512 cap to disk)");
            Gt.assertTrue(helper, diskDiag.getSubmittedCount() == 0,
                    "nothing may reach the disk reader when the dedup guard holds, got "
                            + diskDiag.getSubmittedCount());
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, posC, 0);
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, posD, 0);
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * FP-039: a generation-stage ProtoChunk passing through the real save pass must not
     * mark dirty ({@code LSSServerNetworking.onChunkSaveData}'s {@code instanceof LevelChunk} guard) — proto
     * saves have no LOD-servable content, and marking them would broadcast positions that
     * then resolve not-found. The edited LevelChunk in the same save pass is the positive
     * control proving the save ran and the hook is live. Drain–save–drain runs in one
     * synchronous callback so no other test's marks interleave.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void protoChunkSavesAreExcludedFromDirtyMarking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var liveService = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, liveService != null, "live service required (the save hook feeds it)");
        // Arm the P3 never-registered skip gate — the control assertion below needs the
        // live hook to hash (one-way latch; no Tier 2 test pins the skip).
        liveService.armSaveHookForTest();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var chunkSource = level.getChunkSource();

        // Control: a loaded LevelChunk with an edit must mark in the same save pass.
        var controlPos = new ChunkPos(origin.x - 172, origin.z - 32);
        long controlPacked = PositionUtil.packPosition(controlPos.x, controlPos.z);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, controlPos, 0);
        level.getChunk(controlPos.x, controlPos.z);
        var editPos = new BlockPos(controlPos.x * 16 + 4, -61, controlPos.z * 16 + 4);
        var edit = level.getBlockState(editPos).is(Blocks.STONE) ? Blocks.COBBLESTONE : Blocks.STONE;
        level.setBlock(editPos, edit.defaultBlockState(), 3);

        // Proto: per-run salted coords — a previous run's chunk would load already-generated
        // and not be unsaved, making the save pass skip it and the assertion vacuous.
        int protoCx = origin.x - 168;
        int protoCz = origin.z + (int) Math.floorMod(System.nanoTime(), 64L);
        var proto = chunkSource.getChunk(protoCx, protoCz, ChunkStatus.STRUCTURE_STARTS, true);
        Gt.assertTrue(helper, proto != null && !(proto instanceof LevelChunk),
                "premise: a STRUCTURE_STARTS chunk must still be a ProtoChunk");
        proto.markUnsaved();
        long protoPacked = PositionUtil.packPosition(protoCx, protoCz);

        var tracker = liveService.getDirtyTracker();
        tracker.drainDirty(dim);
        level.save(null, true, false);
        long[] dirty = tracker.drainDirty(dim);
        Gt.assertTrue(helper, containsPosition(dirty, controlPacked),
                "premise/control: the edited LevelChunk must mark dirty in this save pass "
                        + "(proves the save ran and the hook is live)");
        Gt.assertTrue(helper, !containsPosition(dirty, protoPacked),
                "a ProtoChunk save must NOT mark dirty (ChunkSaveDataHook must exclude "
                        + "generation-stage saves — they have no LOD-servable content)");
        chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, controlPos, 0);
        helper.succeed();
    }

    /**
     * WP-028 (T2 transport leg): the largest column the server-side guard admits
     * (sectionBytes == MAX_SECTIONS_SIZE exactly) ships through the REAL flush + Fabric
     * send path to a mock player's connection without an exception-drop, and its true
     * encoded frame size is pinned against the overhead estimate. The mock's local
     * channel may skip late netty stages, so the payload is ALSO encoded through its
     * codec explicitly — the wire bytes are produced either way. The
     * constant-vs-vanilla-frame-cap decision itself belongs to the unit-leg owner (D5);
     * this leg pins that the send pipeline delivers the largest admissible column intact.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void largestEncodableColumnFlushesThroughTheRealSendPath(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        long packed = PositionUtil.packPosition(pcx - 176, pcz - 36);

        byte[] sections = new byte[LSSConstants.MAX_SECTIONS_SIZE];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = (byte) i;
        }
        var payload = new VoxelColumnS2CPayload(pcx - 176, pcz - 36, Level.OVERWORLD,
                LSSConstants.epochSeconds(), sections);

        // Explicit codec encode: the exact wire size, independent of local-channel shortcuts.
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VoxelColumnS2CPayload.CODEC.encode(buf, payload);
            int encoded = buf.readableBytes();
            Gt.assertTrue(helper, encoded > LSSConstants.MAX_SECTIONS_SIZE
                            && encoded - LSSConstants.MAX_SECTIONS_SIZE
                                    <= LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    "the largest admissible column must encode to MAX_SECTIONS_SIZE plus at most "
                            + "ESTIMATED_COLUMN_OVERHEAD_BYTES of header, got " + encoded
                            + " bytes (header " + (encoded - LSSConstants.MAX_SECTIONS_SIZE) + ")");
        } finally {
            buf.release();
        }

        // Real transport: queue on a real state and flush through ServerPlayNetworking.
        var state = new PlayerRequestState(mock, 200, 16);
        state.markHandshakeComplete();
        state.addReadyPayload(new QueuedPayload<>(payload, payload.estimatedBytes(), 1L, packed));
        var limiter = new SharedBandwidthLimiter(1_073_741_824L);
        var diag = new TickDiagnostics();

        helper.succeedWhen(() -> {
            // Bandwidth tokens refill from elapsed wall time; ticks between retries make
            // this converge without any wall-clock sleep.
            long[] dropped = state.flushSendQueue(1_073_741_824L, limiter, diag,
                    p -> ServerPlayNetworking.send(mock, p));
            if (dropped.length > 0) {
                // Terminal: retrying would wait on an emptied queue with a misleading message.
                Gt.fail(helper, "the largest admissible column was exception-dropped by the real "
                        + "send path — the wire envelope rejects what the size guard admits");
            }
            Gt.assertTrue(helper, state.getTotalSectionsSent() == 1,
                    "waiting for the bandwidth window to admit the 2 MiB payload");
            Gt.assertTrue(helper, state.getSendQueueSize() == 0 && !state.hasEnqueuedColumn(packed),
                    "the flushed column must fully leave the send pipeline");
            playerList.remove(mock);
        });
    }

    private static boolean containsPosition(long[] positions, long packed) {
        if (positions == null) return false;
        for (long p : positions) {
            if (p == packed) return true;
        }
        return false;
    }

    /**
     * Far players E1 (FARP §7-A): the SERVER EGRESS surface through the production
     * handshake — a crafted handshake WITH the capability bit subscribes (one without
     * it does not), and a broadcast pass sends a roster + updates for an in-range far
     * target while an out-of-range one is filtered. Client tracker state is Tier 3 /
     * live territory. The client arm plays no part here — this test
     * crafts the bit the way an E2 client will.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void farPlayerSubscriberGetsRosterAndUpdatesForInRangeTargetsOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var viewer = placeMockServerPlayer(helper);
        var farTarget = placeMockServerPlayer(helper);
        var beyondCap = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        var config = dev.vox.lss.config.LSSServerConfig.CONFIG;
        String savedMode = config.farPlayers;
        int savedInterval = config.farPlayersUpdateIntervalTicks;
        try {
            // In-range far target (~500 blocks out); the third mock sits beyond the
            // 2048-block server cap and must be filtered.
            farTarget.setPos(viewer.getX() + 500, viewer.getY(), viewer.getZ());
            beyondCap.setPos(viewer.getX() + 3000, viewer.getY(), viewer.getZ());

            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    viewer, service, reply -> { });
            Gt.assertTrue(helper, service.getFarPlayerService().isSubscribed(viewer.getUUID()),
                    "the capability bit on a CURRENT-dialect handshake subscribes");

            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    farTarget, service, reply -> { });
            Gt.assertTrue(helper, !service.getFarPlayerService().isSubscribed(farTarget.getUUID()),
                    "no bit -> no subscription");

            service.getFarPlayerService().onPrefs(viewer.getUUID(),
                    new dev.vox.lss.common.farplayers.FarPlayerWire.Prefs(true, 0, 0, true, 0));
            config.farPlayers = "on";
            config.farPlayersUpdateIntervalTicks = 2; // the clamp floor — 2 service ticks
            service.tick();
            service.tick();

            var fp = service.getFarPlayerService();
            Gt.assertTrue(helper, fp.rosterFramesSent() >= 1,
                    "a full roster must have gone out, sent=" + fp.rosterFramesSent());
            Gt.assertTrue(helper, fp.updateFramesSent() >= 1,
                    "an updates frame must have gone out, sent=" + fp.updateFramesSent());
            // Entry-count isolation is impossible on the shared gametest server (other
            // tests' mock players are online concurrently and may fall in range) — the
            // exact ring/filter arithmetic is Tier 1's job
            // (FarPlayerBroadcastServiceTest); this tier pins the real egress.
            Gt.assertTrue(helper, fp.entriesSent() >= 1,
                    "at least the in-range target is served, entries=" + fp.entriesSent());
            Gt.assertTrue(helper, fp.bytesSent() > 0, "the dedicated lane counted its bytes");
        } finally {
            config.farPlayers = savedMode;
            config.farPlayersUpdateIntervalTicks = savedInterval;
            config.validate();
            service.shutdown();
        }
        helper.succeed();
    }

    /**
     * Region freshness against REAL game-written region files (region-summary-sync-
     * plan.md P1/P2 — the review's live-coverage gap): every Tier-1 pin reads headers
     * the tests themselves crafted, so this is the one place the z-major header layout,
     * the margined header rung, the tile stamps, and the summary pipeline (ingress →
     * pump admission → sweeper assembly → dedicated-lane send) run against the game's
     * own region writer end to end. Chunk band 250 (negative quadrant — disjoint from
     * every other class's bands per the header comment).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void regionFreshnessServesRealRegionHeaders(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        String dim = level.dimension().location().toString();
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        int cx = pcx - 250;
        int cz = pcz - 14;
        level.getChunk(cx, cz); // force-generate so the save writes a real header entry
        level.save(null, true, false);

        long stamp = service.getRegionStamps().chunkStampSecondsOrUnknown(dim, cx, cz);
        long nowSec = System.currentTimeMillis() / 1000L;
        Gt.assertTrue(helper, stamp > 0
                        && stamp != dev.vox.lss.common.region.RegionStampTable.NEVER_CLEAN
                        && stamp <= nowSec + 3600,
                "a REAL region header must yield a plausible save second (the z-major"
                        + " layout against the game's own writer), got " + stamp);
        long tile = service.getRegionStamps().tileStampSeconds(dim, cx >> 5, cz >> 5);
        Gt.assertTrue(helper, tile >= stamp
                        && tile != dev.vox.lss.common.region.RegionStampTable.NEVER_CLEAN,
                "the tile stamp must cover the chunk's save second, got " + tile);

        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        long clientTs = stamp
                + dev.vox.lss.common.region.RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS + 10;
        Gt.assertTrue(helper, state.tryAdmit(new PendingRequest(cx, cz,
                        SlotType.SYNC_ON_LOAD, clientTs)),
                "premise: pending admitted (the router's admission shape)");
        service.getDiskReader().submitReadDirect(mock.getUUID(), dim, level, cx, cz,
                1L, clientTs);

        service.handleRegionSummaryRequest(mock,
                dev.vox.lss.common.region.RegionSummaryWire.encodeRequest(
                        new dev.vox.lss.common.region.RegionSummaryWire.Request(
                                dim, cx >> 5, cz >> 5, 1)));

        helper.succeedWhen(() -> {
            service.tick();
            var sumDiag = service.getRegionSummaries().diagnostics();
            if (service.getDiskReader().getDiag().getHeaderHitsCount() < 1
                    || !state.hasDiskReadDone(cx, cz)
                    || sumDiag.getFrames() < 1 || sumDiag.getStampsFrames() < 1) {
                // Wall-denominate the wait (the TwoPlayerGameTests fix, panel fold
                // 2026-08-22): this waits on an async pool disk read, the MIN_PRIORITY
                // sweeper daemon's window assembly, and the dedicated-lane sends — all
                // wall-bound work an unthrottled gametest server (~0.2-0.4 ms/tick)
                // outruns in ~0.5 s of wall budget. >=50 ms per waiting tick keeps the
                // ceiling tick-rate-independent; the passing path sleeps only for the
                // handful of ticks the async legs actually take.
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Gt.assertTrue(helper, service.getDiskReader().getDiag().getHeaderHitsCount() >= 1,
                    "the header rung must intercept the margined-fresh read against the"
                            + " real region file");
            Gt.assertTrue(helper, state.hasDiskReadDone(cx, cz),
                    "the intercepted ask resolves up_to_date (done-bit)");
            var summary = service.getRegionSummaries().diagnostics();
            Gt.assertTrue(helper, summary.getFrames() >= 1,
                    "one summary frame must assemble and send, reqs=" + summary.getRequests()
                            + " frames=" + summary.getFrames());
            Gt.assertTrue(helper, summary.getBytes() > 0,
                    "the frame's bytes count on the dedicated lane");
            Gt.assertTrue(helper, summary.getTilesKnown() + summary.getTilesNeverClean()
                            + summary.getTilesNoRegion() == 9,
                    "a radius-1 window reports exactly 9 tiles, known="
                            + summary.getTilesKnown() + " never=" + summary.getTilesNeverClean()
                            + " no_region=" + summary.getTilesNoRegion());
            Gt.assertTrue(helper, summary.getTilesKnown() >= 1,
                    "the saved chunk's own tile must report a real stamp");
            // Stamped up_to_date (the whole server lane's ONE real-server pin, per the
            // 3-Opus fold): the summary request armed eligibility, the header rung's
            // margined-fresh interception is a compare-backed disposition, so its
            // up_to_date must ship a verification-stamp frame on lss:col_stamps.
            Gt.assertTrue(helper, summary.getStampsFrames() >= 1
                            && summary.getStampsEntries() >= 1,
                    "the compare-backed up_to_date must stamp (frames="
                            + summary.getStampsFrames() + " entries="
                            + summary.getStampsEntries() + ")");
            // Terminal pass: tear down like the class's async probe test does — the
            // throwaway service must not leak its threads/pools NOR leave its
            // constructor-published XrayMaskManager as the static manager (the
            // documented gametest static-stomping hazard).
            service.shutdown();
            server.getPlayerList().remove(mock);
        });
    }

    /**
     * The service gate's crafted-frame table on the SHARED glue
     * (service-permission-gate-plan.md §4.2 — the Paper core's twin pins live in
     * LSSPaperPluginGlueTest; this drives the xplat core with a REAL service and the
     * REAL {@code ServiceGateState}): a denied handshake takes the DISABLED rung in the
     * client's OWN dialect (enabled=false reply, no registration, never silence), the
     * denial deposits the re-offer memo exactly once, a HOLDING player under the armed
     * gate is served verbatim (arming alone denies nobody), and — the plan's §8 O2-M3 —
     * a denied re-handshake of a LIVE session runs the unregistration composite (a
     * permission denial is an ADMIN fact, unlike the protocol facts an existing
     * registration deliberately survives).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void serviceGateDeniesInDialectAndUnregistersALiveSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        LSSServerNetworking.SessionConfigResponder recorder = replies::add;
        var config = LSSServerConfig.CONFIG;
        boolean savedArm = config.requireServicePermission;
        config.requireServicePermission = true;
        try {
            var gateState = service.getServiceGateState();
            // The production gate shape with the permission read forced FALSE (no live
            // permission backend exists in a gametest JVM — LoaderServices would answer
            // the default TRUE): latch, memo, and composite are the REAL service's.
            var deny = new dev.vox.lss.common.PlayerServiceGate() {
                @Override
                public boolean hasPermission(String node) {
                    return false;
                }

                @Override
                public boolean claimDenialLog() {
                    return gateState.claimDenialLog(uuid);
                }

                @Override
                public void onServiceDenied(int protocolVersion, int capabilities) {
                    gateState.rememberDenied(uuid, "mock", protocolVersion, capabilities);
                    service.unregisterForServiceGate(uuid);
                }
            };
            int nativeProto = net.minecraft.SharedConstants.getProtocolVersion();

            // 1. Denied CURRENT handshake: enabled=false in the CURRENT shape, no state.
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, deny);
            Gt.assertTrue(helper, replies.size() == 1 && !replies.get(0).enabled()
                            && replies.get(0).protocolVersion() == LSSConstants.PROTOCOL_VERSION
                            && !replies.get(0).v16Wire(),
                    "a denied CURRENT handshake must reply enabled=false in the CURRENT "
                            + "shape, got " + replies);
            Gt.assertTrue(helper, service.getPlayers().get(uuid) == null,
                    "a denied player must never be registered");
            Gt.assertTrue(helper, gateState.isDenied(uuid) && gateState.permissionDeniedTotal() == 1,
                    "the denial must deposit the re-offer memo and count ONE transition");

            // 2. Denied v16 handshake (same session re-asking in an older dialect): the
            //    reply is the 6-field v16 shape, still enabled=false, still no state —
            //    and STILL one counted transition (re-handshakes while denied never
            //    re-count).
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.V16_COMPAT_PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, deny);
            Gt.assertTrue(helper, replies.size() == 2 && !replies.get(1).enabled()
                            && replies.get(1).v16Wire(),
                    "a denied v16 handshake must be denied in its OWN dialect (the 6-field "
                            + "enabled=false shape), got " + replies);
            Gt.assertTrue(helper, service.getPlayers().get(uuid) == null
                            && gateState.permissionDeniedTotal() == 1,
                    "still unregistered, still one transition");

            // 3. The gate disarmed mid-session: the same player registers through the
            //    same entry (the OPEN overload path is production for gate-off in the
            //    sense that serviceDenied short-circuits false before any probe).
            config.requireServicePermission = false;
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, deny);
            Gt.assertTrue(helper, replies.size() == 3 && replies.get(2).enabled()
                            && service.getPlayers().get(uuid) != null,
                    "with the gate off the SAME denying backend is never consulted and the "
                            + "player registers");
            Gt.assertTrue(helper, !gateState.isDenied(uuid),
                    "a successful registration by any path removes the memo entry");
            Gt.assertTrue(helper, service.getFarPlayerService().subscriberCount() == 1,
                    "premise: the live session is a far-player viewer");

            // 4. Re-armed + denied re-handshake of the LIVE session: the composite runs —
            //    state gone, viewer shed — and the reply advertises disabled.
            config.requireServicePermission = true;
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, deny);
            Gt.assertTrue(helper, replies.size() == 4 && !replies.get(3).enabled(),
                    "the revoked re-handshake replies enabled=false (the client disarm)");
            Gt.assertTrue(helper, service.getPlayers().get(uuid) == null,
                    "a permission denial is an ADMIN fact: the live registration must NOT "
                            + "survive it (§8 O2-M3)");
            Gt.assertTrue(helper, service.getFarPlayerService().subscriberCount() == 0,
                    "the composite sheds the far-player viewer too — a revoked player must "
                            + "not keep receiving proxy frames");
            Gt.assertTrue(helper, gateState.isDenied(uuid) && gateState.permissionDeniedTotal() == 2,
                    "the revocation re-deposits the memo (revoke->regrant must heal) and "
                            + "counts a SECOND transition");

            // 5. Armed + HOLDING backend: arming alone denies nobody.
            var hold = new dev.vox.lss.common.PlayerServiceGate() {
                @Override
                public boolean hasPermission(String node) {
                    return true;
                }

                @Override
                public boolean claimDenialLog() {
                    return gateState.claimDenialLog(uuid);
                }

                @Override
                public void onServiceDenied(int protocolVersion, int capabilities) {
                    Gt.fail(helper, "the denial hook must never fire for a holding player");
                }
            };
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, hold);
            Gt.assertTrue(helper, replies.size() == 5 && replies.get(4).enabled()
                            && service.getPlayers().get(uuid) != null,
                    "an armed gate over a holding player serves verbatim");

            // 6. NO_CONSUMER both directions on the SHARED glue (§8 n17's xplat twin):
            //    a caps=0 handshake under a DENYING gate replies enabled=false (the
            //    gate rides the same evaluate input as the kill switch) without
            //    burning the denial log or the memo; under a HOLDING gate the reply
            //    stays enabled=true. Neither registers.
            service.unregisterForServiceGate(uuid); // reset from step 5
            long deniedBefore = gateState.permissionDeniedTotal();
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, 0),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, deny);
            Gt.assertTrue(helper, replies.size() == 6 && !replies.get(5).enabled(),
                    "a DENIED consumer-less handshake advertises enabled=false");
            Gt.assertTrue(helper, gateState.permissionDeniedTotal() == deniedBefore
                            && !gateState.isDenied(uuid),
                    "…but NO_CONSUMER is not a gate decision: no memo deposit, no count");
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, 0),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, hold);
            Gt.assertTrue(helper, replies.size() == 7 && replies.get(6).enabled(),
                    "a HOLDING consumer-less handshake keeps enabled=true — the gate is "
                            + "invisible to players it does not deny");
        } finally {
            config.requireServicePermission = savedArm;
            service.shutdown();
            server.getPlayerList().remove(mock);
        }
        helper.succeed();
    }

    /**
     * The service-gate recheck sweeps end to end on the SHARED service
     * (service-permission-gate-plan.md §2.3 — the seam-level differentials live in
     * PaperServiceGateSweepTest; this drives the xplat twin with a REAL service, real
     * registration, and the REAL replay through the production glue): a registered
     * CURRENT session failing the injected permission probe on two consecutive sweeps
     * is revoked (unregistered, far viewer shed, memo deposited with the LIVE
     * capabilities), and the grant sweep then REPLAYS the remembered handshake through
     * the full production ladder — the player re-registers without any client action.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void serviceGateSweepsRevokeAndReofferALiveSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        LSSServerNetworking.SessionConfigResponder recorder = replies::add;
        var config = LSSServerConfig.CONFIG;
        boolean savedArm = config.requireServicePermission;
        var denying = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            // Register through the production entry (no permission backend in a gametest
            // JVM: the LoaderServices default serves).
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    mock, service, recorder);
            Gt.assertTrue(helper, service.getPlayers().get(uuid) != null && replies.size() == 1,
                    "premise: registered");
            Gt.assertTrue(helper, service.getFarPlayerService().subscriberCount() == 1,
                    "premise: far-player viewer");

            config.requireServicePermission = true;
            service.setPermissionProbeForTest((p, node) -> !denying.get());
            denying.set(true);

            service.runServiceGateSweeps(config);
            Gt.assertTrue(helper, service.getPlayers().get(uuid) != null,
                    "one failing sweep never revokes (flap hysteresis)");

            service.runServiceGateSweeps(config);
            Gt.assertTrue(helper, service.getPlayers().get(uuid) == null,
                    "the second consecutive failing sweep revokes the live session");
            Gt.assertTrue(helper, service.getFarPlayerService().subscriberCount() == 0,
                    "the composite sheds the far-player viewer");
            var remembered = service.getServiceGateState().peekDenied(uuid);
            Gt.assertTrue(helper, remembered != null
                            && remembered.capabilities() == (LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS)
                            && remembered.protocolVersion() == LSSConstants.PROTOCOL_VERSION,
                    "the memo carries the LIVE session's version+capabilities");
            Gt.assertTrue(helper, service.getServiceGateState().permissionDeniedTotal() == 1,
                    "one revocation = one counted transition");

            // The grant: the next sweep replays the stored handshake through the FULL
            // production ladder and the player re-registers, far viewer included.
            denying.set(false);
            service.runServiceGateSweeps(config);
            Gt.assertTrue(helper, service.getPlayers().get(uuid) != null,
                    "the re-offer replays the production ladder and re-registers");
            Gt.assertTrue(helper, service.getFarPlayerService().subscriberCount() == 1,
                    "the replayed capabilities restore the far-player subscription");
            Gt.assertTrue(helper, !service.getServiceGateState().isDenied(uuid),
                    "the successful registration removed the memo entry");

            // A remembered handshake whose rung became UNSERVABLE: the replay takes the
            // production ladder's SILENT VERSION_MISMATCH rung and the entry stays
            // dropped — no reply, no registration churn, no 0.1 Hz duplicate-config
            // loop (plan §4.3's full-ladder proof).
            service.unregisterForServiceGate(uuid);
            service.getServiceGateState().rememberDenied(uuid, "mock", 17, // 17 never shipped
                    LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            denying.set(false);
            service.runServiceGateSweeps(config);
            Gt.assertTrue(helper, !service.getServiceGateState().isDenied(uuid),
                    "the unservable replay drops the entry (taken before the ladder ran)");
            Gt.assertTrue(helper, service.getPlayers().get(uuid) == null,
                    "…without registering anybody (VERSION_MISMATCH is the silent rung)");
            service.runServiceGateSweeps(config);
            Gt.assertTrue(helper, !service.getServiceGateState().isDenied(uuid)
                            && service.getPlayers().get(uuid) == null,
                    "…and it never loops: the entry is gone for good");
        } finally {
            config.requireServicePermission = savedArm;
            service.shutdown();
            server.getPlayerList().remove(mock);
        }
        helper.succeed();
    }
    /**
     * The chunk-load baseline's deferred path (xaero-scatter-remediation-plan.md WI-1b,
     * review M1): a chunk loaded while nobody can seed it (no service yet — the spawn set
     * loads before SERVER_STARTED — or the skip gate shut) is recorded by position and
     * seeded from the REAL loaded chunk when the flush site opens, so its first save is
     * suppressed instead of re-marking. Runs the real serializer against a real
     * LevelChunk — the Tier 1 pins can only drive the position-explicit seam.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void pendingLoadSeedsFlushFromRealLoadedChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 0));
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        Gt.assertTrue(helper, chunk != null,
                    "premise: the structure's chunk is loaded");
        var service = new RequestProcessingService(server);
        try {
            service.armSaveHookForTest();
            ServerReceiverGlue.clearPendingLoadSeeds();
            ServerReceiverGlue.onChunkLoaded(level, chunk, null); // no service: recorded, not seeded
            Gt.assertTrue(helper, ServerReceiverGlue.pendingLoadSeedCount() == 1,
                    "a load with no service records the position");
            int seeded = ServerReceiverGlue.flushPendingLoadSeeds(server, service);
            Gt.assertTrue(helper, seeded == 1,
                    "the flush seeds the still-loaded chunk, got " + seeded);
            Gt.assertTrue(helper, service.getDirtyContentFilter().getTotalSeededLoads() == 1,
                    "seeded_load counts it");
            String dimension = level.dimension().location().toString();
            var obs = service.getDirtyContentFilter().observeSave(level, chunk, dimension);
            Gt.assertTrue(helper, !(obs.changed()),
                    "the chunk's first save after the load seed is suppressed (identical bytes)");
            Gt.assertTrue(helper, ServerReceiverGlue.pendingLoadSeedCount() == 0,
                    "flushed set is cleared");
            helper.succeed();
        } finally {
            service.shutdown();
        }
    }
}
