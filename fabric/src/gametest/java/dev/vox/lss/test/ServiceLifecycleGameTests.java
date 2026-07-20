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

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
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
            helper.assertTrue(pcx - maxDist < 0 && pcz - maxDist < 0,
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
            helper.assertTrue(state.getTotalRequestsReceived() == 2,
                    "only the two boundary positions must pass the distance guard, got "
                            + state.getTotalRequestsReceived());
            var batch = state.peekIncomingBatch();
            helper.assertTrue(batch != null && batch.size() == 2,
                    "the declared want-set must hold exactly the two boundary positions, got "
                            + (batch == null ? "no batch" : batch.size() + " entries"));
            var first = batch.requests()[0];
            helper.assertTrue(first.cx() == pcx + maxDist && first.cz() == pcz,
                    "request at exactly lodDistance+buffer must be accepted, got ["
                            + first.cx() + ", " + first.cz() + "]");
            helper.assertTrue(first.clientTimestamp() == -1L,
                    "client timestamp must survive intact, got " + first.clientTimestamp());
            var second = batch.requests()[1];
            helper.assertTrue(second.cx() == pcx - maxDist && second.cz() == pcz - maxDist,
                    "negative-quadrant boundary coords must round-trip exactly (sign bug in "
                            + "packing or distance), got [" + second.cx() + ", " + second.cz() + "]");
            helper.assertTrue(second.clientTimestamp() == 12345L,
                    "negative-quadrant timestamp must survive intact, got " + second.clientTimestamp());
            // v17: the three beyond-distance entries are dropped AT INGRESS and counted, not
            // silently vanished — the range_filtered counter is the only record they existed.
            helper.assertTrue(state.drainPendingRangeFiltered() == 3,
                    "the three beyond-distance entries must be counted range_filtered at ingress");

            // Unregistered player: silent no-op — no state created, nothing queued anywhere.
            service.handleBatchRequest(stranger, new BatchChunkRequestC2SPayload(
                    new long[]{boundary}, new long[]{-1L}, 1));
            helper.assertTrue(!service.getPlayers().containsKey(stranger.getUUID()),
                    "a batch request from an unregistered player must not create state");
            helper.assertTrue(state.getTotalRequestsReceived() == 2,
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

            helper.assertTrue(state.getTotalRequestsReceived() == 1,
                    "only the in-range position passes; the four extremes are gated without "
                            + "overflow, got " + state.getTotalRequestsReceived());
            var batch = state.peekIncomingBatch();
            helper.assertTrue(batch != null && batch.size() == 1,
                    "the declared want-set must hold only the in-range request, got "
                            + (batch == null ? "no batch" : batch.size() + " entries"));
            var req = batch.requests()[0];
            helper.assertTrue(req.cx() == pcx && req.cz() == pcz,
                    "the surviving request must be the player's own chunk, got ["
                            + req.cx() + ", " + req.cz() + "]");
            helper.assertTrue(state.drainPendingRangeFiltered() == 4,
                    "the four extreme coords must be counted range_filtered at ingress, not "
                            + "slip under the gate");

            // No tick has run, so the gate alone must not have submitted any disk/gen work.
            helper.assertTrue(service.getDiskReader().getPendingResultCount() == 0,
                    "a gated batch must not submit a disk read for an extreme coord");
            var gen = service.getGenerationService();
            helper.assertTrue(gen == null || gen.getActiveCount() == 0,
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
            helper.assertTrue(service.getPlayers().containsKey(uuid), "premise: a player is registered");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) != null,
                    "premise: the disk-reader queue exists");

            service.shutdown();
            helper.assertTrue(service.getPlayers().isEmpty(), "shutdown clears the players map");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) == null,
                    "shutdown tears down the disk-reader result queue");
            var gen = service.getGenerationService();
            helper.assertTrue(gen == null || gen.getActiveCount() == 0,
                    "shutdown clears any active generation");

            // The second call (server-stop after a manual shutdown) must not throw or re-break.
            service.shutdown();
            helper.assertTrue(service.getPlayers().isEmpty(), "a second shutdown stays clean");
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
            helper.assertTrue(service.getPlayers().containsKey(uuid),
                    "registered player must appear in the players map");
            helper.assertTrue(state.hasCompletedHandshake(),
                    "registerPlayer must complete the handshake");
            helper.assertTrue(state.getCapabilities() == LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                    "capabilities from the handshake must be stored");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) != null,
                    "registration must create the disk-reader result queue");

            // computeIfAbsent contract: re-registering an online UUID updates the existing
            // state in place (capability change on re-handshake) and never replaces it.
            var reRegistered = service.registerPlayer(mock, 0);
            helper.assertTrue(reRegistered == state,
                    "re-registering an online player must return the SAME state, not wipe it");
            helper.assertTrue(state.getCapabilities() == 0,
                    "re-registration must apply the new capabilities to the existing state");
            helper.assertTrue(state.hasCompletedHandshake(),
                    "re-registration must keep the handshake complete");

            // Seed an in-flight generation, then removePlayer must clean every structure.
            // No tick() runs between submit and remove, so the entry cannot complete first.
            var gen = service.getGenerationService();
            helper.assertTrue(gen != null,
                    "generation service expected (gametest config has enableChunkGeneration=true)");
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            helper.assertTrue(gen.submitGeneration(uuid, level, pcx - GEN_CHUNK_OFFSET, pcz + GEN_CHUNK_OFFSET, 1L),
                    "a fresh generation service must accept a submission");
            helper.assertTrue(gen.getActiveCount() == 1, "submission must be tracked as active");

            service.removePlayer(uuid);
            helper.assertTrue(!service.getPlayers().containsKey(uuid),
                    "removePlayer must drop the players-map entry");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) == null,
                    "removePlayer must remove the disk-reader result queue");
            helper.assertTrue(gen.getActiveCount() == 0,
                    "removePlayer must release the player's in-flight generation entry");
            helper.assertTrue(gen.getTotalRemovedInFlight() == 1,
                    "the released in-flight generation must be booked as removed (or the "
                            + "submitted/completed accounting never re-balances after a kick)");

            // After removal the same UUID re-registers with a FRESH state.
            var fresh = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            helper.assertTrue(fresh != state,
                    "a removed UUID must re-register with a fresh state object");

            // Lifecycle polarity: discarded but still in the player list is the death/respawn
            // shape — the session must survive. Removing on isRemoved() alone would wipe every
            // player's LOD session on every death.
            mock.discard();
            helper.assertTrue(mock.isRemoved(), "premise: discard marks the entity removed");
            helper.assertTrue(playerList.getPlayer(uuid) != null,
                    "premise: discard must not delist the player");
            service.tick();
            service.tick();
            helper.assertTrue(service.getPlayers().containsKey(uuid),
                    "a discarded-but-listed player must keep its session (death/respawn must not "
                            + "wipe LOD state)");

            // Only a delisted player auto-removes — and without any disconnect event, since a
            // direct player-list removal never fires one.
            playerList.remove(mock);
            helper.assertTrue(playerList.getPlayer(uuid) == null, "premise: player delisted");
            service.tick();
            helper.assertTrue(!service.getPlayers().containsKey(uuid),
                    "one tick must auto-remove a delisted player (disconnect-event-less cleanup)");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) == null,
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
        helper.assertTrue(liveService != null,
                "live RequestProcessingService must be active (save-hook leg depends on it)");

        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        int cx = pcx - PROBE_CHUNK_OFFSET;
        int cz = pcz - PROBE_CHUNK_OFFSET;
        int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;
        helper.assertTrue(PositionUtil.chebyshevDistance(cx, cz, pcx, pcz) <= maxDist,
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
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);

        // Tick 2 (generation light settled): baseline the filter like an earlier save would,
        // then edit, then request — the probe will serve the post-edit bytes.
        helper.runAfterDelay(2, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(filter.contentChanged(level, chunk, dim),
                    "first observation must baseline the virgin filter");
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    "identical content must stay quiet once baselined");
            // Toggle so the edit is a real content change even if a previous run (the gametest
            // world persists) already left stone at this position.
            var edit = level.getBlockState(editPos).is(Blocks.STONE) ? Blocks.COBBLESTONE : Blocks.STONE;
            level.setBlock(editPos, edit.defaultBlockState(), 3);
            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    new long[]{packed}, new long[]{-1L}, 1));
            helper.assertTrue(state.getTotalRequestsReceived() == 1,
                    "the in-range request must be accepted");
        });

        var step = new AtomicInteger();
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 4, "waiting for the baseline+edit setup");
            switch (step.get()) {
                case 0 -> {
                    // Manual tick: main thread probes the loaded chunk, processing thread
                    // serves it, the next manual tick's flush sends it to the mock player.
                    service.tick();
                    helper.assertTrue(state.getTotalSectionsSent() >= 1,
                            "waiting for the probe serve to flush");
                    helper.assertTrue(
                            service.getOffThreadProcessor().getDiagnostics().getTotalInMemory() == 1,
                            "the serve must come from the in-memory probe, not disk");
                    var chunk = level.getChunk(cx, cz);
                    helper.assertTrue(filter.contentChanged(level, chunk, dim),
                            "a probe serve must NOT seed the dirty filter: the save after the "
                                    + "edit no longer sees a change, swallowing the dirty "
                                    + "broadcast other clients need");
                    helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                            "the check above must have stored the new hash (filter is live)");
                    step.set(1);
                    helper.assertTrue(false, "no-seed verified, running the live save-hook leg");
                }
                case 1 -> {
                    // Live end-to-end: edit → real save → the position must surface in the live
                    // dirty tracker (ChunkMapSaveHook → DirtyContentFilter → DirtyColumnTracker).
                    // Drain, save, and re-drain in one callback: saves and the broadcaster only
                    // run on the main thread, so nothing can interleave and steal the mark.
                    var edit = level.getBlockState(editPos).is(Blocks.STONE)
                            ? Blocks.COBBLESTONE : Blocks.STONE;
                    level.setBlock(editPos, edit.defaultBlockState(), 3);
                    liveService.getDirtyTracker().drainDirty(dim);
                    level.save(null, true, false);
                    long[] dirty = liveService.getDirtyTracker().drainDirty(dim);
                    helper.assertTrue(containsPosition(dirty, packed),
                            "a save after a real edit must mark the column dirty end-to-end "
                                    + "(save hook -> content filter -> dirty tracker)");
                    chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                    service.shutdown();
                    server.getPlayerList().remove(mock);
                }
                default -> helper.fail("unexpected probe test step " + step.get());
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

            helper.assertTrue(state.getTotalRequestsReceived() == 0,
                    "extreme coordinates must be dropped by the distance guard (an overflowed "
                            + "Chebyshev distance admits them), got "
                            + state.getTotalRequestsReceived() + " accepted");
            service.tick();
            service.tick();
            helper.assertTrue(state.getHeldSyncSlots() == 0 && state.getHeldGenSlots() == 0,
                    "gated extremes must never hold a slot");
            helper.assertTrue(service.getDiskReader().getDiag().getSubmittedCount() == 0,
                    "gated extremes must never reach the disk reader, got "
                            + service.getDiskReader().getDiag().getSubmittedCount() + " submits");
            helper.assertTrue(service.getGenerationService() != null
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
        helper.assertTrue(live != null, "premise: the dedicated gametest server runs the live service");

        LSSServerNetworking.startServiceForLan(server);
        LSSServerNetworking.startServiceForLan(server);

        helper.assertTrue(LSSServerNetworking.getRequestService() == live,
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
        try {
            service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            var gen = service.getGenerationService();
            helper.assertTrue(gen != null, "generation service expected (gametest config)");
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            helper.assertTrue(gen.submitGeneration(uuid, level, pcx - 132, pcz + 132, 1L),
                    "premise: an in-flight generation entry must exist at shutdown");
            helper.assertTrue(gen.getActiveCount() == 1, "premise: entry tracked as active");

            service.shutdown();
            helper.assertTrue(service.getPlayers().isEmpty(),
                    "the first shutdown must clear the players map");
            helper.assertTrue(gen.getActiveCount() == 0,
                    "the first shutdown must release every in-flight generation entry "
                            + "(a held entry keeps its chunk force-loaded forever)");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) == null,
                    "the first shutdown must drop the per-player disk-reader result queue");

            try {
                service.shutdown();
            } catch (Throwable t) {
                helper.fail("a second shutdown must be a quiet no-op, threw: " + t);
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
        helper.assertTrue(state != null && replies.size() == 1,
                "premise: first handshake must register and reply");

        // Seed live work: a held pending slot, a done-bit, and a declared want-set.
        helper.assertTrue(state.tryAdmit(new PendingRequest(pcx - 148, pcz - 12,
                        SlotType.SYNC_ON_LOAD, false)),
                "premise: pending seeded");
        state.markDiskReadDone(pcx - 148, pcz - 13);
        GameTestSeeding.seedRequest(state, PositionUtil.packPosition(pcx - 149, pcz - 12), -1L);
        helper.assertTrue(state.getTotalRequestsReceived() == 1 && state.peekIncomingBatch() != null,
                "premise: want-set declared");

        // Duplicate handshake: same instance, work survives, config re-sent.
        LSSServerNetworking.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                mockA, service, recorder);
        helper.assertTrue(service.getPlayers().get(uuidA) == state,
                "a duplicate handshake must reuse the SAME state (a replacement wipes pendings)");
        helper.assertTrue(replies.size() == 2,
                "a duplicate handshake must re-send the session config, got " + replies.size());
        helper.assertTrue(state.getHeldSyncSlots() == 1
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
        helper.assertTrue(replies.size() == 3,
                "a caps=0 re-handshake must still be answered with the session config");
        helper.assertTrue(service.getPlayers().get(uuidA) == state
                        && state.getCapabilities() == LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                "the NO_CONSUMER arm must return before registerPlayer: the existing "
                        + "registration (and its capabilities) stays untouched");

        // Service-level capability update (re-register path): now the router must skip A.
        service.registerPlayer(mockA, 0);
        helper.assertTrue(state.getCapabilities() == 0, "premise: capabilities updated in place");

        // Control player proves a routing cycle ran end-to-end while A was skipped.
        var chunkPos = new ChunkPos(pcx - 152, pcz - 16);
        level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x(), chunkPos.z());
        var stateB = service.registerPlayer(mockB, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        GameTestSeeding.seedRequest(stateB, PositionUtil.packPosition(chunkPos.x(), chunkPos.z()), -1L);

        helper.succeedWhen(() -> {
            service.tick();
            helper.assertTrue(
                    service.getOffThreadProcessor().getDiagnostics().getTotalInMemory() >= 1,
                    "waiting for the control player's probe serve (proves routing cycles ran)");
            helper.assertTrue(state.peekIncomingBatch() != null && state.getBacklogSize() == 0,
                    "a caps=0 player's declared want-set must stay unconsumed — the router "
                            + "skips the player wholesale, so the batch is never even taken "
                            + "from the mailbox into the backlog");
            helper.assertTrue(state.getHeldSyncSlots() == 1
                            && state.hasPendingRequest(pcx - 148, pcz - 12),
                    "a caps=0 player's pending slot must be neither leaked nor torn down "
                            + "until disconnect");

            // Disconnect is the cleanup boundary: a fresh registration starts clean.
            service.removePlayer(uuidA);
            var fresh = service.registerPlayer(mockA, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            helper.assertTrue(fresh != state && fresh.getHeldSyncSlots() == 0
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
            helper.assertTrue(replies.size() == 1,
                    "a caps=0 handshake must be answered with exactly one session config, got "
                            + replies.size());
            helper.assertTrue(replies.get(0).protocolVersion() == LSSConstants.PROTOCOL_VERSION
                            && replies.get(0).enabled(),
                    "the reply must advertise the server's protocol version and effective enabled");
            helper.assertTrue(!service.getPlayers().containsKey(mock.getUUID()),
                    "a caps=0 client must NOT be registered (zombie state the router skips forever)");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * WP-035 (server direction): a crafted v15 handshake through the real receiver body
     * must produce ZERO reply frames — replying would kick the client, because a
     * mismatched client's SessionConfig codec has a different field layout on the same
     * channel id — and must leave the connection and player list untouched.
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
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION - 1,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, replies::add);
            helper.assertTrue(replies.isEmpty(),
                    "a version-mismatched handshake must produce zero reply frames (any reply "
                            + "decodes as a DecoderException on the old client and kicks it), got "
                            + replies.size());
            helper.assertTrue(!service.getPlayers().containsKey(uuid),
                    "a version-mismatched client must not be registered");
            helper.assertTrue(playerList.getPlayer(uuid) == mock && !mock.isRemoved()
                            && mock.connection != null,
                    "the mismatch path must leave the player connected and its connection untouched");
        } finally {
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
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
        helper.assertTrue(config.enabled, "premise: gametest config runs enabled");

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
            helper.assertTrue(replies.size() == 1, "premise: REGISTER handshake must reply once");
            var reply = replies.get(0);
            helper.assertTrue(reply.enabled(),
                    "effectiveEnabled must be true (config enabled + service present)");
            helper.assertTrue(reply.lodDistanceChunks() == 251,
                    "lodDistanceChunks must wire from CONFIG.lodDistanceChunks, got "
                            + reply.lodDistanceChunks());
            helper.assertTrue(!reply.generationEnabled(),
                    "generationEnabled must wire from CONFIG.enableChunkGeneration"
                            + " (the concurrency caps left the 4-field wire payload)");
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
        long packed = PositionUtil.packPosition(chunkPos.x(), chunkPos.z());
        var chunkSource = level.getChunkSource();
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x(), chunkPos.z());

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        // Stale done-bit + a ts>0 request: only the frozen-queued clear makes it re-serve.
        state.markDiskReadDone(chunkPos.x(), chunkPos.z());
        GameTestSeeding.seedRequest(state, packed, 5L);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var config = LSSServerConfig.CONFIG;
        try {
            config.enabled = false;
            for (int i = 0; i < 3; i++) {
                service.tick();
            }
            helper.assertTrue(diag.getTotalRequestsRouted() == 0,
                    "a disabled tick must post no snapshot — nothing can route while frozen");
            helper.assertTrue(state.peekIncomingBatch() != null && state.getBacklogSize() == 0,
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
            helper.assertTrue(diag.getTotalRequestsRouted() == 1,
                    "the first resumed tick must post the snapshot that routes the frozen request");
            helper.assertTrue(diag.getTotalInMemory() == 1,
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
        helper.assertTrue(state.tryAdmit(new PendingRequest(pcx - 144, pcz - 8,
                        SlotType.SYNC_ON_LOAD, false)),
                "premise: pending seeded before the respawn");
        state.markDiskReadDone(pcx - 144, pcz - 9);

        var fresh = playerList.respawn(mock, true, Entity.RemovalReason.DISCARDED);
        helper.assertTrue(fresh != mock, "premise: respawn must produce a NEW ServerPlayer instance");
        helper.assertTrue(playerList.getPlayer(uuid) == fresh,
                "premise: the player list must hold the respawned instance");
        helper.assertTrue(mock.isRemoved(), "premise: the old instance is removed");

        service.tick();
        helper.assertTrue(service.getPlayers().get(uuid) == state,
                "the respawn swap must keep the SAME state object (a teardown would wipe the "
                        + "session on every death)");
        helper.assertTrue(state.getPlayer() == fresh,
                "the lifecycle pass must swap the state's player reference to the respawned "
                        + "instance");
        helper.assertTrue(state.getHeldSyncSlots() == 1
                        && state.hasPendingRequest(pcx - 144, pcz - 8)
                        && state.hasDiskReadDone(pcx - 144, pcz - 9),
                "pendings and done-bits must survive the reference swap");

        // A request must now resolve against the NEW reference end-to-end.
        var chunkPos = new ChunkPos(pcx - 144, pcz - 10);
        var chunkSource = level.getChunkSource();
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(chunkPos.x(), chunkPos.z());
        GameTestSeeding.seedRequest(state, PositionUtil.packPosition(chunkPos.x(), chunkPos.z()), -1L);

        helper.succeedWhen(() -> {
            service.tick();
            helper.assertTrue(state.getTotalSectionsSent() >= 1,
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
        level.getChunk(posK1.x(), posK1.z());
        level.getChunk(posK2.x(), posK2.z());
        // The pair must exist on disk: the budget routes them to the disk reader.
        level.save(null, true, false);

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        // ONE declared want-set: 512 filler entries AHEAD of the loaded pair. Far synthetic
        // coords: never loaded (probe miss), pre-seeded done-bit + ts>0 resolves them
        // up-to-date before slot admission.
        var packed = new long[514];
        var stamps = new long[514];
        for (int i = 0; i < 512; i++) {
            state.markDiskReadDone(1_000_000 + i, 77);
            packed[i] = PositionUtil.packPosition(1_000_000 + i, 77);
            stamps[i] = 5L;
        }
        packed[512] = PositionUtil.packPosition(posK1.x(), posK1.z());
        stamps[512] = -1L;
        packed[513] = PositionUtil.packPosition(posK2.x(), posK2.z());
        stamps[513] = -1L;
        GameTestSeeding.seedRequests(state, packed, stamps);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var diskDiag = service.getDiskReader().getDiag();
        helper.succeedWhen(() -> {
            service.tick();
            helper.assertTrue(diskDiag.getSuccessfulReadCount() >= 2 && state.getTotalSectionsSent() >= 2,
                    "waiting for the disk-served pair to flush (budget remainder must still serve)");
            helper.assertTrue(diag.getTotalInMemory() == 0,
                    "the trailing loaded pair must NOT be probe-served: 512 queue entries ahead "
                            + "of it must exhaust the per-tick probe budget (misses count too)");
            helper.assertTrue(diskDiag.getSubmittedCount() == 2,
                    "exactly the budget-excluded pair must reach the disk reader, got "
                            + diskDiag.getSubmittedCount());
            helper.assertTrue(diag.getTotalRequestsRouted() == 514,
                    "every request must be routed exactly once, got " + diag.getTotalRequestsRouted());
            helper.assertTrue(state.getHeldSyncSlots() == 0 && state.getHeldGenSlots() == 0,
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
        level.getChunk(posC.x(), posC.z());
        level.getChunk(posD.x(), posD.z());

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        long packedC = PositionUtil.packPosition(posC.x(), posC.z());
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
        for (int i = 0; i < 510; i++) {
            state.markDiskReadDone(1_010_000 + i, 88);
            packed[5 + i] = PositionUtil.packPosition(1_010_000 + i, 88);
            stamps[5 + i] = 5L;
        }
        packed[515] = PositionUtil.packPosition(posD.x(), posD.z());
        stamps[515] = -1L;
        GameTestSeeding.seedRequests(state, packed, stamps);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var diskDiag = service.getDiskReader().getDiag();
        helper.succeedWhen(() -> {
            service.tick();
            helper.assertTrue(diag.getTotalRequestsRouted() == 516 && state.getTotalSectionsSent() >= 2,
                    "waiting for all 516 requests to route and both columns to flush");
            helper.assertTrue(diag.getTotalInMemory() == 2,
                    "C and D must BOTH probe-serve: duplicate positions must not consume probe "
                            + "budget (a guard regression pushes D past the 512 cap to disk)");
            helper.assertTrue(diskDiag.getSubmittedCount() == 0,
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
     * mark dirty ({@code ChunkMapSaveHook}'s {@code instanceof LevelChunk} guard) — proto
     * saves have no LOD-servable content, and marking them would broadcast positions that
     * then resolve not-found. The edited LevelChunk in the same save pass is the positive
     * control proving the save ran and the hook is live. Drain–save–drain runs in one
     * synchronous callback so no other test's marks interleave.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void protoChunkSavesAreExcludedFromDirtyMarking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var liveService = LSSServerNetworking.getRequestService();
        helper.assertTrue(liveService != null, "live service required (the save hook feeds it)");
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var chunkSource = level.getChunkSource();

        // Control: a loaded LevelChunk with an edit must mark in the same save pass.
        var controlPos = new ChunkPos(origin.x() - 172, origin.z() - 32);
        long controlPacked = PositionUtil.packPosition(controlPos.x(), controlPos.z());
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, controlPos, 0);
        level.getChunk(controlPos.x(), controlPos.z());
        var editPos = new BlockPos(controlPos.x() * 16 + 4, -61, controlPos.z() * 16 + 4);
        var edit = level.getBlockState(editPos).is(Blocks.STONE) ? Blocks.COBBLESTONE : Blocks.STONE;
        level.setBlock(editPos, edit.defaultBlockState(), 3);

        // Proto: per-run salted coords — a previous run's chunk would load already-generated
        // and not be unsaved, making the save pass skip it and the assertion vacuous.
        int protoCx = origin.x() - 168;
        int protoCz = origin.z() + (int) Math.floorMod(System.nanoTime(), 64L);
        var proto = chunkSource.getChunk(protoCx, protoCz, ChunkStatus.STRUCTURE_STARTS, true);
        helper.assertTrue(proto != null && !(proto instanceof LevelChunk),
                "premise: a STRUCTURE_STARTS chunk must still be a ProtoChunk");
        proto.markUnsaved();
        long protoPacked = PositionUtil.packPosition(protoCx, protoCz);

        var tracker = liveService.getDirtyTracker();
        tracker.drainDirty(dim);
        level.save(null, true, false);
        long[] dirty = tracker.drainDirty(dim);
        helper.assertTrue(containsPosition(dirty, controlPacked),
                "premise/control: the edited LevelChunk must mark dirty in this save pass "
                        + "(proves the save ran and the hook is live)");
        helper.assertTrue(!containsPosition(dirty, protoPacked),
                "a ProtoChunk save must NOT mark dirty (ChunkMapSaveHook must exclude "
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
            helper.assertTrue(encoded > LSSConstants.MAX_SECTIONS_SIZE
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
                helper.fail("the largest admissible column was exception-dropped by the real "
                        + "send path — the wire envelope rejects what the size guard admits");
            }
            helper.assertTrue(state.getTotalSectionsSent() == 1,
                    "waiting for the bandwidth window to admit the 2 MiB payload");
            helper.assertTrue(state.getSendQueueSize() == 0 && !state.hasEnqueuedColumn(packed),
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
}
