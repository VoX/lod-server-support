package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the deliverDiskResult ladder and its escalation/staleness edges by injecting
 * {@link ChunkReadResult}s straight into the stub reader's per-player queue:
 * saturated results drop silently and count superseded (nothing marked served — the
 * client recovers by re-declaring, not by a bounce), all-air
 * results serve up-to-date and stamp the timestamp cache (the End-void retry-storm
 * fix), data results become column payloads, not-found escalates ANY request's sync
 * slot to a generation ticket (server-owned generation — the disk miss IS the trigger;
 * gen unavailable answers NOT_GENERATED, a momentarily full gen slot drops silently as
 * superseded) with every outcome freeing the slot, transient generation outcomes drop
 * silently while permanent ones answer NOT_GENERATED, and disk results or generation
 * outcomes carrying a stale dimension are inert.
 */
class OffThreadProcessorDiskResultTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;
    private static final String END = LSSConstants.DIM_STR_THE_END;
    private static final long COLUMN_TS = 1_700_000_000L;

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid, int syncCap, int genCap) { super(uuid, syncCap, genCap); }
        @Override public String getPlayerName() { return "test"; }
        /** Seeds one want-set batch carrying a single request (the pre-v17 per-request
         *  enqueue has no equivalent — each declaration REPLACES the backlog). Every call
         *  site here is separated from the next by a routing cycle, so nothing is superseded. */
        void enqueue(IncomingRequest r) { offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r})); }
    }

    /** No abstract members — subclassing only unlocks instantiation; tests bypass the
     *  executor entirely and inject results via the public per-player queue. */
    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        record DiskSubmit(UUID player, String dimension, int cx, int cz) {}
        record EnqueuedColumn(UUID player, int cx, int cz, String dimension,
                              long columnTimestamp, byte[] bytes) {}

        final ConcurrentLinkedQueue<DiskSubmit> diskSubmits = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<EnqueuedColumn> enqueuedColumns = new ConcurrentLinkedQueue<>();
        volatile boolean rejectEnqueue; // blanket rejection (queue-full style)
        volatile boolean throwOnNextSubmit; // one-shot: fail a phase-4 disk submit to exercise requeue
        // Mirrors the REAL platform guard: only payloads over the send limit bounce, so the
        // 1-byte clearing column would be accepted — pins that no clear is fabricated for a
        // rejected real column (the blanket flag above can't: it rejects the clear too).
        volatile boolean rejectOversizedOnly;

        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader reader,
                      boolean generationAvailable) {
            super(players, reader, generationAvailable, null, 1);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            if (throwOnNextSubmit) {
                throwOnNextSubmit = false;
                throw new RuntimeException("injected phase-4 routing failure");
            }
            diskSubmits.add(new DiskSubmit(playerUuid, dimension, cx, cz));
            return true;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes) {
            if (rejectEnqueue) return false;
            if (rejectOversizedOnly && sectionBytes.length > LSSConstants.MAX_SEND_SECTIONS_SIZE) return false;
            enqueuedColumns.add(new EnqueuedColumn(state.getPlayerUUID(), cx, cz, dimension,
                    columnTimestamp, sectionBytes));
            return true;
        }
    }

    /** One player + stub reader + started processor, wired the way the platform services wire them. */
    private static final class Rig {
        final UUID uuid = UUID.randomUUID();
        final ConcurrentHashMap<UUID, TestState> players = new ConcurrentHashMap<>();
        final StubDiskReader reader = new StubDiskReader();
        final TestState state;
        final TestProcessor proc;

        Rig(boolean generationAvailable) { this(generationAvailable, 4, 4); }

        Rig(boolean generationAvailable, int syncCap, int genCap) {
            this.state = newPlayer(uuid, syncCap, genCap);
            this.players.put(uuid, state);
            this.reader.registerPlayer(uuid);
            this.proc = new TestProcessor(players, reader, generationAvailable);
            this.proc.start();
        }

        TestState addPlayer(UUID u) {
            var s = newPlayer(u, 4, 4);
            players.put(u, s);
            reader.registerPlayer(u);
            return s;
        }

        void inject(ChunkReadResult result) {
            reader.getPlayerQueue(result.playerUuid()).add(result);
        }
    }

    private static TestState newPlayer(UUID uuid, int syncCap, int genCap) {
        var s = new TestState(uuid, syncCap, genCap);
        s.markHandshakeComplete();
        s.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        return s;
    }

    private static TickSnapshot snapshot(String dimension, UUID... uuids) {
        var dims = new HashMap<UUID, String>();
        for (var u : uuids) dims.put(u, dimension);
        return new TickSnapshot(dims, Map.of(), 0, false);
    }

    private static ChunkReadResult dataResult(UUID u, int cx, int cz, String dim,
                                              byte[] bytes, long ts, long order) {
        return new ChunkReadResult(u, cx, cz, bytes, dim,
                bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, ts, false, false, order);
    }

    /** Mirrors AbstractChunkDiskReader's all-air triage: found on disk, no bytes to send. */
    private static ChunkReadResult allAirResult(UUID u, int cx, int cz, String dim, long ts, long order) {
        return new ChunkReadResult(u, cx, cz, null, dim, 0, ts, false, false, order);
    }

    private record Response(UUID player, byte type, long packed) {}

    /** Poll drainSendActions until {@code done} holds over everything drained so far. */
    private static List<Response> drainUntil(TestProcessor proc, Predicate<List<Response>> done)
            throws InterruptedException {
        var collected = new ArrayList<Response>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.test(collected)) {
            if (System.nanoTime() > deadline) fail("timed out draining responses; got " + collected);
            proc.drainSendActions((state, types, positions, count) -> {
                for (int i = 0; i < count; i++) {
                    collected.add(new Response(state.getPlayerUUID(), types[i], positions[i]));
                }
            });
            Thread.sleep(10);
        }
        return collected;
    }

    /** One non-blocking drain of the answers produced so far (for "nothing was sent" pins). */
    private static List<Response> drainSendActions(TestProcessor proc) {
        var collected = new ArrayList<Response>();
        proc.drainSendActions((state, types, positions, count) -> {
            for (int i = 0; i < count; i++) {
                collected.add(new Response(state.getPlayerUUID(), types[i], positions[i]));
            }
        });
        return collected;
    }

    private static Predicate<List<Response>> received(byte type, long packed) {
        return rs -> rs.stream().anyMatch(r -> r.type() == type && r.packed() == packed);
    }

    private static Predicate<List<Response>> received(UUID player, byte type, long packed) {
        return rs -> rs.stream().anyMatch(r -> r.player().equals(player)
                && r.type() == type && r.packed() == packed);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    // ---- deliverDiskResult ladder ----

    @Test
    void saturatedResultDropsSilentlyAndCountsSuperseded() throws Exception {
        // v17: saturation never reaches the wire. The router's headroom gate prevents submits
        // into a full pool, so this residual path (races/shutdown) drops the result silently and
        // books it as superseded. The INVARIANT is unchanged from the old rate-limited bounce:
        // a saturated read must never STRAND the requester. Misclassifying saturated as notFound
        // would answer NotGenerated and make the client give up on a retryable position; the
        // recovery is now re-declaration (the client re-asks every unsatisfied position at 1 Hz),
        // which only works if nothing is stamped, marked served, or left holding a slot.
        var rig = new Rig(false);
        try {
            rig.state.enqueue(new IncomingRequest(5, 5, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "first disk submit");

            rig.inject(ChunkReadResult.saturated(rig.uuid, 5, 5, DIM, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            // Freeing the slot is the delivery's last act, so it is the barrier that makes
            // "and nothing was sent" a deterministic assertion rather than a sleep.
            waitFor(() -> rig.state.getHeldSyncSlots() == 0, "saturated delivery must free the sync slot");
            assertEquals(1, rig.proc.getDiagnostics().getTotalSuperseded(),
                    "the silent drop is booked as superseded — the request-conservation "
                            + "ledger closes without a wire response");
            assertTrue(drainSendActions(rig.proc).isEmpty(),
                    "a saturated result answers NOTHING: no bounce, no not-generated");
            assertFalse(rig.state.hasDiskReadDone(5, 5),
                    "saturated is retryable — must not be marked served");
            assertTrue(rig.proc.enqueuedColumns.isEmpty());

            // The re-declaration must reach the disk again: nothing stamped or marked by the drop
            rig.state.enqueue(new IncomingRequest(5, 5, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 2, "re-declaration resubmits the disk read");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void rejectedEnqueueAnswersUpToDateInsteadOfSilence() throws Exception {
        // An oversized column can't go on the wire. The old path dropped it silently with
        // diskReadDone already set — the client retried an unserveable position forever
        // (or, post-honest-re-resolution, re-read the disk forever). The terminal answer
        // is up-to-date.
        var rig = new Rig(false);
        try {
            rig.proc.rejectEnqueue = true;
            rig.state.enqueue(new IncomingRequest(9, 9, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "disk submit");

            rig.inject(dataResult(rig.uuid, 9, 9, DIM, new byte[]{1, 2, 3}, COLUMN_TS, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            long packed = PositionUtil.packPosition(9, 9);
            drainUntil(rig.proc, received(rig.uuid, LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertTrue(rig.state.hasDiskReadDone(9, 9), "unserveable position resolves terminally");
            assertEquals(0, rig.state.getHeldSyncSlots(), "delivery must free the slot");
            assertTrue(rig.proc.enqueuedColumns.isEmpty());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void allAirResultServesUpToDateAndStampsTimestampCache() throws Exception {
        // Generation available so a regression to "not found" would emit a generation
        // ticket — the End-void retry storm fixed in 761b3fb, pinned here deterministically.
        var rig = new Rig(true);
        try {
            rig.state.enqueue(new IncomingRequest(8, 8, 0L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "disk-first submit");

            rig.inject(allAirResult(rig.uuid, 8, 8, DIM, COLUMN_TS, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            long packed = PositionUtil.packPosition(8, 8);
            drainUntil(rig.proc, received(rig.uuid, LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertTrue(rig.state.hasDiskReadDone(8, 8), "all-air resolves as served");
            assertEquals(0, rig.state.getHeldSyncSlots(), "all-air delivery must free the slot");
            assertEquals(0, rig.state.getHeldGenSlots(), "all-air must not escalate to generation");
            assertNull(rig.proc.pollGenerationTicketRequest(),
                    "all-air must not trigger a generation retry");
            assertTrue(rig.proc.enqueuedColumns.isEmpty(), "all-air has nothing visible to send");

            // The drain pass stamps the timestamp cache: another player's resync at the served
            // timestamp resolves up-to-date without disk IO.
            var u2 = UUID.randomUUID();
            var p2 = rig.addPlayer(u2);
            p2.enqueue(new IncomingRequest(8, 8, COLUMN_TS));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid, u2), List.of());
            drainUntil(rig.proc, received(u2, LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertEquals(1, rig.proc.diskSubmits.size(), "warm request must not submit another disk read");
            assertEquals(0, p2.getHeldSyncSlots(), "warm resolution must not consume a slot");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void invalidationOvertakingAnInFlightReadSuppressesTheStaleStamp() throws Exception {
        // A disk read races a chunk edit: the read captured PRE-edit bytes, and the edit's
        // dirty invalidation is applied while the read is still in flight. Delivering the
        // result must not re-stamp the timestamp cache or re-mark diskReadDone, or the
        // client's dirty re-request draws a false up_to_date and the pre-edit terrain seals
        // against both healing ladders (full-review finding: phase ordering in processCycle).
        var rig = new Rig(false);
        try {
            rig.state.enqueue(new IncomingRequest(7, 7, -1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "disk submit");

            long packed = PositionUtil.packPosition(7, 7);
            rig.proc.invalidateTimestamps(DIM, new long[]{packed});
            rig.inject(dataResult(rig.uuid, 7, 7, DIM, new byte[]{1, 2}, COLUMN_TS, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            // The column is still delivered (better than nothing)...
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "stale column still delivered");
            assertFalse(rig.state.hasDiskReadDone(7, 7),
                    "a stale-against-edit delivery must not set the done-bit");
            assertEquals(0, rig.state.getHeldSyncSlots(), "delivery still frees the slot");

            // ...and the dirty re-request at the delivered stamp re-reads the disk (which by
            // now holds the post-edit bytes) instead of resolving up_to_date/duplicate.
            rig.state.enqueue(new IncomingRequest(7, 7, COLUMN_TS));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 2, "dirty re-request re-reads the disk");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void invalidationOvertakingAnInFlightGenerationSuppressesTheStaleStamp() throws Exception {
        // Generation twin of invalidationOvertakingAnInFlightRead...: a generation outcome
        // captured PRE-edit terrain, and the edit's dirty invalidation is applied while the
        // generation is still in flight. Delivering the outcome must not re-stamp the timestamp
        // cache or mark diskReadDone, or the client's dirty re-request draws a false up_to_date
        // and the pre-edit generated terrain seals. Generation tickets never create a dedup
        // group, so they carry their own in-flight oracle (full-review finding: the gen path
        // bypassed the disk path's invalidation-overtake guard).
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 7, 7); // one disk-first submit, then in-flight gen
            long packed = PositionUtil.packPosition(7, 7);

            // The edit's invalidation lands while the generation is still in flight...
            rig.proc.invalidateTimestamps(DIM, new long[]{packed});
            byte[] bytes = {1, 2};
            var data = new LoadedColumnData(7, 7, bytes,
                    bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 7, 7, DIM, data,
                            COLUMN_TS, ticket.submissionOrder()))));

            // ...the generated column is still delivered (better than nothing)...
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "stale generated column still delivered");
            assertFalse(rig.state.hasDiskReadDone(7, 7),
                    "a stale-against-edit generation must not set the done-bit");
            assertEquals(0, rig.state.getHeldGenSlots(), "delivery still frees the gen slot");

            // ...and the dirty re-request re-reads the disk (post-edit bytes) instead of
            // resolving up_to_date/duplicate off a false stamp.
            rig.state.enqueue(new IncomingRequest(7, 7, COLUMN_TS));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 2, "dirty re-request re-reads the disk");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void generationTrackingIsSweptWhenAPlayerLeavesMidGeneration() throws Exception {
        // A generation escalates and an edit taints it, then the player leaves before the
        // outcome drains (disconnect / dimension change), so nothing ever consumes the taint.
        // The removal event must sweep the per-player generation tracking; otherwise the stale
        // flag leaks and a later generation for the same player+position is spuriously
        // suppressed forever (full-review finding: the guard had no removal-cleanup hook,
        // unlike the disk path's dedup-group sweep in cleanupDedupGroups).
        var rig = new Rig(true);
        long packed = PositionUtil.packPosition(7, 7);
        try {
            escalateToGenTicket(rig, 7, 7);                       // in-flight for this player
            rig.proc.invalidateTimestamps(DIM, new long[]{packed});
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of()); // applyEvents taints it
            // The player leaves mid-generation: the escalated ticket's outcome never drains.
            rig.proc.notifyPlayerRemoved(rig.uuid);
            rig.proc.postSnapshot(snapshot(DIM), List.of());          // applyEvents must sweep it

            // Reconnect discards the old state's held gen slot; re-generate the SAME position
            // with NO fresh edit. A leaked stale flag would resurface here.
            rig.state.removePendingByPosition(7, 7);
            var readmitted = escalateToGenTicket(rig, 7, 7);
            byte[] bytes = {1, 2};
            var data = new LoadedColumnData(7, 7, bytes,
                    bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 7, 7, DIM, data,
                            COLUMN_TS, readmitted.submissionOrder()))));
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "re-generated column delivered");
            assertTrue(rig.state.hasDiskReadDone(7, 7),
                    "the swept taint must not resurface — the fresh generation stamps + marks served");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void aFailedCycleDoesNotRedeliverAnAlreadyProcessedGenerationOutcome() throws Exception {
        // requeueLosslessEvents re-queues a failed cycle's UN-applied events. A generation
        // outcome consumed in processGenerationReady (phase 3) must NOT be re-queued when a
        // LATER phase throws: re-delivery would find the stale mark already consumed and
        // re-stamp pre-edit terrain as up_to_date (full-review finding: error-retry replay
        // defeats the stale guard). The phase-completion flags gate the requeue.
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 5, 5);
            byte[] bytes = {1, 2};
            var data = new LoadedColumnData(5, 5, bytes,
                    bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            // A fresh request whose disk submit throws makes phase 4 fail AFTER phase 3
            // delivered the generation outcome, so the failed cycle re-queues lossless events.
            rig.state.enqueue(new IncomingRequest(6, 6, -1L));
            rig.proc.throwOnNextSubmit = true;
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 5, 5, DIM, data,
                            COLUMN_TS, ticket.submissionOrder()))));
            waitFor(() -> rig.proc.enqueuedColumns.stream().anyMatch(c -> c.cx() == 5),
                    "generation outcome delivered in the failed cycle's phase 3");

            // Drive a recovery cycle (the one-shot throw has cleared). A re-queued outcome would
            // re-deliver a SECOND (5,5) column here; the (8,8) submit signals the cycle ran.
            rig.state.enqueue(new IncomingRequest(8, 8, -1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.stream().anyMatch(s -> s.cx() == 8),
                    "recovery cycle routed the (8,8) request");
            long served55 = rig.proc.enqueuedColumns.stream().filter(c -> c.cx() == 5).count();
            assertEquals(1, served55, "the generation outcome must be delivered exactly once — "
                    + "the error-retry requeue must not re-deliver an already-consumed outcome");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void anEditTaintsEveryInFlightGenerationHolderOfAPosition() throws Exception {
        // Generation does not dedup, so two players can generate the same column independently.
        // An edit that overtakes the position must taint BOTH outcomes (per-player), not just
        // the first to drain — the multi-holder invariant the per-player rewrite replaced the
        // refcount to preserve, previously unpinned (full-review finding).
        var rig = new Rig(true);
        var bUuid = UUID.randomUUID();
        var bState = rig.addPlayer(bUuid);
        try {
            // Both players independently escalate a generation at the SAME (5,5) — generation
            // does not dedup, so both are in flight at once.
            var ticketA = escalateToGenTicket(rig, rig.state, rig.uuid, 5, 5);
            var ticketB = escalateToGenTicket(rig, bState, bUuid, 5, 5);

            // The edit taints the position while BOTH generations are in flight.
            rig.proc.invalidateTimestamps(DIM, new long[]{PositionUtil.packPosition(5, 5)});
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid, bUuid), List.of());

            // Deliver both outcomes; each must be treated as stale (no done-bit → re-resolves).
            var dataA = new LoadedColumnData(5, 5, new byte[]{1, 2},
                    2 + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            var dataB = new LoadedColumnData(5, 5, new byte[]{1, 2},
                    2 + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid, bUuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 5, 5, DIM, dataA, COLUMN_TS,
                            ticketA.submissionOrder()),
                    new TickSnapshot.GenerationReadyData(bUuid, 5, 5, DIM, dataB, COLUMN_TS,
                            ticketB.submissionOrder()))));
            waitFor(() -> rig.proc.enqueuedColumns.size() >= 2, "both generated columns delivered");
            assertFalse(rig.state.hasDiskReadDone(5, 5),
                    "player A's tainted generation must not set the done-bit");
            assertFalse(bState.hasDiskReadDone(5, 5),
                    "player B's tainted generation must not set the done-bit — EVERY holder is tainted");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void notFoundInvalidatesAStaleTimestampStamp() throws Exception {
        // Region trimmed/deleted outside Minecraft: the position was served (and stamped)
        // once, but disk now says not-found. Without invalidation the stale stamp answers
        // up_to_date to data-claiming clients forever — the ghost terrain can never clear.
        var rig = new Rig(false);
        try {
            long packed = PositionUtil.packPosition(12, 12);
            rig.state.enqueue(new IncomingRequest(12, 12, -1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "first disk submit");
            rig.inject(dataResult(rig.uuid, 12, 12, DIM, new byte[]{1}, COLUMN_TS, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "column served + stamped");

            // The dirty pipeline reopens the position; the re-read finds the region gone.
            rig.proc.clearDiskReadDone(rig.uuid, new long[]{packed});
            rig.state.enqueue(new IncomingRequest(12, 12, COLUMN_TS - 1));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 2, "re-read submitted");
            rig.inject(ChunkReadResult.empty(rig.uuid, 12, 12, DIM, 2L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            drainUntil(rig.proc, received(LSSConstants.RESPONSE_NOT_GENERATED, packed));

            // A resync at the old stamp must RE-RESOLVE (stale stamp invalidated), not draw
            // a false up_to_date for data that no longer exists.
            rig.state.enqueue(new IncomingRequest(12, 12, COLUMN_TS));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 3,
                    "the not-found position must re-read, not answer up_to_date");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void oversizedResultForClaimsDataAnswersUpToDateNotClear() throws Exception {
        // An enqueue REJECTION (oversized column) is not an all-air resolution: the server
        // KNOWS real content exists there, so an authoritative clear would erase the client's
        // stale-but-real terrain and seal fabricated air (the 1-byte clear passes the size
        // guard the real column failed). The terminal answer is up_to_date — the client keeps
        // what it has.
        var rig = new Rig(false);
        try {
            rig.proc.rejectOversizedOnly = true;
            rig.state.enqueue(new IncomingRequest(9, 9, COLUMN_TS)); // ts>0: claimsData resync
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "disk submit");

            rig.inject(dataResult(rig.uuid, 9, 9, DIM,
                    new byte[LSSConstants.MAX_SEND_SECTIONS_SIZE + 1], COLUMN_TS + 5, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            long packed = PositionUtil.packPosition(9, 9);
            drainUntil(rig.proc, received(rig.uuid, LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertTrue(rig.proc.enqueuedColumns.isEmpty(),
                    "no clearing column may be fabricated for a rejected real column");
            assertTrue(rig.state.hasDiskReadDone(9, 9), "unserveable position resolves terminally");
            assertEquals(0, rig.state.getHeldSyncSlots());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void allAirResultForClaimsDataSendsClearingColumn() throws Exception {
        // The WS3 server half, previously unpinned: a genuinely all-air resolution for a
        // data-claiming resync client sends the authoritative 0-section column (carrying the
        // server timestamp) so the client clears its ghost terrain.
        var rig = new Rig(true);
        try {
            rig.proc.rejectOversizedOnly = true; // real-guard model: the clear must pass
            rig.state.enqueue(new IncomingRequest(8, 8, COLUMN_TS));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "disk submit");

            rig.inject(allAirResult(rig.uuid, 8, 8, DIM, COLUMN_TS + 5, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "clearing column enqueued");
            var col = rig.proc.enqueuedColumns.poll();
            assertArrayEquals(new byte[]{0x00}, col.bytes(), "authoritative clear = zero-section body");
            assertEquals(COLUMN_TS + 5, col.columnTimestamp(), "clear carries the server timestamp");
            assertTrue(rig.state.hasDiskReadDone(8, 8));
            assertEquals(0, rig.state.getHeldSyncSlots());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void oversizedGenerationOutcomeAnswersUpToDateNotClear() throws Exception {
        var rig = new Rig(true);
        try {
            rig.proc.rejectOversizedOnly = true;
            var ticket = escalateToGenTicket(rig, 4, 6);

            byte[] oversized = new byte[LSSConstants.MAX_SEND_SECTIONS_SIZE + 1];
            var data = new LoadedColumnData(4, 6, oversized,
                    oversized.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 4, 6, DIM, data,
                            COLUMN_TS, ticket.submissionOrder()))));

            long packed = PositionUtil.packPosition(4, 6);
            drainUntil(rig.proc, received(rig.uuid, LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertTrue(rig.proc.enqueuedColumns.isEmpty(),
                    "no clearing column may be fabricated for a rejected generated column");
            assertEquals(0, rig.state.getHeldGenSlots());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void allAirGenerationOutcomeStampsTimestampCache() throws Exception {
        // The generation all-air path previously skipped the timestamp stamp (the data path
        // stamps inside enqueueLoadedColumn), so the same void column re-resolved every
        // session instead of converging to a warm up_to_date.
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 4, 6);

            var allAir = new LoadedColumnData(4, 6, null, 0);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 4, 6, DIM, allAir,
                            COLUMN_TS, ticket.submissionOrder()))));

            long packed = PositionUtil.packPosition(4, 6);
            drainUntil(rig.proc, received(rig.uuid, LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertTrue(rig.proc.enqueuedColumns.isEmpty(), "gen requests (ts=0) never claim data");

            // Warm resync at the stamped timestamp resolves without another disk read.
            int diskBefore = rig.proc.diskSubmits.size();
            var u2 = UUID.randomUUID();
            var p2 = rig.addPlayer(u2);
            p2.enqueue(new IncomingRequest(4, 6, COLUMN_TS));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid, u2), List.of());
            drainUntil(rig.proc, received(u2, LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertEquals(diskBefore, rig.proc.diskSubmits.size(),
                    "warm request must not submit another disk read");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void dataResultEnqueuesColumnPayloadAndFreesSlot() throws Exception {
        var rig = new Rig(false);
        try {
            rig.state.enqueue(new IncomingRequest(2, 3, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "disk submit");

            byte[] bytes = {10, 20, 30};
            rig.inject(dataResult(rig.uuid, 2, 3, DIM, bytes, COLUMN_TS, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "column payload enqueued");

            var col = rig.proc.enqueuedColumns.poll();
            assertEquals(rig.uuid, col.player());
            assertEquals(2, col.cx());
            assertEquals(3, col.cz());
            assertEquals(DIM, col.dimension());
            assertEquals(COLUMN_TS, col.columnTimestamp());
            assertArrayEquals(bytes, col.bytes());
            assertTrue(rig.proc.enqueuedColumns.isEmpty(), "exactly one payload per result");
            assertEquals(0, rig.state.getHeldSyncSlots(), "data delivery must free the slot");
            assertTrue(rig.state.hasDiskReadDone(2, 3));
        } finally {
            rig.proc.shutdown();
        }
    }

    // ---- not-found → generation escalation ----

    /** Routes a GENERATION request (clientTimestamp 0) through the disk-first path,
     *  injects not-found, and returns the escalated generation ticket. */
    private static OffThreadProcessor.GenerationTicketRequest escalateToGenTicket(
            Rig rig, int cx, int cz) throws InterruptedException {
        return escalateToGenTicket(rig, rig.state, rig.uuid, cx, cz);
    }

    /** As above, for a specific player — so two players can be escalated independently at the
     *  same position (generation does not dedup). Routes only {@code uuid} in each snapshot. */
    private static OffThreadProcessor.GenerationTicketRequest escalateToGenTicket(
            Rig rig, TestState state, UUID uuid, int cx, int cz) throws InterruptedException {
        long before = rig.proc.diskSubmits.stream().filter(s -> s.player().equals(uuid)).count();
        state.enqueue(new IncomingRequest(cx, cz, 0L));
        rig.proc.postSnapshot(snapshot(DIM, uuid), List.of());
        waitFor(() -> rig.proc.diskSubmits.stream().filter(s -> s.player().equals(uuid)).count() == before + 1,
                "disk-first submit");

        rig.inject(ChunkReadResult.empty(uuid, cx, cz, DIM, 1L));
        rig.proc.postSnapshot(snapshot(DIM, uuid), List.of());

        var ref = new AtomicReference<OffThreadProcessor.GenerationTicketRequest>();
        waitFor(() -> {
            var t = rig.proc.pollGenerationTicketRequest();
            if (t != null && t.playerUuid().equals(uuid)) ref.set(t);
            return ref.get() != null;
        }, "escalated generation ticket");
        return ref.get();
    }

    @Test
    void notFoundEscalatesGenerationRequestToTicketAndSwapsSlot() throws Exception {
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 4, 6);
            assertEquals(rig.uuid, ticket.playerUuid());
            assertEquals(4, ticket.cx());
            assertEquals(6, ticket.cz());
            assertEquals(DIM, ticket.dimension());
            assertEquals(0, rig.state.getHeldSyncSlots(), "disk slot freed on not-found delivery");
            assertEquals(1, rig.state.getHeldGenSlots(), "escalation holds a generation slot");
            assertFalse(rig.state.hasDiskReadDone(4, 6), "escalated position is not served yet");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void escalatedGenerationSuccessDeliversColumnAndFreesGenSlot() throws Exception {
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 4, 6);

            byte[] bytes = {1, 2};
            var data = new LoadedColumnData(4, 6, bytes,
                    bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 4, 6, DIM, data,
                            COLUMN_TS, ticket.submissionOrder()))));
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "generated column payload");

            var col = rig.proc.enqueuedColumns.poll();
            assertEquals(4, col.cx());
            assertEquals(6, col.cz());
            assertEquals(DIM, col.dimension());
            assertEquals(COLUMN_TS, col.columnTimestamp());
            assertArrayEquals(bytes, col.bytes());
            assertEquals(0, rig.state.getHeldGenSlots(), "generation outcome must free the gen slot");
            assertTrue(rig.state.hasDiskReadDone(4, 6), "generated column counts as served");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void permanentGenerationFailureSendsNotGeneratedAndFreesGenSlot() throws Exception {
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 4, 6);

            // Permanent unservability (extraction error / failed load) — the one disposition
            // that reaches the wire: the client session-satisfies and stops asking.
            rig.proc.feedGenerationFailure(rig.uuid, 4, 6, DIM, ticket.submissionOrder(), false);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            long packed = PositionUtil.packPosition(4, 6);
            drainUntil(rig.proc, received(LSSConstants.RESPONSE_NOT_GENERATED, packed));
            assertEquals(0, rig.state.getHeldGenSlots(), "failed generation must free the gen slot");
            assertFalse(rig.state.hasDiskReadDone(4, 6),
                    "a failed position must not be marked served");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void transientGenerationFailureDropsSilentlyAndFreesGenSlot() throws Exception {
        // Timeout / capacity rejection: NOT_GENERATED is session-permanent on the client, so
        // a transient outcome must never answer it — silent drop, counted superseded, and the
        // client's next want-set declaration retries the position.
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 4, 6);

            rig.proc.feedGenerationFailure(rig.uuid, 4, 6, DIM, ticket.submissionOrder(), true);
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            waitFor(() -> rig.state.getHeldGenSlots() == 0,
                    "transient outcome must free the gen slot");
            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() == 1,
                    "the silent drop is booked as superseded");
            assertEquals(1, rig.proc.getDiagnostics().getTotalMissDropped(),
                    "a capacity-reject transient is a miss-drop (law A5: the miss produced "
                            + "neither a generation submission nor a wire answer)");
            assertTrue(drainSendActions(rig.proc).isEmpty(),
                    "a transient generation failure answers NOTHING on the wire");
            assertFalse(rig.state.hasDiskReadDone(4, 6),
                    "transient is retryable — must not be marked served");

            // The re-declaration must reach the disk again: nothing stamped or marked.
            rig.state.enqueue(new IncomingRequest(4, 6, -1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 2, "re-declared position re-reads disk");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void timeoutShapedTransientOutcomeIsNotAMissDrop() throws Exception {
        // A timeout outcome's generation WAS submitted, so its disk miss is already balanced
        // by generation.submitted in law A5 — counting it into miss_dropped would over-balance.
        // (Law A5 also skips timeout windows entirely; this pins the counter's own semantics.)
        var rig = new Rig(true);
        try {
            var ticket = escalateToGenTicket(rig, 4, 6);

            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 4, 6, DIM, null,
                            0L, ticket.submissionOrder(), true))));

            waitFor(() -> rig.state.getHeldGenSlots() == 0,
                    "transient outcome must free the gen slot");
            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() == 1,
                    "the silent drop is booked as superseded");
            assertEquals(0, rig.proc.getDiagnostics().getTotalMissDropped(),
                    "a timeout-shaped transient must NOT count as a miss-drop");
            assertTrue(drainSendActions(rig.proc).isEmpty(),
                    "a timeout answers NOTHING on the wire");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void notFoundEscalatesToGenerationForAnySyncRequest() throws Exception {
        // Server-owned generation: the disk miss IS the trigger — no client classification.
        // A plain resync (ts>0) that misses disk escalates to a generation ticket.
        var rig = new Rig(true);
        try {
            rig.state.enqueue(new IncomingRequest(1, 9, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "disk submit");

            rig.inject(ChunkReadResult.empty(rig.uuid, 1, 9, DIM, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            var ref = new AtomicReference<OffThreadProcessor.GenerationTicketRequest>();
            waitFor(() -> {
                var t = rig.proc.pollGenerationTicketRequest();
                if (t != null) ref.set(t);
                return ref.get() != null;
            }, "sync not-found must escalate to a generation ticket");
            assertEquals(1, ref.get().cx());
            assertEquals(9, ref.get().cz());
            assertEquals(0, rig.state.getHeldSyncSlots(), "escalation must swap the sync slot");
            assertEquals(1, rig.state.getHeldGenSlots(), "escalation holds a gen slot");
            assertTrue(drainSendActions(rig.proc).isEmpty(),
                    "an escalated miss answers nothing until the generation resolves");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void notFoundWithGenCapFullDropsSilentlyAsSuperseded() throws Exception {
        // A momentarily full gen slot is TRANSIENT: under permanent NOT_GENERATED semantics a
        // wire answer here would blank the position for the whole session — the miss drops
        // silently (superseded) and the next want-set declaration retries it.
        var rig = new Rig(true, 4, 1);
        try {
            escalateToGenTicket(rig, 1, 1); // holds the only gen slot
            assertEquals(1, rig.state.getHeldGenSlots());

            rig.state.enqueue(new IncomingRequest(2, 2, 0L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 2, "second disk-first submit");
            rig.inject(ChunkReadResult.empty(rig.uuid, 2, 2, DIM, 2L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() == 1,
                    "the cap-full miss is booked as superseded");
            assertEquals(1, rig.proc.getDiagnostics().getTotalMissDropped(),
                    "a cap-full miss is a miss-drop (law A5's dedicated term)");
            assertTrue(drainSendActions(rig.proc).isEmpty(),
                    "a cap-full miss answers NOTHING: no NOT_GENERATED, no bounce");
            assertFalse(rig.state.hasPendingRequest(2, 2),
                    "dropped escalation must leave no pending entry");
            assertEquals(1, rig.state.getHeldGenSlots(), "the first escalation keeps its slot");
            assertEquals(0, rig.state.getHeldSyncSlots());
            assertNull(rig.proc.pollGenerationTicketRequest(), "no ticket for the dropped position");
            assertFalse(rig.state.hasDiskReadDone(2, 2),
                    "dropped position stays re-declarable");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void ghostNotFoundWithoutPendingDropsSilently() throws Exception {
        // A not-found delivery with no live pending behind it (duplicate/raced result) must
        // not answer the wire: NOT_GENERATED off a ghost delivery would session-satisfy a
        // position the player may still want.
        var rig = new Rig(false);
        try {
            rig.inject(ChunkReadResult.empty(rig.uuid, 3, 3, DIM, 1L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid), List.of());

            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() == 1,
                    "the ghost delivery is booked as superseded");
            assertEquals(1, rig.proc.getDiagnostics().getTotalMissDropped(),
                    "a ghost not-found is a miss-drop (law A5's dedicated term)");
            assertTrue(drainSendActions(rig.proc).isEmpty(),
                    "a pending-less not-found answers NOTHING");
        } finally {
            rig.proc.shutdown();
        }
    }

    // ---- stale-dimension inertness ----

    @Test
    void staleSnapshotDiskResultIsInertForAReRegisteredSession() throws Exception {
        // The mid-cycle swap race: a dimension change replaces the state AFTER the snapshot
        // was built, so the snapshot-dimension check compares OLD-vs-OLD and passes while the
        // states map already holds the FRESH session. The state's own registeredDimension is
        // the only honest gate — without it, the old result marks an unearned done-bit on the
        // new session (a stale up_to_date seal until dirty broadcast/reconnect).
        var rig = new Rig(false);
        try {
            // Player A's fresh session is registered in the END; the snapshot and the late
            // result both still carry the OLD dimension (overworld). Player B (no registered
            // dimension — the bare-rig guard skip) is the delivery sentinel: FIFO within one
            // cycle, so B's payload proves A's stale result was already consumed.
            rig.state.setRegisteredDimension(END);
            var bUuid = UUID.randomUUID();
            var b = rig.addPlayer(bUuid);
            rig.inject(dataResult(rig.uuid, 3, 3, DIM, new byte[]{9}, COLUMN_TS, 1L));
            rig.inject(dataResult(bUuid, 4, 4, DIM, new byte[]{7}, COLUMN_TS, 2L));
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid, bUuid), List.of());
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "sentinel payload for player B");

            var sentinel = rig.proc.enqueuedColumns.poll();
            assertEquals(bUuid, sentinel.player(), "only B's result may deliver");
            assertTrue(rig.proc.enqueuedColumns.isEmpty(),
                    "an old-session result must not produce a payload for the fresh session");
            assertFalse(rig.state.hasDiskReadDone(3, 3),
                    "an old-session result must not mark the fresh session's position served");
            assertTrue(b.hasDiskReadDone(4, 4), "the unguarded sentinel serves normally");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void staleSnapshotGenerationOutcomeIsInertForAReRegisteredSession() throws Exception {
        // Phase-3 twin of the disk-result guard. Worst variant without it: a PERMANENT
        // generation failure from the old session answers ColumnNotGenerated produced WITH
        // the fresh state — under v17 the client session-satisfies it permanently.
        var rig = new Rig(true);
        try {
            rig.state.setRegisteredDimension(END);
            var bUuid = UUID.randomUUID();
            rig.addPlayer(bUuid); // sentinel: no registered dimension, outcome delivers
            rig.proc.postSnapshot(snapshot(DIM, rig.uuid, bUuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 4, 6, DIM, null, 0L, 1L),
                    new TickSnapshot.GenerationReadyData(bUuid, 8, 8, DIM, null, 0L, 2L))));

            var delivered = drainUntil(rig.proc,
                    received(bUuid, LSSConstants.RESPONSE_NOT_GENERATED, PositionUtil.packPosition(8, 8)));
            assertTrue(delivered.stream().noneMatch(r -> r.player().equals(rig.uuid)),
                    "an old-session permanent failure must not answer NOT_GENERATED "
                            + "for the fresh session (session-permanent under v17)");
            assertFalse(rig.state.hasDiskReadDone(4, 6));
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void staleDimensionDiskResultIsInert() throws Exception {
        var rig = new Rig(true);
        try {
            // Live pending in the End at (3,3) — the state a dimension switch leaves behind
            rig.state.enqueue(new IncomingRequest(3, 3, 1L));
            rig.proc.postSnapshot(snapshot(END, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "End disk submit");

            // A result from before the switch (overworld) plus an End sentinel behind it:
            // FIFO drain means the sentinel's payload proves the stale one was already handled.
            rig.inject(dataResult(rig.uuid, 3, 3, DIM, new byte[]{9}, COLUMN_TS, 1L));
            rig.inject(dataResult(rig.uuid, 4, 4, END, new byte[]{7}, COLUMN_TS, 2L));
            rig.proc.postSnapshot(snapshot(END, rig.uuid), List.of());
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "sentinel End payload");

            var sentinel = rig.proc.enqueuedColumns.poll();
            assertEquals(4, sentinel.cx());
            assertEquals(4, sentinel.cz());
            assertEquals(END, sentinel.dimension());
            assertTrue(rig.proc.enqueuedColumns.isEmpty(),
                    "stale-dimension result must not produce a payload");
            assertEquals(1, rig.state.getHeldSyncSlots(),
                    "stale result must not resolve the live End pending");
            assertFalse(rig.state.hasDiskReadDone(3, 3),
                    "stale result must not mark the position served");

            // The position still resolves when the real End result arrives
            rig.inject(dataResult(rig.uuid, 3, 3, END, new byte[]{5}, COLUMN_TS + 1, 3L));
            rig.proc.postSnapshot(snapshot(END, rig.uuid), List.of());
            waitFor(() -> !rig.proc.enqueuedColumns.isEmpty(), "real End payload");
            var col = rig.proc.enqueuedColumns.poll();
            assertEquals(3, col.cx());
            assertEquals(3, col.cz());
            assertEquals(END, col.dimension());
            assertEquals(0, rig.state.getHeldSyncSlots(), "real End result resolves the pending");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void staleDimensionGenerationOutcomeIsInert() throws Exception {
        var rig = new Rig(true);
        try {
            // Live End pending at (6,6)
            rig.state.enqueue(new IncomingRequest(6, 6, 0L));
            rig.proc.postSnapshot(snapshot(END, rig.uuid), List.of());
            waitFor(() -> rig.proc.diskSubmits.size() == 1, "End disk submit");

            // Stale overworld outcome for the same position, with an End sentinel behind it
            // in the same lossless event batch (processed in order within one cycle)
            var staleData = new LoadedColumnData(6, 6, new byte[]{1},
                    1 + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
            rig.proc.postSnapshot(snapshot(END, rig.uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(rig.uuid, 6, 6, DIM, staleData, COLUMN_TS, 5L),
                    new TickSnapshot.GenerationReadyData(rig.uuid, 7, 7, END, null, 0L, 6L))));

            drainUntil(rig.proc, received(LSSConstants.RESPONSE_NOT_GENERATED,
                    PositionUtil.packPosition(7, 7)));
            assertEquals(1, rig.state.getHeldSyncSlots(),
                    "stale outcome must not resolve the live End pending");
            assertFalse(rig.state.hasDiskReadDone(6, 6),
                    "stale outcome must not mark the position served");
            assertTrue(rig.proc.enqueuedColumns.isEmpty(),
                    "stale outcome must not enqueue its column");
        } finally {
            rig.proc.shutdown();
        }
    }
}
