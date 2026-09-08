package dev.vox.lss.test;

import dev.vox.lss.common.processing.RequestRegistration;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.ChunkGenerationService;
import dev.vox.lss.networking.server.DirtyContentFilter;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ChunkGenerationService} books and the dimension-change re-registration protocol on a
 * real dedicated server:
 *
 * <ul>
 *   <li><b>Piggyback single-ticket lifecycle</b> — a second request for an in-flight chunk must
 *       attach to the existing entry (one {@code LSS_GEN_TICKET} in the level's real
 *       {@link TicketStorage}, {@code totalSubmitted} booked once) and completion must emit one
 *       outcome per callback, release the ticket (the force-load leak), and free the per-player
 *       concurrency counts.</li>
 *   <li><b>Cap boundaries</b> — exact per-player and global rejection boundaries, rejected
 *       submissions leaking neither books nor tickets, and piggybacks bypassing both caps
 *       (they consume no new entry).</li>
 *   <li><b>removePlayer determinism</b> — removing one piggybacked player must keep the shared
 *       entry and its ticket alive for the remaining player; only orphaned entries release their
 *       ticket and book {@code totalRemovedInFlight} (the term that re-balances soak law A4
 *       after a kick or dimension change).</li>
 *   <li><b>Timeout boundary</b> — an unloadable chunk fails every piggybacked callback exactly
 *       on the first tick past {@code generationTimeoutSeconds} and releases the ticket.</li>
 *   <li><b>Dimension change</b> — the lifecycle pass must replace the player's state (fresh
 *       object, capabilities preserved, handshake complete), drop stale work (queued requests,
 *       in-flight generation entry + ticket, queued generation ticket requests via the drain's
 *       dimension guard), and do all of it exactly once.</li>
 * </ul>
 *
 * <p>Dimension changes are triggered with {@code ServerPlayer.setServerLevel} instead of the full
 * {@code teleportTo} machinery: gametest mock players have no client to answer the position
 * confirmation a real cross-dimension teleport awaits, and the unit under test
 * ({@code PlayerRequestState.checkDimensionChange}) reads only {@code player.level()} — the
 * end-to-end teleport path is exercised by the dimension-trip soak scenario. The player's level
 * is restored before the mock leaves the player list, so the half-state never outlives a test.
 *
 * <p>Like {@code ServiceLifecycleGameTests}, service-level tests construct their OWN
 * {@code RequestProcessingService} (the live singleton must stay player-free for
 * {@code LSSGameTests}); generation-book tests construct a {@code ChunkGenerationService}
 * directly from a hand-built config so cap and timeout boundaries are exact regardless of the
 * run-dir config. Chunk offsets are unique per test (160..240 band, disjoint from the 64..120
 * band other gametest classes use) because tests in a batch run concurrently and the gametest
 * world persists across runs.
 */
public class GenerationLifecycleGameTests {
    private static final java.util.Map<UUID, RequestRegistration> TEST_REGISTRATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static RequestRegistration registration(UUID uuid) {
        return TEST_REGISTRATIONS.computeIfAbsent(uuid, ignored -> new RequestRegistration());
    }


    private static final int PIGGYBACK_CHUNK_OFFSET = 160;
    private static final int CAP_CHUNK_OFFSET = 176;
    private static final int TIMEOUT_CHUNK_OFFSET = 192;
    private static final int DIMENSION_CHUNK_OFFSET = 208;
    private static final int STALE_TICKET_CHUNK_OFFSET = 224;
    private static final int REMOVAL_CHUNK_OFFSET = 240;
    private static final int REMOVED_DRAIN_CHUNK_OFFSET = 248;
    private static final int SEED_SUPPRESS_CHUNK_OFFSET = 256;
    private static final int GEN_REASK_CHUNK_OFFSET = 264;
    private static final int SERIALIZER_FAULT_CHUNK_OFFSET = 280;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    private static ChunkGenerationService newGenService(int globalCap, int perPlayerCap, int timeoutSeconds) {
        var config = new LSSServerConfig();
        config.generationConcurrencyLimitGlobal = globalCap;
        config.generationConcurrencyLimitPerPlayer = perPlayerCap;
        config.generationTimeoutSeconds = timeoutSeconds;
        return new ChunkGenerationService(config);
    }

    private static TicketStorage ticketStorage(ServerLevel level) {
        // Same instance the chunk source uses: SavedDataStorage caches per SavedDataType.
        return level.getDataStorage().computeIfAbsent(TicketStorage.TYPE);
    }

    /**
     * Count LSS-shaped tickets (load-capable, no timeout) at a chunk position. The timeout
     * filter excludes transient vanilla chunk-system tickets (e.g. "unknown", timeout 1) that
     * can appear while a chunk loads; at the far-away chunks these tests use, no other
     * no-timeout load ticket source exists (player_loading needs a nearby player, forced needs
     * /forceload), so this count is exactly the LSS generation ticket count.
     */
    private static int lssTicketCount(TicketStorage tickets, int cx, int cz) {
        int count = 0;
        for (var ticket : tickets.getTickets(ChunkPos.asLong(cx, cz))) {
            if (ticket.getType().doesLoad() && !ticket.getType().hasTimeout()) count++;
        }
        return count;
    }

