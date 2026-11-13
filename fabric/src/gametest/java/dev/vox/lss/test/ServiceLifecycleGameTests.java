package dev.vox.lss.test;

import static dev.vox.lss.test.TestPositions.chunkAt;
import static dev.vox.lss.test.TestPositions.holdChunk;
import static dev.vox.lss.test.TestPositions.releaseChunk;
import static dev.vox.lss.test.TestPositions.saveHolder;

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
import net.minecraft.gametest.framework.GameTest;
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

    /** Waiting ticks left before the timeout when a failing wait tears down. */
    private static final int TEARDOWN_MARGIN_TICKS = 5;

    /**
     * {@code succeedWhen} whose teardown runs on BOTH outcomes (0.19.0 review O6): on the
     * passing tick, or on the last waiting ticks before {@code maxTicks} — a timed-out test
     * must not leak its service threads and mock players into the shared gametest server.
     * After a timeout teardown the criterion never runs again: the remaining ticks re-throw
     * the last failure, so the reported message describes the real state, not a torn-down
     * service.
     */
    private static void succeedWhenThenTearDown(GameTestHelper helper, int maxTicks,
                                                Runnable criterion, Runnable teardown) {
        var waits = new AtomicInteger();
        var tornDown = new java.util.concurrent.atomic.AtomicBoolean();
        var lastFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        helper.succeedWhen(() -> {
            if (tornDown.get()) {
                var failure = lastFailure.get();
                if (failure != null) throw failure;
                return;
            }
            try {
                criterion.run();
            } catch (RuntimeException e) {
                lastFailure.set(e);
                if (waits.incrementAndGet() >= maxTicks - TEARDOWN_MARGIN_TICKS
                        && tornDown.compareAndSet(false, true)) {
                    teardown.run();
                }
                throw e;
            }
            if (tornDown.compareAndSet(false, true)) teardown.run();
        });
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
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
            helper.assertTrue(state.getOutboundPendingBytes() == -1,
                    "premise: nothing sampled before the first flush");

            service.tick(); // flushSendQueues samples the probe once per player per tick

            long pending = state.getOutboundPendingBytes();
            helper.assertTrue(pending >= 0,
                    "the gauge must resolve through the real mixins + channel; -1 means the"
                            + " accessors did not apply and the instrument is dead, got " + pending);
            helper.assertTrue(state.getOutboundPendingHighWater() >= pending,
                    "high-water must track the sampled value");
        } finally {
            service.shutdown();
        }
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
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
            int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks() + LSSConstants.LOD_DISTANCE_BUFFER;
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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

    @GameTest(template = "fabric-gametest-api-v1:empty")
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
            helper.assertTrue(gen.submitGeneration(uuid, service.getPlayers().get(uuid).registration(), level, pcx - GEN_CHUNK_OFFSET, pcz + GEN_CHUNK_OFFSET, 1L),
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 300)
    public void probeServesLoadedChunkFromMemoryWithoutSeedingDirtyFilter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var liveService = LSSServerNetworking.getRequestService();
        helper.assertTrue(liveService != null,
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
        int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks() + LSSConstants.LOD_DISTANCE_BUFFER;
        helper.assertTrue(PositionUtil.chebyshevDistance(cx, cz, pcx, pcz) <= maxDist,
                "premise: the probe chunk must be inside the request distance guard");
        var chunkPos = chunkAt(cx, cz);
        var chunkSource = level.getChunkSource();
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        long packed = PositionUtil.packPosition(cx, cz);
        // Grass surface block of the default superflat preset.
        var editPos = new BlockPos(cx * 16 + 4, -61, cz * 16 + 4);

        // Keep the chunk loaded for the whole test so the serve must come from the probe path.
        holdChunk(chunkSource, chunkPos);
        level.getChunk(cx, cz);

        var service = new RequestProcessingService(server);
        var filter = service.getDirtyContentFilter();
        // Review-P3 latch wiring pin (three-lens round): registerPlayer must arm the
        // save-hook gate — the Tier 2 arming seams cannot notice a wiring regression.
        helper.assertTrue(!service.hasEverRegisteredPlayer(), "premise: fresh service, latch unarmed");
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        helper.assertTrue(service.hasEverRegisteredPlayer(),
                "registerPlayer must flip the save-hook latch");

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
                    // dirty tracker (ChunkSaveDataHook → DirtyContentFilter → DirtyColumnTracker).
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
                    releaseChunk(chunkSource, chunkPos);
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
        helper.assertTrue(dev.vox.lss.networking.server.XrayMaskManager.current() != null,
                "service construction must publish the x-ray mask manager");
        try {
            service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            var gen = service.getGenerationService();
            helper.assertTrue(gen != null, "generation service expected (gametest config)");
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            helper.assertTrue(gen.submitGeneration(uuid, service.getPlayers().get(uuid).registration(), level, pcx - 132, pcz + 132, 1L),
                    "premise: an in-flight generation entry must exist at shutdown");
            helper.assertTrue(gen.getActiveCount() == 1, "premise: entry tracked as active");

            service.shutdown();
            helper.assertTrue(dev.vox.lss.networking.server.XrayMaskManager.current() == null,
                    "shutdown must retract the x-ray mask manager");
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
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
                        SlotType.SYNC_ON_LOAD, 0L)),
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
        var chunkPos = chunkAt(pcx - 152, pcz - 16);
        holdChunk(level.getChunkSource(), chunkPos);
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

            releaseChunk(level.getChunkSource(), chunkPos);
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
     * WP-035 (server direction): a crafted foreign-version handshake through the real
     * receiver body must produce ZERO reply frames — replying would kick the client,
     * because a mismatched client's SessionConfig codec has a different field layout on
     * the same channel id — and must leave the connection and player list untouched.
     * Protocol 17 is the mismatch specimen: it never shipped, and BOTH its neighbors now
     * have compat rungs (16 since v0.7.0, 18 since v0.9.1 — see
     * v18HandshakeRegistersNativelyAndEchoesProtocol18 below), so PROTOCOL_VERSION - 1
     * no longer mismatches on default config.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 1200)
    public void v18HandshakeRegistersNativelyAndEchoesProtocol18(GameTestHelper helper) {
        legacyDialectHandshakeAndDelivery(helper, LSSConstants.V18_COMPAT_PROTOCOL_VERSION,
                V18_DELIVERY_CHUNK_OFFSET);
    }

    /** The v19 rung through the PRODUCTION receiver (protocol 20, XVER §4.2): a v0.9.x
     *  client registers natively with the V19 dialect, the reply echoes 19, and since C2
     *  a probe serve must translate at enqueue and ship at the CURRENT header — same
     *  sectionsSent + done-bit distinguisher as the v18 twin above. */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 1200)
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
        helper.assertTrue(replies.size() == 1,
                "a v" + announcedVersion + " handshake on default config must be answered, got "
                        + replies.size());
        helper.assertTrue(replies.get(0).protocolVersion() == announcedVersion,
                "the reply must echo protocol " + announcedVersion + " — the legacy client "
                        + "disables itself on any other version, got "
                        + replies.get(0).protocolVersion());
        // The echo must carry the REAL config, not zeroes (the dialect-19 soak lever
        // caught a decode-side flavor of this — pin the encode side too).
        helper.assertTrue(replies.get(0).enabled(),
                "the legacy echo must carry the real enabled flag");
        helper.assertTrue(replies.get(0).lodDistanceChunks() == LSSServerConfig.CONFIG.lodDistanceChunks(),
                "the legacy echo must carry the real LOD distance, got "
                        + replies.get(0).lodDistanceChunks());
        var state = service.getPlayers().get(uuid);
        helper.assertTrue(state != null,
                "a v" + announcedVersion + " handshake must register natively (not fall to "
                        + "the v16 shim)");
        boolean v18 = announcedVersion == LSSConstants.V18_COMPAT_PROTOCOL_VERSION;
        helper.assertTrue(v18 ? service.getDialectTracker().isV18(uuid)
                        : service.getDialectTracker().isV19(uuid),
                "the session must carry its dialect membership (the egress gates on it)");
        helper.assertTrue(!service.getV16CompatManager().isV16(uuid),
                "a v" + announcedVersion + " session is NOT a v16 compat session");
        if (v18) {
            helper.assertTrue(!state.wantsCompressedColumns(),
                    "a v18 session must be forced codec-RAW even when the handshake "
                            + "(hostilely) declares the zstd capability bit");
        }

        // ---- C2 delivery leg: one probe-servable column through the legacy egress ----
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        int cx = pcx - chunkOffset;
        int cz = pcz - chunkOffset;
        var chunkPos = chunkAt(cx, cz);
        var chunkSource = level.getChunkSource();
        // Loaded for the whole test: the serve must come from the in-memory probe.
        holdChunk(chunkSource, chunkPos);
        level.getChunk(cx, cz);
        service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                new long[]{PositionUtil.packPosition(cx, cz)}, new long[]{-1L}, 1));
        helper.assertTrue(state.getTotalRequestsReceived() == 1,
                "premise: the delivery-leg request must pass the distance guard");

        helper.succeedWhen(() -> {
            service.tick();
            helper.assertTrue(state.getTotalSectionsSent() >= 1,
                    "waiting for the probe serve to flush through the legacy egress — a "
                            + "translation failure at enqueue answers up_to_date and never "
                            + "sends a section, so this wait times out on one");
            helper.assertTrue(state.hasDiskReadDone(cx, cz),
                    "the done-bit must SURVIVE the flush (no drop path fired)");
            releaseChunk(chunkSource, chunkPos);
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * CG-028: the REGISTER reply's field wiring at the Fabric call site — lodDistance
     * distinct from the protocol version (int transposition) and generationEnabled opposed
     * to enabled (boolean transposition) across the 4-field frame. The service owns a detached
     * configuration facade, so this test cannot retune other tests or the live lifecycle.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void sessionConfigReplyWiresConfigFieldsByNameNotPosition(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        config.set("lod.distance.default_chunks", 251);
        config.set("generation.enabled", false);
        var service = new RequestProcessingService(server, config);
        helper.assertTrue(config.enabled(), "premise: gametest config runs enabled");

        var replies = new ArrayList<SessionConfigS2CPayload>();
        try {
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, replies::add);
            helper.assertTrue(replies.size() == 1, "premise: REGISTER handshake must reply once");
            var reply = replies.get(0);
            helper.assertTrue(reply.enabled(),
                    "effectiveEnabled must be true (config enabled + service present)");
            helper.assertTrue(reply.lodDistanceChunks() == 251,
                    "lodDistanceChunks must wire from the service configuration, got "
                            + reply.lodDistanceChunks());
            helper.assertTrue(!reply.generationEnabled(),
                    "generationEnabled must wire from the service policy"
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
            helper.assertTrue(replies.isEmpty(),
                    "a Via-mismatched v19 handshake must stay SILENT, got "
                            + replies.size() + " reply frame(s)");
            helper.assertTrue(!service.getPlayers().containsKey(mock.getUUID()),
                    "a Via-mismatched legacy client must never register");

            // Fail-open twin: the same frame with no Via signal registers normally.
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(19, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, replies::add,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 774);
            helper.assertTrue(!replies.isEmpty(),
                    "no Via signal must leave the v19 rung untouched (fail-open)");
            helper.assertTrue(service.getPlayers().containsKey(mock.getUUID()),
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
     * pins "resumes within a tick". This is an isolated consumer branch test, not a reload test: service.enabled is
     * restart-only in production. The fixture swaps complete immutable snapshots.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void disabledTickFreezesPipelineAndFrozenEventsApplyOnFirstResumedCycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkPos = chunkAt(pcx - 140, pcz - 4);
        long packed = PositionUtil.packPosition(chunkPos.x(), chunkPos.z());
        var chunkSource = level.getChunkSource();
        holdChunk(chunkSource, chunkPos);
        level.getChunk(chunkPos.x(), chunkPos.z());

        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        // Stale done-bit + a ts>0 request: only the frozen-queued clear makes it re-serve.
        state.markDiskReadDone(chunkPos.x(), chunkPos.z());
        GameTestSeeding.seedRequest(state, packed, 5L);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        try {
            config.set("service.enabled", false);
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
            config.set("service.enabled", true);
        }
        service.tick(); // exactly one resumed tick — the wait below never ticks again

        helper.succeedWhen(() -> {
            helper.assertTrue(diag.getTotalRequestsRouted() == 1,
                    "the first resumed tick must post the snapshot that routes the frozen request");
            helper.assertTrue(diag.getTotalInMemory() == 1,
                    "the frozen done-bit clear must apply BEFORE routing: the ts>0 re-request "
                            + "must probe-serve (a stale done-bit answers it up-to-date instead)");
            releaseChunk(chunkSource, chunkPos);
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
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
                        SlotType.SYNC_ON_LOAD, 0L)),
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
        var chunkPos = chunkAt(pcx - 144, pcz - 10);
        var chunkSource = level.getChunkSource();
        holdChunk(chunkSource, chunkPos);
        level.getChunk(chunkPos.x(), chunkPos.z());
        GameTestSeeding.seedRequest(state, PositionUtil.packPosition(chunkPos.x(), chunkPos.z()), -1L);

        helper.succeedWhen(() -> {
            service.tick();
            helper.assertTrue(state.getTotalSectionsSent() >= 1,
                    "waiting for the post-respawn request to serve through the new player "
                            + "reference (probe + flush both read state.getPlayer())");
            releaseChunk(chunkSource, chunkPos);
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void probeBudgetPushesTrailingLoadedRequestsToDiskWithoutStarvation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();
        var posK1 = chunkAt(pcx - 156, pcz - 20);
        var posK2 = chunkAt(pcx - 156, pcz - 21);
        holdChunk(chunkSource, posK1);
        holdChunk(chunkSource, posK2);
        level.getChunk(posK1.x(), posK1.z());
        level.getChunk(posK2.x(), posK2.z());
        // The pair must exist on disk before the budget forces this fallback.
        var savedColumns = new SavedColumnFixture(level, posK1, posK2);

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
        packed[512] = PositionUtil.packPosition(posK1.x(), posK1.z());
        stamps[512] = -1L;
        packed[513] = PositionUtil.packPosition(posK2.x(), posK2.z());
        stamps[513] = -1L;
        var admitted = new java.util.concurrent.atomic.AtomicBoolean();
        var diag = service.getOffThreadProcessor().getDiagnostics();
        var diskDiag = service.getDiskReader().getDiag();
        helper.succeedWhen(() -> {
            if (!admitted.get()) {
                savedColumns.assertSaved(helper);
                GameTestSeeding.seedRequests(state, packed, stamps);
                admitted.set(true);
            }
            service.tick();
            helper.assertTrue(diskDiag.getSuccessfulReadCount() >= 2 && state.getTotalSectionsSent() >= 2,
                    "waiting for the disk-served pair to flush (budget remainder must still serve): success="
                            + diskDiag.getSuccessfulReadCount() + ", sent=" + state.getTotalSectionsSent()
                            + "; " + service.getDiskReader().getDiagnostics());
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
            releaseChunk(chunkSource, posK1);
            releaseChunk(chunkSource, posK2);
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void duplicateQueuedPositionsSerializeOncePerProbePass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();
        var posC = chunkAt(pcx - 164, pcz - 24);
        var posD = chunkAt(pcx - 164, pcz - 25);
        holdChunk(chunkSource, posC);
        holdChunk(chunkSource, posD);
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
        // NOTE (M3): far seeds outside the sweep radius — see the sweep note above
        // (safe while maxTicks < 1200).
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
            releaseChunk(chunkSource, posC);
            releaseChunk(chunkSource, posD);
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * FP-039: a generation-stage ProtoChunk passing through the real save pass must not
     * mark dirty ({@code LSSServerNetworking.onChunkSaveData}'s {@code instanceof LevelChunk} guard) — proto
     * saves have no LOD-servable content, and marking them would broadcast positions that
     * then resolve not-found. The edited LevelChunk in the same save pass is the positive
     * control proving the save ran and the hook is live. The real proto snapshot is
     * also invoked explicitly: saveAll may omit a generation-stage holder entirely.
     * Drain–save–snapshot–drain runs in one synchronous callback.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 300)
    public void protoChunkSavesAreExcludedFromDirtyMarking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var liveService = LSSServerNetworking.getRequestService();
        helper.assertTrue(liveService != null, "live service required (the save hook feeds it)");
        // Arm the P3 never-registered skip gate — the control assertion below needs the
        // live hook to hash (one-way latch; no Tier 2 test pins the skip).
        liveService.armSaveHookForTest();
        var origin = chunkAt(helper.absolutePos(BlockPos.ZERO));
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var chunkSource = level.getChunkSource();

        // Control: a loaded LevelChunk with an edit must mark in the same save pass.
        var controlPos = chunkAt(origin.x() - 172, origin.z() - 32);
        long controlPacked = PositionUtil.packPosition(controlPos.x(), controlPos.z());
        holdChunk(chunkSource, controlPos);
        var control = level.getChunk(controlPos.x(), controlPos.z());
        var editPos = new BlockPos(controlPos.x() * 16 + 4, -61, controlPos.z() * 16 + 4);

        // Proto: per-run salted coords — a previous run's chunk would load already-generated
        // and not be unsaved, making the save pass skip it and the assertion vacuous.
        int protoCx = origin.x() - 168;
        int protoCz = origin.z() + (int) Math.floorMod(System.nanoTime(), 64L);
        var proto = chunkSource.getChunk(protoCx, protoCz, ChunkStatus.STRUCTURE_STARTS, true);
        helper.assertTrue(proto != null && !(proto instanceof LevelChunk),
                "premise: a STRUCTURE_STARTS chunk must still be a ProtoChunk");
        proto.setUnsaved(true); // 1.21.1 line: markUnsaved is 26.x
        long protoPacked = PositionUtil.packPosition(protoCx, protoCz);

        helper.startSequence().thenWaitUntil(() -> {
            // C2ME distinguishes obtaining a LevelChunk from its holder becoming
            // accessible and entering the visible save set. Settle that premise before
            // editing; do not weaken the immediate save-hook receipt into eventual success.
            var holder = saveHolder(chunkSource, controlPos);
            helper.assertTrue(helper.getTick() >= 2 && holder != null
                            && holder.wasAccessibleSinceLastSave() && holder.isReadyForSaving(),
                    "waiting for the control holder to become save-eligible; holder=" + holder
                            + ", status=" + control.getFullStatus());
        }).thenExecute(() -> {
            // This is a one-shot assertion after readiness, not an edit/retry loop.
            var edit = level.getBlockState(editPos).is(Blocks.STONE) ? Blocks.COBBLESTONE : Blocks.STONE;
            level.setBlock(editPos, edit.defaultBlockState(), 3);
            var tracker = liveService.getDirtyTracker();
            tracker.drainDirty(dim);
            level.save(null, true, false);
            net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, proto);
            long[] dirty = tracker.drainDirty(dim);
            helper.assertTrue(containsPosition(dirty, controlPacked),
                    "premise/control: the edited LevelChunk must mark dirty in this save pass "
                            + "(proves the save ran and the hook is live); unsaved=" + control.isUnsaved());
            helper.assertTrue(!containsPosition(dirty, protoPacked),
                    "a ProtoChunk save must NOT mark dirty (ChunkSaveDataHook must exclude "
                            + "generation-stage saves — they have no LOD-servable content)");
            releaseChunk(chunkSource, controlPos);
        }).thenSucceed();
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
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

    /** A far-player viewer snapshot for driving {@code FarPlayerBroadcastService.tick} directly. */
    private static dev.vox.lss.common.farplayers.FarPlayerBroadcastService.PlayerSnapshot viewerSnapshot(
            java.util.UUID uuid, String dimension) {
        return new dev.vox.lss.common.farplayers.FarPlayerBroadcastService.PlayerSnapshot(
                uuid, "viewer", dimension, 0, 64, 0, 0f, 0f, 0f, (byte) 0, 0, 0, 0,
                false, false, true, false, false, 0L, new String[0], new int[0], null);
    }

    /**
     * Distance 0 through the reload re-push with a live (mock) player in the world being
     * turned off: the CURRENT session is told exactly {@code enabled=false, 0}, its state is
     * replaced (in-flight work retired, served-set reset), nothing it asks is accepted, and
     * the far-player roster re-sends at a bumped epoch — the client ends its far-player
     * session on that enabled=false, so without the full roster every later update carries
     * an epoch it no longer knows and far players vanish until a dimension change or rejoin.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void aReloadThatTurnsTheWorldOffParksThePlayerAndReSendsTheFarPlayerRoster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var viewer = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        config.set("lod.distance.default_chunks", 64);
        var service = new RequestProcessingService(server, config);
        String world = level.dimension().location().toString();
        try {
            var replies = new ArrayList<SessionConfigS2CPayload>();
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    viewer, service, replies::add);
            helper.assertTrue(replies.size() == 1 && replies.getFirst().enabled()
                            && replies.getFirst().lodDistanceChunks() == 64,
                    "premise: a served world at 64, got " + replies);
            var fp = service.getFarPlayerService();
            fp.onPrefs(viewer.getUUID(), new dev.vox.lss.common.farplayers.FarPlayerWire.Prefs(true, 0, 0, true, 0));
            var fullRosterEpochs = new ArrayList<Integer>();
            dev.vox.lss.common.farplayers.FarPlayerBroadcastService.FrameSender sender = (to, channel, body) -> {
                if (to.equals(viewer.getUUID()) && channel.equals(LSSConstants.CHANNEL_FAR_PLAYER_ROSTER)) {
                    var roster = dev.vox.lss.common.farplayers.FarPlayerWire.decodeRoster(body);
                    if (roster.full()) fullRosterEpochs.add(roster.epoch());
                }
                return true;
            };
            var settings = new dev.vox.lss.common.farplayers.FarPlayerBroadcastService.Settings(
                    "on", 2048, 0, false, java.util.List.of(), 10, false);
            var online = java.util.List.of(viewerSnapshot(viewer.getUUID(), world));
            long now = 1_000_000L;
            fp.tick(now, online, settings, sender);
            helper.assertTrue(fullRosterEpochs.size() == 1, "premise: the first full roster went out");

            var before = service.getPlayers().get(viewer.getUUID());
            var pushes = new ArrayList<SessionConfigS2CPayload>();
            service.setSessionConfigPusher((to, payload) -> { if (to == viewer) pushes.add(payload); });
            config.set("lod.distance.by_dimension", java.util.Map.of(world, 0));
            service.repushSessionConfig();

            helper.assertTrue(pushes.size() == 1 && !pushes.getFirst().enabled()
                            && pushes.getFirst().lodDistanceChunks() == 0,
                    "the off world is pushed as exactly enabled=false, distance 0, got " + pushes);
            var after = service.getPlayers().get(viewer.getUUID());
            helper.assertTrue(after != null && after != before && before.registration().isRetired(),
                    "the player stays registered with a fresh state; the old one is retired");
            int cx = viewer.getBlockX() >> 4, cz = viewer.getBlockZ() >> 4;
            service.handleBatchRequest(viewer, new BatchChunkRequestC2SPayload(
                    new long[]{PositionUtil.packPosition(cx, cz)}, new long[]{-1L}, 1));
            helper.assertTrue(after.getTotalRequestsReceived() == 0,
                    "nothing is accepted in the off world, not even the player's own chunk");

            now += dev.vox.lss.common.farplayers.FarPlayerBroadcastService.FULL_ROSTER_MIN_INTERVAL_MILLIS + 1;
            fp.tick(now, online, settings, sender);
            helper.assertTrue(fullRosterEpochs.size() == 2 && fullRosterEpochs.get(1) > fullRosterEpochs.get(0),
                    "the reload-off re-sends a full far-player roster at a bumped epoch, got " + fullRosterEpochs);
        } finally {
            service.shutdown();
            server.getPlayerList().remove(viewer);
        }
        helper.succeed();
    }

    /**
     * Distance 0 across a dimension change (xplat — the Paper twin is pinned in
     * PaperRequestProcessingServiceTest): portalling INTO an off world pushes exactly
     * {@code enabled=false, 0} and its asks are range-filtered; portalling back out pushes
     * {@code enabled=true} with the served distance. Dropping the {@code newDist > 0}
     * conjunct would push {@code enabled=true, 0}, which the client clamps to 1 and then
     * declares against a filter that accepts nothing, forever.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void enteringAndLeavingAnOffWorldPushesDisabledThenEnabled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var endLevel = server.getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        var mock = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        config.set("lod.distance.default_chunks", 64);
        config.set("lod.distance.by_dimension", java.util.Map.of("minecraft:the_end", 0));
        var service = new RequestProcessingService(server, config);
        try {
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, reply -> { });
            var pushes = new ArrayList<SessionConfigS2CPayload>();
            service.setSessionConfigPusher((to, payload) -> { if (to == mock) pushes.add(payload); });

            mock.setServerLevel(endLevel);
            service.tick();
            helper.assertTrue(pushes.size() == 1 && !pushes.getFirst().enabled()
                            && pushes.getFirst().lodDistanceChunks() == 0,
                    "entering the off world pushes enabled=false, 0, got " + pushes);
            var parked = service.getPlayers().get(mock.getUUID());
            helper.assertTrue(parked != null, "the player stays registered in the off world");
            int cx = mock.getBlockX() >> 4, cz = mock.getBlockZ() >> 4;
            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    new long[]{PositionUtil.packPosition(cx, cz)}, new long[]{-1L}, 1));
            helper.assertTrue(parked.getTotalRequestsReceived() == 0,
                    "an ask in the off world is range-filtered, not accepted");

            mock.setServerLevel(level);
            service.tick();
            helper.assertTrue(pushes.size() == 2 && pushes.get(1).enabled()
                            && pushes.get(1).lodDistanceChunks() == 64,
                    "leaving the off world pushes enabled=true with the served distance, got " + pushes);
        } finally {
            mock.setServerLevel(level);
            service.shutdown();
            server.getPlayerList().remove(mock);
        }
        helper.succeed();
    }

    /**
     * SP-062 counts idle-but-connected players toward the per-player bandwidth share; a
     * player parked in a world with no LOD service can never spend (every ask is
     * range-filtered), so it must not shrink the share of the players being served.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void playersParkedInAnOffWorldDoNotDiluteTheBandwidthShare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var endLevel = server.getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        var config = new ServiceSettingsFixture();
        config.set("lod.distance.default_chunks", 64);
        config.set("lod.distance.by_dimension", java.util.Map.of("minecraft:the_end", 0));
        var service = new RequestProcessingService(server, config);
        var mocks = new ArrayList<net.minecraft.server.level.ServerPlayer>();
        try {
            for (int i = 0; i < 4; i++) {
                var mock = placeMockServerPlayer(helper);
                mocks.add(mock);
                if (i >= 2) mock.setServerLevel(endLevel);
                service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            }
            service.tick();
            helper.assertTrue(service.lastBandwidthSharingPlayerCount() == 2,
                    "only the two players in a served world share the bandwidth, got "
                            + service.lastBandwidthSharingPlayerCount());
        } finally {
            service.shutdown();
            for (var mock : mocks) {
                mock.setServerLevel(level);
                server.getPlayerList().remove(mock);
            }
        }
        helper.succeed();
    }

    /**
     * Far players E1 (FARP §7-A): the SERVER EGRESS surface through the production
     * handshake — a crafted handshake WITH the capability bit subscribes (one without
     * it does not), and a broadcast pass sends a roster + updates for an in-range far
     * target while an out-of-range one is filtered. Client tracker state is Tier 3 /
     * live territory. The client arm plays no part here — this test
     * crafts the bit the way an E2 client will.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void farPlayerSubscriberGetsRosterAndUpdatesForInRangeTargetsOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var viewer = placeMockServerPlayer(helper);
        var farTarget = placeMockServerPlayer(helper);
        var beyondCap = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        String savedMode = config.farPlayers();
        int savedInterval = config.farPlayersUpdateIntervalTicks();
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
            helper.assertTrue(service.getFarPlayerService().isSubscribed(viewer.getUUID()),
                    "the capability bit on a CURRENT-dialect handshake subscribes");

            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    farTarget, service, reply -> { });
            helper.assertTrue(!service.getFarPlayerService().isSubscribed(farTarget.getUUID()),
                    "no bit -> no subscription");

            service.getFarPlayerService().onPrefs(viewer.getUUID(),
                    new dev.vox.lss.common.farplayers.FarPlayerWire.Prefs(true, 0, 0, true, 0));
            config.set("far_players.mode", "on");
            config.set("far_players.update_interval_ticks", 2); // the clamp floor — 2 service ticks
            service.tick();
            service.tick();

            var fp = service.getFarPlayerService();
            helper.assertTrue(fp.rosterFramesSent() >= 1,
                    "a full roster must have gone out, sent=" + fp.rosterFramesSent());
            helper.assertTrue(fp.updateFramesSent() >= 1,
                    "an updates frame must have gone out, sent=" + fp.updateFramesSent());
            // Entry-count isolation is impossible on the shared gametest server (other
            // tests' mock players are online concurrently and may fall in range) — the
            // exact ring/filter arithmetic is Tier 1's job
            // (FarPlayerBroadcastServiceTest); this tier pins the real egress.
            helper.assertTrue(fp.entriesSent() >= 1,
                    "at least the in-range target is served, entries=" + fp.entriesSent());
            helper.assertTrue(fp.bytesSent() > 0, "the dedicated lane counted its bytes");
        } finally {
            config.set("far_players.mode", savedMode);
            config.set("far_players.update_interval_ticks", savedInterval);
            service.shutdown();
        }
        helper.succeed();
    }

    /**
     * Mode "required" (hide-permission plan §2b) through the REAL broadcast pass of the
     * shared core (Fabric, NeoForge and Sponge run it): a player who turned Share My
     * Position off is told once, in chat, that it does not apply on this server; a player
     * who shares is told nothing; mode "on" tells nobody. The exact filter arithmetic and
     * the once-per-connection latch are Tier 1's job (FarPlayerBroadcastServiceTest); this
     * tier pins that the service's own tick builds the snapshots, reads the live mode and
     * reaches the player through the system-message lane.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void requiredModeTellsAnOverriddenPlayerOnceInChat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var viewer = placeMockServerPlayer(helper);
        var optedOut = placeMockServerPlayer(helper);
        var sharer = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        String savedMode = config.farPlayers().equals("opt-in") ? "opt_in" : config.farPlayers();
        int savedInterval = config.farPlayersUpdateIntervalTicks();
        try {
            optedOut.setPos(viewer.getX() + 500, viewer.getY(), viewer.getZ());
            sharer.setPos(viewer.getX() + 600, viewer.getY(), viewer.getZ());
            var told = new ArrayList<String>();
            service.setFarPlayerNoticeSenderForTest((player, line) ->
                    told.add(player.getUUID() + "|" + line.fallback()));
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    viewer, service, reply -> { });
            var fp = service.getFarPlayerService();
            fp.onPrefs(viewer.getUUID(), new dev.vox.lss.common.farplayers.FarPlayerWire.Prefs(true, 0, 0, true, 0));
            fp.onPrefs(optedOut.getUUID(), new dev.vox.lss.common.farplayers.FarPlayerWire.Prefs(true, 0, 0, false, 0));
            fp.onPrefs(sharer.getUUID(), new dev.vox.lss.common.farplayers.FarPlayerWire.Prefs(true, 0, 0, true, 0));
            config.set("far_players.update_interval_ticks", 2); // the clamp floor — 2 service ticks

            config.set("far_players.mode", "on");
            service.tick();
            service.tick();
            helper.assertTrue(told.isEmpty(), "mode on honors the opt-out: nobody is told, got " + told);
            helper.assertTrue(!fp.diagLine().contains("overridden="), "mode on: " + fp.diagLine());

            config.set("far_players.mode", "required");
            for (int i = 0; i < 6; i++) service.tick();
            String expected = optedOut.getUUID() + "|"
                    + dev.vox.lss.common.farplayers.FarPlayerBroadcastService.requiredSharingNotice().fallback();
            helper.assertTrue(told.equals(java.util.List.of(expected)),
                    "exactly one chat line, to the opted-out player only, got " + told);
            helper.assertTrue(fp.diagLine().contains("overridden=1"),
                    "the diag line counts the overridden player: " + fp.diagLine());
            helper.assertTrue(fp.hasRequiredSharingNotice(optedOut.getUUID())
                            && !fp.hasRequiredSharingNotice(sharer.getUUID()),
                    "the per-connection latch holds the overridden player only");
        } finally {
            config.set("far_players.mode", savedMode);
            config.set("far_players.update_interval_ticks", savedInterval);
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 1200)
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
        helper.assertTrue(stamp > 0
                        && stamp != dev.vox.lss.common.region.RegionStampTable.NEVER_CLEAN
                        && stamp <= nowSec + 3600,
                "a REAL region header must yield a plausible save second (the z-major"
                        + " layout against the game's own writer), got " + stamp);
        long tile = service.getRegionStamps().tileStampSeconds(dim, cx >> 5, cz >> 5);
        helper.assertTrue(tile >= stamp
                        && tile != dev.vox.lss.common.region.RegionStampTable.NEVER_CLEAN,
                "the tile stamp must cover the chunk's save second, got " + tile);

        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        long clientTs = stamp
                + dev.vox.lss.common.region.RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS + 10;
        helper.assertTrue(state.tryAdmit(new PendingRequest(cx, cz,
                        SlotType.SYNC_ON_LOAD, clientTs)),
                "premise: pending admitted (the router's admission shape)");
        service.getDiskReader().submitReadDirect(mock.getUUID(), state.registration(), dim, level, cx, cz,
                1L, clientTs);

        service.handleRegionSummaryRequest(mock,
                dev.vox.lss.common.region.RegionSummaryWire.encodeRequest(
                        new dev.vox.lss.common.region.RegionSummaryWire.Request(
                                dim, cx >> 5, cz >> 5, 1)));

        succeedWhenThenTearDown(helper, 1200, () -> {
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
            helper.assertTrue(service.getDiskReader().getDiag().getHeaderHitsCount() >= 1,
                    "the header rung must intercept the margined-fresh read against the"
                            + " real region file");
            helper.assertTrue(state.hasDiskReadDone(cx, cz),
                    "the intercepted ask resolves up_to_date (done-bit)");
            var summary = service.getRegionSummaries().diagnostics();
            // The whole summary line at timeout (2026-10-14 fold): tiles counted = the
            // sweeper assembled; refresh_ms_max = how long it took; expired/reassembled
            // = a ready frame aged out before a drain (a tick-thread stall) — so a
            // further sighting says which leg stalled without a re-run.
            helper.assertTrue(summary.getFrames() >= 1,
                    "one summary frame must assemble and send, reqs=" + summary.getRequests()
                            + " frames=" + summary.getFrames() + " [" + summary.diagLineOrNull()
                            + "; header_hits="
                            + service.getDiskReader().getDiag().getHeaderHitsCount() + "]");
            helper.assertTrue(summary.getBytes() > 0,
                    "the frame's bytes count on the dedicated lane");
            // A frame that ages out unsent during a tick stall is re-assembled (the
            // 2026-10-14 fold), and every assembly counts its tiles: 9 per assembly.
            long assemblies = 1 + summary.getFramesReassembled();
            helper.assertTrue(summary.getTilesKnown() + summary.getTilesNeverClean()
                            + summary.getTilesNoRegion() == 9 * assemblies,
                    "a radius-1 window reports exactly 9 tiles per assembly, known="
                            + summary.getTilesKnown() + " never=" + summary.getTilesNeverClean()
                            + " no_region=" + summary.getTilesNoRegion()
                            + " reassembled=" + summary.getFramesReassembled());
            helper.assertTrue(summary.getTilesKnown() >= 1,
                    "the saved chunk's own tile must report a real stamp");
            // Stamped up_to_date (the whole server lane's ONE real-server pin, per the
            // 3-Opus fold): the summary request armed eligibility, the header rung's
            // margined-fresh interception is a compare-backed disposition, so its
            // up_to_date must ship a verification-stamp frame on lss:col_stamps.
            helper.assertTrue(summary.getStampsFrames() >= 1
                            && summary.getStampsEntries() >= 1,
                    "the compare-backed up_to_date must stamp (frames="
                            + summary.getStampsFrames() + " entries="
                            + summary.getStampsEntries() + ")");
        }, () -> {
            // Tear down like the class's async probe test does — the throwaway service
            // must not leak its threads/pools NOR leave its constructor-published
            // XrayMaskManager as the static manager (the documented gametest
            // static-stomping hazard) — on a timeout too.
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void serviceGateDeniesInDialectAndUnregistersALiveSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        LSSServerNetworking.SessionConfigResponder recorder = replies::add;
        boolean savedArm = config.requireServicePermission();
        config.set("service.require_permission", true);
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
            helper.assertTrue(replies.size() == 1 && !replies.get(0).enabled()
                            && replies.get(0).protocolVersion() == LSSConstants.PROTOCOL_VERSION
                            && !replies.get(0).v16Wire(),
                    "a denied CURRENT handshake must reply enabled=false in the CURRENT "
                            + "shape, got " + replies);
            helper.assertTrue(service.getPlayers().get(uuid) == null,
                    "a denied player must never be registered");
            helper.assertTrue(gateState.isDenied(uuid) && gateState.permissionDeniedTotal() == 1,
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
            helper.assertTrue(replies.size() == 2 && !replies.get(1).enabled()
                            && replies.get(1).v16Wire(),
                    "a denied v16 handshake must be denied in its OWN dialect (the 6-field "
                            + "enabled=false shape), got " + replies);
            helper.assertTrue(service.getPlayers().get(uuid) == null
                            && gateState.permissionDeniedTotal() == 1,
                    "still unregistered, still one transition");

            // 3. The gate disarmed mid-session: the same player registers through the
            //    same entry (the OPEN overload path is production for gate-off in the
            //    sense that serviceDenied short-circuits false before any probe).
            config.set("service.require_permission", false);
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, deny);
            helper.assertTrue(replies.size() == 3 && replies.get(2).enabled()
                            && service.getPlayers().get(uuid) != null,
                    "with the gate off the SAME denying backend is never consulted and the "
                            + "player registers");
            helper.assertTrue(!gateState.isDenied(uuid),
                    "a successful registration by any path removes the memo entry");
            helper.assertTrue(service.getFarPlayerService().subscriberCount() == 1,
                    "premise: the live session is a far-player viewer");

            // 4. Re-armed + denied re-handshake of the LIVE session: the composite runs —
            //    state gone, viewer shed — and the reply advertises disabled.
            config.set("service.require_permission", true);
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, deny);
            helper.assertTrue(replies.size() == 4 && !replies.get(3).enabled(),
                    "the revoked re-handshake replies enabled=false (the client disarm)");
            helper.assertTrue(service.getPlayers().get(uuid) == null,
                    "a permission denial is an ADMIN fact: the live registration must NOT "
                            + "survive it (§8 O2-M3)");
            helper.assertTrue(service.getFarPlayerService().subscriberCount() == 0,
                    "the composite sheds the far-player viewer too — a revoked player must "
                            + "not keep receiving proxy frames");
            helper.assertTrue(gateState.isDenied(uuid) && gateState.permissionDeniedTotal() == 2,
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
                    helper.fail("the denial hook must never fire for a holding player");
                }
            };
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, hold);
            helper.assertTrue(replies.size() == 5 && replies.get(4).enabled()
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
            helper.assertTrue(replies.size() == 6 && !replies.get(5).enabled(),
                    "a DENIED consumer-less handshake advertises enabled=false");
            helper.assertTrue(gateState.permissionDeniedTotal() == deniedBefore
                            && !gateState.isDenied(uuid),
                    "…but NO_CONSUMER is not a gate decision: no memo deposit, no count");
            dev.vox.lss.networking.server.ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, 0),
                    mock, service, recorder,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, nativeProto, hold);
            helper.assertTrue(replies.size() == 7 && replies.get(6).enabled(),
                    "a HOLDING consumer-less handshake keeps enabled=true — the gate is "
                            + "invisible to players it does not deny");
        } finally {
            config.set("service.require_permission", savedArm);
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void serviceGateSweepsRevokeAndReofferALiveSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        LSSServerNetworking.SessionConfigResponder recorder = replies::add;
        boolean savedArm = config.requireServicePermission();
        var denying = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            // Register through the production entry (no permission backend in a gametest
            // JVM: the LoaderServices default serves).
            LSSServerNetworking.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS),
                    mock, service, recorder);
            helper.assertTrue(service.getPlayers().get(uuid) != null && replies.size() == 1,
                    "premise: registered");
            helper.assertTrue(service.getFarPlayerService().subscriberCount() == 1,
                    "premise: far-player viewer");

            config.set("service.require_permission", true);
            service.setPermissionProbeForTest((p, node) -> !denying.get());
            denying.set(true);

            service.runServiceGateSweeps(config);
            helper.assertTrue(service.getPlayers().get(uuid) != null,
                    "one failing sweep never revokes (flap hysteresis)");

            service.runServiceGateSweeps(config);
            helper.assertTrue(service.getPlayers().get(uuid) == null,
                    "the second consecutive failing sweep revokes the live session");
            helper.assertTrue(service.getFarPlayerService().subscriberCount() == 0,
                    "the composite sheds the far-player viewer");
            var remembered = service.getServiceGateState().peekDenied(uuid);
            helper.assertTrue(remembered != null
                            && remembered.capabilities() == (LSSConstants.CAPABILITY_VOXEL_COLUMNS
                                    | LSSConstants.CAPABILITY_FAR_PLAYERS)
                            && remembered.protocolVersion() == LSSConstants.PROTOCOL_VERSION,
                    "the memo carries the LIVE session's version+capabilities");
            helper.assertTrue(service.getServiceGateState().permissionDeniedTotal() == 1,
                    "one revocation = one counted transition");

            // The grant: the next sweep replays the stored handshake through the FULL
            // production ladder and the player re-registers, far viewer included.
            denying.set(false);
            service.runServiceGateSweeps(config);
            helper.assertTrue(service.getPlayers().get(uuid) != null,
                    "the re-offer replays the production ladder and re-registers");
            helper.assertTrue(service.getFarPlayerService().subscriberCount() == 1,
                    "the replayed capabilities restore the far-player subscription");
            helper.assertTrue(!service.getServiceGateState().isDenied(uuid),
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
            helper.assertTrue(!service.getServiceGateState().isDenied(uuid),
                    "the unservable replay drops the entry (taken before the ladder ran)");
            helper.assertTrue(service.getPlayers().get(uuid) == null,
                    "…without registering anybody (VERSION_MISMATCH is the silent rung)");
            service.runServiceGateSweeps(config);
            helper.assertTrue(!service.getServiceGateState().isDenied(uuid)
                            && service.getPlayers().get(uuid) == null,
                    "…and it never loops: the entry is gone for good");
        } finally {
            config.set("service.require_permission", savedArm);
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void pendingLoadSeedsFlushFromRealLoadedChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 0));
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        helper.assertTrue(chunk != null,
                    "premise: the structure's chunk is loaded");
        var service = new RequestProcessingService(server);
        try {
            service.armSaveHookForTest();
            ServerReceiverGlue.clearPendingLoadSeeds();
            ServerReceiverGlue.onChunkLoaded(level, chunk, null, true); // a NEW chunk: never seeded, never recorded
            helper.assertTrue(ServerReceiverGlue.pendingLoadSeedCount() == 0,
                    "a freshly generated chunk is not recorded — its first save must still broadcast");
            ServerReceiverGlue.onChunkLoaded(level, chunk, null, false); // no service: recorded, not seeded
            helper.assertTrue(ServerReceiverGlue.pendingLoadSeedCount() == 1,
                    "a load with no service records the position");
            int seeded = ServerReceiverGlue.flushPendingLoadSeeds(server, service);
            helper.assertTrue(seeded == 1,
                    "the flush seeds the still-loaded chunk, got " + seeded);
            helper.assertTrue(service.getDirtyContentFilter().getTotalSeededLoads() == 1,
                    "seeded_load counts it");
            String dimension = level.dimension().location().toString();
            var obs = service.getDirtyContentFilter().observeSave(level, chunk, dimension);
            helper.assertFalse(obs.changed(),
                    "the chunk's first save after the load seed is suppressed (identical bytes)");
            helper.assertTrue(ServerReceiverGlue.pendingLoadSeedCount() == 0,
                    "flushed set is cleared");
            helper.succeed();
        } finally {
            ServerReceiverGlue.clearPendingLoadSeeds(); // never leave static state for later tests
            service.shutdown();
        }
    }
    // ---- the server map-ingestion request (map-policy plan A3/A5 "server glue") ----

    private static final int MAP_BIT = LSSConstants.CAPABILITY_MAP_POLICY;
    private static final int VOXEL = LSSConstants.CAPABILITY_VOXEL_COLUMNS;

    /**
     * The map-policy rung on the SHARED glue through the PRODUCTION 4-arg entry (the real
     * gate, the real service, the real memo): a capable CURRENT session registers and its
     * reply carries the deny flag; the Fabric ladder-race row — a 0.19 client's raced
     * 19 rung WITH the bit takes the QUIET refusal (the 19 echo, enabled=false, no memo,
     * no chat line, the live registration and its CURRENT dialect untouched) and its
     * downgrade-guard re-announce re-registers with flags; a 0.18-shaped session (no bit)
     * re-handshaking while armed takes the REAL refusal (reply in its own dialect, the
     * live-session unregistration composite, memo reason MAP_POLICY, ONE counted
     * transition, the chat line ONCE per connection across its whole discovery ladder);
     * the replay-loop pin (an armed policy with the permission gate OFF replays nobody
     * across N sweeps); and the disarm replay that heals the refused session.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapPolicyRefusesInDialectQuietlyForARacedRungAndNeverReplayLoops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var replies = new ArrayList<SessionConfigS2CPayload>();
        LSSServerNetworking.SessionConfigResponder recorder = replies::add;
        var notices = new ArrayList<String>();
        service.setMapPolicyNoticeSenderForTest((player, text) -> notices.add(text));
        var gateState = service.getServiceGateState();
        try {
            config.set("privacy.request_no_map_ingestion", true);

            // 1. A capable CURRENT session registers; the reply carries the deny flag.
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), mock, service, recorder);
            helper.assertTrue(service.getPlayers().get(uuid) != null && replies.size() == 1
                            && replies.get(0).enabled()
                            && replies.get(0).sessionFlags() == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                    "a 0.19 client registers and is told the request, got " + replies);

            // 2. Its raced 19 rung WITH the bit: the QUIET refusal — the reply only.
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, VOXEL | MAP_BIT), mock, service, recorder);
            var quiet = replies.get(1);
            helper.assertTrue(quiet.protocolVersion() == LSSConstants.V19_COMPAT_PROTOCOL_VERSION
                            && !quiet.enabled()
                            && quiet.sessionFlags() == LSSConstants.SESSION_FLAGS_ABSENT,
                    "the raced rung gets a 19 echo, enabled=false, never the flags field: " + quiet);
            helper.assertTrue(service.getPlayers().get(uuid) != null,
                    "the QUIET refusal never unregisters the live session");
            // isCurrent, not dialectOf: dialectOf defaults an UNMARKED session to CURRENT, so
            // it could not see the quiet refusal shedding the mark (review fold A8).
            helper.assertTrue(service.getDialectTracker().isCurrent(uuid),
                    "…nor sheds or flips its CURRENT dialect mark (it never registered as V19)");
            helper.assertTrue(!gateState.isDenied(uuid) && gateState.mapPolicyRefusedTotal() == 0
                            && notices.isEmpty(),
                    "no memo, no counter, no chat line for a raced rung");

            // 3. The client's downgrade guard re-announces v20: REGISTER again, flags again.
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), mock, service, recorder);
            helper.assertTrue(replies.get(2).enabled() && replies.get(2).sessionFlags() == 1,
                    "the heal re-registers with its flags");
            helper.assertTrue(service.getDialectTracker().isCurrent(uuid),
                    "…and re-marks the CURRENT dialect");

            // 4. Disarmed, an old client (no bit) registers — then the policy arms and the
            //    SAME live session re-handshakes: the REAL refusal + unregistration composite.
            config.set("privacy.request_no_map_ingestion", false);
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL), mock, service, recorder);
            helper.assertTrue(replies.get(3).enabled()
                            && replies.get(3).sessionFlags() == LSSConstants.SESSION_FLAGS_ABSENT,
                    "a session without the bit gets the 0.18.0 frame: " + replies.get(3));
            config.set("privacy.request_no_map_ingestion", true);
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL), mock, service, recorder);
            helper.assertTrue(!replies.get(4).enabled()
                            && replies.get(4).protocolVersion() == LSSConstants.PROTOCOL_VERSION,
                    "the real refusal replies enabled=false in the CURRENT shape");
            helper.assertTrue(service.getPlayers().get(uuid) == null,
                    "a live re-handshake that can no longer be served is unregistered");
            var memo = gateState.peekDenied(uuid);
            helper.assertTrue(memo != null && memo.has(dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY)
                            && gateState.mapPolicyRefusedTotal() == 1
                            && gateState.permissionDeniedTotal() == 0,
                    "memo reason MAP_POLICY, one map-policy transition, no permission count");
            helper.assertTrue(notices.size() == 1 && notices.get(0).contains("0.19.0 or newer"),
                    "the chat line: " + notices);

            // 5. The old client's discovery ladder (19, then 16) — refused in each dialect,
            //    still one transition and one chat line for the connection.
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, VOXEL), mock, service, recorder);
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.V16_COMPAT_PROTOCOL_VERSION, VOXEL), mock, service, recorder);
            helper.assertTrue(!replies.get(5).enabled()
                            && replies.get(5).protocolVersion() == LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                    "refused in the v19 dialect");
            helper.assertTrue(!replies.get(6).enabled() && replies.get(6).v16Wire(),
                    "refused in the v16 dialect (6-field enabled=false)");
            helper.assertTrue(gateState.mapPolicyRefusedTotal() == 1 && notices.size() == 1,
                    "re-refusals while refused never re-count or re-notify");

            // 6. The replay-loop pin: policy armed, permission gate OFF, N sweeps — the
            //    memo stays put and nothing replays (a replay would take and re-deposit the
            //    entry: a second transition).
            for (int i = 0; i < 5; i++) service.runServiceGateSweeps(config);
            helper.assertTrue(gateState.isDenied(uuid) && gateState.mapPolicyRefusedTotal() == 1
                            && service.getPlayers().get(uuid) == null,
                    "an armed policy with the gate off replays nobody");

            // 7. Disarm: the grant sweep replays the stored handshake through the full
            //    production ladder — the session re-registers, no chat line (never from a replay).
            config.set("privacy.request_no_map_ingestion", false);
            service.replayDeniedHandshakes(config);
            helper.assertTrue(service.getPlayers().get(uuid) != null && !gateState.isDenied(uuid),
                    "the disarm heals the refused session");
            helper.assertTrue(service.getDialectTracker().isCurrent(uuid),
                    "…in the CURRENT dialect: the ladder's 19/16 rungs never replaced the stored "
                            + "v20 handshake (review fold A11)");
            helper.assertTrue(notices.size() == 1, "a registering replay sends no chat line");
        } finally {
            service.shutdown();
            server.getPlayerList().remove(mock);
        }
        helper.succeed();
    }

    /**
     * Review fold A6: a player permission-REVOKED first and granted while the map policy is
     * armed lands on MAP_POLICY_REFUSED through the grant sweep's replay — a MAP_POLICY
     * transition, so the chat line goes out (the old "never from a replay" rule meant this
     * player never learned why). Exactly one line after the grant; still one after N sweeps
     * (the armed policy keeps the entry remembered, not replayed, and the latch holds).
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapPolicyRefusalOnAGrantReplayNotifiesOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var notices = new ArrayList<String>();
        service.setMapPolicyNoticeSenderForTest((player, text) -> notices.add(text));
        var gateState = service.getServiceGateState();
        var denying = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL), mock, service, reply -> { });
            helper.assertTrue(service.getPlayers().get(uuid) != null, "premise: an old client registered");
            config.set("service.require_permission", true);
            service.setPermissionProbeForTest((p, node) -> !denying.get());
            denying.set(true);
            service.runServiceGateSweeps(config);
            service.runServiceGateSweeps(config);
            helper.assertTrue(service.getPlayers().get(uuid) == null && gateState.isDenied(uuid)
                            && notices.isEmpty(),
                    "premise: revoked for the permission, no map-policy line yet");

            config.set("privacy.request_no_map_ingestion", true);
            denying.set(false);
            service.runServiceGateSweeps(config); // the grant replays — onto the armed policy
            var memo = gateState.peekDenied(uuid);
            helper.assertTrue(memo != null && memo.has(dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY)
                            && service.getPlayers().get(uuid) == null,
                    "the replay is refused by the policy: " + memo);
            helper.assertTrue(notices.size() == 1, "exactly one chat line after the grant: " + notices);
            for (int i = 0; i < 5; i++) service.runServiceGateSweeps(config);
            helper.assertTrue(notices.size() == 1 && gateState.mapPolicyRefusedTotal() == 1,
                    "still one after N sweeps");
        } finally {
            config.set("service.require_permission", false);
            service.shutdown();
            server.getPlayerList().remove(mock);
        }
        helper.succeed();
    }

    /**
     * The shared service's live half (map-policy plan A3.4): the arm sweep revokes ONLY a
     * registered CURRENT session without the bit (per-player enabled=false push, the
     * composite, memo, chat once) while a capable one keeps serving; it is idempotent;
     * the 200-tick cadence enforces the policy for a registration that raced the arm;
     * and the dimension-change push (every Nether trip at stock settings) carries the
     * capable session's flags.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void mapPolicyArmSweepCadenceAndDimensionChangePush(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var endLevel = server.getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        var old = placeMockServerPlayer(helper);
        var capable = placeMockServerPlayer(helper);
        var raced = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        config.set("lod.distance.default_chunks", 64);
        config.set("lod.distance.by_dimension", java.util.Map.of("minecraft:the_end", 32));
        var service = new RequestProcessingService(server, config);
        var notices = new ArrayList<java.util.UUID>();
        service.setMapPolicyNoticeSenderForTest((player, text) -> notices.add(player.getUUID()));
        try {
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL), old, service, reply -> { });
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), capable, service, reply -> { });
            var pushes = new ArrayList<Object[]>();
            service.setSessionConfigPusher((to, payload) -> pushes.add(new Object[]{to, payload}));

            helper.assertTrue(service.runMapPolicySweep(config) == 0, "disarmed: inert");
            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true); // as the arming reconcile would
            helper.assertTrue(service.runMapPolicySweep(config) == 1, "exactly the old session");
            // Map-ingestion permission plan §3.3: the same pass tells the capable session its
            // new decision (it was last sent flags 0 at its disarmed handshake) — AFTER the
            // revocation, in one flags-only push.
            helper.assertTrue(pushes.size() == 2 && pushes.get(0)[0] == old
                            && !((SessionConfigS2CPayload) pushes.get(0)[1]).enabled()
                            && ((SessionConfigS2CPayload) pushes.get(0)[1]).sessionFlags()
                                    == LSSConstants.SESSION_FLAGS_ABSENT,
                    "a per-player enabled=false push without a flags field, first");
            helper.assertTrue(pushes.get(1)[0] == capable
                            && ((SessionConfigS2CPayload) pushes.get(1)[1]).enabled()
                            && ((SessionConfigS2CPayload) pushes.get(1)[1]).sessionFlags()
                                    == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                    "then the capable session's flags-only push");
            helper.assertTrue(service.getPlayers().get(old.getUUID()) == null
                            && service.getPlayers().get(capable.getUUID()) != null,
                    "the old session is revoked; the capable one keeps serving");
            helper.assertTrue(notices.equals(java.util.List.of(old.getUUID()))
                            && service.getServiceGateState().mapPolicyRefusedTotal() == 1,
                    "chat once, one counted transition");
            helper.assertTrue(service.runMapPolicySweep(config) == 0 && pushes.size() == 2,
                    "idempotent");

            // The cadence: a registration that raced the arm (decided on the pre-arm config;
            // the handshake's register path marks the CURRENT dialect first).
            service.getDialectTracker().onHandshake(raced.getUUID(),
                    dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT);
            service.registerPlayer(raced, VOXEL);
            service.adoptMapPolicyForTest(false); // review fold A9: the reload owns the transition
            for (int i = 0; i < 200; i++) service.tick();
            helper.assertTrue(service.getPlayers().get(raced.getUUID()) != null,
                    "an arm the reconcile has not adopted: the cadence waits for it");
            service.adoptMapPolicyForTest(true);
            for (int i = 0; i < 199; i++) service.tick();
            helper.assertTrue(service.getPlayers().get(raced.getUUID()) != null, "199 ticks: not yet");
            service.tick();
            helper.assertTrue(service.getPlayers().get(raced.getUUID()) == null,
                    "the 200-tick cadence enforces the policy");

            // The dimension-change push carries the capable session's flags.
            pushes.clear();
            capable.setServerLevel(endLevel);
            service.tick();
            var dim = pushes.stream().filter(e -> e[0] == capable).toList();
            helper.assertTrue(dim.size() == 1
                            && ((SessionConfigS2CPayload) dim.get(0)[1]).lodDistanceChunks() == 32
                            && ((SessionConfigS2CPayload) dim.get(0)[1]).sessionFlags()
                                    == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                    "the dimension-change push must carry the flags (absent would keep the "
                            + "client's value, but the census requires it): " + dim.size());

            // Review fold A9: a policy-only repush carries the flags WITHOUT the off-world
            // re-register (and its roster re-send) a distance change runs.
            config.set("lod.distance.by_dimension", java.util.Map.of("minecraft:the_end", 0));
            var before = service.getPlayers().get(capable.getUUID());
            pushes.clear();
            service.repushSessionConfig(true);
            helper.assertTrue(pushes.isEmpty(),
                    "a flags-only repush skips a session whose decision did not change "
                            + "(map-ingestion permission plan §3.3)");
            // A decision change (the player is granted the exemption) makes it push.
            service.setMapIngestionExemptProbeForTest(p -> p == capable);
            service.repushSessionConfig(true);
            var flagsOnly = pushes.stream().filter(e -> e[0] == capable).toList();
            helper.assertTrue(service.getPlayers().get(capable.getUUID()) == before,
                    "flags-only: the off-world session is not removed and re-registered");
            helper.assertTrue(flagsOnly.size() == 1
                            && !((SessionConfigS2CPayload) flagsOnly.get(0)[1]).enabled()
                            && ((SessionConfigS2CPayload) flagsOnly.get(0)[1]).sessionFlags() == 0,
                    "…and the push still says off, with the new flags");
            service.setMapIngestionExemptProbeForTest(null);
            service.repushSessionConfig();
            helper.assertTrue(service.getPlayers().get(capable.getUUID()) != before,
                    "the distance-change mode still re-registers an off-world session");
        } finally {
            capable.setServerLevel(level);
            service.shutdown();
            server.getPlayerList().remove(old);
            server.getPlayerList().remove(capable);
            server.getPlayerList().remove(raced);
        }
        helper.succeed();
    }

    /**
     * Arm and disarm through {@code reconcileSettings} — the reload owner path (map-policy
     * plan A3.4): the arm REVOKES the session without the bit BEFORE the re-push (a
     * session about to lose service must not first get an enabled=true rebuild), the
     * re-push carries flags 1 to the capable session only, and the feedback reports the
     * revocation (never the legacy-reconnect count); the disarm re-pushes flags 0 and
     * re-offers the revoked session at once.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void mapPolicyReloadRevokesBeforeTheRepushAndDisarmHeals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var old = placeMockServerPlayer(helper);
        var capable = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        service.setMapPolicyNoticeSenderForTest((player, text) -> { });
        LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                LSSConstants.PROTOCOL_VERSION, VOXEL), old, service, reply -> { });
        LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), capable, service, reply -> { });
        var pushes = new java.util.concurrent.CopyOnWriteArrayList<Object[]>();
        service.setSessionConfigPusher((to, payload) -> pushes.add(new Object[]{to, payload}));
        var disarmed = config.snapshot();
        config.set("privacy.request_no_map_ingestion", true);
        var armedSettings = config.snapshot();
        var arm = service.reconcileSettings(disarmed, armedSettings, 1);
        var disarm = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        succeedWhenThenTearDown(helper, 600, () -> {
            if (!arm.isDone()) {
                helper.assertTrue(false, "waiting for the arming reconcile");
            }
            helper.assertTrue(!arm.isCompletedExceptionally(), "the arming reconcile failed");
            if (disarm.get() == null) {
                helper.assertTrue(pushes.size() == 2, "revocation + one repush, got " + pushes.size());
                helper.assertTrue(pushes.get(0)[0] == old
                                && !((SessionConfigS2CPayload) pushes.get(0)[1]).enabled(),
                        "REVOKE FIRST: the old session's enabled=false precedes the repush");
                helper.assertTrue(pushes.get(1)[0] == capable
                                && ((SessionConfigS2CPayload) pushes.get(1)[1]).enabled()
                                && ((SessionConfigS2CPayload) pushes.get(1)[1]).sessionFlags() == 1,
                        "the repush reaches only the capable session, carrying flags 1");
                helper.assertTrue(service.settingsFeedback().mapPolicyRevoked() == 1
                                && service.settingsFeedback().legacyReconnects() == 0,
                        "the feedback reports the revocation, not a legacy reconnect");
                helper.assertTrue(service.settingsFeedback().mapPolicyArmed(),
                        "…and the policy THIS revision adopted (review fold A9)");
                pushes.clear();
                config.set("privacy.request_no_map_ingestion", false);
                disarm.set(service.reconcileSettings(armedSettings, config.snapshot(), 2));
                helper.assertTrue(false, "waiting for the disarming reconcile");
            }
            if (!disarm.get().isDone()) helper.assertTrue(false, "waiting for the disarming reconcile");
            helper.assertTrue(!disarm.get().isCompletedExceptionally(), "the disarming reconcile failed");
            helper.assertTrue(pushes.stream().anyMatch(e -> e[0] == capable
                            && ((SessionConfigS2CPayload) e[1]).sessionFlags() == 0),
                    "the disarm repush carries flags 0 to the capable session");
            helper.assertTrue(service.getPlayers().get(old.getUUID()) != null,
                    "the revoked session is re-offered at once and re-registers");
        }, () -> {
            service.shutdown();
            server.getPlayerList().remove(old);
            server.getPlayerList().remove(capable);
        });
    }

    /**
     * 0.20.0 implementation-review fold: an arming reload that ALSO changes the distance
     * runs the full re-push, which carries and records every CURRENT session's flags — the
     * map-policy sweep before it must not push the capable session too. Exactly one push per
     * capable session (the PaperMapPolicyTest twin pins the same on Paper).
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void mapPolicyArmingReloadWithADistanceChangePushesEachCapableSessionOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var old = placeMockServerPlayer(helper);
        var capable = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        service.setMapPolicyNoticeSenderForTest((player, text) -> { });
        LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                LSSConstants.PROTOCOL_VERSION, VOXEL), old, service, reply -> { });
        LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), capable, service, reply -> { });
        var pushes = new java.util.concurrent.CopyOnWriteArrayList<Object[]>();
        service.setSessionConfigPusher((to, payload) -> pushes.add(new Object[]{to, payload}));
        var disarmed = config.snapshot();
        config.set("privacy.request_no_map_ingestion", true);
        config.set("lod.distance.default_chunks", 251);
        var arm = service.reconcileSettings(disarmed, config.snapshot(), 1);
        succeedWhenThenTearDown(helper, 600, () -> {
            if (!arm.isDone()) helper.assertTrue(false, "waiting for the arming reconcile");
            helper.assertTrue(!arm.isCompletedExceptionally(), "the arming reconcile failed");
            var toCapable = pushes.stream().filter(e -> e[0] == capable).toList();
            helper.assertTrue(toCapable.size() == 1,
                    "exactly one push to the capable session, got " + toCapable.size());
            var push = (SessionConfigS2CPayload) toCapable.get(0)[1];
            helper.assertTrue(push.enabled() && push.sessionFlags() == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION
                            && push.lodDistanceChunks() == 251,
                    "the full re-push carries the new distance and the armed flags: " + push);
            helper.assertTrue(Boolean.TRUE.equals(service.getServiceGateState().lastMapFlagsSent(capable.getUUID())),
                    "…and records them");
            helper.assertTrue(pushes.get(0)[0] == old && !((SessionConfigS2CPayload) pushes.get(0)[1]).enabled()
                            && pushes.stream().filter(e -> e[0] == old).count() == 1,
                    "REVOKE FIRST, once");
            int before = pushes.size();
            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.size() == before, "the cadence sweep finds nothing new to tell");
        }, () -> {
            service.shutdown();
            server.getPlayerList().remove(old);
            server.getPlayerList().remove(capable);
        });
    }

    /**
     * 0.20.0 implementation-review fold: the 10 s sweep reads the exemption in three states,
     * and a FAILED read (a throwing permission backend) keeps the flags a capable session was
     * last sent — no push, no flip of a granted player; a real revoke still lands on the next
     * good read. The handshake stays fail-closed (see the provider-less test below).
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapIngestionSweepKeepsTheLastSentFlagsOnAFailedRead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var capable = placeMockServerPlayer(helper);
        var uuid = capable.getUUID();
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var exempt = new java.util.concurrent.atomic.AtomicBoolean(true);
        var failing = new java.util.concurrent.atomic.AtomicBoolean(false);
        service.setMapIngestionExemptProbeForTest(p -> {
            if (failing.get()) throw new IllegalStateException("backend exploded");
            return p.getUUID().equals(uuid) && exempt.get();
        });
        var gateState = service.getServiceGateState();
        try {
            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true);
            var replies = new ArrayList<SessionConfigS2CPayload>();
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), capable, service, replies::add);
            helper.assertTrue(replies.size() == 1 && replies.get(0).sessionFlags() == 0
                            && Boolean.FALSE.equals(gateState.lastMapFlagsSent(uuid)),
                    "premise: the granted capable client may ingest");
            var pushes = new ArrayList<SessionConfigS2CPayload>();
            service.setSessionConfigPusher((to, payload) -> { if (to == capable) pushes.add(payload); });

            failing.set(true);
            helper.assertTrue(service.mapIngestionExemption(capable) == dev.vox.lss.common.MapPolicy.Exemption.FAILED,
                    "premise: the read fails");
            service.runMapPolicySweep(config);
            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.isEmpty(), "a failed read pushes nothing, got " + pushes.size());
            helper.assertTrue(Boolean.FALSE.equals(gateState.lastMapFlagsSent(uuid))
                            && gateState.mapPolicyExemptCount() == 1,
                    "the session keeps what it was last told (and is counted so)");
            helper.assertTrue(service.denyMapIngestionFor(capable, config),
                    "every other site stays fail-closed on the same read");

            failing.set(false);
            exempt.set(false);
            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.size() == 1
                            && pushes.get(0).sessionFlags() == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                    "a real revoke lands on the next good read");
        } finally {
            service.shutdown();
            server.getPlayerList().remove(capable);
        }
        helper.succeed();
    }

    /**
     * 0.20.0 implementation-review fold, reconcile-driven: a disarm ends the exempt
     * admissions of older clients, so after the grant is withdrawn a re-arm decides the
     * older client afresh and refuses it as a non-exempt older client.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 800)
    public void mapPolicyDisarmClearsExemptAdmissionsSoAReArmDecidesAfresh(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var old = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        service.setMapPolicyNoticeSenderForTest((player, text) -> { });
        var exempt = java.util.concurrent.ConcurrentHashMap.<java.util.UUID>newKeySet();
        exempt.add(old.getUUID());
        service.setMapIngestionExemptProbeForTest(p -> exempt.contains(p.getUUID()));
        LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                LSSConstants.PROTOCOL_VERSION, VOXEL), old, service, reply -> { });
        var pushes = new java.util.concurrent.CopyOnWriteArrayList<Object[]>();
        service.setSessionConfigPusher((to, payload) -> pushes.add(new Object[]{to, payload}));
        var gateState = service.getServiceGateState();
        var disarmedSettings = config.snapshot();
        config.set("privacy.request_no_map_ingestion", true);
        var armedSettings = config.snapshot();
        var arm = service.reconcileSettings(disarmedSettings, armedSettings, 1);
        var disarm = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        var rearm = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        succeedWhenThenTearDown(helper, 800, () -> {
            if (!arm.isDone()) helper.assertTrue(false, "waiting for the arming reconcile");
            helper.assertTrue(!arm.isCompletedExceptionally(), "the arming reconcile failed");
            if (disarm.get() == null) {
                helper.assertTrue(service.getPlayers().get(old.getUUID()) != null
                                && gateState.isMapPolicyExemptAdmission(old.getUUID()),
                        "premise: the exempt older session is admitted and keeps serving");
                config.set("privacy.request_no_map_ingestion", false);
                disarm.set(service.reconcileSettings(armedSettings, config.snapshot(), 2));
                helper.assertTrue(false, "waiting for the disarming reconcile");
            }
            if (!disarm.get().isDone()) helper.assertTrue(false, "waiting for the disarming reconcile");
            helper.assertTrue(!disarm.get().isCompletedExceptionally(), "the disarming reconcile failed");
            if (rearm.get() == null) {
                helper.assertTrue(!gateState.isMapPolicyExemptAdmission(old.getUUID()),
                        "the disarm ends the admission");
                exempt.remove(old.getUUID()); // the grant is withdrawn while disarmed
                pushes.clear();
                config.set("privacy.request_no_map_ingestion", true);
                rearm.set(service.reconcileSettings(disarmedSettings, config.snapshot(), 3));
                helper.assertTrue(false, "waiting for the re-arming reconcile");
            }
            if (!rearm.get().isDone()) helper.assertTrue(false, "waiting for the re-arming reconcile");
            helper.assertTrue(!rearm.get().isCompletedExceptionally(), "the re-arming reconcile failed");
            helper.assertTrue(service.getPlayers().get(old.getUUID()) == null
                            && gateState.peekDenied(old.getUUID()) != null
                            && gateState.peekDenied(old.getUUID()).has(dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY),
                    "re-armed: refused as a non-exempt older client");
            helper.assertTrue(pushes.size() == 1 && pushes.get(0)[0] == old
                            && !((SessionConfigS2CPayload) pushes.get(0)[1]).enabled(),
                    "one revocation push, got " + pushes.size());
            helper.assertTrue(service.settingsFeedback().mapPolicyRevoked() == 1,
                    "the re-arm reports the revocation");
        }, () -> {
            service.shutdown();
            server.getPlayerList().remove(old);
        });
    }

    /**
     * 0.19.0 review O6 P1 — the xplat twin of PaperMapPolicyTest's legacy pins: a LIVE arm
     * never revokes a legacy-dialect session mid-session. v19, v18 and v16 sessions keep
     * serving until rejoin (where the handshake refuses them) — no push, no memo, no chat
     * line — while a CURRENT session without the bit in the SAME sweep is revoked, so the
     * sweep is live, not vacuous. Pins {@code runMapPolicySweep}'s explicit-CURRENT guard
     * (the reload's one-shot and the 200-tick cadence both run it): dropped, or loosened
     * to {@code dialectOf}, a reload would revoke an old client with a v20 frame.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapPolicyArmNeverRevokesALegacySessionMidSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var v19 = placeMockServerPlayer(helper);
        var v18 = placeMockServerPlayer(helper);
        var v16 = placeMockServerPlayer(helper);
        var current = placeMockServerPlayer(helper);
        var legacy = java.util.List.of(v19, v18, v16);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var notices = new ArrayList<java.util.UUID>();
        service.setMapPolicyNoticeSenderForTest((player, text) -> notices.add(player.getUUID()));
        try {
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, VOXEL), v19, service, reply -> { });
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.V18_COMPAT_PROTOCOL_VERSION, VOXEL), v18, service, reply -> { });
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.V16_COMPAT_PROTOCOL_VERSION, VOXEL), v16, service, reply -> { });
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL), current, service, reply -> { });
            for (var p : legacy) {
                helper.assertTrue(service.getPlayers().get(p.getUUID()) != null
                                && !service.getDialectTracker().isCurrent(p.getUUID()),
                        "premise: a registered legacy-dialect session ("
                                + service.getDialectTracker().dialectOf(p.getUUID()) + ")");
            }
            helper.assertTrue(service.getPlayers().get(current.getUUID()) != null
                            && service.getDialectTracker().isCurrent(current.getUUID()),
                    "premise: a registered CURRENT session without the bit");
            var pushes = new ArrayList<Object[]>();
            service.setSessionConfigPusher((to, payload) -> pushes.add(new Object[]{to, payload}));

            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true); // as the arming reconcile would
            helper.assertTrue(service.runMapPolicySweep(config) == 1,
                    "the arm revokes exactly the CURRENT session without the bit");
            helper.assertTrue(service.getPlayers().get(current.getUUID()) == null
                            && notices.equals(java.util.List.of(current.getUUID())),
                    "control: the CURRENT session is revoked and told once");
            var gateState = service.getServiceGateState();
            for (var p : legacy) {
                var uuid = p.getUUID();
                var dialect = service.getDialectTracker().dialectOf(uuid);
                helper.assertTrue(service.getPlayers().get(uuid) != null,
                        dialect + ": a legacy session keeps serving until rejoin");
                helper.assertTrue(pushes.stream().noneMatch(e -> e[0] == p),
                        dialect + ": no mid-session disable push (a v20 frame it cannot read)");
                helper.assertTrue(!gateState.isDenied(uuid) && !notices.contains(uuid),
                        dialect + ": no memo and no chat line");
            }
            helper.assertTrue(service.runMapPolicySweep(config) == 0 && pushes.size() == 1,
                    "a repeated sweep (the 200-tick cadence) revokes nobody");
        } finally {
            service.shutdown();
            for (var p : java.util.List.of(v19, v18, v16, current)) {
                server.getPlayerList().remove(p);
            }
        }
        helper.succeed();
    }

    /**
     * 0.19.0 review O1: the chat line is once per CONNECTION across a respawn. Vanilla's
     * respawn (death, End exit) builds a NEW {@code ServerPlayer} on the SAME connection,
     * so the old player-instance latch re-sent the line on the review's sequence (Paper
     * reuses its entity and never did): refused at handshake (line 1), respawn, disarm
     * (the replay registers the new instance), re-arm (the sweep revokes it). Still one
     * line — and the stale pre-respawn instance (the cadence sweep runs before the
     * lifecycle pass swaps it) claims nothing either. A new connection still gets its own.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapPolicyChatLineIsOncePerConnectionAcrossARespawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var told = new ArrayList<net.minecraft.server.level.ServerPlayer>();
        service.setMapPolicyNoticeSenderForTest((player, text) -> told.add(player));
        try {
            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true);
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL), mock, service, reply -> { });
            helper.assertTrue(told.size() == 1 && service.getPlayers().get(uuid) == null,
                    "premise: refused at handshake with one chat line");

            var fresh = playerList.respawn(mock, true, Entity.RemovalReason.DISCARDED);
            helper.assertTrue(fresh != mock && fresh.connection == mock.connection,
                    "premise: respawn builds a NEW ServerPlayer on the SAME connection");

            config.set("privacy.request_no_map_ingestion", false);
            service.adoptMapPolicyForTest(false);
            service.replayDeniedHandshakes(config);
            var state = service.getPlayers().get(uuid);
            helper.assertTrue(state != null && state.getPlayer() == fresh,
                    "premise: the disarm replay registers the respawned instance");

            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true);
            helper.assertTrue(service.runMapPolicySweep(config) == 1
                            && service.getPlayers().get(uuid) == null,
                    "premise: the re-arm sweep revokes the session");
            helper.assertTrue(told.size() == 1,
                    "ONE line per connection across respawn + disarm + re-arm, got " + told.size());
            service.noticeMapPolicyRefusal(mock);
            helper.assertTrue(told.size() == 1, "the stale pre-respawn instance claims nothing");

            service.getServiceGateState().onDisconnect(uuid);
            service.noticeMapPolicyRefusal(fresh);
            helper.assertTrue(told.size() == 2 && told.get(1) == fresh,
                    "after the disconnect sweep a connection claims afresh");
        } finally {
            service.shutdown();
            var listed = playerList.getPlayer(uuid);
            if (listed != null) playerList.remove(listed);
        }
        helper.succeed();
    }


    // ---- the map-ingestion exemption (map-ingestion permission plan §3, §6) ----

    /**
     * A CAPABLE (0.19+) client is re-evaluated by the 10 s sweep: a grant and a revoke each
     * reach it within ONE sweep as one flags-only push (version, enabled and distance as the
     * session has them — the client's fast path), and an unchanged session is never
     * re-pushed — including after a dimension change, which rebuilds the per-dimension
     * request state: the last-sent flags live on the gate state (§3.3). The diag count is
     * the sweep's cache.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapIngestionExemptionReachesACapableClientWithinOneSweep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var endLevel = server.getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        var capable = placeMockServerPlayer(helper);
        var uuid = capable.getUUID();
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var exempt = new java.util.concurrent.atomic.AtomicBoolean(false);
        service.setMapIngestionExemptProbeForTest(p -> p.getUUID().equals(uuid) && exempt.get());
        var gateState = service.getServiceGateState();
        try {
            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true);
            var replies = new ArrayList<SessionConfigS2CPayload>();
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), capable, service, replies::add);
            helper.assertTrue(replies.size() == 1 && replies.get(0).enabled()
                            && replies.get(0).sessionFlags() == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                    "premise: a non-exempt capable client is told the request");
            helper.assertTrue(Boolean.TRUE.equals(gateState.lastMapFlagsSent(uuid)),
                    "the handshake reply records the flags it sent");
            var pushes = new ArrayList<SessionConfigS2CPayload>();
            service.setSessionConfigPusher((to, payload) -> { if (to == capable) pushes.add(payload); });

            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.isEmpty(), "nothing changed: no push");
            helper.assertTrue(gateState.mapPolicyExemptCount() == 0, "exempt=0");

            exempt.set(true); // granted
            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.size() == 1, "the grant lands within one sweep, got " + pushes.size());
            var grant = pushes.get(0);
            helper.assertTrue(grant.protocolVersion() == LSSConstants.PROTOCOL_VERSION && grant.enabled()
                            && grant.lodDistanceChunks() == replies.get(0).lodDistanceChunks()
                            && grant.sessionFlags() == 0,
                    "a flags-only push (same version, enabled, distance; flags 0): " + grant);
            helper.assertTrue(gateState.mapPolicyExemptCount() == 1, "the sweep caches exempt=1");
            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.size() == 1, "no redundant push while nothing changes");

            // A dimension change WITHOUT a distance change rebuilds the request state but
            // sends nothing — and the next sweep still knows what the session was told.
            var before = service.getPlayers().get(uuid);
            capable.setServerLevel(endLevel);
            service.tick();
            helper.assertTrue(service.getPlayers().get(uuid) != null
                            && service.getPlayers().get(uuid) != before,
                    "premise: the dimension change rebuilt the per-dimension request state");
            helper.assertTrue(pushes.size() == 1, "same distance: no dimension-change push");
            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.size() == 1,
                    "no redundant push after a dimension change (the last-sent flags survive it)");

            exempt.set(false); // revoked
            service.runMapPolicySweep(config);
            helper.assertTrue(pushes.size() == 2
                            && pushes.get(1).sessionFlags() == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION
                            && pushes.get(1).enabled(),
                    "the revoke lands within one sweep, still serving, flags 1");
            helper.assertTrue(gateState.mapPolicyExemptCount() == 0, "exempt=0 again");
            helper.assertTrue(service.getPlayers().get(uuid) != null, "a capable session is never revoked");
        } finally {
            capable.setServerLevel(level);
            service.shutdown();
            server.getPlayerList().remove(capable);
        }
        helper.succeed();
    }

    /**
     * OLDER clients (no {@code CAPABILITY_MAP_POLICY}, legacy dialects included) are decided
     * at the handshake (§3.4): an exempt one is SERVED (no flags field — the 0.18.0 frame),
     * a non-exempt one refused as before; the sweep never revokes the exempt admission
     * mid-session, even after the grant is withdrawn — the next join applies it. A refused
     * player granted later is re-offered by the grant sweep (its replayed handshake is the
     * new decision — {@code mapPolicyCleared} rides the per-player helper).
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapIngestionExemptOlderClientsAreDecidedAtHandshake(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var exemptOld = placeMockServerPlayer(helper);
        var plainOld = placeMockServerPlayer(helper);
        var exemptV19 = placeMockServerPlayer(helper);
        var plainV19 = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        var exempt = java.util.concurrent.ConcurrentHashMap.<java.util.UUID>newKeySet();
        exempt.add(exemptOld.getUUID());
        exempt.add(exemptV19.getUUID());
        service.setMapIngestionExemptProbeForTest(p -> exempt.contains(p.getUUID()));
        service.setMapPolicyNoticeSenderForTest((player, text) -> { });
        var gateState = service.getServiceGateState();
        var replies = new java.util.HashMap<java.util.UUID, SessionConfigS2CPayload>();
        try {
            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true);
            for (var p : java.util.List.of(exemptOld, plainOld)) {
                LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                        LSSConstants.PROTOCOL_VERSION, VOXEL), p, service, r -> replies.put(p.getUUID(), r));
            }
            for (var p : java.util.List.of(exemptV19, plainV19)) {
                LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                        LSSConstants.V19_COMPAT_PROTOCOL_VERSION, VOXEL), p, service, r -> replies.put(p.getUUID(), r));
            }
            var served = replies.get(exemptOld.getUUID());
            helper.assertTrue(service.getPlayers().get(exemptOld.getUUID()) != null && served.enabled()
                            && served.sessionFlags() == LSSConstants.SESSION_FLAGS_ABSENT,
                    "an exempt 0.10-0.18 client is served with the 0.18.0 frame: " + served);
            helper.assertTrue(gateState.isMapPolicyExemptAdmission(exemptOld.getUUID()),
                    "…and its admission is recorded as decided at handshake");
            helper.assertTrue(service.getPlayers().get(exemptV19.getUUID()) != null
                            && replies.get(exemptV19.getUUID()).enabled()
                            && replies.get(exemptV19.getUUID()).protocolVersion() == LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                    "an exempt legacy-dialect client is served in its own dialect");
            for (var p : java.util.List.of(plainOld, plainV19)) {
                var memo = gateState.peekDenied(p.getUUID());
                helper.assertTrue(service.getPlayers().get(p.getUUID()) == null
                                && !replies.get(p.getUUID()).enabled()
                                && memo != null && memo.has(dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY),
                        "a non-exempt older client is refused as before");
            }

            helper.assertTrue(service.runMapPolicySweep(config) == 0
                            && service.getPlayers().get(exemptOld.getUUID()) != null,
                    "the sweep leaves the exempt admission alone");
            helper.assertTrue(gateState.mapPolicyExemptCount() == 1,
                    "exempt= counts the CURRENT-dialect admission (legacy sessions are not swept)");
            exempt.remove(exemptOld.getUUID()); // the grant is withdrawn mid-session
            helper.assertTrue(service.runMapPolicySweep(config) == 0
                            && service.getPlayers().get(exemptOld.getUUID()) != null,
                    "decided at handshake only: no mid-session revocation of an older client");
            LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, VOXEL), exemptOld, service, r -> replies.put(exemptOld.getUUID(), r));
            helper.assertTrue(service.getPlayers().get(exemptOld.getUUID()) == null
                            && !replies.get(exemptOld.getUUID()).enabled(),
                    "its next handshake applies the withdrawal");

            exempt.add(plainOld.getUUID()); // granted while refused
            service.runServiceGateSweeps(config); // the grant sweep (the 10 s cadence)
            helper.assertTrue(service.getPlayers().get(plainOld.getUUID()) != null
                            && !gateState.isDenied(plainOld.getUUID()),
                    "a refused player granted the exemption is re-offered within one sweep");
        } finally {
            service.shutdown();
            for (var p : java.util.List.of(exemptOld, plainOld, exemptV19, plainV19)) {
                server.getPlayerList().remove(p);
            }
        }
        helper.succeed();
    }

    /**
     * Fail-closed (§3.1): on Fabric with NO permission provider the production read exempts
     * NOBODY (the service gate's reads would have answered yes), and a throwing read is not
     * exempt either — an older client is refused and a capable one is told the request.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 400)
    public void mapIngestionWithoutAProviderOrOnAThrowingReadExemptsNobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var old = placeMockServerPlayer(helper);
        var capable = placeMockServerPlayer(helper);
        var config = new ServiceSettingsFixture();
        var service = new RequestProcessingService(server, config);
        service.setMapPolicyNoticeSenderForTest((player, text) -> { });
        var replies = new java.util.HashMap<java.util.UUID, SessionConfigS2CPayload>();
        try {
            helper.assertTrue("none".equals(dev.vox.lss.platform.LoaderServices.get().permissionProviderToken()),
                    "premise: no fabric-permissions-api provider in the gametest runtime");
            config.set("privacy.request_no_map_ingestion", true);
            service.adoptMapPolicyForTest(true);
            helper.assertTrue(!service.mapIngestionExempt(old) && !service.mapIngestionExempt(capable),
                    "the production read (default FALSE) exempts nobody without a provider");
            for (boolean throwing : new boolean[]{false, true}) {
                if (throwing) {
                    service.setMapIngestionExemptProbeForTest(p -> {
                        throw new IllegalStateException("backend exploded");
                    });
                    service.getServiceGateState().onDisconnect(old.getUUID());
                    service.getServiceGateState().onDisconnect(capable.getUUID());
                    helper.assertTrue(!service.mapIngestionExempt(old), "a throwing read is not exempt");
                }
                replies.clear();
                LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                        LSSConstants.PROTOCOL_VERSION, VOXEL), old, service, r -> replies.put(old.getUUID(), r));
                LSSServerNetworking.handleHandshake(new HandshakeC2SPayload(
                        LSSConstants.PROTOCOL_VERSION, VOXEL | MAP_BIT), capable, service,
                        r -> replies.put(capable.getUUID(), r));
                helper.assertTrue(service.getPlayers().get(old.getUUID()) == null
                                && !replies.get(old.getUUID()).enabled(),
                        "throwing=" + throwing + ": the older client is refused");
                helper.assertTrue(replies.get(capable.getUUID()).sessionFlags()
                                == LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                        "throwing=" + throwing + ": the capable client is told the request");
                service.runMapPolicySweep(config);
                helper.assertTrue(service.getServiceGateState().mapPolicyExemptCount() == 0,
                        "throwing=" + throwing + ": exempt=0");
            }
        } finally {
            service.shutdown();
            server.getPlayerList().remove(old);
            server.getPlayerList().remove(capable);
        }
        helper.succeed();
    }
}
