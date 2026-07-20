package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the tick phases ({@link LodRequestManager#tickWithContext}) — the fixed
 * phase order (dimension/cache → movement → metrics → backpressure → cache gate → scan+send),
 * the want-set batch cap, and the lifecycle exits (movement prune, dimension change, disconnect)
 * that must never silently orphan a position. Also pins the movement phase's cadence
 * DECOUPLING (see its javadoc): a chunk crossing re-centers the ring walk but never touches
 * the scan counter, so the primed first scan survives a join outside chunk (0,0) and
 * sustained fast movement keeps declaring on schedule — the pre-v17 movement debounce
 * starved re-declaration (the want-set's only self-heal) for as long as crossings outpaced
 * the 20-tick window, which stopped LOD generation entirely during creative flight.
 */
class LodRequestManagerTickTest {

    private LodRequestManager manager;

    /** Copies of sent batches — the manager reuses its send buffers across ticks. */
    record SentBatch(long[] positions, long[] timestamps, int count) {}

    private final List<SentBatch> sent = new ArrayList<>();

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        setupManager(config(2, true));
    }

    private void setupManager(SessionConfigS2CPayload cfg) {
        setupManager(cfg, "lss-tick-test");
    }

    private void setupManager(SessionConfigS2CPayload cfg, String serverAddress) {
        manager = new LodRequestManager();
        manager.onSessionConfig(cfg, serverAddress);
        manager.markCacheLoadedForTest();
        sent.clear();
        manager.setBatchSenderForTest(p -> sent.add(new SentBatch(
                Arrays.copyOf(p.packedPositions(), p.count()),
                Arrays.copyOf(p.clientTimestamps(), p.count()),
                p.count())));
    }

    private static SessionConfigS2CPayload config(int lodDistance, boolean generationEnabled) {
        return new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, generationEnabled);
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    /** Drive tickScanPhase until the 20-tick cadence fires; returns the scanned count. */
    private int fireScanPhase(int playerCx, int playerCz, int viewDistance) {
        for (int i = 0; i <= LSSConstants.TICKS_PER_SECOND; i++) {
            int n = manager.tickScanPhase(playerCx, playerCz, viewDistance, 0, () -> 0);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    // ---- first-tick movement quirk (CL-002, pinned) ----

    @Test
    void firstTickAtChunkOriginRunsThePrimedScanImmediately() {
        manager.tickWithContext(0, 0, dim("overworld"), 0, 0, 0L, () -> 0);

        assertEquals(1, sent.size(), "a (0,0) join keeps the primed immediate first scan");
        assertEquals(24, sent.get(0).count(),
                "the whole lod-2 / vd-0 annulus ships as ONE want-set batch in the scan's own tick"
                        + " — no drip-feed remainder trails it");
    }

    @Test
    void firstTickOutsideChunkOriginKeepsThePrimedImmediateScan() {
        // The movement branch fires on tick 1 for a player joining outside chunk (0,0)
        // (lastChunkX/Z init to (0,0)) — it must only prune + re-center, never cancel the
        // primed join scan. The old debounce here cost every such join ~1 s of LOD delay.
        var overworld = dim("overworld");

        manager.tickWithContext(10, 0, overworld, 0, 0, 0L, () -> 0);
        assertEquals(1, sent.size(),
                "the primed first scan fires on tick 1 even through the movement branch");
    }

    // ---- the cadence is decoupled from boundary crossings (CL-003 successor) ----

    @Test
    void chunkBoundaryCrossingFasterThanTheCadenceDoesNotStarveScans() {
        // Cross a chunk boundary every 10 ticks for 60 ticks — twice per cadence window.
        // The old movement debounce restarted the counter on every crossing, so this loop
        // used to produce ZERO scans (and with them zero re-declarations — the want-set's
        // only self-heal): sustained creative flight stopped LOD generation entirely. The
        // cadence is now free-running: the primed tick-1 scan plus one per full window,
        // each declared from the CURRENT center (replace semantics absorb the churn).
        var overworld = dim("overworld");

        for (int i = 0; i < 60; i++) {
            int cx = 1 - (i / 10) % 2;
            manager.tickWithContext(cx, 0, overworld, 0, 0, 0L, () -> 0);
        }
        assertEquals(3, sent.size(),
                "scans fire on schedule while moving: the primed tick-1 scan + ticks 21 and 41"
                        + " — a movement-restarted cadence would have produced zero");
    }

    // backoffSkippedScanStillSweepsTimeoutsAndBackoffsDoNotCompound is DELETED: there is no
    // rate-limit backoff to compound and no timeout sweep to ride the cadence.
    // sendPerTickDerivesFromScannedCountWithFloorSixteen is DELETED with the drip-feed: a scan's
    // want-set is sent whole in the scan's own tick, so there is no per-tick send cap to derive.

    // ---- the want-set cap (CL-021 successor) ----

    @Test
    void oneScanNeverDeclaresMoreThanTheProtocolBatchCap() {
        // The surviving half of CL-021: the send buffers are sized at MAX_BATCH_CHUNK_REQUESTS, so
        // a want-set larger than the cap would overflow them. The drip-feed used to enforce this at
        // the drain; the scanner now enforces it as a budget clamp, before it writes a single entry.
        setupManager(config(512, true)); // lod 512: the disc dwarfs any budget

        int scanned = fireScanPhase(0, 0, 0);

        assertEquals(LSSConstants.WANT_SET_BUDGET, scanned,
                "one scan declares exactly the constant want-set budget — which the invariant"
                        + " test pins under MAX_BATCH_CHUNK_REQUESTS, so the want-set always fits"
                        + " one wire frame (replace semantics tear across frames) and the fixed"
                        + " send buffers can never overflow");
        assertEquals(1, sent.size(), "one scan, one batch — never a split");
        assertEquals(LSSConstants.WANT_SET_BUDGET, sent.get(0).count());
    }

    // ---- lifecycle exits inform the state map — no silent orphans (CL-025 matrix rows) ----

    @Test
    void movementPruneDropsOutOfRangeColumnsAndTrackingTogether() {
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        long near = PositionUtil.packPosition(1, 1);
        manager.onColumnReceived(far, 5000L, overworld);
        manager.onColumnReceived(near, 5000L, overworld);
        manager.trackerForTest().replaceWith(new long[]{far}, 1);

        manager.tickWithContext(3, 0, overworld, 64, 0, 0L, () -> 0); // movement: prune at lod+32 = 34

        assertEquals(-1L, manager.columnsForTest().timestampFor(far),
                "out-of-range stamp pruned (re-requested as unknown when back in range)");
        assertFalse(manager.trackerForTest().isInFlight(far),
                "out-of-range in-flight tracking pruned with it — no slot leak");
        assertEquals(5000L, manager.columnsForTest().timestampFor(near), "in-range state survives");
        assertEquals(1, manager.getReceivedColumnCount());
    }

    @Test
    void dimensionChangeResetsAllRequestStateWithoutSilentOrphans() {
        var overworld = dim("overworld");
        var end = dim("the_end");
        setupManager(config(2, true), "lss-cl025-" + System.nanoTime());
        manager.tickWithContext(0, 0, overworld, 64, 0, 0L, () -> 0); // establish dimension A
        long stamped = PositionUtil.packPosition(1, 1);
        long inFlightOnly = PositionUtil.packPosition(2, 1);
        manager.onColumnReceived(stamped, 5000L, overworld);
        manager.trackerForTest().replaceWith(new long[]{inFlightOnly}, 1);

        manager.tickWithContext(0, 0, end, 64, 0, 0L, () -> 0); // the flip

        assertEquals(0, manager.getPendingCount(), "no awaited survivor can gate a status into the fresh dimension");
        assertEquals(0, manager.getReceivedColumnCount());
        assertEquals(-1L, manager.columnsForTest().timestampFor(stamped),
                "wholesale reset: the old stamp lives on only in the saved cache");
        assertEquals(-1L, manager.columnsForTest().classify(inFlightOnly),
                "a position in flight at the flip stays unknown -> re-requested, never orphaned");
        assertFalse(manager.columnsForTest().hasRetries(), "no stale retry marks leak into the fresh map");
        assertEquals(0, sent.size(), "nothing was sent around the flip");
        assertEquals(1, manager.getTotalColumnsReceived(),
                "lifetime totals survive the flip (soak A1/A6 anchors; only rolling rates reset)");
    }

    @Test
    void disconnectClearsInFlightTrackingButKeepsColumnStampsForTheCacheSave() {
        var overworld = dim("overworld");
        long stamped = PositionUtil.packPosition(1, 1);
        long inFlightOnly = PositionUtil.packPosition(2, 1);
        manager.setLastDimensionForTest(overworld);
        manager.onColumnReceived(stamped, 5000L, overworld);
        manager.trackerForTest().replaceWith(new long[]{inFlightOnly}, 1);

        manager.disconnect();

        assertEquals(0, manager.getPendingCount(), "disconnect drops the whole awaiting set");
        assertEquals(5000L, manager.columnsForTest().timestampFor(stamped),
                "stamps survive disconnect — the session-gate teardown saves them to the cache next");
        assertEquals(-1L, manager.columnsForTest().classify(inFlightOnly),
                "an answer-less awaited position stays unknown -> the next session re-requests it");
        assertFalse(manager.columnsForTest().hasRetries(),
                "no retry marks at disconnect: the session is over, nothing left to rescan");
    }

    // ---- backpressure halt order (CL-070) ----

    @Test
    void backpressureHaltsBeforeCachePollAndScanAndClearsTheServerBacklogOnce() {
        var overworld = dim("overworld");
        long cached = PositionUtil.packPosition(1, 1);
        var loaded = new Long2LongOpenHashMap();
        loaded.put(cached, 5000L);
        manager.setPendingCacheLoadForTest(CompletableFuture.completedFuture(loaded));

        int halt = ClientColumnProcessor.MAX_QUEUED_COLUMNS * 3 / 4;
        assertEquals(6000, halt, "pinned threshold: 3/4 of the 8000-column decode queue");
        assertEquals(halt, LodRequestManager.haltThreshold());
        manager.tickWithContext(0, 0, overworld, 0, halt, 0L, () -> 0); // exactly at threshold: halted

        assertEquals(-1L, manager.columnsForTest().timestampFor(cached),
                "cache poll skipped while backpressured");
        assertEquals(1, sent.size(),
                "entering the halt sends the empty backpressure clear — silence would leave the"
                        + " server pumping the last want-set (up to 1024 backlogged asks)");
        assertEquals(0, sent.get(0).count(), "no want-set is declared while halted, only the clear");

        manager.tickWithContext(0, 0, overworld, 0, halt - 1, 0L, () -> 0); // one below: all resume

        assertEquals(5000L, manager.columnsForTest().timestampFor(cached), "cache result applied");
        assertEquals(2, sent.size(), "the scan resumes");
        assertEquals(1, sent.get(1).count(),
                "one below the halt the queue-pressure scale floors the budget at 1. Under replace"
                        + " semantics that shrunken want-set is an ACTIVE brake, not just a slower"
                        + " ask rate: it REPLACES the server's whole backlog with a single entry,"
                        + " so the decode queue gets to drain before the next full declaration.");
    }

    @Test
    void backpressureAlsoHaltsOnTheByteDimensionOfTheQueue() {
        // Admission is bounded by count AND bytes (ClientColumnProcessor.admits); for columns
        // above ~44 KiB the 256 MiB byte cap binds before the 6000-count halt. A count-only
        // halt would let arrivals hit the byte-cap DROP path instead — each drop burns an
        // ingest-failure strike and four park the position for the whole session. The halt
        // must therefore fire at 3/4 of EITHER cap, keeping the designed halt+clear ahead
        // of the drop regime regardless of column size.
        var overworld = dim("overworld");
        long byteHalt = LodRequestManager.byteHaltThreshold();
        assertEquals(192L * 1024 * 1024, byteHalt,
                "pinned threshold: 3/4 of the 256 MiB decode-queue byte cap");

        manager.tickWithContext(0, 0, overworld, 0, 0, byteHalt, () -> 0); // count 0, bytes at threshold

        assertEquals(1, sent.size(), "byte-dimension halt sends the empty backpressure clear");
        assertEquals(0, sent.get(0).count(), "no want-set is declared while byte-halted");

        manager.tickWithContext(0, 0, overworld, 0, 0, byteHalt - 1, () -> 0); // one below: resumes

        assertEquals(2, sent.size(), "the scan resumes one byte below the threshold");
        assertTrue(sent.get(1).count() > 0, "a real want-set is declared again after recovery");
    }

    @Test
    void movementPruneStillRunsOnABackpressuredTick() {
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        manager.onColumnReceived(far, 5000L, overworld);

        manager.tickWithContext(3, 0, overworld, 64,
                ClientColumnProcessor.MAX_QUEUED_COLUMNS * 3 / 4, 0L, () -> 0);

        assertEquals(-1L, manager.columnsForTest().timestampFor(far),
                "the movement prune precedes the backpressure halt — state never goes stale"
                        + " just because the decode queue is full");
    }

    // ---- teleport drops stale wants outright (CL-071 successor) ----

    @Test
    void teleportDropsStaleWantsFromTheNextBatch() {
        // CL-071 pinned that a queue entry committed BEFORE a teleport was exempt from the movement
        // prune, still went out once, and was absorbed by the server's distance guard (bounded by
        // in-flight tracking to one ask). The want-set removes the exemption AND the ask: there is
        // no committed queue to survive the teleport — every scan writes the window fresh from the
        // player's current position, so a stale want simply never appears in a batch again. Any
        // already-declared stale entry is destroyed server-side by the next batch's replace.
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        manager.onColumnReceived(far, 5000L, overworld);

        // The teleport tick: movement phase (prune + recenter) runs BEFORE the scan phase,
        // and the cadence is decoupled from movement, so the primed scan fires on this very
        // tick — already from the NEW position, already without the pruned want.
        manager.tickWithContext(300, 0, overworld, 0, 0, 0L, () -> 0);

        assertEquals(-1L, manager.columnsForTest().timestampFor(far), "premise: column state pruned");
        assertEquals(1, sent.size(),
                "the teleport tick's own scan declares from the NEW position — the movement"
                        + " phase pruned first, so nothing stale can ride the same tick");
        var batch = sent.get(0);
        for (int i = 0; i < batch.count(); i++) {
            long p = batch.positions()[i];
            assertNotEquals(far, p, "a pre-teleport want must never appear in a post-teleport batch");
            int cheb = Math.max(Math.abs(PositionUtil.unpackX(p) - 300),
                    Math.abs(PositionUtil.unpackZ(p)));
            assertTrue(cheb <= 2, "every declared position is inside the NEW window; got cheb=" + cheb);
        }
    }

    // ---- generation toggle suppression at want-set level (CG-029) ----

    @Test
    void notGeneratedParksPermanentlyAndLegacyZeroStampsDeclareAsNoData() {
        // The client never classifies generation anymore (server-owned generation): a
        // NOT_GENERATED answer parks the position for the session regardless of the
        // generationEnabled flag, and NO ask ever leaves the client with ts==0.
        setupManager(config(2, false));
        long notGen = PositionUtil.packPosition(1, 1);
        manager.trackerForTest().replaceWith(new long[]{notGen}, 1);
        manager.onColumnNotGenerated(notGen); // permanent session-satisfy

        assertEquals(23, fireScanPhase(0, 0, 0),
                "the parked position is excluded from the scan (24-position annulus minus it)");
        for (var batch : sent) {
            for (int i = 0; i < batch.count(); i++) {
                assertNotEquals(0L, batch.timestamps()[i], "the client never emits ts==0");
                assertNotEquals(notGen, batch.positions()[i], "the parked position is never asked");
            }
        }

        // A legacy cache 0-stamp (written by a released pre-server-owned-generation client)
        // re-declares as -1 next session — never as 0, and never silently SATISFIED (R5).
        setupManager(config(2, true));
        var restored = new Long2LongOpenHashMap();
        restored.put(notGen, 0L);
        manager.columnsForTest().loadFrom(restored);
        assertEquals(24, fireScanPhase(0, 0, 0),
                "the legacy 0-stamp re-enters the scan as an ordinary no-data want");
        boolean declared = false;
        for (var batch : sent) {
            for (int i = 0; i < batch.count(); i++) {
                assertNotEquals(0L, batch.timestamps()[i], "the client never emits ts==0");
                if (batch.positions()[i] == notGen) {
                    assertEquals(-1L, batch.timestamps()[i], "a legacy 0-stamp declares as -1");
                    declared = true;
                }
            }
        }
        assertTrue(declared, "the legacy-stamp position went on the wire as -1");
    }

    // ---- requested total counts every DECLARED entry (HD-040 manager half) ----

    @Test
    void totalPositionsRequestedCountsEveryDeclaredEntry() {
        // requested_total changes meaning with the want-set: it is now "want-set entries DECLARED",
        // so an unanswered position is counted again on every 1 Hz re-declare. It inflates during
        // activity, and that inflation is exactly what the re-derived soak law A1 balances against
        // (the old law counted distinct first-asks). Do not "fix" this back to distinct positions.
        assertEquals(0, manager.getTotalPositionsRequested());
        assertEquals(0, manager.getTotalSendCycles());

        assertEquals(24, fireScanPhase(0, 0, 0), "scan 1 declares the annulus");
        assertEquals(24, manager.getTotalPositionsRequested());
        assertEquals(1, manager.getTotalSendCycles());

        assertEquals(24, fireScanPhase(0, 0, 0), "scan 2 re-declares the same unanswered positions");

        assertEquals(48, manager.getTotalPositionsRequested(),
                "a re-declared position counts TWICE — requested_total counts declarations, not"
                        + " distinct positions");
        assertEquals(2, manager.getTotalSendCycles());
    }
}