    // maxTicks 1200 (not 600): waits on real cold chunk generation, which can exceed 600 ticks
    // on a first run on starved (2-core CI) machines — same budget as FP-033's precedent below.
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void piggybackedGenerationSharesOneTicketAndCompletesEveryCallback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + PIGGYBACK_CHUNK_OFFSET;
        int cz = origin.z + 3;
        var tickets = ticketStorage(level);
        var gen = newGenService(3, 2, 60);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();

        Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 0,
                "premise: no leftover generation ticket at the test chunk");

        Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, cx, cz, 11L),
                "a fresh generation service must accept the first submission");
        Gt.assertTrue(helper, gen.getTotalSubmitted() == 1, "first submission must book submitted=1");
        Gt.assertTrue(helper, gen.getActiveCount() == 1, "first submission must create one active entry");
        Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 1,
                "first submission must add exactly one load ticket");

        Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, cx, cz, 22L),
                "a second submission for the same in-flight chunk must piggyback (accepted)");
        Gt.assertTrue(helper, gen.getTotalSubmitted() == 1,
                "piggyback must not double-book submitted, got " + gen.getTotalSubmitted());
        Gt.assertTrue(helper, gen.getActiveCount() == 1, "piggyback must reuse the single active entry");
        Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 1,
                "piggyback must NOT add a second ticket for the same chunk");

        var outcomes = new AtomicReference<List<TickSnapshot.GenerationReadyData>>();
        var slotReuse = new AtomicReference<boolean[]>();
        helper.succeedWhen(() -> {
            if (outcomes.get() == null) {
                var ready = gen.tick();
                Gt.assertTrue(helper, !ready.isEmpty(), "waiting for the ticketed chunk to load/generate");
                outcomes.set(List.copyOf(ready));
            }
            var ready = outcomes.get();
            Gt.assertTrue(helper, ready.size() == 2,
                    "completion must emit one outcome per piggybacked callback, got " + ready.size());
            var byPlayer = new HashMap<UUID, TickSnapshot.GenerationReadyData>();
            for (var outcome : ready) byPlayer.put(outcome.playerUuid(), outcome);
            var forA = byPlayer.get(playerA);
            var forB = byPlayer.get(playerB);
            Gt.assertTrue(helper, forA != null && forB != null,
                    "both piggybacked players must receive an outcome (a lost callback strands "
                            + "that client's pending slot forever)");
            Gt.assertTrue(helper, forA.submissionOrder() == 11L && forB.submissionOrder() == 22L,
                    "each outcome must carry its own callback's submissionOrder, got A="
                            + forA.submissionOrder() + " B=" + forB.submissionOrder());
            for (var outcome : ready) {
                Gt.assertTrue(helper, outcome.cx() == cx && outcome.cz() == cz,
                        "outcome coords must match the request, got [" + outcome.cx() + ", " + outcome.cz() + "]");
                Gt.assertTrue(helper, LSSConstants.DIM_STR_OVERWORLD.equals(outcome.dimension()),
                        "outcome dimension must be the submitting level's, got " + outcome.dimension());
                Gt.assertTrue(helper, outcome.columnData() != null,
                        "a completion outcome must carry column data (null means failure)");
                Gt.assertTrue(helper, outcome.columnData().serializedSections() != null,
                        "a generated superflat column must serialize non-air sections");
                Gt.assertTrue(helper, outcome.columnTimestamp() > 0,
                        "completion must carry a real timestamp for the up-to-date economy");
            }
            Gt.assertTrue(helper, gen.getTotalCompleted() == 1,
                    "the shared entry completes ONCE, not once per callback, got " + gen.getTotalCompleted());
            Gt.assertTrue(helper, gen.getActiveCount() == 0, "the completed entry must leave the active set");
            Gt.assertTrue(helper, gen.getTotalTimeouts() == 0 && gen.getTotalRemovedInFlight() == 0,
                    "completion must not book a timeout or an in-flight removal");
            Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 0,
                    "completion must release the generation ticket (or the chunk stays "
                            + "force-loaded forever)");

            // Per-player cap is 2; two fresh submissions only fit if completion decremented
            // the per-player count held by the completed entry.
            if (slotReuse.get() == null) {
                slotReuse.set(new boolean[]{
                        gen.submitGeneration(playerA, registration(playerA), level, cx + 1, cz, 33L),
                        gen.submitGeneration(playerA, registration(playerA), level, cx + 2, cz, 44L)});
                gen.shutdown();
            }
            Gt.assertTrue(helper, slotReuse.get()[0] && slotReuse.get()[1],
                    "completion must free the per-player concurrency count (leaked count would "
                            + "reject the second post-completion submission)");
            Gt.assertTrue(helper, lssTicketCount(tickets, cx + 1, cz) == 0
                            && lssTicketCount(tickets, cx + 2, cz) == 0,
                    "shutdown must release every remaining generation ticket");
            Gt.assertTrue(helper, gen.getActiveCount() == 0, "shutdown must clear the active set");
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void generationCapBoundariesRejectExactlyAtCapWithoutLeakingTickets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int bx = origin.x + CAP_CHUNK_OFFSET;
        int z = origin.z + 5;
        var tickets = ticketStorage(level);
        var gen = newGenService(4, 2, 60);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        var playerC = UUID.randomUUID();
        var playerD = UUID.randomUUID();
        var playerE = UUID.randomUUID();
        try {
            // Per-player boundary: cap 2, global has room (2 < 4) — rejection is per-player.
            Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, bx, z, 1L),
                    "submission 1 of 2 under the per-player cap must be accepted");
            Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, bx + 1, z, 2L),
                    "submission 2 of 2 at the per-player cap boundary must be accepted");
            Gt.assertTrue(helper, !gen.submitGeneration(playerA, registration(playerA), level, bx + 2, z, 3L),
                    "a third distinct-chunk submission must be rejected at per-player cap 2");
            Gt.assertTrue(helper, gen.getTotalSubmitted() == 2,
                    "a rejected submission must not book submitted, got " + gen.getTotalSubmitted());
            Gt.assertTrue(helper, gen.getActiveCount() == 2,
                    "a rejected submission must not create an entry");
            Gt.assertTrue(helper, lssTicketCount(tickets, bx + 2, z) == 0,
                    "a rejected submission must NOT leak a load ticket");

            // Piggyback bypasses the per-player cap: it consumes no new entry or ticket.
            Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, bx, z, 4L),
                    "piggyback under cap must be accepted");
            Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, bx + 3, z, 5L),
                    "playerB's second submission must be accepted (global 3 of 4)");
            Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, bx + 1, z, 6L),
                    "a piggyback AT the per-player cap must be accepted — it consumes no new slot");
            Gt.assertTrue(helper, gen.getActiveCount() == 3 && gen.getTotalSubmitted() == 3,
                    "piggybacks must not create entries or book submissions, active="
                            + gen.getActiveCount() + " submitted=" + gen.getTotalSubmitted());

            // Global boundary: 4th entry fills the global cap; a FRESH player (count 0) is
            // rejected — unambiguously the global cap.
            Gt.assertTrue(helper, gen.submitGeneration(playerC, registration(playerC), level, bx + 4, z, 7L),
                    "the 4th entry at the global cap boundary must be accepted");
            Gt.assertTrue(helper, !gen.submitGeneration(playerD, registration(playerD), level, bx + 5, z, 8L),
                    "a fresh player's submission must be rejected once the global cap is full");
            Gt.assertTrue(helper, lssTicketCount(tickets, bx + 5, z) == 0,
                    "a globally rejected submission must not leak a ticket");
            Gt.assertTrue(helper, gen.submitGeneration(playerE, registration(playerE), level, bx, z, 9L),
                    "a piggyback while global-full must be accepted — it consumes no new entry");
            Gt.assertTrue(helper, gen.getTotalSubmitted() == 4 && gen.getActiveCount() == 4,
                    "books after the boundary dance: submitted=" + gen.getTotalSubmitted()
                            + " active=" + gen.getActiveCount() + " (both must be 4)");
            Gt.assertTrue(helper, lssTicketCount(tickets, bx, z) == 1,
                    "three piggybacked callbacks must still share exactly ONE ticket");
        } finally {
            gen.shutdown();
        }
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void removePlayerKeepsSharedEntriesAliveReleasesOrphanedTicketsAndBalancesBooks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int bx = origin.x + REMOVAL_CHUNK_OFFSET;
        int z = origin.z + 9;
        var tickets = ticketStorage(level);
        var gen = newGenService(4, 2, 60);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        var playerF = UUID.randomUUID();
        try {
            // A holds R0 and R1; B piggybacks R0 and holds R2.
            Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, bx, z, 1L), "seed R0");
            Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, bx + 1, z, 2L), "seed R1");
            Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, bx, z, 3L), "piggyback R0");
            Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, bx + 2, z, 4L), "seed R2");
            Gt.assertTrue(helper, gen.getActiveCount() == 3 && gen.getTotalSubmitted() == 3,
                    "premise: three active entries, three booked submissions");

            // Removing B must keep the shared entry R0 (A still waits on it) and its ticket;
            // releasing the ticket here would silently strand A's generation forever.
            gen.removePlayer(playerB, registration(playerB));
            Gt.assertTrue(helper, gen.getActiveCount() == 2,
                    "removing B must drop only B's orphaned entry (R2), active=" + gen.getActiveCount());
            Gt.assertTrue(helper, lssTicketCount(tickets, bx, z) == 1,
                    "the shared entry's ticket must survive while another player still waits on it");
            // Orphan releases are DEFERRED (the disconnect-sweep stagger — a one-call bulk
            // release froze a C2ME server for 60 s inside the distance-graph fixpoint);
            // one tick's drain releases it.
            gen.tick();
            Gt.assertTrue(helper, lssTicketCount(tickets, bx + 2, z) == 0,
                    "the orphaned entry's ticket must be released within one drain tick");
            Gt.assertTrue(helper, gen.getTotalRemovedInFlight() == 1,
                    "exactly the orphaned entry must book removedInFlight, got "
                            + gen.getTotalRemovedInFlight());
            Gt.assertTrue(helper, gen.getTotalSubmitted() == 3,
                    "removal must never un-book submissions");

            // Removing A orphans R0 and R1 — both book immediately, release via the stagger.
            gen.removePlayer(playerA, registration(playerA));
            Gt.assertTrue(helper, gen.getActiveCount() == 0, "removing the last waiter must clear the active set");
            Gt.assertTrue(helper, gen.getTotalRemovedInFlight() == 3,
                    "each orphan-removed ENTRY books removedInFlight once, got "
                            + gen.getTotalRemovedInFlight());

            // Cancel-and-reuse: readmitting R0 while its release is still PENDING must
            // reuse the held ticket (no second add — the completion path's single removal
            // keeps the books 1:1) and the cancelled release must never fire.
            Gt.assertTrue(helper, gen.submitGeneration(playerF, registration(playerF), level, bx, z, 5L),
                    "readmission against a pending deferred release must be accepted");
            gen.tick(); // drains R1's release; R0's was cancelled by the readmission
            Gt.assertTrue(helper, lssTicketCount(tickets, bx, z) == 1,
                    "the readmitted entry reuses the still-held ticket — exactly one, not two");
            Gt.assertTrue(helper, lssTicketCount(tickets, bx + 1, z) == 0,
                    "the un-readmitted orphan's ticket drains within one tick");

            // Removal must free capacity: a fresh player fits again.
            Gt.assertTrue(helper, gen.submitGeneration(playerF, registration(playerF), level, bx + 3, z, 5L),
                    "capacity freed by removePlayer must be reusable");
            Gt.assertTrue(helper, gen.getTotalSubmitted() == gen.getTotalCompleted()
                            + gen.getTotalTimeouts() + gen.getTotalRemovedInFlight() + gen.getActiveCount(),
                    "soak law A4 identity must hold locally: submitted(" + gen.getTotalSubmitted()
                            + ") == completed(" + gen.getTotalCompleted() + ") + timeouts("
                            + gen.getTotalTimeouts() + ") + removedInFlight(" + gen.getTotalRemovedInFlight()
                            + ") + active(" + gen.getActiveCount() + ")");
        } finally {
            gen.shutdown();
        }
        Gt.assertTrue(helper, lssTicketCount(tickets, bx + 3, z) == 0,
                "shutdown must release the remaining entry's ticket");
        Gt.assertTrue(helper, lssTicketCount(tickets, bx, z) == 0,
                "shutdown must also release the readmitted entry's reused ticket");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void generationTimeoutFailsEveryCallbackAtExactBoundaryAndReleasesTicket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + TIMEOUT_CHUNK_OFFSET;
        int cz = origin.z + 7;
        var tickets = ticketStorage(level);
        // timeout 1s = 20 ticks; chunk promotion needs main-thread task pumping, which cannot
        // happen while this callback spins tick() — the chunk deterministically never loads.
        var gen = newGenService(3, 2, 1);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        try {
            Gt.assertTrue(helper, level.getChunkSource().getChunkNow(cx, cz) == null,
                    "premise: the timeout chunk must not already be loaded");
            Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, cx, cz, 7L), "seed the entry");
            Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, cx, cz, 8L), "piggyback the entry");
            Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 1, "premise: ticket held");

            int timeoutTicks = LSSConstants.TICKS_PER_SECOND; // generationTimeoutSeconds = 1
            for (int i = 1; i <= timeoutTicks; i++) {
                Gt.assertTrue(helper, gen.tick().isEmpty(),
                        "must not time out before the boundary (ticksWaiting > timeout): premature "
                                + "outcome on tick " + i + " of " + timeoutTicks);
            }
            var failures = gen.tick();
            Gt.assertTrue(helper, failures.size() == 2,
                    "the first tick past the boundary must fail every piggybacked callback, got "
                            + failures.size() + " outcomes");
            var byPlayer = new HashMap<UUID, TickSnapshot.GenerationReadyData>();
            for (var outcome : failures) byPlayer.put(outcome.playerUuid(), outcome);
            var forA = byPlayer.get(playerA);
            var forB = byPlayer.get(playerB);
            Gt.assertTrue(helper, forA != null && forB != null,
                    "both piggybacked players must get a failure outcome");
            Gt.assertTrue(helper, forA.submissionOrder() == 7L && forB.submissionOrder() == 8L,
                    "each failure must carry its own callback's submissionOrder");
            for (var outcome : failures) {
                Gt.assertTrue(helper, outcome.columnData() == null,
                        "timeout outcomes must carry null column data (the failure marker the "
                                + "processing thread routes to ColumnNotGenerated)");
                Gt.assertTrue(helper, outcome.cx() == cx && outcome.cz() == cz,
                        "failure coords must match the request");
                Gt.assertTrue(helper, LSSConstants.DIM_STR_OVERWORLD.equals(outcome.dimension()),
                        "failure outcomes must carry the dimension, got " + outcome.dimension());
            }
            Gt.assertTrue(helper, gen.getTotalTimeouts() == 1,
                    "the shared entry books ONE timeout, not one per callback, got "
                            + gen.getTotalTimeouts());
            Gt.assertTrue(helper, gen.getTotalCompleted() == 0 && gen.getTotalRemovedInFlight() == 0,
                    "a timeout must book neither a completion nor an in-flight removal");
            Gt.assertTrue(helper, gen.getActiveCount() == 0, "the timed-out entry must leave the active set");
            // Timeout releases are DEFERRED (staggered ≤4/tick, the mass-removal C2ME freeze
            // guard — see DeferredTicketReleases); one pending release drains at the top of
            // the next tick. The pin's spirit is unchanged: the ticket must not leak.
            Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 1,
                    "the timeout tick must DEFER the release, not remove inline — a mass-"
                            + "timeout wave releasing inline is the C2ME 60s-freeze shape");
            gen.tick();
            Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 0,
                    "timeout must release the generation ticket (or the never-loading chunk's "
                            + "ticket leaks forever)");
        } finally {
            gen.shutdown();
        }
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void dimensionChangeReplacesStatePreservesCapabilitiesAndDropsStaleWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        ServerLevel endLevel = server.getLevel(Level.END);
        Gt.assertTrue(helper, endLevel != null, "the End dimension must exist on the gametest server");
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int gx = origin.x + DIMENSION_CHUNK_OFFSET;
        int gz = origin.z + 11;
        var overworldTickets = ticketStorage(level);
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        try {
            var oldState = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            Gt.assertTrue(helper, oldState.getLastDimension().equals(Level.OVERWORLD),
                    "premise: the mock player starts in the overworld");

            // Stale work of all three kinds: queued incoming requests, an in-flight generation
            // entry (with its MC ticket), and the per-player disk-reader queue.
            GameTestSeeding.seedRequests(oldState,
                    new long[]{PositionUtil.packPosition(gx, gz + 1),
                            PositionUtil.packPosition(gx, gz + 2)},
                    new long[]{-1L, 12345L});
            Gt.assertTrue(helper, oldState.getTotalRequestsReceived() == 2
                            && oldState.peekIncomingBatch() != null,
                    "premise: a stale want-set is declared on the old state");

            var gen = service.getGenerationService();
            Gt.assertTrue(helper, gen != null,
                    "generation service expected (gametest config has enableChunkGeneration=true)");
            Gt.assertTrue(helper, level.getChunkSource().getChunkNow(gx, gz) == null,
                    "premise: the in-flight generation chunk must not be loaded (it must survive "
                            + "the tick's generation pass un-completed)");
            Gt.assertTrue(helper, gen.submitGeneration(uuid, oldState.registration(), level, gx, gz, 1L),
                    "premise: in-flight generation seeded");
            Gt.assertTrue(helper, lssTicketCount(overworldTickets, gx, gz) == 1,
                    "premise: generation ticket held in the old dimension");

            var diskQueueBefore = service.getDiskReader().getPlayerQueue(uuid);
            Gt.assertTrue(helper, diskQueueBefore != null,
                    "premise: registration created the disk-reader result queue");

            mock.setServerLevel(endLevel);
            Gt.assertTrue(helper, mock.level().dimension().equals(Level.END),
                    "premise: the player's level switched to the End");

            service.tick();

            var newState = service.getPlayers().get(uuid);
            Gt.assertTrue(helper, newState != null, "a dimension change must keep the player registered");
            Gt.assertTrue(helper, newState != oldState,
                    "a dimension change must REPLACE the state object (disconnect teardown + "
                            + "fresh registration), not mutate the old one");
            Gt.assertTrue(helper, newState.getCapabilities() == LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                    "the fresh state must inherit the session's capabilities — losing them makes "
                            + "the router skip every request for the rest of the session");
            Gt.assertTrue(helper, newState.hasCompletedHandshake(),
                    "the fresh state must be handshake-complete (no client re-handshake happens "
                            + "on a dimension change)");
            Gt.assertTrue(helper, newState.getLastDimension().equals(Level.END),
                    "the fresh state must adopt the new dimension as its baseline");
            Gt.assertTrue(helper, newState.getTotalRequestsReceived() == 0
                            && newState.getBacklogSize() == 0
                            && newState.peekIncomingBatch() == null,
                    "the want-set declared before the dimension change must die with the old "
                            + "state — neither its mailbox batch nor a backlog may carry over");
            Gt.assertTrue(helper, gen.getActiveCount() == 0,
                    "the in-flight generation entry must be dropped on dimension change");
            Gt.assertTrue(helper, gen.getTotalRemovedInFlight() == 1,
                    "the dropped in-flight generation must be booked as removed (the A4 "
                            + "re-balancing term), got " + gen.getTotalRemovedInFlight());
            // The dimension-change sweep defers its ticket release (disconnect-sweep
            // stagger); the next service tick's generation drain executes it.
            service.tick();
            Gt.assertTrue(helper, lssTicketCount(overworldTickets, gx, gz) == 0,
                    "the old dimension's generation ticket must be released, not leaked");
            var diskQueueAfter = service.getDiskReader().getPlayerQueue(uuid);
            Gt.assertTrue(helper, diskQueueAfter != null,
                    "re-registration must recreate the disk-reader result queue");
            Gt.assertTrue(helper, diskQueueAfter != diskQueueBefore,
                    "the disk-reader queue must be torn down and recreated (a carried-over queue "
                            + "would deliver old-dimension results to the new session)");

            service.tick();
            Gt.assertTrue(helper, service.getPlayers().get(uuid) == newState,
                    "the replacement must happen exactly once — the next tick must keep the "
                            + "fresh state instead of re-resetting every tick");
            Gt.assertTrue(helper, gen.getTotalRemovedInFlight() == 1,
                    "no repeated removal booking on subsequent ticks");
        } finally {
            mock.setServerLevel(level);
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * The drain-side dimension guard: a generation ticket request admitted by the processing
     * thread BEFORE a dimension change targets the old dimension's coordinates and must be
     * dropped by {@code drainGenerationTicketRequests}, never submitted into the new dimension.
     * The wait observes {@code heldGenSlots == 1} on the old state — the disk-notfound →
     * generation re-admission that immediately precedes the ticket-request enqueue — and only
     * manual snapshots (no {@code service.tick()}) drive the processing thread until then, so no
     * drain can run between the conversion and the dimension flip.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void dimensionChangeDropsStaleGenerationTicketRequestsViaDrainGuard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerLevel endLevel = server.getLevel(Level.END);
        Gt.assertTrue(helper, endLevel != null, "the End dimension must exist on the gametest server");
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + STALE_TICKET_CHUNK_OFFSET;
        // This chunk must not exist on disk (a found disk read never converts to generation).
        // No run ever generates it — the guard drops it pre-generation — and the per-run salt
        // keeps even pathological cross-run coordinate collisions away.
        int cz = origin.z + (int) Math.floorMod(System.nanoTime(), 64L);
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        var gen = service.getGenerationService();
        Gt.assertTrue(helper, gen != null, "generation service expected");
        var oldState = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        Gt.assertTrue(helper, level.getChunkSource().getChunkNow(cx, cz) == null,
                "premise: the requested chunk must not be loaded (a probe serve would bypass "
                        + "the disk-notfound path)");
        GameTestSeeding.seedRequest(oldState, PositionUtil.packPosition(cx, cz), 0L); // ts 0 = generation

        // Tick 1 registers the dimension context and posts the routing snapshot. Its drain
        // cannot submit anything: the disk-notfound -> generation conversion needs a second
        // processing cycle, and no second snapshot exists within this tick.
        service.tick();

        var phase = new AtomicInteger();
        helper.succeedWhen(() -> {
            if (phase.get() == 0) {
                if (oldState.getHeldGenSlots() != 1) {
                    // Drive the processing thread without running the main-thread drain.
                    service.getOffThreadProcessor().postSnapshot(new TickSnapshot(
                            Map.of(uuid, LSSConstants.DIM_STR_OVERWORLD), Map.of(),
                            LSSServerConfig.CONFIG.sendQueueLimitPerPlayer, false), List.of());
                    Gt.assertTrue(helper, false,
                            "waiting for the disk-notfound -> generation conversion (heldGenSlots=1)");
                }
                // Conversion confirmed: the stale ticket request (old dimension) is enqueued.
                // Flip the dimension before any drain can submit it.
                mock.setServerLevel(endLevel);
                phase.set(1);
            }
            service.tick();
            Gt.assertTrue(helper, phase.incrementAndGet() >= 5,
                    "letting the stale ticket request reach a post-flip drain");
            Gt.assertTrue(helper, service.getPlayers().get(uuid) != null
                            && service.getPlayers().get(uuid) != oldState,
                    "premise: the dimension change replaced the state");
            Gt.assertTrue(helper, gen.getTotalSubmitted() == 0,
                    "a ticket request admitted before the dimension change must be DROPPED by "
                            + "the drain's dimension guard — submitting it would generate "
                            + "old-dimension coordinates inside the new dimension");
            Gt.assertTrue(helper, gen.getActiveCount() == 0,
                    "no generation entry may exist for the dropped stale request");
            Gt.assertTrue(helper, service.getOffThreadProcessor().pollGenerationTicketRequest() == null,
                    "the stale ticket request must be consumed by the drain, not left queued");
            // FP-019: the guard must DROP, never feed a failure — a regression that called
            // feedGenerationFailure (with the current dimension) instead of dropping would
            // emit a spurious NOT_GENERATED outcome and book genDrained for it.
            Gt.assertTrue(helper, 
                    service.getOffThreadProcessor().getDiagnostics().getTotalGenDrained() == 0,
                    "the dimension guard's drop must not produce a generation outcome: "
                            + "genDrained must stay 0, got " + service.getOffThreadProcessor()
                                    .getDiagnostics().getTotalGenDrained());
            // Success path cleanup (a failed run leaves only a delisted-on-cleanup mock).
            mock.setServerLevel(level);
            service.shutdown();
            server.getPlayerList().remove(mock);
        });
    }

    /**
     * FP-021: the {@code !player.isRemoved()} arm of the generation-ticket drain. A
     * discarded-but-still-listed player (the death window) keeps its session, but a ticket
     * request drained while the player entity is removed must NOT create an MC ticket —
     * it must feed a failure outcome so the pending slot frees and the client hears
     * ColumnNotGenerated, instead of force-loading a chunk for an entity that cannot
     * receive it.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void removedButListedPlayerAtDrainFeedsFailureWithoutCreatingTicket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + REMOVED_DRAIN_CHUNK_OFFSET;
        // Must not exist on disk (a found read never converts to a generation ticket), and
        // the failure feed means no run ever generates it — but salt anyway (world persists).
        int cz = origin.z + (int) Math.floorMod(System.nanoTime(), 64L);
        var tickets = ticketStorage(level);
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        var gen = service.getGenerationService();
        Gt.assertTrue(helper, gen != null, "generation service expected");
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        Gt.assertTrue(helper, level.getChunkSource().getChunkNow(cx, cz) == null,
                "premise: the requested chunk must not be loaded");
        GameTestSeeding.seedRequest(state, PositionUtil.packPosition(cx, cz), 0L); // ts 0 = generation

        // Tick 1 registers the dimension context; the disk-notfound -> generation conversion
        // needs further processing cycles driven by manual snapshots (no drain in between).
        service.tick();

        var phase = new AtomicInteger();
        helper.succeedWhen(() -> {
            if (phase.get() == 0) {
                if (state.getHeldGenSlots() != 1) {
                    service.getOffThreadProcessor().postSnapshot(new TickSnapshot(
                            Map.of(uuid, LSSConstants.DIM_STR_OVERWORLD), Map.of(),
                            LSSServerConfig.CONFIG.sendQueueLimitPerPlayer, false), List.of());
                    Gt.assertTrue(helper, false,
                            "waiting for the disk-notfound -> generation conversion (heldGenSlots=1)");
                }
                // Ticket request enqueued for the main-thread drain; discard the player
                // BEFORE any drain can run. The player list still holds it (death shape).
                mock.discard();
                Gt.assertTrue(helper, mock.isRemoved() && playerList.getPlayer(uuid) != null,
                        "premise: discarded but still listed");
                phase.set(1);
            }
            service.tick();
            var diag = service.getOffThreadProcessor().getDiagnostics();
            Gt.assertTrue(helper, diag.getTotalGenDrained() >= 1,
                    "waiting for the failure feed to drain as a generation outcome");
            Gt.assertTrue(helper, gen.getTotalSubmitted() == 0,
                    "a removed player's ticket request must never reach submitGeneration "
                            + "(no MC ticket for an entity that cannot receive the column)");
            Gt.assertTrue(helper, gen.getActiveCount() == 0 && lssTicketCount(tickets, cx, cz) == 0,
                    "no generation entry or load ticket may exist for the dropped request");
            Gt.assertTrue(helper, state.getHeldGenSlots() == 0,
                    "the failure outcome must free the pending generation slot, held="
                            + state.getHeldGenSlots());
            Gt.assertTrue(helper, service.getOffThreadProcessor().pollGenerationTicketRequest() == null,
                    "the ticket request must be consumed by the drain");
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * FP-023: a queued re-request racing its own generation completion into the same
     * processing cycle costs exactly ONE column payload. The completion (phase 3) marks
     * the done-bit and enqueues the payload before routing (phase 4) consumes the
     * re-request, which must resolve as a duplicate answer — and the completed position is
     * excluded from that tick's probe pass by the genReadyPositions skip-set, so the probe
     * cannot double-serve it either (in_memory stays 0). The load wait deliberately does
     * NOT tick the service: generation completion only fires inside {@code service.tick()},
     * so the re-request can always be queued before the completion tick.
     */
    // maxTicks 1200 (not 600): waits on real cold chunk generation (see FP-033's precedent below).
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void sameTickGenerationCompletionAndQueuedReRequestServeExactlyOnePayload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + GEN_REASK_CHUNK_OFFSET;
        int cz = origin.z + (int) Math.floorMod(System.nanoTime(), 64L);
        long packed = PositionUtil.packPosition(cx, cz);
        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        var gen = service.getGenerationService();
        Gt.assertTrue(helper, gen != null, "generation service expected");
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        Gt.assertTrue(helper, level.getChunkSource().getChunkNow(cx, cz) == null,
                "premise: the chunk must not be loaded (the request must take the "
                        + "disk-notfound -> generation route)");
        GameTestSeeding.seedRequest(state, packed, 0L);

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var phase = new AtomicInteger();
        helper.succeedWhen(() -> {
            if (phase.get() == 0) {
                service.tick();
                Gt.assertTrue(helper, gen.getActiveCount() == 1,
                        "waiting for the generation ticket submission");
                phase.set(1);
                Gt.assertTrue(helper, false, "ticket submitted, waiting for the chunk to load");
            }
            if (phase.get() == 1) {
                // No service.tick() here: the chunk loads on the server's own chunk-system
                // ticks, and refraining from ticking guarantees the completion has not fired.
                Gt.assertTrue(helper, level.getChunkSource().getChunkNow(cx, cz) != null,
                        "waiting for the ticketed chunk to load");
                // ts>0 keeps the duplicate answer independent of the concurrent flush
                // (a ts<=0 re-ask could legally re-resolve if the payload already left).
                // A fresh single-entry want-set: the first declaration was consumed by a
                // routing cycle long ago (its generation is in flight), so this re-declaration
                // supersedes nothing.
                GameTestSeeding.seedRequest(state, packed, 5L);
                service.tick(); // harvests the completion AND routes the re-request
                phase.set(2);
                Gt.assertTrue(helper, false, "completion tick executed, awaiting the outcome");
            }
            service.tick();
            Gt.assertTrue(helper, diag.getTotalGenDrained() == 1 && state.getTotalSectionsSent() >= 1,
                    "waiting for the generation outcome to drain and the payload to flush");
            Gt.assertTrue(helper, state.getTotalSectionsSent() == 1,
                    "the completion + same-tick re-request must serve exactly ONE payload, got "
                            + state.getTotalSectionsSent());
            Gt.assertTrue(helper, gen.getTotalCompleted() == 1,
                    "exactly one generation completion expected");
            Gt.assertTrue(helper, diag.getTotalInMemory() == 0,
                    "the re-request must not be probe-served: the completed position is "
                            + "skip-set-excluded from the probe pass and resolves as a duplicate");
            Gt.assertTrue(helper, diag.getTotalRequestsRouted() == 2,
                    "both the original and the re-request must route exactly once, got "
                            + diag.getTotalRequestsRouted());
            Gt.assertTrue(helper, state.getHeldSyncSlots() == 0 && state.getHeldGenSlots() == 0,
                    "all slots must be free at rest");
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * FP-032 (T2 leg): an End-void generation completion is a COMPLETION (columnData
     * present, all-air serialization, real timestamp) and seeds the dirty filter with the
     * ALL_AIR sentinel at the completion call-site, so the chunk's following unload-save
     * hashes equal and stays quiet. The virgin-filter control proves the quiet came from
     * the seed. A regression classifying all-air completions as failures parks the void
     * forever; a lost sentinel seed re-marks every void column after every save.
     */
    // maxTicks 1200 (not 600): waits on real cold chunk generation (see FP-033's precedent below).
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void endVoidGenerationCompletionSeedsTheAllAirSentinel(GameTestHelper helper) {
        ServerLevel endLevel = helper.getLevel().getServer().getLevel(Level.END);
        Gt.assertTrue(helper, endLevel != null, "the End dimension must exist on the gametest server");
        var dim = LSSConstants.DIM_STR_THE_END;
        // Void guarantee band (see SerializerParityGameTests): density contributes nothing
        // between the main island and the outer islands; salted, disjoint from other tests.
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int salt = Math.floorMod(origin.x * 31 + origin.z, 64);
        int cx = 48 + (salt & 7);
        int cz = 18 + ((salt >> 3) & 7);
        var gen = newGenService(3, 2, 60);
        var filter = new DirtyContentFilter();
        gen.setDirtyContentFilter(filter);
        var player = UUID.randomUUID();
        Gt.assertTrue(helper, gen.submitGeneration(player, registration(player), endLevel, cx, cz, 1L),
                "premise: submission accepted");

        var outcomes = new AtomicReference<List<TickSnapshot.GenerationReadyData>>();
        helper.succeedWhen(() -> {
            if (outcomes.get() == null) {
                var ready = gen.tick();
                Gt.assertTrue(helper, !ready.isEmpty(), "waiting for the End chunk to generate");
                outcomes.set(List.copyOf(ready));
            }
            var outcome = outcomes.get().get(0);
            Gt.assertTrue(helper, outcome.columnData() != null,
                    "an all-air End completion must carry column data — null means failure, "
                            + "which would loop the void through NOT_GENERATED forever");
            Gt.assertTrue(helper, outcome.columnData().serializedSections() == null,
                    "premise: the void chunk must serialize as all-air");
            Gt.assertTrue(helper, outcome.columnTimestamp() > 0,
                    "the completion must carry a real timestamp for the up-to-date economy");
            Gt.assertTrue(helper, gen.getTotalCompleted() == 1 && gen.getTotalRemovedInFlight() == 0,
                    "all-air completion books as completed, not removed");

            var chunk = endLevel.getChunkSource().getChunkNow(cx, cz);
            Gt.assertTrue(helper, chunk != null, "premise: the generated chunk is still loaded");
            Gt.assertTrue(helper, !filter.contentChanged(endLevel, chunk, dim),
                    "the completion must seed the ALL_AIR sentinel: the chunk's following "
                            + "unload-save must hash equal and stay quiet");
            Gt.assertTrue(helper, new DirtyContentFilter().contentChanged(endLevel, chunk, dim),
                    "control: a virgin filter marks the same save — the quiet above must come "
                            + "from the completion-time seed");
            gen.shutdown();
        });
    }

    /**
     * FP-033: a Throwable (here an Error) thrown by the column serializer during a
     * generation completion. The catch books removedInFlight and fans a failure outcome to
     * every piggybacked callback; the finally releases the load ticket and drops the
     * active entry — pinned here because a skipped release force-loads the chunk forever
     * and re-throws every server tick (D7: the release already sits in a finally; this
     * test is the regression pin).
     */
    // maxTicks 1200 (not 600): the faulted entry generates a chunk at the gametest structure's
    // far origin, whose cold generation can exceed 600 ticks on the first run (the warm re-run is
    // fast) — a 600-tick budget made this an intermittent timeout flake.
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void serializationThrowableDuringCompletionReleasesTicketAndBalancesBooks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + SERIALIZER_FAULT_CHUNK_OFFSET;
        int cz = origin.z + 5;
        var tickets = ticketStorage(level);
        var config = new LSSServerConfig();
        config.generationConcurrencyLimitGlobal = 3;
        config.generationConcurrencyLimitPerPlayer = 2;
        config.generationTimeoutSeconds = 60;
        var gen = new ChunkGenerationService(config,
                (lvl, chunk, x, z) -> {
                    throw new AssertionError("injected serialization failure");
                });
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, cx, cz, 7L), "seed the entry");
        Gt.assertTrue(helper, gen.submitGeneration(playerB, registration(playerB), level, cx, cz, 8L), "piggyback the entry");
        Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 1, "premise: ticket held");

        var outcomes = new AtomicReference<List<TickSnapshot.GenerationReadyData>>();
        helper.succeedWhen(() -> {
            if (outcomes.get() == null) {
                var ready = gen.tick();
                Gt.assertTrue(helper, !ready.isEmpty(), "waiting for the chunk to load into the fault");
                outcomes.set(List.copyOf(ready));
            }
            var ready = outcomes.get();
            Gt.assertTrue(helper, ready.size() == 2,
                    "the serialization fault must fail EVERY piggybacked callback, got "
                            + ready.size());
            for (var outcome : ready) {
                Gt.assertTrue(helper, outcome.columnData() == null,
                        "fault outcomes must carry null column data (the failure marker)");
            }
            Gt.assertTrue(helper, gen.getTotalRemovedInFlight() == 1,
                    "the faulted entry books removedInFlight ONCE (the A4 re-balancing term), got "
                            + gen.getTotalRemovedInFlight());
            Gt.assertTrue(helper, gen.getTotalCompleted() == 0 && gen.getTotalTimeouts() == 0,
                    "a serialization fault is neither a completion nor a timeout");
            Gt.assertTrue(helper, gen.getActiveCount() == 0,
                    "the faulted entry must leave the active set (a retained entry re-throws "
                            + "every tick)");
            Gt.assertTrue(helper, lssTicketCount(tickets, cx, cz) == 0,
                    "the finally must release the load ticket even on an Error — a skipped "
                            + "release force-loads the chunk forever");
            Gt.assertTrue(helper, gen.getTotalSubmitted() == gen.getTotalCompleted()
                            + gen.getTotalTimeouts() + gen.getTotalRemovedInFlight() + gen.getActiveCount(),
                    "soak law A4 identity must hold after the fault");
            // The catch must also free the per-player concurrency counts (cap is 2).
            Gt.assertTrue(helper, gen.submitGeneration(playerA, registration(playerA), level, cx + 1, cz, 9L)
                            && gen.submitGeneration(playerA, registration(playerA), level, cx + 2, cz, 10L),
                    "the fault must free the per-player concurrency counts");
            gen.shutdown();
            Gt.assertTrue(helper, lssTicketCount(tickets, cx + 1, cz) == 0
                            && lssTicketCount(tickets, cx + 2, cz) == 0,
                    "shutdown must release the post-fault entries' tickets");
        });
    }

    /**
     * FP-041 (non-air leg): the completion-time {@code DirtyContentFilter.seed} call-site,
     * wired to the LIVE service's filter so the REAL save hook consults the seeded hash:
     * a generation-served superflat chunk's immediate save must produce zero dirty marks
     * (the B5 class — losing this seed re-sends every generated column once, +73 MB on a
     * fresh backfill). Same-callback save keeps the serialized content identical to the
     * completion-time seed; cross-tick byte determinism is pinned separately by
     * SerializerParityGameTests. Salted coords: a rerun would load the chunk
     * already-generated and not-unsaved, and the save pass would skip it vacuously.
     */
    // maxTicks 1200 (not 600): waits on real cold chunk generation (see FP-033's precedent above).
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void generationServeSeedSuppressesTheFollowingSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var liveService = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, liveService != null, "live service required (the save hook feeds it)");
        // Arm the P3 never-registered skip gate: the quiet-save assertion below must
        // observe the live hook actually hashing (a skipped hook would fake the quiet).
        liveService.armSaveHookForTest();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + SEED_SUPPRESS_CHUNK_OFFSET;
        int cz = origin.z + (int) Math.floorMod(System.nanoTime(), 64L);
        long packed = PositionUtil.packPosition(cx, cz);
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var gen = newGenService(3, 2, 60);
        gen.setDirtyContentFilter(liveService.getDirtyContentFilter());
        var player = UUID.randomUUID();
        Gt.assertTrue(helper, level.getChunkSource().getChunkNow(cx, cz) == null,
                "premise: the chunk must generate fresh (a reloaded chunk is not unsaved and "
                        + "the save pass would skip it)");
        Gt.assertTrue(helper, gen.submitGeneration(player, registration(player), level, cx, cz, 1L), "premise: submitted");

        helper.succeedWhen(() -> {
            var ready = gen.tick();
            Gt.assertTrue(helper, !ready.isEmpty(), "waiting for the chunk to generate");
            var outcome = ready.get(0);
            Gt.assertTrue(helper, outcome.columnData() != null
                            && outcome.columnData().serializedSections() != null,
                    "premise: a superflat completion serves non-air content");

            var chunk = level.getChunkSource().getChunkNow(cx, cz);
            Gt.assertTrue(helper, chunk != null, "premise: the generated chunk is still loaded");
            Gt.assertTrue(helper, new DirtyContentFilter().contentChanged(level, chunk, dim),
                    "control: a virgin filter marks this save — quiet below must come from "
                            + "the completion-time seed");

            // Same-callback drain-save-drain: no other test's marks can interleave, and the
            // chunk's content cannot drift between the seed and the save.
            var tracker = liveService.getDirtyTracker();
            tracker.drainDirty(dim);
            level.save(null, true, false);
            long[] dirty = tracker.drainDirty(dim);
            Gt.assertTrue(helper, !containsPosition(dirty, packed),
                    "a generation-served chunk's save must NOT re-mark dirty: the completion "
                            + "seeds the filter with the served bytes (losing the seed re-sends "
                            + "every generated column a second time)");
            gen.shutdown();
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
