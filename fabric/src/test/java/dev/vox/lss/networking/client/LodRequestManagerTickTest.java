package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.LSSClientConfig;
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
        // Slow start off for this suite (join-slow-start-plan.md §1.4 — the frontier-
        // damping test pattern): these pins assert UNCAPPED first walks and cadence
        // shapes; productionDefaultEnablesSlowStart pins the real default wiring.
        manager.joinSlowStartEnabled = () -> false;
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
            int n = manager.tickScanPhase(playerCx, playerCz, viewDistance, 0, 0L, -1, () -> 0);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    // ---- first-tick movement quirk (CL-002, pinned) ----

    @Test
    void firstTickAtChunkOriginRunsThePrimedScanImmediately() {
        manager.tickWithContext(0, 0, dim("overworld"), 0, 0, 0L, -1, () -> 0);

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

        manager.tickWithContext(10, 0, overworld, 0, 0, 0L, -1, () -> 0);
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
            manager.tickWithContext(cx, 0, overworld, 0, 0, 0L, -1, () -> 0);
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
        // Stamp far directly: manager.onColumnReceived range-guards ingress, so a (500,500)
        // stamp from center (0,0) would be silently dropped and the prune assertion vacuous.
        manager.columnsForTest().onReceived(far, 5000L);
        manager.onColumnReceived(near, 5000L, overworld);
        manager.trackerForTest().replaceWith(new long[]{far}, 1);

        // Movement past the hysteresis threshold (≥ PRUNE_HYSTERESIS_CHUNKS): prune at lod+32 = 34.
        manager.tickWithContext(LodRequestManager.PRUNE_HYSTERESIS_CHUNKS + 1, 0, overworld,
                64, 0, 0L, -1, () -> 0);

        assertEquals(-1L, manager.columnsForTest().timestampFor(far),
                "out-of-range stamp pruned (re-requested as unknown when back in range)");
        assertFalse(manager.trackerForTest().isInFlight(far),
                "out-of-range in-flight tracking pruned with it — no slot leak");
        assertEquals(5000L, manager.columnsForTest().timestampFor(near), "in-range state survives");
        assertEquals(1, manager.getReceivedColumnCount());
    }

    @Test
    void movementPruneDefersBelowTheHysteresisThreshold() {
        // 2026-08-05 review P2: the prunes are full-state iterations and memory-bounding
        // only — a sub-threshold crossing must NOT pay them (a dropped frame per crossing
        // in flight), while the state stays intact and a later threshold-crossing prune
        // catches up. The scan recenter itself is not deferred (pinned elsewhere).
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        // Stamp far directly (bypassing the ingress range guard) so the survive/prune
        // assertions below observe the real prune, not a never-stamped position. The
        // tracker is deliberately not asserted here: the scan firing on these ticks
        // replaces it wholesale, which would mask (or fake) the prune either way.
        manager.columnsForTest().onReceived(far, 5000L);

        manager.tickWithContext(3, 0, overworld, 64, 0, 0L, -1, () -> 0); // below threshold

        assertEquals(5000L, manager.columnsForTest().timestampFor(far),
                "sub-threshold crossing defers the prune — the stamp survives");

        // Accumulated travel crosses the threshold: the deferred prune fires and catches up.
        manager.tickWithContext(LodRequestManager.PRUNE_HYSTERESIS_CHUNKS, 0, overworld,
                64, 0, 0L, -1, () -> 0);

        assertEquals(-1L, manager.columnsForTest().timestampFor(far),
                "accumulated ≥ threshold travel prunes what the deferral kept");
    }

    @Test
    void dimensionChangeResetsAllRequestStateWithoutSilentOrphans() {
        var overworld = dim("overworld");
        var end = dim("the_end");
        setupManager(config(2, true), "lss-cl025-" + System.nanoTime());
        manager.tickWithContext(0, 0, overworld, 64, 0, 0L, -1, () -> 0); // establish dimension A
        long stamped = PositionUtil.packPosition(1, 1);
        long inFlightOnly = PositionUtil.packPosition(2, 1);
        manager.onColumnReceived(stamped, 5000L, overworld);
        manager.trackerForTest().replaceWith(new long[]{inFlightOnly}, 1);

        manager.tickWithContext(0, 0, end, 64, 0, 0L, -1, () -> 0); // the flip

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
        manager.tickWithContext(0, 0, overworld, 0, halt, 0L, -1, () -> 0); // exactly at threshold: halted

        assertEquals(-1L, manager.columnsForTest().timestampFor(cached),
                "cache poll skipped while backpressured");
        assertEquals(1, sent.size(),
                "entering the halt sends the empty backpressure clear — silence would leave the"
                        + " server pumping the last want-set (up to 1024 backlogged asks)");
        assertEquals(0, sent.get(0).count(), "no want-set is declared while halted, only the clear");

        manager.tickWithContext(0, 0, overworld, 0, halt - 1, 0L, -1, () -> 0); // one below: all resume

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

        manager.tickWithContext(0, 0, overworld, 0, 0, byteHalt, -1, () -> 0); // count 0, bytes at threshold

        assertEquals(1, sent.size(), "byte-dimension halt sends the empty backpressure clear");
        assertEquals(0, sent.get(0).count(), "no want-set is declared while byte-halted");

        manager.tickWithContext(0, 0, overworld, 0, 0, byteHalt - 1, -1, () -> 0); // one below: resumes

        assertEquals(2, sent.size(), "the scan resumes one byte below the threshold");
        assertTrue(sent.get(1).count() > 0, "a real want-set is declared again after recovery");
    }

    // ---- consumer ingest-backlog halt (issue #71) ----

    @Test
    void ingestBacklogHaltsDeclarationsWithOneEdgeTriggeredClearPerCrossing() {
        var overworld = dim("overworld");
        int haltSections = LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        assertEquals(6144, haltSections,
                "pinned threshold: the consumer ingest-backlog halt point");

        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, haltSections, () -> 0); // at threshold
        assertEquals(1, sent.size(), "entering the ingest halt sends the empty clear");
        assertEquals(0, sent.get(0).count(), "the clear is an empty want-set");

        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, haltSections + 500, () -> 0); // deeper in
        assertEquals(1, sent.size(), "edge-triggered: no second clear inside the same crossing");

        // Cause-switch inside the episode: a decode-queue-halted tick while the flag is
        // still set is the SAME episode — one flag, one clear, regardless of which signal
        // holds the halt (the design's "per crossing", not "per cause").
        manager.tickWithContext(0, 0, overworld, 0, LodRequestManager.haltThreshold(), 0L,
                -1, () -> 0);
        assertEquals(1, sent.size(), "switching halt cause must not restart the episode");

        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, haltSections - 1, () -> 0); // recovery
        assertEquals(2, sent.size(), "the scan resumes one section below the halt");
        assertEquals(1, sent.get(1).count(),
                "just below the halt the taper floors the budget at 1 — the shrunken want-set"
                        + " REPLACES the server backlog, an active brake while the consumer drains");

        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, haltSections, () -> 0); // re-cross
        assertEquals(3, sent.size(), "a re-crossing re-arms and re-sends the clear");
        assertEquals(0, sent.get(2).count(), "the re-cross clear is empty again");
    }

    @Test
    void noSignalIngestBacklogNeverHalts() {
        var overworld = dim("overworld");
        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, -1, () -> 0);
        assertEquals(1, sent.size(), "no-signal (-1) ticks scan normally");
        assertTrue(sent.get(0).count() > 0, "a real want-set is declared, not a clear");
        assertEquals(-1, manager.getLastIngestBacklog(), "the polled value is surfaced for diag");
    }

    @Test
    void productionBacklogSupplierReadsTheApiAggregateBehindTheConfigGate() {
        // Wiring pin (the seam's DEFAULT is production): tick() polls
        // LSSApi.maxReportedIngestBacklog() gated on enableIngestBackpressure — a revert of
        // either half of that expression reds this test without a running client.
        var reporting = new dev.vox.lss.api.VoxelColumnConsumer() {
            @Override
            public void onVoxelColumnReceived(net.minecraft.client.multiplayer.ClientLevel level,
                                              ResourceKey<Level> dimension, int chunkX, int chunkZ,
                                              dev.vox.lss.api.VoxelColumnData columnData) {}

            @Override
            public int pendingIngestBacklog() { return 4321; }
        };
        dev.vox.lss.api.LSSApi.registerColumnConsumer(reporting);
        boolean previous = dev.vox.lss.config.LSSClientConfig.CONFIG.enableIngestBackpressure;
        try {
            dev.vox.lss.config.LSSClientConfig.CONFIG.enableIngestBackpressure = true;
            assertEquals(4321, manager.ingestBacklogSupplier.getAsInt(),
                    "the production supplier must surface the LSSApi aggregate");
            dev.vox.lss.config.LSSClientConfig.CONFIG.enableIngestBackpressure = false;
            assertEquals(-1, manager.ingestBacklogSupplier.getAsInt(),
                    "the kill switch must force no-signal");
        } finally {
            dev.vox.lss.config.LSSClientConfig.CONFIG.enableIngestBackpressure = previous;
            dev.vox.lss.api.LSSApi.removeColumnConsumer(reporting);
        }
    }

    @Test
    void movementPruneStillRunsOnABackpressuredTick() {
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        // Direct stamp: the ingress range guard would drop a (500,500) receive from (0,0),
        // leaving the pruned-to--1 assertion vacuously true.
        manager.columnsForTest().onReceived(far, 5000L);

        manager.tickWithContext(LodRequestManager.PRUNE_HYSTERESIS_CHUNKS + 1, 0, overworld, 64,
                ClientColumnProcessor.MAX_QUEUED_COLUMNS * 3 / 4, 0L, -1, () -> 0);

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
        manager.tickWithContext(300, 0, overworld, 0, 0, 0L, -1, () -> 0);

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

    // ---- adaptive cadence through the production tick path ----
    // (docs/planning/adaptive-scan-cadence-design.md — the wiring pins: tracker::size
    // supplier from the constructor, arming in tickScanPhase beside the tracker replace.)

    /**
     * Pin the kill-switch seam ON for this rig: the default seam reads the global
     * LSSClientConfig.CONFIG, which in a dev Tier-1 JVM resolves to the developer's
     * LOCAL (gitignored) config file — a local false would red these tests confusingly.
     * killSwitchBindsThroughTheProductionConfigRead pins the default binding itself.
     */
    private void enableAdaptiveSeam() {
        manager.scannerForTest().adaptiveCadenceEnabled = () -> true;
    }

    /** One plain tick at (0,0)/vd 0 with no pressure signals. */
    private void plainTick(ResourceKey<Level> dim) {
        manager.tickWithContext(0, 0, dim, 0, 0, 0L, -1, () -> 0);
    }

    /** Answer count positions of the batch as received column data. */
    private void answer(SentBatch batch, ResourceKey<Level> dim, int count) {
        for (int i = 0; i < count; i++) {
            manager.onColumnReceived(batch.positions()[i], 5000L, dim);
        }
    }

    /** Ticks until a batch beyond {@code already} ships; returns the 1-based tick count. */
    private int ticksToNextBatch(ResourceKey<Level> dim, int already) {
        for (int t = 1; t <= LSSConstants.TICKS_PER_SECOND; t++) {
            plainTick(dim);
            if (sent.size() > already) return t;
        }
        throw new AssertionError("no further batch within a fallback window");
    }

    @Test
    void nearlyAnsweredBatchFastFiresThroughTheRealTickPath() {
        enableAdaptiveSeam();
        var overworld = dim("overworld");
        plainTick(overworld); // primed scan: the 24-position lod-2/vd-0 annulus
        assertEquals(1, sent.size());
        answer(sent.get(0), overworld, 23); // 1 of 24 outstanding <= threshold 24/20 = 1

        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, ticksToNextBatch(overworld, 1),
                "the production wiring (constructor supplier + tickScanPhase arming) fast-fires"
                        + " at the floor once the batch is >=95% answered");
        assertEquals(1, sent.get(1).count(),
                "the fast walk re-declares exactly the one still-unanswered position");
    }

    @Test
    void partiallyAnsweredBatchStaysPeriodic() {
        enableAdaptiveSeam();
        // The supplier-wiring pin (the #71 pattern): if the scanner consulted anything but
        // the live tracker::size, the 12 unanswered positions would read as drained and
        // this would fast-fire at tick 5.
        var overworld = dim("overworld");
        plainTick(overworld);
        assertEquals(1, sent.size());
        answer(sent.get(0), overworld, 12); // 12 of 24 outstanding — far above threshold 1

        assertEquals(LSSConstants.TICKS_PER_SECOND, ticksToNextBatch(overworld, 1),
                "a half-answered batch rides the 20-tick fallback");
    }

    @Test
    void sendFailureDisarmsTheFastCadence() {
        enableAdaptiveSeam();
        var overworld = dim("overworld");
        // The first scan's send THROWS: sendRequests empties the tracker (replaceWith 0),
        // which without the catch's disarm would read as "all answered" and turn a dying
        // connection into a 4 Hz retry hammer.
        manager.setBatchSenderForTest(p -> { throw new RuntimeException("wire down"); });
        plainTick(overworld);
        assertEquals(0, sent.size(), "the batch never reached the wire");

        manager.setBatchSenderForTest(p -> sent.add(new SentBatch(
                Arrays.copyOf(p.packedPositions(), p.count()),
                Arrays.copyOf(p.clientTimestamps(), p.count()),
                p.count())));
        assertEquals(LSSConstants.TICKS_PER_SECOND, ticksToNextBatch(overworld, 0),
                "the retry rides the 1 Hz fallback, exactly as before the adaptive cadence");
    }

    @Test
    void disconnectDisarmsTheFastCadence() {
        enableAdaptiveSeam();
        var overworld = dim("overworld");
        plainTick(overworld);
        assertEquals(1, sent.size());
        manager.disconnect(); // clears the tracker: outstanding reads 0 against an armed count

        assertEquals(LSSConstants.TICKS_PER_SECOND, ticksToNextBatch(overworld, 1),
                "disconnect() disarms — the defensive teardown must not leave a fast trigger"
                        + " armed over an emptied awaiting set");
    }

    @Test
    void pressureAboveTheQuarterLineHoldsFastFires() {
        // The sharp pressure-gate pin through the production path, WITHOUT a halt (a halt
        // ships the clear batch, whose own disarm would mask this gate — see the next
        // test): queue at exactly 1/4 of the halt threshold, far below the halt itself,
        // keeps an otherwise fully fast-eligible client at the periodic cadence.
        enableAdaptiveSeam();
        var overworld = dim("overworld");
        int quarter = LodRequestManager.haltThreshold() / SpiralScanner.FAST_RESCAN_PRESSURE_DIVISOR;
        manager.tickWithContext(0, 0, overworld, 0, quarter, 0L, -1, () -> 0); // primed scan
        assertEquals(1, sent.size());
        answer(sent.get(0), overworld, 23); // fast-eligible by outstanding (1 <= 24/20)

        int ticksToNext = -1;
        for (int t = 1; t <= LSSConstants.TICKS_PER_SECOND; t++) {
            manager.tickWithContext(0, 0, overworld, 0, quarter, 0L, -1, () -> 0);
            if (sent.size() > 1) { ticksToNext = t; break; }
        }
        assertEquals(LSSConstants.TICKS_PER_SECOND, ticksToNext,
                "queue at the 1/4 line: the fast fire is held and the fallback declares"
                        + " (load-bearing now that the gate is proportional, not strict zero)");
    }

    @Test
    void backpressureClearDisarmsTheFastCadence() {
        // The clear batch is a real (empty) declaration that bypasses tickScanPhase — it
        // must disarm like any other declare. Without sendClearBatch's noteDeclared(0),
        // the recovery tick below (queue fully drained, outstanding 1 <= threshold) would
        // fast-fire straight out of the halt: a re-declare storm against a server backlog
        // the clear just emptied.
        enableAdaptiveSeam();
        var overworld = dim("overworld");
        plainTick(overworld); // primed scan
        answer(sent.get(0), overworld, 23); // fast-eligible by outstanding (1 <= 24/20)

        // Climb to one tick short of the floor, then halt: the halt path returns before
        // the scan phase (counter frozen) and ships the one edge-triggered clear.
        for (int t = 0; t < SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS - 1; t++) plainTick(overworld);
        for (int t = 0; t < 3; t++) {
            manager.tickWithContext(0, 0, overworld, 0, LodRequestManager.haltThreshold(), 0L, -1, () -> 0);
        }
        assertEquals(2, sent.size(), "halted ticks never scan — only the one clear ships");
        assertEquals(0, sent.get(1).count(), "...and it is the empty backpressure clear");

        // Recovery with the queue FULLY drained: no pressure, outstanding below threshold —
        // only the clear's disarm can (and must) hold the fast path. The counter was
        // frozen at floor-1 through the halt, so the fallback lands 20-(floor-1) recovery
        // ticks later; a missing disarm would instead fast-fire on recovery tick 1.
        int ticksToNext = -1;
        for (int t = 1; t <= LSSConstants.TICKS_PER_SECOND; t++) {
            plainTick(overworld);
            if (sent.size() > 2) { ticksToNext = t; break; }
        }
        assertEquals(LSSConstants.TICKS_PER_SECOND - (SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS - 1),
                ticksToNext,
                "the clear disarmed: the next declaration rides the fallback, never a 250 ms"
                        + " re-declare right after the backlog was deliberately emptied");
        assertEquals(1, sent.get(2).count(), "…and it re-declares the one still-unanswered position");
    }

    @Test
    void convergedFastWalkDisarmsThroughTheManagerArming() {
        // THE safety property, pinned through the PRODUCTION arming path (the scanner-level
        // twin arms by hand): tickScanPhase's noteDeclared(scanned) must stay UNCONDITIONAL.
        // An `if (scanned > 0)` guard on it — the obvious "optimization" — would leave the
        // scanner armed over an empty tracker after a converged walk: a silent 250 ms
        // full-spiral walk loop that no sent-batch assertion can ever see.
        enableAdaptiveSeam();
        var overworld = dim("overworld");
        plainTick(overworld);
        assertEquals(1, sent.size());
        answer(sent.get(0), overworld, sent.get(0).count()); // fully answered: converged

        long fastBefore = manager.getFastScans();
        for (int t = 0; t < SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS; t++) plainTick(overworld);
        assertEquals(fastBefore + 1, manager.getFastScans(),
                "the one converged fast walk happens at the floor...");
        assertEquals(1, sent.size(), "...and sends nothing (convergence invariant)");

        for (int t = 0; t < LSSConstants.TICKS_PER_SECOND - 1; t++) plainTick(overworld);
        assertEquals(fastBefore + 1, manager.getFastScans(),
                "...and its 0-count declare disarmed: no further fast walk in the fallback window");
        assertEquals(1, sent.size(), "a converged client stays silent throughout");
    }

    // ---- transfer governor wiring (adaptive-transfer-rate-plan.md, impl review M1:
    // ---- every one of these call sites could previously be unplugged with the whole
    // ---- unit suite green) ----

    /** Engage the manager's own governor directly with a known shape: measured
     *  400 KB/s over 8 KB columns → desired 272 KB/s, R = 34, burst = ceil(34/4) = 9. */
    private void engageGovernor() {
        manager.transferGovernorEnabled = () -> true; // the local-config note above
        manager.governor.tick(1, 0, 0, 0, 0, 1, false, 50, true);
        manager.governor.tick(1 + TransferRateGovernor.INTERVAL_MILLIS,
                800 * 1024, 100, 20_000, 20_000, 1, false, 2_000, true);
        manager.governor.tick(1 + 2 * TransferRateGovernor.INTERVAL_MILLIS,
                1600 * 1024, 200, 40_000, 40_000, 1, false, 2_000, true); // debounce
        assertTrue(manager.governor.isEngaged(), "rig engagement");
    }

    @Test
    void engagedGovernorShrinksTheDeclaredBatchThroughTheProductionWiring() {
        // The ctor's two supplier bindings, THIS way round: the budget site must read
        // the governed BURST (9), the spacing site the sustained R (34). Deleting
        // either binding declares the full 24-position annulus; SWAPPING the lambdas
        // declares min(24, 34) = 24 — both red here.
        var overworld = dim("overworld");
        enableAdaptiveSeam(); // the burst path is cadence-conditional (impl MAJOR-1)
        engageGovernor();
        plainTick(overworld);
        assertEquals(1, sent.size());
        assertEquals(9, sent.get(0).count(),
                "the governed burst cap (ceil(R/4)) must reach the scanner's budget site");
    }

    @Test
    void governorKillSwitchBindsThroughTheProductionConfigRead() {
        // The production binding pin (the adaptive-cadence pattern): the manager's
        // DEFAULT seam must read LSSClientConfig.CONFIG.enableAdaptiveTransferRate — a
        // hardcoded true would keep a governed cap alive with the shipped kill switch off.
        var overworld = dim("overworld");
        boolean old = LSSClientConfig.CONFIG.enableAdaptiveTransferRate;
        LSSClientConfig.CONFIG.enableAdaptiveTransferRate = false;
        try {
            manager.governor.tick(1, 0, 0, 0, 0, 1, false, 50, true);
            manager.governor.tick(1 + TransferRateGovernor.INTERVAL_MILLIS,
                    800 * 1024, 100, 20_000, 20_000, 1, false, 2_000, true);
            manager.governor.tick(1 + 2 * TransferRateGovernor.INTERVAL_MILLIS,
                    1600 * 1024, 200, 40_000, 40_000, 1, false, 2_000, true); // debounce
            assertTrue(manager.governor.isEngaged(), "rig engagement");
            plainTick(overworld); // the production tick reads config false → hard reset
            assertFalse(manager.governor.isEngaged(),
                    "config false must hard-reset through the DEFAULT seam");
            assertEquals(24, sent.get(0).count(), "no governed cap: the full annulus declares");
        } finally {
            LSSClientConfig.CONFIG.enableAdaptiveTransferRate = old;
        }
    }

    @Test
    void legacySessionExcludesTheGovernor() {
        // The v16 exclusion is a MANAGER conjunct (isLegacySession), not a governor
        // param — a legacy-fallback session's pacing is the drip-feed's own.
        setupManager(new SessionConfigS2CPayload(
                LSSConstants.V16_COMPAT_PROTOCOL_VERSION, true, 2, true));
        engageGovernor();
        plainTick(dim("overworld"));
        assertFalse(manager.governor.isEngaged(),
                "a v16 session must exclude (and hard-reset) the governor");
    }

    @Test
    void pingProbeFiresAtOneHertzOnlyWhileGovernorActive() {
        // Live round 5: the governor's congestion signal moved off the 30 s tab ping
        // onto our own 1 Hz probe — the traced runaway lasted exactly the tab-ping
        // blind spot. Pins the cadence (per second, never per tick) and the active
        // gate (an inactive governor sends no probes).
        var overworld = dim("overworld");
        long[] now = {5_000};
        int[] probes = {0};
        manager.governor.clock = () -> now[0];
        manager.transferGovernorEnabled = () -> true;
        manager.pingProbeSender = () -> probes[0]++;
        plainTick(overworld);
        plainTick(overworld);
        plainTick(overworld);
        assertEquals(1, probes[0], "same-millisecond ticks share one probe");
        now[0] = 5_999;
        plainTick(overworld);
        assertEquals(1, probes[0], "sub-second elapse must not probe");
        now[0] = 6_000;
        plainTick(overworld);
        assertEquals(2, probes[0], "the 1 Hz cadence fires on the second");
        manager.transferGovernorEnabled = () -> false;
        now[0] = 9_000;
        plainTick(overworld);
        assertEquals(2, probes[0], "an inactive governor sends no probes");
    }

    @Test
    void liveMissingVanillaSampleReachesTheGovernorAtProbeCadence() {
        // The vanilla-first wiring pin (round-5 shape): tickWithContext samples the
        // LIVE missingVanilla supplier on the 1 Hz probe cadence and feeds the
        // governor's floor/excess tracking — never the scanner's periodic cache
        // (unbounded staleness under fast cadence; stale-dimension seeds). Unplugged,
        // the final kept-up interval climbs (or movement-holds); wired, the 20→40
        // excess forces the vanilla-first CUT.
        var overworld = dim("overworld");
        engageGovernor();
        long engageEnd = 1 + 2 * TransferRateGovernor.INTERVAL_MILLIS;
        long[] now = {engageEnd};
        manager.governor.clock = () -> now[0];
        // First probe second: the floor sample (20)...
        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, -1, () -> 20);
        // ...next probe second: the excess view (40). Sub-second ticks in between
        // must NOT sample (the cadence gate).
        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, -1, () -> 999_999);
        now[0] = engageEnd + 1_000;
        manager.tickWithContext(0, 0, overworld, 0, 0, 0L, -1, () -> 40);
        long desired = manager.governor.getDesiredBytesPerSec();
        // A kept-up interval (measured = desired over EXACTLY one interval from the
        // engage evaluation, generous offer) evaluated directly: the wired governor
        // cuts below desired; unplugged it reads kept-up and CLIMBS (a longer elapsed
        // here would read as an offer-backed shortfall and cut unplugged too — the
        // vacuity this timing avoids).
        manager.governor.tick(engageEnd + TransferRateGovernor.INTERVAL_MILLIS,
                1600 * 1024 + desired * 2, 300, 60_000, 60_000, 1, false, 2_000, true);
        assertTrue(manager.governor.getDesiredBytesPerSec() < desired,
                "the live missing-vanilla sample must reach the governor and cut");
    }

    @Test
    void probeGateExcludesLegacySessionsAndHarnessJvms() {
        // The other two arms of the probe gate (round-5 review n5): a v16 session and
        // a harness JVM must both stay probe-silent.
        int[] probes = {0};
        setupManager(new SessionConfigS2CPayload(
                LSSConstants.V16_COMPAT_PROTOCOL_VERSION, true, 2, true));
        manager.transferGovernorEnabled = () -> true;
        manager.pingProbeSender = () -> probes[0]++;
        manager.governor.clock = () -> 5_000;
        plainTick(dim("overworld"));
        assertEquals(0, probes[0], "a legacy session never probes");
        System.setProperty("lss.soak", "true");
        try {
            setupManager(config(2, true));
            manager.transferGovernorEnabled = () -> true;
            manager.pingProbeSender = () -> probes[0]++;
            manager.governor.clock = () -> 5_000;
            plainTick(dim("overworld"));
            assertEquals(0, probes[0], "a harness JVM never probes");
        } finally {
            System.clearProperty("lss.soak");
        }
    }

    @Test
    void killSwitchBindsThroughTheProductionConfigRead() {
        // The production binding pin (the #71 config-gate pattern): the scanner's DEFAULT
        // seam must read LSSClientConfig.CONFIG.enableAdaptiveScanCadence — a hardcoded
        // `() -> true` or a re-bind to any other default-true boolean passes every other
        // test green while the shipped kill switch silently stops killing.
        var overworld = dim("overworld");
        boolean old = LSSClientConfig.CONFIG.enableAdaptiveScanCadence;
        LSSClientConfig.CONFIG.enableAdaptiveScanCadence = false;
        try {
            plainTick(overworld);
            assertEquals(1, sent.size());
            answer(sent.get(0), overworld, 23); // fast-eligible by every other condition

            assertEquals(LSSConstants.TICKS_PER_SECOND, ticksToNextBatch(overworld, 1),
                    "config false must hold the periodic cadence through the DEFAULT seam");
        } finally {
            LSSClientConfig.CONFIG.enableAdaptiveScanCadence = old;
        }
    }
    // ---- Join slow start: the production wiring pins (join-slow-start-plan.md §1.4) ----

    @Test
    void productionDefaultEnablesSlowStartAndClampsTheFirstWalk() {
        // The productionDefaultEnablesOutwardDamping pattern: every other test in this
        // suite disables slow start at setupManager; THIS one constructs the manager
        // with the real config-backed supplier and proves the default wiring — the
        // session starts ramped and the very first declaration is budget-clamped
        // (64 KB/s over the 32 KB pre-sample seed -> 2 col/s -> quarter-batch burst
        // cap 1), never the uncapped 800-position flood.
        var m = new LodRequestManager();
        m.onSessionConfig(config(8, true), "lss-slow-start-pin");
        m.markCacheLoadedForTest();
        var firstCounts = new java.util.ArrayList<Integer>();
        m.setBatchSenderForTest(p -> firstCounts.add(p.count()));
        m.tickWithContext(0, 0, dim("overworld"), 0, 0, 0L, -1, () -> 0);
        org.junit.jupiter.api.Assertions.assertTrue(
                m.getGovernedRateLabel().startsWith("ramp@"),
                "a fresh production-wired session must start in RAMP, got '"
                        + m.getGovernedRateLabel() + "'");
        org.junit.jupiter.api.Assertions.assertFalse(firstCounts.isEmpty(),
                "the ramped session still declares immediately (LODs begin at once)");
        org.junit.jupiter.api.Assertions.assertTrue(firstCounts.get(0) <= 2,
                "the first walk must be clamped by the ramp's burst cap, got "
                        + firstCounts.get(0));
        // Negative arm (impl review: without it a supplier hardcoded to true passes):
        // the same production wiring with the shipped toggle OFF declares uncapped.
        boolean old = LSSClientConfig.CONFIG.enableJoinSlowStart;
        LSSClientConfig.CONFIG.enableJoinSlowStart = false;
        try {
            var m2 = new LodRequestManager();
            m2.onSessionConfig(config(8, true), "lss-slow-start-pin-off");
            m2.markCacheLoadedForTest();
            var offCounts = new java.util.ArrayList<Integer>();
            m2.setBatchSenderForTest(p -> offCounts.add(p.count()));
            m2.tickWithContext(0, 0, dim("overworld"), 0, 0, 0L, -1, () -> 0);
            org.junit.jupiter.api.Assertions.assertFalse(offCounts.isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(offCounts.get(0) > 2,
                    "toggle off: the first walk is uncapped (the supplier reads config,"
                            + " not a constant), got " + offCounts.get(0));
        } finally {
            LSSClientConfig.CONFIG.enableJoinSlowStart = old;
        }
    }

    @Test
    void chunkCrossingHandsTheScannerTheRealCrossingDelta() {
        // The one line that supplies recenter(d) its delta (review round MAJOR: computing
        // it AFTER the anchor update makes every crossing recenter(0) — the prefix never
        // decrements, the crescent band never reopens, and every position exiting
        // vanilla's view behind a moving player becomes a permanent LOD hole while the
        // walk looks perfectly healthy; the entire scanner suite calls recenter(d)
        // directly and could not see it).
        var overworld = dim("overworld");
        setupManager(config(8, true));

        manager.tickWithContext(0, 0, overworld, 16, 0, 0L, -1, () -> 0);
        assertEquals(9, manager.getConfirmedRing(),
                "premise: the whole lod-8 disc is inside vd 16, so the primed walk confirms past it");

        manager.tickWithContext(1, 0, overworld, 16, 0, 0L, -1, () -> 0);
        assertEquals(8, manager.getConfirmedRing(),
                "a d=1 crossing decrements the prefix by exactly 1");

        manager.tickWithContext(4, 0, overworld, 16, 0, 0L, -1, () -> 0);
        assertEquals(5, manager.getConfirmedRing(),
                "a d=3 crossing decrements by 3 — the REAL delta reaches the scanner");
    }

    // ---- Window-limited latch wiring (ramp-window-limited-credit-plan.md §3.2) ----

    @Test
    void truncatedGovernedWalkLatchesOnFastFiresOnly() {
        // A RAMP-fresh governor's burst budget is 1 (64 KB/s over the 32 KB seed,
        // quartered) — the lod-8 disc truncates against it and the send succeeds.
        // The PRIMED first scan is a FALLBACK fire and must NOT latch (dynamics
        // review MAJOR-2: fallback-clocked full-budget re-declares on a backlogged
        // slow link would satisfy answeredAllAsked to ~5.3x the link rate — only
        // COMPLETION-CLOCKED fast fires may claim window-limited). Answering the
        // batch arms the fast path; the fast fire latches.
        setupManager(config(8, true));
        manager.joinSlowStartEnabled = () -> true;
        manager.transferGovernorEnabled = () -> true;
        enableAdaptiveSeam();
        var overworld = dim("overworld");
        plainTick(overworld);
        assertEquals(1, sent.size(), "the primed first scan shipped");
        assertEquals(1, sent.get(0).count(), "the governed burst budget clamped the walk to 1");
        assertFalse(manager.governor.windowLimitedLatched(),
                "the primed scan is a FALLBACK fire — fallback fires never latch");
        answer(sent.get(0), overworld, 1); // outstanding 0 → fast eligible
        // The spacing gate (sustained 2/s, lastSent 1) spaces the fast fire to
        // tick 10 — before the 20-tick fallback, so the fire is the FAST path.
        int ticks = ticksToNextBatch(overworld, 1);
        assertTrue(ticks < LSSConstants.TICKS_PER_SECOND,
                "premise: fired before the fallback window (tick " + ticks + ")");
        assertTrue(manager.scannerForTest().wasLastScanFast(),
                "premise: the follow-up fire is the FAST path");
        assertTrue(manager.governor.windowLimitedLatched(),
                "a successfully-sent, fast-fired, governed-cap-truncated walk latches");
    }

    @Test
    void failedSendNeverLatchesTheWindowLimit() {
        setupManager(config(8, true));
        manager.joinSlowStartEnabled = () -> true;
        manager.transferGovernorEnabled = () -> true;
        enableAdaptiveSeam();
        manager.setBatchSenderForTest(payload -> {
            throw new RuntimeException("transport down");
        });
        plainTick(dim("overworld"));
        assertFalse(manager.governor.windowLimitedLatched(),
                "nothing was offered — a failed send must not latch");
    }

    @Test
    void manualBelowTheGovernedHalfNeverLatches() {
        // The implementation panel's MAJOR-1 pin: with the adaptive cadence OFF the
        // scan's governed half is the FULL sustained rate (2 at a fresh RAMP), so a
        // manual knob of 1 is the binding clamp — and the latch must compare against
        // the SAME composed governed half. The pre-fix code re-read burst =
        // ceil(sustained/4) = 1 <= manual and latched a manually-capped walk.
        int prior = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
        LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = 1;
        try {
            setupManager(config(8, true));
            manager.joinSlowStartEnabled = () -> true; // live RAMP: governed half = 2
            manager.transferGovernorEnabled = () -> true;
            // Adaptive cadence forced OFF (the CONFIG default is on): the governed
            // half of the min-compose is sustainedColumnsPerSecond() = 2.
            manager.scannerForTest().adaptiveCadenceEnabled = () -> false;
            plainTick(dim("overworld"));
            assertEquals(1, sent.size());
            assertEquals(1, sent.get(0).count(), "the manual knob (1) clamped the walk");
            assertFalse(manager.governor.windowLimitedLatched(),
                    "manual (1) < the governed half (2): the manual knob was the binder");
        } finally {
            LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = prior;
        }
    }

    @Test
    void manualCapBindingNeverLatchesTheWindowLimit() {
        // The min-compose's manual half as the binder (governed burst 0 — slow start
        // off): the walk truncates against the MANUAL knob, and the latch's
        // governed-binding conjunct must refuse (a manually-capped loop never claims
        // to be governor-window-limited).
        int prior = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
        LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = 10;
        try {
            setupManager(config(8, true)); // joinSlowStartEnabled=false in setupManager
            manager.transferGovernorEnabled = () -> true;
            enableAdaptiveSeam();
            plainTick(dim("overworld"));
            assertEquals(1, sent.size());
            assertTrue(sent.get(0).count() <= 10, "the manual cap clamped the walk");
            assertFalse(manager.governor.windowLimitedLatched(),
                    "the manual knob was the binder — no governed window-limit claim");
        } finally {
            LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = prior;
        }
    }
}
