package dev.vox.lss.test;

import static dev.vox.lss.test.TestPositions.chunkAt;
import static dev.vox.lss.test.TestPositions.holdChunk;
import static dev.vox.lss.test.TestPositions.releaseChunk;

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
import net.minecraft.gametest.framework.GameTest;
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 1200)
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
        var chunkPositions = new TestPositions.ChunkAt[3];
        for (int i = 0; i < 3; i++) {
            chunkPositions[i] = chunkAt(pcx - DEDUP_CHUNK_OFFSET, pcz + i);
            positions[i] = PositionUtil.packPosition(chunkPositions[i].x(), chunkPositions[i].z());
            holdChunk(chunkSource, chunkPositions[i]);
            level.getChunk(chunkPositions[i].x(), chunkPositions[i].z());
        }
        var savedColumns = new SavedColumnFixture(level, chunkPositions);

        var service = new RequestProcessingService(server);
        var stateA = service.registerPlayer(mockA, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var stateB = service.registerPlayer(mockB, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            if (step.get() == 0) {
                savedColumns.assertSaved(helper);
                // Release only after proving real saved content. The measured serves
                // must come from DISK, which is what engages the dedup tracker.
                for (var pos : chunkPositions) releaseChunk(chunkSource, pos);
                step.set(1);
            }
            if (step.get() == 1) {
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
                step.set(2);
                helper.assertTrue(false, "requests queued, awaiting dedup convergence");
            }
            service.tick();
            var diskDiag = service.getDiskReader().getDiag();
            if (stateA.getTotalSectionsSent() < 3 || stateB.getTotalSectionsSent() < 3) {
                // Wall-denominate the wait (the same fix as the fan-out step below): an
                // unthrottled gametest server burns all 1200 timeout ticks in ~0.3-0.5 s
                // of WALL time, but this wait is on three REAL disk reads + fan-out —
                // wall-bound work a loaded box can push past half a second (sighted
                // 2026-08-21 as A=0 B=0 under box contention). >=50 ms per waiting tick
                // keeps the ceiling tick-rate-independent; the passing path sleeps only
                // for the handful of ticks the reads actually take.
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            helper.assertTrue(stateA.getTotalSectionsSent() == 3 && stateB.getTotalSectionsSent() == 3,
                    "waiting for BOTH players to receive all three columns (fan-out delivery), A="
                            + stateA.getTotalSectionsSent() + " B=" + stateB.getTotalSectionsSent());
            helper.assertTrue(diskDiag.getSubmittedCount() == 3,
                    "six overlapping requests must submit exactly THREE disk reads (the second "
                            + "player attaches to the in-flight dedup groups), got "
                            + diskDiag.getSubmittedCount());
            helper.assertTrue(diskDiag.getSuccessfulReadCount() == 3,
                    "all three deduped reads must resolve with content: success="
                            + diskDiag.getSuccessfulReadCount() + ", not_found=" + diskDiag.getNotFoundCount()
                            + ", errors=" + diskDiag.getErrorCount() + ", all_air=" + diskDiag.getAllAirCount()
                            + ", completed=" + diskDiag.getCompletedCount() + "; "
                            + service.getDiskReader().getDiagnostics());
            helper.assertTrue(stateA.getHeldSyncSlots() == 0 && stateA.getHeldGenSlots() == 0
                            && stateB.getHeldSyncSlots() == 0 && stateB.getHeldGenSlots() == 0,
                    "every slot must be free once both players converged");
            service.shutdown();
            playerList.remove(mockA);
            playerList.remove(mockB);
        });
    }

    /**
     * SP-062, re-derived for debt-carrying buckets (R2-2): bandwidth fairness with one busy
     * and one idle handshaked player, composed exactly as the service composes it (one
     * shared allocation per round, both players flushed against it). Pins: the idle player
     * never spends; each round's busy spend is bounded by the 250 ms burst window of its
     * fair share (alloc/4 + one payload overshoot); the busy player's CUMULATIVE spend
     * stays within the global cap over the sampled window — the enforcement itself, which
     * the old forgiven-overdraft buckets violated by ~3x with payloads above the per-tick
     * refill (and whose free overshoot is what the pre-R2-2 flavor of this test relied on
     * to "exhaust" the bucket); a debt-driven zero-allocation state (one oversized send
     * taking the SHARED bucket negative — the unit-pinned fairness change, composed here)
     * flushes nothing while it lasts; and the bucket recovers on later ticks. Bounds,
     * never exact splits — refills are wall-time driven and the loop only ever waits on
     * tick-spaced counter observations.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void bandwidthFairnessBoundsBusySpendAndIdleDilutionRecovers(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var busyMock = placeMockServerPlayer(helper);
        var idleMock = placeMockServerPlayer(helper);
        long globalCap = 65536;
        int payloadBytes = 8192;
        // FAKE nano-clock, advanced 50 ms per succeedWhen pass: refills are wall-clock
        // driven while gametest ticks run ACCELERATED (hundreds of retries per wall
        // second), so real-clock refills starve and any debt outlives the tick budget.
        // The injected clock (the R2-2 seam) makes every refill deterministic per round.
        long[] clock = {0};
        var limiter = new SharedBandwidthLimiter(globalCap, () -> clock[0]);
        var busy = new PlayerRequestState(busyMock, 200, 16, () -> clock[0]);
        var idle = new PlayerRequestState(idleMock, 200, 16, () -> clock[0]);
        busy.markHandshakeComplete();
        idle.markHandshakeComplete();
        var filler = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 1, true);
        // 60 > fairnessRounds: a stalled tick makes one round's wall-clock refill cover a
        // whole burst window (one payload per round, worst case), so a 40-payload queue
        // could drain to empty during the fairness window and strand the recovery phase.
        for (int i = 0; i < 60; i++) {
            busy.addReadyPayload(new QueuedPayload<>(filler, payloadBytes, i,
                    PositionUtil.packPosition(1_020_000 + i, 99)));
        }
        var diag = new TickDiagnostics();
        var step = new AtomicInteger();
        var rounds = new AtomicInteger();
        var busyAtZero = new AtomicLong(-1);

        final int fairnessRounds = 40; // ~2 s of tick-rounds for the cumulative-cap window
        var windowStartNanos = new AtomicLong(-1);
        var debtInjected = new AtomicLong();
        helper.succeedWhen(() -> {
            clock[0] += 50_000_000L; // one nominal tick of fake time per pass
            helper.assertTrue(helper.getTick() >= 2,
                    "waiting one tick so token buckets have elapsed time to refill from");
            if (step.get() == 0) {
                windowStartNanos.compareAndSet(-1, clock[0]);
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
                if (rounds.incrementAndGet() < fairnessRounds) {
                    helper.assertTrue(false, "round " + rounds.get() + " running");
                }
                // The enforcement pin (R2-2): cumulative busy spend over the sampled window
                // stays within the GLOBAL cap (x1.3 wall-time slack + one payload). The old
                // forgiven-overdraft buckets shipped one payload per round here — ~8 KB per
                // 50 ms round = ~160 KB/s against a 64 KB/s cap — a ~2.5x violation this
                // bound reds.
                long elapsedNanos = Math.max(1, clock[0] - windowStartNanos.get());
                long capBudget = globalCap * elapsedNanos / 1_000_000_000L;
                helper.assertTrue(busy.getTotalBytesSent() <= capBudget * 13 / 10 + payloadBytes,
                        "cumulative busy spend must respect the global cap: spent="
                                + busy.getTotalBytesSent() + " capBudget=" + capBudget);
                // Debt-driven zero-allocation state (the unit-pinned shared-bucket fairness
                // change, composed here): one oversized send takes the shared bucket
                // negative; sampled sub-millisecond, no refill can run, so it reads as a
                // true zero-token state in which nothing flushes.
                // Sized to leave ~ONE payload of net debt: refills are WALL-CLOCK driven
                // while gametest ticks run accelerated, so a large debt (e.g. 2x cap =
                // ~2 wall-seconds of refill) can outlive the whole tick budget. Re-sample
                // sub-millisecond (the refill's <1ms skip guard makes it exact, +-1 from
                // the per-player halving) so the flushes above are accounted for.
                long inject = limiter.getPerPlayerAllocation(2) * 2 + payloadBytes;
                limiter.recordSend((int) inject);
                // Accumulate, don't overwrite: an assert-throw below re-enters this step-0
                // block next tick and records a SECOND injection — the final accounting
                // identity must include every one, or the original failure gets masked by
                // a permanently-unsatisfiable identity.
                debtInjected.addAndGet(inject);
                helper.assertTrue(limiter.getPerPlayerAllocation(2) == 0,
                        "shared-bucket debt must zero every player's allocation");
                long beforeZeroRound = busy.getTotalBytesSent();
                busy.flushSendQueue(0, limiter, diag, p -> {});
                helper.assertTrue(busy.getTotalBytesSent() == beforeZeroRound,
                        "a zero-token round must flush nothing");
                helper.assertTrue(busy.getSendQueueSize() > 0,
                        "premise: payloads must remain queued for the recovery phase");
                busyAtZero.set(busy.getTotalBytesSent());
                step.set(1);
            }
            // Recovery: later ticks pay the debt down and refill past zero; sends resume.
            long alloc = limiter.getPerPlayerAllocation(2);
            busy.flushSendQueue(alloc, limiter, diag, p -> {});

            helper.assertTrue(busy.getTotalBytesSent() > busyAtZero.get(),
                    "waiting for the debt-paid bucket to admit a post-exhaustion send");
            helper.assertTrue(idle.getTotalBytesSent() == 0,
                    "the idle player must end the test having spent nothing");
            helper.assertTrue(limiter.getTotalBytesSent()
                            == busy.getTotalBytesSent() + debtInjected.get(),
                    "global accounting identity: every counted byte is the busy player's "
                            + "plus the injected debt");
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void vanillaPlayerWithoutHandshakeStaysInvisibleWhilePipelineFlows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var vanilla = placeMockServerPlayer(helper);
        var registered = placeMockServerPlayer(helper);
        int pcx = registered.getBlockX() >> 4;
        int pcz = registered.getBlockZ() >> 4;
        var chunkPos = chunkAt(pcx - VANILLA_CHUNK_OFFSET, pcz + 6);
        long packed = PositionUtil.packPosition(chunkPos.x(), chunkPos.z());
        var chunkSource = level.getChunkSource();
        holdChunk(chunkSource, chunkPos);
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
            releaseChunk(chunkSource, chunkPos);
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 1200)
    public void editedColumnPropagatesToBothHoldersThroughBroadcastFanout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var liveService = LSSServerNetworking.getRequestService();
        helper.assertTrue(liveService != null, "live service required (the save hook feeds it)");
        // The mock players below register on this test's OWN service, but the save-hook
        // assertion at the end goes through the LIVE service's filter/tracker — arm its
        // never-registered skip gate (review P3) or the hook would skip the hash and the
        // dirty mark. One-way latch; arming cannot affect what other tests pin.
        liveService.armSaveHookForTest();
        var mockA = placeMockServerPlayer(helper);
        var mockB = placeMockServerPlayer(helper);
        int pcx = mockA.getBlockX() >> 4;
        int pcz = mockA.getBlockZ() >> 4;
        var chunkPos = chunkAt(pcx - FANOUT_CHUNK_OFFSET, pcz + 4);
        helper.assertTrue(FANOUT_CHUNK_OFFSET <= LSSServerConfig.CONFIG.lodDistanceChunks,
                "premise: the column must be inside the broadcaster's RAW lodDistance range");
        long packed = PositionUtil.packPosition(chunkPos.x(), chunkPos.z());
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var chunkSource = level.getChunkSource();
        holdChunk(chunkSource, chunkPos);
        level.getChunk(chunkPos.x(), chunkPos.z());
        var editPos = new BlockPos(chunkPos.x() * 16 + 4, -61, chunkPos.z() * 16 + 4);

        var service = new RequestProcessingService(server);
        var stateA = service.registerPlayer(mockA, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var stateB = service.registerPlayer(mockB, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();
        // Wall-clock gate for step 1's re-ask (the 2026-08-15 flake diagnosis): the
        // duplicate-serve grace (LSSConstants.SEND_DEPARTURE_GRACE_MILLIS) and the
        // probe-suppress TTL (AbstractPlayerRequestState.PROBE_SUPPRESS_TTL_NANOS,
        // 1500 ms, package-private) are WALL-denominated while this test's timeout is
        // TICK-denominated — an unthrottled gametest server can burn all 1200 ticks
        // inside the grace window, where every ts<=0 re-ask is silently grace-absorbed
        // (the product's designed crossing-re-ask behavior) and the test wedges. Wait
        // out both windows so the re-ask exercises the honest PROBE re-resolution the
        // step exists to pin.
        var reaskWallDeadline = new AtomicLong();

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
                    reaskWallDeadline.set(System.currentTimeMillis()
                            + LSSConstants.SEND_DEPARTURE_GRACE_MILLIS + 1_500L + 200L);
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
                    if (System.currentTimeMillis() < reaskWallDeadline.get()) {
                        // Ticks are wall-decoupled on an unthrottled gametest server
                        // (~0.2-0.4 ms/tick measured), so a bare wall-deadline assert
                        // races the tick ceiling. Sleeping converts each waiting tick
                        // into >=50 ms of wall time: the ~2.2 s wait costs <=46 ticks
                        // at ANY tick rate, well inside the standard 1200 ceiling.
                        try {
                            Thread.sleep(50L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        helper.assertTrue(false, "waiting out the departure-grace + "
                                + "probe-suppress wall-clock windows");
                    }
                    if (stateA.getTotalSectionsSent() < 2
                            && GameTestSeeding.noDeclarationOutstanding(stateA)
                            && !stateA.hasEnqueuedColumn(packed)
                            && !stateA.hasPendingRequest(chunkPos.x(), chunkPos.z())) {
                        GameTestSeeding.seedRequest(stateA, packed, -1L);
                    }
                    service.tick();
                    helper.assertTrue(stateA.getTotalSectionsSent() == 2,
                            "waiting for A's post-edit re-serve (a ts<=0 re-ask of a flushed "
                                    + "position re-resolves) [DIAG sent=" + stateA.getTotalSectionsSent()
                                    + " reqs=" + stateA.getTotalRequestsReceived()
                                    + " outstanding=" + !GameTestSeeding.noDeclarationOutstanding(stateA)
                                    + " enq=" + stateA.hasEnqueuedColumn(packed)
                                    + " pend=" + stateA.hasPendingRequest(chunkPos.x(), chunkPos.z()) + "]");
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
                    releaseChunk(chunkSource, chunkPos);
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
