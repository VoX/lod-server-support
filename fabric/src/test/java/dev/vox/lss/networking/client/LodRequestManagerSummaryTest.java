package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.region.RegionSummaryWire;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The manager half of region-summary sync (region-summary-sync-plan.md §6): the
 * at-entry request (both dimension-entry sites, correct center/radius, fire-and-forget),
 * its three gates (client kill switch, harness property gate, CURRENT dialect), and the
 * S2C frame ladder — apply-side kill switch, dimension binding, malformed containment,
 * the buffered apply behind an in-flight cache load (latest wins, failure re-applies
 * FIRST), and the attributability counters. The per-column validation semantics live in
 * {@link ColumnStateMapTest}'s tile-validation section.
 */
class LodRequestManagerSummaryTest {

    private static final long POS = PositionUtil.packPosition(10, -3);
    private static final int POS_TILE_X = 10 >> 5;   // 0
    private static final int POS_TILE_Z = -3 >> 5;   // -1

    private LodRequestManager manager;
    private final List<byte[]> requests = new ArrayList<>();

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        manager = new LodRequestManager();
        manager.joinSlowStartEnabled = () -> false;
        manager.onSessionConfig(new SessionConfigS2CPayload(
                LSSConstants.PROTOCOL_VERSION, true, 2, true),
                "lss-summary-test-" + System.nanoTime());
        requests.clear();
        manager.setSummarySenderForTest(requests::add);
        manager.summaryHarnessGate = () -> false;
        manager.summarySessionVersion = () -> LSSConstants.PROTOCOL_VERSION;
        manager.setBatchSenderForTest(p -> { });
    }

    @AfterEach
    void restoreConfig() {
        LSSClientConfig.CONFIG.enableRegionSummarySync = true;
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("lss_test:" + name));
    }

    private void tick(int cx, int cz, ResourceKey<Level> d) {
        manager.tickWithContext(cx, cz, d, 0, 0, 0L, -1, () -> 0);
    }

    /** The codec's zigzag-varlong shape (its own writer is package-private). */
    private static void zigVarLong(dev.vox.lss.common.wire.WireBytes.Writer w, long value) {
        long z = (value << 1) ^ (value >> 63);
        while ((z & ~0x7FL) != 0) {
            w.writeByte((int) ((z & 0x7F) | 0x80));
            z >>>= 7;
        }
        w.writeByte((int) z);
    }

    /** A radius-0 frame for POS's tile carrying one stamp. */
    private static byte[] frame(String dimension, long stamp) {
        return RegionSummaryWire.encodeSummary(new RegionSummaryWire.Summary(
                dimension, POS_TILE_X, POS_TILE_Z, 0, new long[]{stamp}));
    }

    // ---- the at-entry request ----

    @Test
    void initialEntryAndDimensionChangeBothFireTheRequest() {
        var overworld = dim("overworld");
        tick(100, -200, overworld); // first tick = the initial-load site
        assertEquals(1, requests.size(), "the initial dimension entry requests a summary");
        var req = RegionSummaryWire.decodeRequest(requests.get(0));
        assertEquals("lss_test:overworld", req.dimension());
        assertEquals(100 >> 5, req.centerTileX(), "center = the player's own tile");
        assertEquals(-200 >> 5, req.centerTileZ());
        assertEquals((2 + 31) / 32 + 1, req.tileRadius(),
                "radius = ceil(effective lod distance / 32) + 1");

        tick(100, -200, overworld);
        assertEquals(1, requests.size(), "same dimension = no re-request");

        tick(8, 8, dim("the_end")); // the dimension-change site
        assertEquals(2, requests.size(), "a dimension change re-requests");
        assertEquals("lss_test:the_end",
                RegionSummaryWire.decodeRequest(requests.get(1)).dimension());
    }

    @Test
    void allThreeRequestGatesHold() {
        LSSClientConfig.CONFIG.enableRegionSummarySync = false;
        tick(0, 0, dim("overworld"));
        assertTrue(requests.isEmpty(), "the client kill switch stops the request");

        setUp(); // fresh manager
        manager.summaryHarnessGate = () -> true;
        tick(0, 0, dim("overworld"));
        assertTrue(requests.isEmpty(),
                "harness clients never request — no soak baseline can shift");

        setUp();
        manager.summarySessionVersion = () -> 18;
        tick(0, 0, dim("overworld"));
        assertTrue(requests.isEmpty(), "legacy dialects never request (CURRENT only)");
    }

    @Test
    void aThrowingSenderIsContained() {
        manager.setSummarySenderForTest(body -> {
            throw new IllegalStateException("channel gone");
        });
        assertDoesNotThrow(() -> tick(0, 0, dim("overworld")),
                "fire-and-forget: a send failure is today's behavior, never a tick error");
    }

    // ---- the S2C frame ladder ----

    private void seedStamped(ResourceKey<Level> d, long stamp) {
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(d);
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, stamp);
        manager.columnsForTest().loadFrom(loaded);
    }

    private static byte[] stampsFrame(String dimension, long pos, long second) {
        return dev.vox.lss.common.region.ColumnStampsWire.encode(
                dimension, new long[]{pos}, new long[]{second}, 1);
    }

    /** Raw frame builder for HOSTILE shapes {@code encode} itself now refuses
     *  (the producer-side second bound) — the client must still contain them. */
    private static byte[] rawStampsFrame(String dimension, long pos, long second) {
        var w = new dev.vox.lss.common.wire.WireBytes.Writer(64);
        w.writeByte(dev.vox.lss.common.region.ColumnStampsWire.VERSION);
        w.writeUtf(dimension);
        writeZigVarLong(w, second); // base = the second itself
        w.writeVarInt(1);
        w.writeLong(pos);
        writeZigVarLong(w, 0);      // delta 0
        return w.toByteArray();
    }

    private static void writeZigVarLong(dev.vox.lss.common.wire.WireBytes.Writer w, long value) {
        long zig = (value << 1) ^ (value >> 63);
        while ((zig & ~0x7FL) != 0) {
            w.writeByte((int) ((zig & 0x7F) | 0x80));
            zig >>>= 7;
        }
        w.writeByte((int) zig);
    }

    // ---- stamped up_to_date (stamped-up-to-date-plan.md §4) ----

    @Test
    void aColumnStampsFrameRatchetsAndCounts() {
        seedStamped(dim("overworld"), 5000L);
        long now = System.currentTimeMillis() / 1000L;
        manager.onColumnStamps(stampsFrame("lss_test:overworld", POS, now));
        assertEquals(1, manager.getSummaryStampsApplied());
        assertEquals(0, manager.getSummaryStampsIgnored());
        // An idempotent replay (the loss-tolerant recur) counts ignored, not applied.
        manager.onColumnStamps(stampsFrame("lss_test:overworld", POS, now));
        assertEquals(1, manager.getSummaryStampsApplied());
        assertEquals(1, manager.getSummaryStampsIgnored());
        // The heal chain end to end: a tile stamp between old and new now validates.
        var outcome = manager.columnsForTest().applyTileValidation(
                PositionUtil.unpackX(POS) >> 5, PositionUtil.unpackZ(POS) >> 5, now - 100);
        assertTrue(outcome.fullyValidated(), "the ratcheted stamp clears the tile compare");
    }

    @Test
    void aColumnStampsFrameForAnotherDimensionDrops() {
        seedStamped(dim("overworld"), 5000L);
        long now = System.currentTimeMillis() / 1000L;
        manager.onColumnStamps(stampsFrame("lss_test:the_end", POS, now));
        assertEquals(0, manager.getSummaryStampsApplied());
        assertEquals(0, manager.getSummaryStampsIgnored());
        assertEquals(5000L, manager.columnsForTest().classify(POS), "state untouched");
    }

    @Test
    void aHostileColumnStampsFrameIsContained() {
        seedStamped(dim("overworld"), 5000L);
        assertDoesNotThrow(() -> manager.onColumnStamps(new byte[]{9, 1, 2, 3}));
        // The permanent-seal shape: a frame whose second is beyond now+skew drops WHOLE.
        // Hand-crafted raw — encode itself refuses this second (the producer bound).
        long hostile = System.currentTimeMillis() / 1000L
                + dev.vox.lss.common.region.ColumnStampsWire.FUTURE_SKEW_ALLOWANCE_SECONDS + 500;
        assertDoesNotThrow(() -> manager.onColumnStamps(
                rawStampsFrame("lss_test:overworld", POS, hostile)));
        assertEquals(0, manager.getSummaryStampsApplied());
        assertEquals(5000L, manager.columnsForTest().classify(POS), "no seal, no ratchet");
    }

    @Test
    void aStampsFrameDuringACacheLoadIsAHarmlessNoOp() {
        // The no-buffering decision (plan §4, pinned per the 3-Opus fold): during a
        // load the leaf map is empty, so every entry no-ops as `ignored` and no leaf
        // is allocated — and after adoption the un-ratcheted stamps validate nothing
        // they shouldn't (the frame was a stale prior-session shape by definition).
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new java.util.concurrent.CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);
        int leaves = manager.columnsForTest().leafCountForTest();
        manager.onColumnStamps(stampsFrame("lss_test:overworld", POS,
                System.currentTimeMillis() / 1000L));
        assertEquals(0, manager.getSummaryStampsApplied());
        assertEquals(1, manager.getSummaryStampsIgnored(), "empty map: counted ignored");
        assertEquals(leaves, manager.columnsForTest().leafCountForTest(),
                "a frame must not allocate leaves during a load");
    }

    @Test
    void aStampsFrameBeforeAnyTickIsContained() {
        // lastDimension == null (pre-first-tick): silently dropped, no counters.
        assertDoesNotThrow(() -> manager.onColumnStamps(stampsFrame(
                "lss_test:overworld", POS, System.currentTimeMillis() / 1000L)));
        assertEquals(0, manager.getSummaryStampsApplied());
        assertEquals(0, manager.getSummaryStampsIgnored());
    }

    @Test
    void aMixedFrameCountsAppliedAndIgnoredPerEntry() {
        seedStamped(dim("overworld"), 5000L);
        long now = System.currentTimeMillis() / 1000L;
        long unknown = PositionUtil.packPosition(200, 200); // never stamped
        byte[] mixed = dev.vox.lss.common.region.ColumnStampsWire.encode(
                "lss_test:overworld", new long[]{POS, unknown}, new long[]{now, now}, 2);
        manager.onColumnStamps(mixed);
        assertEquals(1, manager.getSummaryStampsApplied(), "the stamped position ratchets");
        assertEquals(1, manager.getSummaryStampsIgnored(), "the unknown one no-ops");
    }

    @Test
    void columnStampsRespectTheKillSwitch() {
        seedStamped(dim("overworld"), 5000L);
        LSSClientConfig.CONFIG.enableRegionSummarySync = false;
        manager.onColumnStamps(stampsFrame("lss_test:overworld", POS,
                System.currentTimeMillis() / 1000L));
        assertEquals(0, manager.getSummaryStampsApplied());
        assertEquals(5000L, manager.columnsForTest().classify(POS));
    }

    @Test
    void aMatchingFrameValidatesAndCounts() {
        var overworld = dim("overworld");
        seedStamped(overworld, 7000L);
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(1, manager.getSummaryColumnsValidated());
        assertEquals(1, manager.getSummaryTilesClean());
        assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(POS));
    }

    @Test
    void staleResidueCountsTheTileStale() {
        seedStamped(dim("overworld"), 5000L);
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated());
        assertEquals(1, manager.getSummaryTilesStale());
        assertEquals(5000L, manager.columnsForTest().classify(POS), "residue re-declares");
    }

    @Test
    void neverCleanTilesCountUnknownAndValidateNothing() {
        seedStamped(dim("overworld"), 5000L);
        manager.onRegionSummaryFrame(frame("lss_test:overworld",
                RegionSummaryWire.STAMP_NEVER_CLEAN));
        assertEquals(0, manager.getSummaryColumnsValidated());
        assertEquals(1, manager.getSummaryTilesUnknown());
        assertEquals(5000L, manager.columnsForTest().classify(POS));
    }

    @Test
    void noRegionTilesAreNoEvidenceAndValidateNothing() {
        // Final honesty review MAJOR-1: the table's never-observed scope is
        // server-lifetime only — a region deleted while the server was OFF also reads
        // STAMP_NO_REGION, and validating cached stamps against it would seal the
        // deleted terrain forever (never re-asked, so never even regenerated). Both
        // sentinels validate nothing; the stamped residue re-declares and heals.
        // Counted under its OWN counter (tiles_no_region, the server-disposition
        // mirror) so the harness honesty legs on tiles_unknown stay sharp — a window
        // legitimately covers many never-generated regions.
        seedStamped(dim("overworld"), 5000L);
        manager.onRegionSummaryFrame(frame("lss_test:overworld",
                RegionSummaryWire.STAMP_NO_REGION));
        assertEquals(0, manager.getSummaryColumnsValidated());
        assertEquals(1, manager.getSummaryTilesNoRegion());
        assertEquals(0, manager.getSummaryTilesUnknown());
        assertEquals(5000L, manager.columnsForTest().classify(POS),
                "the cached stamp re-declares — the read path's not-found ladder heals");
    }

    @Test
    void aRevokedPositionReopensItsRingAndRedeclares() {
        // Final review, client lens MAJOR-1 end to end: a fresher frame's revocation
        // below the scanner's confirmed prefix must REOPEN the position's ring — the
        // needs bit alone is structurally outside both walk intervals and the position
        // would otherwise never re-declare until a full scanner reset (demonstrated
        // orphan: a fully-quiesced client with hundreds of needy positions).
        var overworld = dim("overworld");
        setupWithLod(12, new LodRequestManager(new SpiralScanner())); // legacy-arm mechanics pin
        long pos = PositionUtil.packPosition(10, 3); // ring 10 of the lod-12 disc
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        // The WHOLE disc is cache-stamped, so one validating frame satisfies every
        // position and the first walk confirms all rings with zero declarations —
        // no answer choreography needed for the confirmed-prefix premise.
        var loaded = new Long2LongOpenHashMap();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                loaded.put(PositionUtil.packPosition(x, z), 7000L);
            }
        }
        manager.columnsForTest().loadFrom(loaded);
        manager.onRegionSummaryFrame(frame9("lss_test:overworld", 1L));
        driveScansUntilQuiet(overworld);
        int confirmed = manager.getConfirmedRing();
        assertTrue(confirmed > 10, "premise: the walk confirmed past the position's ring "
                + "(confirmed=" + confirmed + ")");
        assertFalse(declared(pos), "premise: the validated position never declared");
        sent.clear();

        // The fresher frame revokes the summary's own claim...
        manager.onRegionSummaryFrame(frame9("lss_test:overworld", 9999L));
        assertTrue(manager.getReopenedRingCount() > 0,
                "the revocation must reopen the position's ring below the prefix");
        // ...and the next scheduled scan re-declares it.
        for (int i = 0; i <= 21 && !declared(pos); i++) {
            manager.tickWithContext(0, 0, overworld, 0, 0, 0L, -1, () -> 0);
        }
        assertTrue(declared(pos),
                "the revoked position re-declares at the next scheduled scan");
    }

    @Test
    void aRevokedPositionRedeclaresOnTheRegionArmThroughNeedsBitsAlone() {
        // Region twin of the legacy reopen pin above (region-scan-plan.md §10 policy
        // (c)): the region walk has no prefix to reopen — the revocation's needs bit
        // ALONE must re-declare, and the reopen surface stays a structural no-op.
        var overworld = dim("overworld");
        setupWithLod(12, new LodRequestManager(new RegionScanner()));
        long pos = PositionUtil.packPosition(10, 3);
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var loaded = new Long2LongOpenHashMap();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                loaded.put(PositionUtil.packPosition(x, z), 7000L);
            }
        }
        manager.columnsForTest().loadFrom(loaded);
        manager.onRegionSummaryFrame(frame9("lss_test:overworld", 1L));
        driveScansUntilQuiet(overworld);
        assertFalse(declared(pos), "premise: the validated position never declared");
        sent.clear();

        manager.onRegionSummaryFrame(frame9("lss_test:overworld", 9999L));
        assertEquals(0, manager.getReopenedRingCount(),
                "the region arm's reopen surface is a no-op — needs bits carry the revocation");
        for (int i = 0; i <= 21 && !declared(pos); i++) {
            manager.tickWithContext(0, 0, overworld, 0, 0, 0L, -1, () -> 0);
        }
        assertTrue(declared(pos), "the revoked position re-declares via its needs bit");
    }

    /** Rig variant with a custom session distance + a batch recorder. */
    private void setupWithLod(int lod) {
        setupWithLod(lod, new LodRequestManager());
    }

    /** Arm-explicit variant — the revocation ring-REOPEN pin is legacy-arm mechanics
     *  (region-scan-plan.md §10 policy (a)); its region twin asserts re-declaration. */
    private void setupWithLod(int lod, LodRequestManager m) {
        manager = m;
        manager.joinSlowStartEnabled = () -> false;
        manager.onSessionConfig(new SessionConfigS2CPayload(
                LSSConstants.PROTOCOL_VERSION, true, lod, true),
                "lss-summary-lod-" + System.nanoTime());
        requests.clear();
        manager.setSummarySenderForTest(requests::add);
        manager.summaryHarnessGate = () -> false;
        manager.summarySessionVersion = () -> LSSConstants.PROTOCOL_VERSION;
        sent.clear();
        manager.setBatchSenderForTest(p -> sent.add(java.util.Arrays.copyOf(
                p.packedPositions(), p.count())));
    }

    private final List<long[]> sent = new ArrayList<>();

    private boolean declared(long packed) {
        for (long[] batch : sent) {
            for (long p : batch) {
                if (p == packed) return true;
            }
        }
        return false;
    }

    /** Drive ticks until two consecutive scan windows produce no batches. */
    private void driveScansUntilQuiet(net.minecraft.resources.ResourceKey
            <net.minecraft.world.level.Level> d) {
        int quietTicks = 0;
        for (int i = 0; i < 200 && quietTicks < 45; i++) {
            int before = sent.size();
            manager.tickWithContext(0, 0, d, 0, 0, 0L, -1, () -> 0);
            quietTicks = sent.size() == before ? quietTicks + 1 : 0;
        }
    }

    /** A radius-1 frame centered on tile (0,0) carrying one stamp for all 9 tiles. */
    private static byte[] frame9(String dimension, long stamp) {
        long[] stamps = new long[9];
        java.util.Arrays.fill(stamps, stamp);
        return RegionSummaryWire.encodeSummary(new RegionSummaryWire.Summary(
                dimension, 0, 0, 1, stamps));
    }

    @Test
    void frameForAnotherDimensionDrops() {
        seedStamped(dim("overworld"), 7000L);
        manager.onRegionSummaryFrame(frame("lss_test:the_end", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "the dimension echo is the entire anti-stale binding");
        assertEquals(7000L, manager.columnsForTest().classify(POS));
    }

    @Test
    void malformedFrameAndApplyKillSwitchDropContained() {
        seedStamped(dim("overworld"), 7000L);
        assertDoesNotThrow(() -> manager.onRegionSummaryFrame(new byte[]{7, 7, 7}));
        assertEquals(0, manager.getSummaryColumnsValidated());

        LSSClientConfig.CONFIG.enableRegionSummarySync = false;
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "never request, never apply — a mid-session flip stops both halves");
    }

    // ---- buffered apply behind the cache load ----

    @Test
    void frameRacingTheCacheLoadBuffersAndAppliesAfterTheLoad() {
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "applied against the empty pre-load map it would validate nothing —"
                        + " must buffer");

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        pending.complete(loaded);
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(1, manager.getSummaryColumnsValidated(),
                "the buffered frame applies right after adoptLoaded");
        assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(POS));
    }

    @Test
    void bufferedFramesAreLatestWins() {
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.onRegionSummaryFrame(frame("lss_test:overworld", 9999L)); // would validate 0
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L)); // validates POS

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        pending.complete(loaded);
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(1, manager.getSummaryColumnsValidated(), "only the LATEST frame applied");
        assertEquals(1, manager.getSummaryTilesClean() + manager.getSummaryTilesStale()
                + manager.getSummaryTilesUnknown() + manager.getSummaryTilesNoRegion(),
                "exactly one frame's tiles counted");
    }

    @Test
    void anIngestFailureDuringTheLoadReappliesBeforeTheBufferedFrame() {
        // The sealed-failure hazard: the rejection unstamps AFTER the load lands and
        // BEFORE the frame applies, so the frame finds no candidate bit — the failed
        // column re-declares instead of being validated off its stale loaded stamp.
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        manager.onIngestFailure(overworld, POS);

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        pending.complete(loaded);
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "the frame must not seal a rejected column validated");
        assertEquals(-1L, manager.columnsForTest().classify(POS),
                "the rejected column re-declares as a first serve");
    }

    @Test
    void aHostileCenterFrameIsContainedAndWritesNothing() {
        // The MAJOR-1 shape end to end: a frame whose center would overflow the tile
        // walk must be rejected at decode (the wire domain bound) and contained here —
        // no throw into the client tick, no leaf state written.
        seedStamped(dim("overworld"), 7000L);
        var w = new dev.vox.lss.common.wire.WireBytes.Writer(64);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("lss_test:overworld");
        zigVarLong(w, Integer.MAX_VALUE);
        zigVarLong(w, Integer.MAX_VALUE);
        w.writeVarInt(0);
        zigVarLong(w, 0);
        assertDoesNotThrow(() -> manager.onRegionSummaryFrame(w.toByteArray()));
        assertEquals(0, manager.getSummaryColumnsValidated());
        assertEquals(0, manager.getSummaryTilesClean() + manager.getSummaryTilesStale()
                + manager.getSummaryTilesUnknown() + manager.getSummaryTilesNoRegion(),
                "a rejected frame counts nothing");
        assertEquals(7000L, manager.columnsForTest().classify(POS), "state untouched");
    }

    @Test
    void flushCacheDropsABufferedFrame() {
        // /lss clearcache (or reset) mid-load: a frame buffered for the pre-flush
        // state must die with it, never apply against the post-flush map.
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        manager.setPendingCacheLoadForTest(new CompletableFuture<>());
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));

        manager.flushCache();

        manager.setLastDimensionForTest(overworld);
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        manager.setPendingCacheLoadForTest(CompletableFuture.completedFuture(loaded));
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "the pre-flush frame must not validate the post-flush map");
    }

    @Test
    void productionHarnessGateReadsTheSystemProperties() {
        // The gate seam's PRODUCTION default (the productionDefaultEnablesSlowStart
        // idiom): every other test overrides it, so a refactor could silently neuter
        // the soak-baseline protection without this pin.
        var fresh = new LodRequestManager();
        String[] props = {"lss.soak", "lss.benchmark", "lss.soak.summary"};
        String[] saved = new String[props.length];
        for (int i = 0; i < props.length; i++) saved[i] = System.getProperty(props[i]);
        try {
            for (String p : props) System.clearProperty(p);
            assertFalse(fresh.summaryHarnessGate.getAsBoolean(),
                    "production clients are ungated");
            System.setProperty("lss.soak", "true");
            assertTrue(fresh.summaryHarnessGate.getAsBoolean(),
                    "soak clients are gated off requesting");
            System.setProperty("lss.soak.summary", "true");
            assertFalse(fresh.summaryHarnessGate.getAsBoolean(),
                    "the summary scenarios opt back in");
            System.clearProperty("lss.soak.summary");
            System.clearProperty("lss.soak");
            System.setProperty("lss.benchmark", "true");
            assertTrue(fresh.summaryHarnessGate.getAsBoolean(),
                    "benchmark clients are gated off requesting");
        } finally {
            for (int i = 0; i < props.length; i++) {
                if (saved[i] == null) System.clearProperty(props[i]);
                else System.setProperty(props[i], saved[i]);
            }
        }
    }

    @Test
    void theClientRadiusNeverTripsTheServerWindowClamp() {
        // Cross-module inequality (the WantSetBudgetInvariantTest idiom, exercised
        // through REAL code both sides): the radius the real manager requests at a
        // given session distance must clear the real service's admission clamp with
        // zero range_filtered — a future client-side "+ buffer" would silently start
        // charging every honest client.
        for (int lod : new int[]{1, 24, 512, 2048}) {
            var mgr = new LodRequestManager();
            mgr.joinSlowStartEnabled = () -> false;
            mgr.onSessionConfig(new SessionConfigS2CPayload(
                    LSSConstants.PROTOCOL_VERSION, true, lod, true), "lss-radius-" + lod);
            mgr.summaryHarnessGate = () -> false;
            mgr.summarySessionVersion = () -> LSSConstants.PROTOCOL_VERSION;
            mgr.setBatchSenderForTest(p -> { });
            var captured = new ArrayList<byte[]>();
            mgr.setSummarySenderForTest(captured::add);
            mgr.tickWithContext(0, 0, dim("overworld"), 0, 0, 0L, -1, () -> 0);
            assertEquals(1, captured.size(), "lod " + lod + ": one request at entry");
            var req = RegionSummaryWire.decodeRequest(captured.get(0));

            var service = new dev.vox.lss.common.region.RegionSummaryService(
                    (d, tx, tz) -> RegionSummaryWire.STAMP_NO_REGION, () -> lod);
            try {
                service.offerRequest(java.util.UUID.randomUUID(), req);
                service.pump(u -> new dev.vox.lss.common.region.RegionSummaryService
                                .PlayerAnchor(req.dimension(), 0, 0),
                        (p, f) -> dev.vox.lss.common.region.RegionSummaryService
                                .SendOutcome.SENT);
                assertEquals(0, service.diagnostics().getRangeFiltered(),
                        "lod " + lod + ": an honest client's request must never clamp");
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void aDimensionChangeInvalidatesTheBufferedFrame() {
        var overworld = dim("overworld");
        var end = dim("the_end");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));

        tick(0, 0, end); // dimension change — the stale buffered frame must die

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        manager.setPendingCacheLoadForTest(CompletableFuture.completedFuture(loaded));
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "a frame buffered for the OLD dimension must never validate the new one");
    }
}
