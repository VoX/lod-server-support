package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the decode-queue accounting in ClientColumnProcessor. queueSize is the client's
 * backpressure signal — LodRequestManager hard-halts all requesting when it nears
 * MAX_QUEUED_COLUMNS and SpiralScanner scales its budget from it — so an over-count
 * freezes the request loop forever while an under-count lets the decode queue grow
 * without bound. The drop counter feeds /lsslod stats and soak delivery accounting.
 *
 * <p>Also pins the stamp-honesty contract of the drain and clear paths: every column the
 * processor consumes without dispatching to a consumer (overflow drop, backlog clear,
 * empty/corrupt bytes, undispatched at teardown) must surface as exactly one ingest
 * failure report — the receive handler stamped the position at arrival, so any silent
 * loss is a permanent false stamp the next session would resync over.
 */
class ClientColumnProcessorTest {

    private static PalettedContainerFactory FACTORY;
    /** Section count of the test "client level" the drain runs against. */
    private static final int LEVEL_SECTIONS = 4;
    private static final int MIN_SECTION_Y = -4;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FACTORY = PalettedContainerFactory.create(buildRegistryAccess());
    }

    /**
     * Minimal RegistryAccess for {@link PalettedContainerFactory#create}: only the BIOME
     * registry is read, and the decode tests never reference a non-default biome, so a
     * single PLAINS entry (the factory default) suffices — no golden bytes depend on ids
     * here (contrast NbtSectionSerializerTest, which must pin a multi-biome order).
     */
    private static RegistryAccess buildRegistryAccess() {
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, src.getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    private record Report(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}
    private record Dispatched(int chunkX, int chunkZ, int sectionCount) {}

    private final List<Report> reports = new ArrayList<>();
    private final List<Dispatched> dispatches = new ArrayList<>();
    private final ClientColumnProcessor.ColumnDispatcher recordingDispatcher =
            (d, cx, cz, data) -> dispatches.add(new Dispatched(cx, cz, data.sections().length));

    private ClientColumnProcessor processor;
    private ResourceKey<Level> dim;
    private VoxelColumnS2CPayload payload;

    @BeforeEach
    void setUp() {
        processor = new ClientColumnProcessor(
                (d, cx, cz) -> reports.add(new Report(d, cx, cz)),
                () -> null);
        dim = dimKey("processor");
        payload = new VoxelColumnS2CPayload(0, 0, dim, 1L, new byte[0]);
    }

    private static ResourceKey<Level> dimKey(String name) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("lss_test:" + name));
    }

    private void offer(int count) {
        for (int i = 0; i < count; i++) processor.offer(payload, false);
    }

    // ---- Wire-byte builders (SectionSerializer grammar) ----

    private static byte[] drainToArray(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    /** Wire bytes claiming {@code claimed} sections while carrying {@code actual} real (empty, lightless) ones. */
    private static byte[] sectionWire(int claimed, int actual) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(claimed);
            for (int i = 0; i < actual; i++) {
                buf.writeByte(i);
                new LevelChunkSection(FACTORY).write(buf);
                buf.writeBoolean(false);
                buf.writeBoolean(false);
            }
            return drainToArray(buf);
        } finally {
            buf.release();
        }
    }

    /** Claims one section but carries no bytes for it — decode throws at the first read. */
    private static byte[] truncatedColumnWire() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(1);
            return drainToArray(buf);
        } finally {
            buf.release();
        }
    }

    /** A valid section, then a blockLight flag promising 2048 bytes with only 16 present. */
    private static byte[] truncatedLightWire() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(1);
            buf.writeByte(0);
            new LevelChunkSection(FACTORY).write(buf);
            buf.writeBoolean(true);
            buf.writeBytes(new byte[16]);
            return drainToArray(buf);
        } finally {
            buf.release();
        }
    }

    private void drainNow() {
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
    }

    /** A real manager configured like the production factory wires it, scanning {@code dim}. */
    private LodRequestManager managedManager() {
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, true), "lss-test");
        manager.setLastDimensionForTest(dim);
        return manager;
    }

    /** Records teardown-relevant manager calls; overrides keep the test off disk. */
    private static final class OrderRecordingManager extends LodRequestManager {
        private final List<String> events;

        OrderRecordingManager(List<String> events) {
            this.events = events;
        }

        @Override
        public void onIngestFailure(ResourceKey<Level> dimension, long packed) {
            events.add("report");
        }

        @Override
        public void disconnect() {
            events.add("disconnect");
        }

        @Override
        public void saveCache() {
            events.add("save");
        }
    }

    // ---- Queue accounting (backpressure signal + drop stats) ----

    @Test
    void offerStopsAtCapAndCountsDrops() {
        offer(ClientColumnProcessor.MAX_QUEUED_COLUMNS);
        assertEquals(ClientColumnProcessor.MAX_QUEUED_COLUMNS, processor.getQueuedCount());
        assertEquals(0, processor.getColumnsDropped());

        offer(3);
        assertEquals(ClientColumnProcessor.MAX_QUEUED_COLUMNS, processor.getQueuedCount(),
                "offers past the cap must not grow the queue");
        assertEquals(3, processor.getColumnsDropped());
    }

    @Test
    void errorDuringDrainReportsBeforePropagating() {
        // Throwable containment (mirrors LSSApi.dispatchColumn's policy): an Error escaping
        // the per-column try would consume the column — polled, decremented — with no
        // dispatch AND no report, a permanent false received-stamp persisted to the cache.
        // The report must land BEFORE the Error propagates.
        var wire = sectionWire(1, 1);
        processor.offer(new VoxelColumnS2CPayload(3, 4, dim, 9L, wire), false);
        assertThrows(LinkageError.class, () -> processor.drainColumnQueue(dim, LEVEL_SECTIONS,
                MIN_SECTION_Y, false, FACTORY,
                (d, cx, cz, data) -> { throw new LinkageError("boom"); },
                processor.sessionEpochForTest()));
        assertEquals(List.of(new Report(dim, 3, 4)), reports,
                "the column must report (unstamp) before the Error surfaces");
        assertEquals(0, processor.getQueuedCount(), "the column was consumed exactly once");
    }

    @Test
    void admissionIsBoundedByBytesAsWellAsCount() {
        // The count cap alone admits up to 8000 x 2 MiB = 16 GiB of retained payloads from a
        // hostile server before any drop fires; the byte budget closes that.
        assertTrue(ClientColumnProcessor.admits(0, 0, 1024));
        assertTrue(ClientColumnProcessor.admits(0,
                ClientColumnProcessor.MAX_QUEUED_BYTES - 10, 10), "budget boundary is inclusive");
        assertFalse(ClientColumnProcessor.admits(0,
                ClientColumnProcessor.MAX_QUEUED_BYTES - 10, 11), "over-budget payload must drop");
        assertFalse(ClientColumnProcessor.admits(ClientColumnProcessor.MAX_QUEUED_COLUMNS, 0, 1),
                "count cap still applies");
    }

    @Test
    void offerTracksQueuedBytesAndShutdownDrainsThem() {
        var sized = new VoxelColumnS2CPayload(0, 0, dimKey("bytes"), 1L, new byte[64]);
        processor.offer(sized, false);
        processor.offer(sized, false);
        assertEquals(128, processor.getQueuedBytes(), "offers must count toward the byte budget");
        processor.shutdown();
        assertEquals(0, processor.getQueuedBytes(),
                "a stale byte budget would throttle the next session's admissions");
    }

    @Test
    void shutdownZeroesBackpressureSignalAndKeepsDropStats() {
        offer(ClientColumnProcessor.MAX_QUEUED_COLUMNS + 5);
        assertEquals(5, processor.getColumnsDropped());

        processor.shutdown();
        assertEquals(0, processor.getQueuedCount(),
                "a stale non-zero queueSize would keep the request loop halted after reconnect");
        assertEquals(5, processor.getColumnsDropped(),
                "shutdown must not reset drop stats (resetStats owns that)");

        // Counter integrity: the next session admits again from a consistent zero.
        offer(1);
        assertEquals(1, processor.getQueuedCount());
        assertEquals(5, processor.getColumnsDropped(), "post-shutdown offers fit again, no new drops");
    }

    @Test
    void resetStatsClearsDropCounterButNotQueue() {
        offer(ClientColumnProcessor.MAX_QUEUED_COLUMNS + 2);
        assertEquals(2, processor.getColumnsDropped());

        processor.resetStats();
        assertEquals(0, processor.getColumnsDropped());
        assertEquals(ClientColumnProcessor.MAX_QUEUED_COLUMNS, processor.getQueuedCount(),
                "resetStats clears statistics only, never queued work");
    }

    @Test
    void disabledScheduleClearsBacklogAndZeroesSignal() {
        offer(10);
        assertEquals(10, processor.getQueuedCount());

        // serverEnabled=false short-circuits before any Minecraft client state is touched.
        processor.scheduleProcessing(false);
        assertEquals(0, processor.getQueuedCount(),
                "disabled session must drop the backlog and release the backpressure signal");
    }

    // ---- implicit-sky fill: absent air ABOVE the served band is full-bright (2026-07-27) ----

    @Test
    void implicitSkyFillAddsBrightAirAboveTheTopServedSection() {
        // Vanilla stores sky layers only to heightmap+1; above is IMPLICITLY full-bright.
        // Without the fill, faces sampling never-served air above/beside terrain (leaf
        // tops, cliff sides at a chunk border against a lower column) shade black.
        var present = new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(-4, new LevelChunkSection(FACTORY), null, null),
                new VoxelColumnData.SectionData(-3, new LevelChunkSection(FACTORY), null, null)};
        var filled = ClientColumnProcessor.withImplicitSkyAbove(present, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY);

        var byY = new java.util.HashMap<Integer, VoxelColumnData.SectionData>();
        for (var s : filled) byY.put(s.sectionY(), s);
        assertEquals(java.util.Set.of(-4, -3, -2, -1), byY.keySet(),
                "every absent Y above the top served section fills to the level top");
        for (int y : new int[]{-2, -1}) {
            var s = byY.get(y);
            assertTrue(s.section().hasOnlyAir(), "fill sections are all-air");
            assertNull(s.blockLight(), "no fabricated block light");
            assertNotNull(s.skyLight(), "fill sections carry sky light");
            for (byte b : s.skyLight().getData()) {
                assertEquals((byte) 0xFF, b, "fill sky light is full-bright");
            }
        }
        assertNull(byY.get(-4).skyLight(), "served sections are untouched");
    }

    @Test
    void implicitSkyFillLeavesClearsAndFullColumnsAlone() {
        assertEquals(0, ClientColumnProcessor.withImplicitSkyAbove(
                        new VoxelColumnData.SectionData[0], LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY).length,
                "a zero-section CLEAR must stay a clear");
        var full = new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(-1, new LevelChunkSection(FACTORY), null, null)};
        assertSame(full, ClientColumnProcessor.withImplicitSkyAbove(full, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY),
                "a column already serving the top section gains nothing");
    }

    // ---- WS3 authoritative air-fill: a resync clears ghost terrain for absent sections ----

    @Test
    void airFillAddsAllAirSectionsForEveryAbsentYOnResync() {
        var present = new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(-4, new LevelChunkSection(FACTORY), null, null)};
        var filled = ClientColumnProcessor.withAirFilledAbsentSections(present, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY, false);

        var ys = new java.util.HashSet<Integer>();
        for (var s : filled) ys.add(s.sectionY());
        assertEquals(java.util.Set.of(-4, -3, -2, -1), ys,
                "every section-Y in [minSectionY, minSectionY+levelSectionCount) is present after air-fill");
        for (var s : filled) {
            if (s.sectionY() != -4) {
                assertTrue(s.section().hasOnlyAir(), "absent-Y sections are all-air (clear ghost terrain)");
                assertNull(s.skyLight());
                assertNull(s.blockLight());
            }
        }
    }

    // ---- M1 (2026-07-28 review round): sky fill runs BEFORE the resync air-fill ----
    // The v0.8.0 order ran the air-fill first on resyncs, covering every section-Y so the
    // sky pass early-returned — every re-served column (dirty edit, warm-rejoin re-serve,
    // ingest retry) dispatched dark above-band air, regressing the black-boundary-faces
    // fix on second serves. These pins compose the two passes through the REAL drain,
    // which no test did before (the gap that let the wrong order ship).

    private final List<VoxelColumnData> captured = new ArrayList<>();
    private final ClientColumnProcessor.ColumnDispatcher capturingDispatcher =
            (d, cx, cz, data) -> captured.add(data);

    /** One real (empty, lightless) section at the given sectionY (the wire byte IS the Y). */
    private static byte[] sectionWireAtY(int sectionY) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(1);
            buf.writeByte(sectionY);
            new LevelChunkSection(FACTORY).write(buf);
            buf.writeBoolean(false);
            buf.writeBoolean(false);
            return drainToArray(buf);
        } finally {
            buf.release();
        }
    }

    private java.util.Map<Integer, VoxelColumnData.SectionData> drainWithSkyAndCapture(
            byte[] wire, boolean resync) {
        processor.offer(new VoxelColumnS2CPayload(0, 0, dim, 1L, wire), resync);
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, true, FACTORY,
                capturingDispatcher, processor.sessionEpochForTest());
        assertEquals(1, captured.size(), "exactly one column dispatched");
        var byY = new java.util.HashMap<Integer, VoxelColumnData.SectionData>();
        for (var s : captured.get(0).sections()) byY.put(s.sectionY(), s);
        return byY;
    }

    @Test
    void resyncDeliveryKeepsImplicitSkyAboveTheServedBand() {
        var byY = drainWithSkyAndCapture(sectionWireAtY(-2), true); // served mid-band
        assertEquals(java.util.Set.of(-4, -3, -2, -1), byY.keySet(),
                "resync air-fill still completes the column");
        assertNotNull(byY.get(-1).skyLight(), "above-band fill must carry sky light — "
                + "the v0.8.0 order dispatched it dark on every resync");
        for (byte b : byY.get(-1).skyLight().getData()) {
            assertEquals((byte) 0xFF, b, "above-band sky is full-bright");
        }
        assertNull(byY.get(-2).skyLight(), "the served section is untouched");
        assertNull(byY.get(-3).skyLight(), "below-band resync fill stays dark air");
        assertNull(byY.get(-4).skyLight(), "below-band resync fill stays dark air");
        assertTrue(byY.get(-3).section().hasOnlyAir());
    }

    @Test
    void skyFillWithoutResyncLeavesBelowBandAbsent() {
        var byY = drainWithSkyAndCapture(sectionWireAtY(-2), false);
        assertEquals(java.util.Set.of(-2, -1), byY.keySet(),
                "a first serve fills only above the band — below-band Ys stay absent");
    }

    @Test
    void resyncClearInSkyDimensionAirFillsBright() {
        // An all-air column in a sky dimension is sky-15 top to bottom (vertical
        // propagation undiminished) — a dark fill turned WorldEdit-cleared columns into
        // light-0 volumes that shaded every neighbouring face black.
        var byY = drainWithSkyAndCapture(sectionWire(0, 0), true);
        assertEquals(java.util.Set.of(-4, -3, -2, -1), byY.keySet(),
                "an authoritative clear air-fills the whole column");
        for (var s : byY.values()) {
            assertNotNull(s.skyLight(), "sky-dimension clear fill is FULL BRIGHT");
            for (byte b : s.skyLight().getData()) assertEquals((byte) 0xFF, b);
            assertTrue(s.section().hasOnlyAir(), "the clear is still all-air");
        }
    }

    @Test
    void resyncClearInDarkDimensionStaysDarkAir() {
        processor.offer(new VoxelColumnS2CPayload(0, 0, dim, 1L, sectionWire(0, 0)), true);
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY,
                capturingDispatcher, processor.sessionEpochForTest());
        assertEquals(1, captured.size());
        assertEquals(LEVEL_SECTIONS, captured.get(0).sections().length);
        for (var s : captured.get(0).sections()) {
            assertNull(s.skyLight(), "no sky light in a dark dimension");
        }
    }

    @Test
    // NOT the drain-order guard (it calls the helpers directly in the fixed order and
    // stays green under a drain regression) — resyncDeliveryKeepsImplicitSkyAboveTheServedBand
    // is the real pin; this documents the unit-level composition semantics.
    void skyThenAirFillUnitCompositionMatchesTheDrainOrder() {
        var present = new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(-2, new LevelChunkSection(FACTORY), null, null)};
        var sky = ClientColumnProcessor.withImplicitSkyAbove(present, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY);
        var full = ClientColumnProcessor.withAirFilledAbsentSections(sky, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY, false);
        assertEquals(4, full.length);
        var byY = new java.util.HashMap<Integer, VoxelColumnData.SectionData>();
        for (var s : full) byY.put(s.sectionY(), s);
        assertNotNull(byY.get(-1).skyLight(), "unit order pin: sky first, air-fill second");
        assertNull(byY.get(-3).skyLight(), "air-fill below the band is dark");
    }

    @Test
    void resyncColumnAirFillsAbsentSectionsButFirstServeDoesNot() {
        var dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("lss_test:processor"));
        // sectionWire writes sections at Y=0,1,..., so use minSectionY=0 -> range {0,1,2,3}.
        byte[] wire = sectionWire(1, 1); // one section at Y=0

        processor.offer(new VoxelColumnS2CPayload(0, 0, dim, 5000L, wire), true); // resync
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, 0, false, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
        assertEquals(LEVEL_SECTIONS, dispatches.get(dispatches.size() - 1).sectionCount(),
                "a resync fills every absent section-Y with air (present + air = full level range)");

        processor.offer(new VoxelColumnS2CPayload(1, 1, dim, 5000L, wire), false); // first serve
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, 0, false, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
        assertEquals(1, dispatches.get(dispatches.size() - 1).sectionCount(),
                "a first serve dispatches only the carried section — no air-fill (absent == air == no-op)");
    }

    @Test
    void reportUndispatchedUnstampsQueuedColumnsBeforeTheCacheFlush() {
        var dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("lss_test:processor"));
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, true), "lss-test");
        manager.setLastDimensionForTest(dim);
        long packed = PositionUtil.packPosition(0, 0);
        manager.onColumnReceived(packed, 5000L, dim); // stamped at arrival, decode still pending

        offer(1);
        processor.reportUndispatched(manager);

        assertEquals(0, processor.getQueuedCount(), "undispatched queue must be drained");
        assertEquals(-1L, manager.columnsForTest().classify(packed),
                "an undispatched column's stamp must be forgotten before saveCache persists it");
        assertEquals(1, manager.getTotalIngestFailures());
    }

    // ---- CL-037: overflow drops report the position ----

    @Test
    void overflowDropReportsThePositionForReRequest() {
        offer(ClientColumnProcessor.MAX_QUEUED_COLUMNS);
        assertEquals(List.of(), reports, "in-cap offers must not report");

        processor.offer(new VoxelColumnS2CPayload(7, -3, dim, 1L, new byte[0]), false);

        assertEquals(1, processor.getColumnsDropped());
        assertEquals(List.of(new Report(dim, 7, -3)), reports,
                "exactly one report for the dropped position — it was stamped received at"
                        + " arrival, so an unreported drop is a permanent hole");
    }

    // ---- CL-038: decode section-count clamp + truncation consequence ----

    @Test
    void decodeClampsHostileSectionCountToLevelSectionCount() {
        // Claims a million sections; carries LEVEL_SECTIONS real ones. Without the clamp
        // the claim sizes the array and the decode walks off the end of the buffer.
        byte[] wire = sectionWire(1_000_000, LEVEL_SECTIONS);

        var sections = ClientColumnProcessor.decodeSections(wire, LEVEL_SECTIONS, FACTORY);

        assertEquals(LEVEL_SECTIONS, sections.length,
                "the claimed varint must never size the allocation — hostile counts clamp to the level height");
        for (int i = 0; i < sections.length; i++) {
            assertEquals(i, sections[i].sectionY(), "clamped decode still reads the real sections in order");
        }
    }

    @Test
    void decodeTruncatesSectionsBeyondLevelHeightSilently() {
        // A server world taller than the client level: 6 real sections against 4 client
        // sections. Pinned consequence: the first 4 decode, the tail bytes are ignored —
        // no throw, no report (the column dispatches partially, silently).
        byte[] wire = sectionWire(6, 6);

        var sections = ClientColumnProcessor.decodeSections(wire, LEVEL_SECTIONS, FACTORY);

        assertEquals(LEVEL_SECTIONS, sections.length,
                "sections beyond the client level's height are silently dropped");
        assertEquals(LEVEL_SECTIONS - 1, sections[sections.length - 1].sectionY(),
                "exactly the first levelSectionCount sections decode; the tail is left unread");
    }

    @Test
    void decodeNegativeSectionCountYieldsEmptyColumn() {
        byte[] wire = sectionWire(-3, 0);

        var sections = ClientColumnProcessor.decodeSections(wire, LEVEL_SECTIONS, FACTORY);

        assertEquals(0, sections.length,
                "a negative claimed count clamps to zero, never a negative-size allocation");
    }

    // ---- isClearColumn: identify a 0-section authoritative clear ----

    @Test
    void isClearColumnTrueForZeroSectionBody() {
        assertTrue(ClientColumnProcessor.isClearColumn(sectionWire(0, 0)),
                "a 0-section body is the wire form of a content->air clear");
    }

    @Test
    void isClearColumnFalseForContentBody() {
        assertFalse(ClientColumnProcessor.isClearColumn(sectionWire(1, 1)),
                "any body carrying sections is content, not a clear");
    }

    @Test
    void isClearColumnFalseForEmptyOrNullBytes() {
        assertFalse(ClientColumnProcessor.isClearColumn(new byte[0]));
        assertFalse(ClientColumnProcessor.isClearColumn(null));
    }

    // ---- CL-039: decode failures report once and the drain continues ----

    @Test
    void corruptAndTruncatedColumnsReportOnceEachAndDrainContinues() {
        processor.offer(new VoxelColumnS2CPayload(1, 1, dim, 1L, truncatedColumnWire()), false);
        processor.offer(new VoxelColumnS2CPayload(3, 3, dim, 1L, truncatedLightWire()), false);
        processor.offer(new VoxelColumnS2CPayload(2, 2, dim, 1L, sectionWire(1, 1)), false);

        drainNow();

        assertEquals(List.of(new Report(dim, 1, 1), new Report(dim, 3, 3)), reports,
                "exactly one ingest-failure report per undecodable column");
        assertEquals(List.of(new Dispatched(2, 2, 1)), dispatches,
                "no partial dispatch of a failed column (the truncated-light one decoded a full"
                        + " section before throwing) and the drain continues to the healthy column");
        assertEquals(0, processor.getQueuedCount());
    }

    // ---- CL-040: stale-dimension columns are consumed silently ----

    @Test
    void wrongDimensionColumnIsConsumedWithoutDispatchOrReport() {
        processor.offer(new VoxelColumnS2CPayload(1, 1, dimKey("elsewhere"), 1L, sectionWire(1, 1)), false);

        drainNow();

        assertEquals(0, processor.getQueuedCount(), "the stale-dimension column is consumed, not requeued");
        assertEquals(List.of(), dispatches, "never dispatched into the wrong dimension's consumers");
        assertEquals(List.of(), reports,
                "and never reported: the manager's receive guard kept this dimension's map unstamped,"
                        + " so a report would unstamp an unrelated position in the current dimension");
    }

    // ---- CL-041: epoch bump mid-drain ----

    @Test
    void epochBumpMidDrainStopsAtNextPollAndRemainderIsReported() {
        var manager = managedManager();
        manager.onColumnReceived(PositionUtil.packPosition(1, 1), 5000L, dim);
        manager.onColumnReceived(PositionUtil.packPosition(2, 2), 5000L, dim);
        manager.onColumnReceived(PositionUtil.packPosition(3, 3), 5000L, dim);
        processor.offer(new VoxelColumnS2CPayload(1, 1, dim, 5000L, sectionWire(1, 1)), false);
        processor.offer(new VoxelColumnS2CPayload(2, 2, dim, 5000L, sectionWire(1, 1)), false);
        processor.offer(new VoxelColumnS2CPayload(3, 3, dim, 5000L, sectionWire(1, 1)), false);
        int epoch = processor.sessionEpochForTest();

        ClientColumnProcessor.ColumnDispatcher teardownMidDispatch = (d, cx, cz, data) -> {
            dispatches.add(new Dispatched(cx, cz, data.sections().length));
            // Teardown lands while the first column is mid-dispatch, then a straggler
            // arrives: the stale drain must stop at its NEXT poll, not finish the queue.
            processor.reportUndispatched(manager);
            processor.offer(new VoxelColumnS2CPayload(4, 4, dim, 5000L, sectionWire(1, 1)), false);
        };
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY, teardownMidDispatch, epoch);

        assertEquals(List.of(new Dispatched(1, 1, 1)), dispatches,
                "at most the already-polled column dispatches (the accepted ≤1 residual)");
        assertEquals(5000L, manager.getColumnTimestamp(1, 1),
                "the residual dispatch is truthful — its stamp stays");
        assertEquals(-1L, manager.getColumnTimestamp(2, 2),
                "the un-polled remainder is unstamped via reportUndispatched");
        assertEquals(-1L, manager.getColumnTimestamp(3, 3));
        assertEquals(2, manager.getTotalIngestFailures());
        assertEquals(1, processor.getQueuedCount(),
                "the post-bump straggler is never polled by the stale drain");
    }


    @Test void ownedAlreadyPolledCallbackIsRetiredBeforeItsCompletion() {
        var manager = managedManager();
        manager.deliveryExecutor = Runnable::run;
        long packed = PositionUtil.packPosition(1, 1);
        manager.onColumnReceived(packed, 5000L, dim);
        var receipt = manager.trackDelivery(dim, packed, -1L);
        processor.offer(new VoxelColumnS2CPayload(1, 1, dim, 5000L, sectionWire(1, 1)), false, receipt);
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY,
                (d, x, z, data) -> {
                    assertSame(receipt, dev.vox.lss.api.LSSApi.captureIngestFailureHandle());
                    manager.retireAcquisition();
                    processor.reportUndispatched(manager);
                    assertEquals(-1L, manager.getColumnTimestamp(1, 1),
                            "retirement accounts for a receipt already outside the queue");
                    receipt.report(); // late callback cannot charge intentional retirement
                }, processor.sessionEpochForTest());
        assertEquals(-1L, manager.getColumnTimestamp(1, 1));
        assertEquals(0, manager.getTotalIngestFailures());
        assertEquals(0, processor.getQueuedCount());
    }

    // ---- CL-044: the three clear paths report instead of silently dropping (D2) ----

    @Test
    void receiveServerLodsDisableFlipReportsClearedBacklog() {
        processor.offer(new VoxelColumnS2CPayload(1, 2, dim, 1L, sectionWire(1, 1)), false);
        processor.offer(new VoxelColumnS2CPayload(3, 4, dim, 1L, sectionWire(1, 1)), false);
        boolean prior = LSSClientConfig.CONFIG.receiveServerLods();
        dev.vox.lss.config.ClientConfigTestSupport.set("lod.receive", false);
        try {
            processor.scheduleProcessing(true); // serverEnabled stays true — the config flip clears
        } finally {
            dev.vox.lss.config.ClientConfigTestSupport.set("lod.receive", prior);
        }

        assertEquals(0, processor.getQueuedCount());
        assertEquals(List.of(new Report(dim, 1, 2), new Report(dim, 3, 4)), reports,
                "a mid-session receiveServerLods flip must report every cleared column"
                        + " — each was stamped received at arrival");
    }

    @Test
    void consumerDeregistrationReportsClearedBacklog() {
        assertFalse(LSSApi.hasVoxelConsumers(),
                "precondition: consumer-registering tests must deregister in finally");
        processor.offer(new VoxelColumnS2CPayload(8, 9, dim, 1L, sectionWire(1, 1)), false);
        boolean prior = LSSClientConfig.CONFIG.receiveServerLods();
        dev.vox.lss.config.ClientConfigTestSupport.set("lod.receive", true);
        try {
            processor.scheduleProcessing(true); // no consumer registered — the dereg clear path
        } finally {
            dev.vox.lss.config.ClientConfigTestSupport.set("lod.receive", prior);
        }

        assertEquals(0, processor.getQueuedCount());
        assertEquals(List.of(new Report(dim, 8, 9)), reports,
                "columns cleared because the last consumer deregistered must still be reported");
    }

    @Test
    void levelNullRaceReportsClearedBacklog() {
        VoxelColumnConsumer consumer = (level, d, cx, cz, data) -> {};
        boolean prior = LSSClientConfig.CONFIG.receiveServerLods();
        dev.vox.lss.config.ClientConfigTestSupport.set("lod.receive", true);
        LSSApi.registerColumnConsumer(consumer);
        try {
            processor.offer(new VoxelColumnS2CPayload(3, 3, dim, 1L, sectionWire(1, 1)), false);
            processor.scheduleProcessing(true); // levelSupplier yields null: the disconnect race window
        } finally {
            LSSApi.removeColumnConsumer(consumer);
            dev.vox.lss.config.ClientConfigTestSupport.set("lod.receive", prior);
        }

        assertEquals(0, processor.getQueuedCount());
        assertEquals(List.of(new Report(dim, 3, 3)), reports,
                "a level-null clear must report, not silently drop the backlog");
    }

    // ---- CL-045: empty section bytes are reported, never silently skipped ----

    @Test
    void emptyAndNullSectionBytesReportAndDrainContinues() {
        processor.offer(new VoxelColumnS2CPayload(4, 5, dim, 1L, new byte[0]), false);
        processor.offer(new VoxelColumnS2CPayload(6, 7, dim, 1L, null), false);
        processor.offer(new VoxelColumnS2CPayload(2, 2, dim, 1L, sectionWire(1, 1)), false);

        drainNow();

        assertEquals(List.of(new Report(dim, 4, 5), new Report(dim, 6, 7)), reports,
                "the position was stamped received at arrival — a silent drop here is a permanent false stamp");
        assertEquals(List.of(new Dispatched(2, 2, 1)), dispatches, "the drain continues past defensive reports");
        assertEquals(0, processor.getQueuedCount());
    }

    // ---- CL-046: report → saveCache order at both teardown boundaries + epoch guard ----

    @Test
    void disconnectTeardownReportsUndispatchedBeforeSavingCache() {
        var events = new ArrayList<String>();
        var gate = new ClientSessionGate(processor, version -> {}, cfg -> new OrderRecordingManager(events));
        gate.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, true), true, true);
        processor.offer(new VoxelColumnS2CPayload(5, 5, dim, 1L, sectionWire(1, 1)), false);

        gate.onDisconnect();

        assertEquals(List.of("report", "disconnect", "save"), events,
                "report → saveCache order is load-bearing: a save-first teardown persists stamps"
                        + " for columns no consumer ever saw and the next session resyncs over holes");
        assertEquals(0, processor.getQueuedCount());
    }

    /**
     * The dimension-change boundary (LodRequestManager.onDimensionChange) must report
     * undispatched columns BEFORE saveCache persists the old dimension's map. The routine
     * is private and reachable only through tick() (needs a live client), and its report
     * call drains LSSClientNetworking's static processor — both crossed via reflection;
     * the assertion is purely the recording manager's event order.
     */
    @Test
    void dimensionChangeReportsUndispatchedBeforeSavingCache() throws Exception {
        var events = new ArrayList<String>();
        var manager = new OrderRecordingManager(events);

        // The static processor lives in the loader-neutral glue since N-3.
        var processorField = ClientNetGlue.class.getDeclaredField("columnProcessor");
        processorField.setAccessible(true);
        var staticProcessor = (ClientColumnProcessor) processorField.get(null);
        try {
            staticProcessor.offer(new VoxelColumnS2CPayload(1, 2, dim, 9L, new byte[0]), false);

            var onDimensionChange = LodRequestManager.class
                    .getDeclaredMethod("onDimensionChange", ResourceKey.class);
            onDimensionChange.setAccessible(true);
            onDimensionChange.invoke(manager, dimKey("destination"));

            assertEquals(List.of("report", "save"), events,
                    "a dimension change must unstamp undispatched columns before the cache flush");
        } finally {
            staticProcessor.shutdown(); // leave the shared static processor's queue empty for other tests
        }
    }

    @Test
    void epochGuardRefusesStaleDrainAfterTeardown() {
        var manager = managedManager();
        int staleEpoch = processor.sessionEpochForTest();
        processor.offer(new VoxelColumnS2CPayload(1, 1, dim, 1L, sectionWire(1, 1)), false);
        processor.reportUndispatched(manager); // teardown bumps the session epoch
        processor.offer(new VoxelColumnS2CPayload(2, 2, dim, 1L, sectionWire(1, 1)), false); // straggler

        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY, recordingDispatcher, staleEpoch);

        assertEquals(List.of(), dispatches,
                "a drain scheduled before teardown must not dispatch into the new session");
        assertEquals(1, processor.getQueuedCount(),
                "the straggler is left for shutdown, not consumed by the stale drain");

        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
        assertEquals(List.of(new Dispatched(2, 2, 1)), dispatches,
                "only the epoch gates the drain — a current-epoch drain still serves");
    }

    @Test
    void teardownBetweenEpochCheckAndPollCannotAcceptANewSessionColumn() throws Exception {
        var replacement = managedManager();
        replacement.deliveryExecutor = Runnable::run;
        long packed = PositionUtil.packPosition(9, 9);
        var queueField = ClientColumnProcessor.class.getDeclaredField("columnQueue");
        queueField.setAccessible(true);
        // Force the real check/poll interleaving without timing or a production test hook.
        // The teardown's recursive poll sees an empty queue; only afterward does the new
        // owner admit a receipt into the lifetime processor's shared queue.
        queueField.set(processor, new java.util.concurrent.ConcurrentLinkedQueue<Object>() {
            private boolean firstPoll = true;
            @Override public Object poll() {
                if (firstPoll) {
                    firstPoll = false;
                    processor.shutdown();
                    replacement.onColumnReceived(packed, 5000L, dim);
                    var receipt = replacement.trackDelivery(dim, packed, -1L);
                    processor.offer(new VoxelColumnS2CPayload(9, 9, dim, 5000L,
                            sectionWire(1, 1)), false, receipt);
                }
                return super.poll();
            }
        });

        int oldEpoch = processor.sessionEpochForTest();
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY,
                recordingDispatcher, oldEpoch);

        assertTrue(dispatches.isEmpty(), "an old drain must not dispatch into its replacement");
        assertEquals(-1L, replacement.getColumnTimestamp(9, 9),
                "a new receipt stolen by the old drain must be re-requested, never accepted silently");
        assertEquals(1, replacement.getTotalIngestFailures());
        assertEquals(0, processor.getQueuedCount());
        assertEquals(0L, processor.getQueuedBytes());
    }

    // ---- CL-047: a decode-failure unstamp survives the disconnect cache flush ----

    @Test
    void decodeFailureUnstampSurvivesDisconnectCachePersistence() {
        String serverAddr = "lss-test-cl047-" + System.nanoTime();
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, true), serverAddr);
        manager.setLastDimensionForTest(dim);
        manager.onColumnReceived(PositionUtil.packPosition(10, 10), 5000L, dim);
        manager.onColumnReceived(PositionUtil.packPosition(11, 11), 6000L, dim);

        // Reporter routes straight to the manager — the production LSSClientNetworking
        // hop (mc.execute) collapsed to a synchronous call.
        var proc = new ClientColumnProcessor(
                (d, cx, cz) -> manager.onIngestFailure(d, PositionUtil.packPosition(cx, cz)),
                () -> null);
        var gate = new ClientSessionGate(proc, version -> {}, cfg -> manager);
        gate.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, true), true, true);
        try {
            proc.offer(new VoxelColumnS2CPayload(11, 11, dim, 6000L, truncatedColumnWire()), false);
            proc.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY, (d, cx, cz, data) -> {},
                    proc.sessionEpochForTest());
            assertEquals(-1L, manager.getColumnTimestamp(11, 11),
                    "premise: the decode failure unstamped the position");

            gate.onDisconnect(); // teardown saves the cache asynchronously
            ColumnCacheStore.flushPendingIo();

            var reloaded = ColumnCacheStore.load(serverAddr, dim);
            assertEquals(5000L, reloaded.get(PositionUtil.packPosition(10, 10)),
                    "the healthy stamp persists — proves the disconnect save actually ran");
            assertEquals(-1L, reloaded.get(PositionUtil.packPosition(11, 11)),
                    "the failed-decode position must be absent from the persisted cache,"
                            + " or the next session resyncs as up-to-date over the hole");
        } finally {
            ColumnCacheStore.clearForServer(serverAddr);
        }
    }

    // ---- issue #382: sky light for a column that arrived without any ----
    // A chunk saved without light (vanilla's ChunkDeleteLightFix strips it on a world upgrade)
    // is served from disk as ordinary terrain with no light layers; every consumer drew it
    // black (reproduced live 2026-11-12: all 2143 stripped far columns dark). The drain now
    // estimates sky light for a column that has blocks and not one non-zero sky nibble.
    // The light rule itself is pinned in SkyLightEstimateTest (common) and against vanilla's
    // engine in ClientSkyLightEstimatorTest; these pin WHEN it runs and where in the drain.

    /** The taller test level these cases use: section Ys -4..3. */
    private static final int TALL_SECTIONS = 8;

    private record WireSection(int y, LevelChunkSection section, byte[] blockLight, byte[] skyLight) {}

    private static WireSection at(int y, LevelChunkSection section) {
        return new WireSection(y, section, null, null);
    }

    /** A section of stone from its bottom up to local {@code topY} (15 = solid). */
    private static LevelChunkSection stoneUpTo(int topY) {
        var section = new LevelChunkSection(FACTORY);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        for (int y = 0; y <= topY; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            section.setBlockState(x, y, z, stone, false);
        }
        return section;
    }

    /** A one-block stone platform at local y=8 over the west half (x 0..7), air elsewhere. */
    private static LevelChunkSection halfPlatform() {
        var section = new LevelChunkSection(FACTORY);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 8; x++) section.setBlockState(x, 8, z, stone, false);
        return section;
    }

    private static byte[] columnWire(int claimed, WireSection... sections) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(claimed);
            for (var s : sections) {
                buf.writeByte(s.y());
                s.section().write(buf);
                buf.writeBoolean(s.blockLight() != null);
                if (s.blockLight() != null) buf.writeBytes(s.blockLight());
                buf.writeBoolean(s.skyLight() != null);
                if (s.skyLight() != null) buf.writeBytes(s.skyLight());
            }
            return drainToArray(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] columnWire(WireSection... sections) {
        return columnWire(sections.length, sections);
    }

    private java.util.Map<Integer, VoxelColumnData.SectionData> drainTall(byte[] wire, boolean resync,
                                                                         boolean hasSkyLight) {
        captured.clear();
        processor.offer(new VoxelColumnS2CPayload(0, 0, dim, 1L, wire), resync);
        processor.drainColumnQueue(dim, TALL_SECTIONS, MIN_SECTION_Y, hasSkyLight, FACTORY,
                capturingDispatcher, processor.sessionEpochForTest());
        assertEquals(1, captured.size(), "exactly one column dispatched");
        assertEquals(List.of(), reports, "no ingest failure");
        var byY = new java.util.HashMap<Integer, VoxelColumnData.SectionData>();
        for (var s : captured.get(0).sections()) byY.put(s.sectionY(), s);
        return byY;
    }

    private static void assertFullBright(VoxelColumnData.SectionData section, String what) {
        assertNotNull(section, what);
        assertTrue(section.section().hasOnlyAir(), what + ": all-air");
        assertNotNull(section.skyLight(), what + ": carries sky light");
        for (byte b : section.skyLight().getData()) assertEquals((byte) 0xFF, b, what + ": sky 15");
    }

    /** Stone up to local y=7 under open sky: 15 above the surface, 0 inside the stone. */
    private static void assertLitSurface(VoxelColumnData.SectionData section) {
        assertNotNull(section.skyLight(), "the surface section must carry the estimated sky layer");
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(y >= 8 ? 15 : 0, section.skyLight().get(x, y, z), "x=" + x + " y=" + y + " z=" + z);
        }
    }

    @Test
    void aContentColumnWithoutSkyLightIsLitByTheEstimate() {
        var byY = drainTall(columnWire(at(-4, stoneUpTo(15)), at(-3, stoneUpTo(7))), false, true);

        assertEquals(java.util.Set.of(-4, -3, -2, -1, 0, 1, 2, 3), byY.keySet(),
                "the two served sections, the air section above the surface, and the implicit sky above that");
        assertLitSurface(byY.get(-3));
        assertFalse(byY.get(-3).section().hasOnlyAir(), "the decoded terrain is kept");
        assertNull(byY.get(-3).blockLight(), "no block light is invented");
        assertNull(byY.get(-4).skyLight(), "solid stone under the surface receives nothing and stays without a layer");
        for (int y = -2; y <= 3; y++) assertFullBright(byY.get(y), "air section " + y);
        assertEquals(1, processor.getSkyEstimatedColumns());
    }

    @Test
    void skyLayersThatArePresentButAllZeroCountAsNoSkyLight() {
        // Older saves and servers ship allocated-but-dark layers; they are not real light.
        var wire = columnWire(new WireSection(-4, stoneUpTo(15), null, new byte[2048]),
                new WireSection(-3, stoneUpTo(7), null, new byte[2048]));
        var byY = drainTall(wire, false, true);

        assertLitSurface(byY.get(-3));
        assertNotNull(byY.get(-4).skyLight(), "the dark layer that was sent stays");
        for (byte b : byY.get(-4).skyLight().getData()) assertEquals(0, b);
        assertFullBright(byY.get(-2), "the air section above the surface");
        assertEquals(1, processor.getSkyEstimatedColumns());
    }

    @Test
    void oneNonZeroSkyNibbleAnywhereLeavesTheColumnExactlyAsSent() {
        // Real light must never be replaced: a lit column's caves and overhangs stay as
        // the server sent them. One nibble, on the deepest section, is enough.
        byte[] almostDark = new byte[2048];
        almostDark[5] = 0x01;
        var wire = columnWire(new WireSection(-4, stoneUpTo(15), null, almostDark), at(-3, stoneUpTo(7)));
        var byY = drainTall(wire, false, true);

        assertNull(byY.get(-3).skyLight(), "the section without a layer keeps none");
        assertArrayEquals(almostDark, byY.get(-4).skyLight().getData());
        assertFullBright(byY.get(-2), "the implicit-sky fill still runs above the served top");
        assertEquals(0, processor.getSkyEstimatedColumns());
    }

    @Test
    void noSkyEstimateInADimensionWithoutSkyLight() {
        // The H-12 class must not return: Nether terrain lit as daylight.
        var byY = drainTall(columnWire(at(-4, stoneUpTo(15)), at(-3, stoneUpTo(7))), false, false);

        assertEquals(java.util.Set.of(-4, -3), byY.keySet());
        assertNull(byY.get(-3).skyLight());
        assertNull(byY.get(-4).skyLight());
        assertEquals(0, processor.getSkyEstimatedColumns());
    }

    @Test
    void noSkyEstimateForAnAllAirColumnOrAClear() {
        var air = drainTall(columnWire(at(-3, new LevelChunkSection(FACTORY))), false, true);
        assertNull(air.get(-3).skyLight(), "a served all-air section is untouched");
        assertEquals(0, processor.getSkyEstimatedColumns());

        var clear = drainTall(columnWire(), true, true);
        assertEquals(TALL_SECTIONS, clear.size(), "the clear keeps its own bright air-fill");
        assertEquals(0, processor.getSkyEstimatedColumns());
    }

    @Test
    void noSkyEstimateAfterATruncatedDecode() {
        // The wire carries one section more than the client level has, and that section is
        // a solid lid over everything. The decode drops it; light derived from the rest
        // would show daylight where the server's world has none.
        var sections = new WireSection[TALL_SECTIONS + 1];
        for (int i = 0; i < TALL_SECTIONS - 1; i++) sections[i] = at(MIN_SECTION_Y + i, stoneUpTo(15));
        sections[TALL_SECTIONS - 1] = at(MIN_SECTION_Y + TALL_SECTIONS - 1, stoneUpTo(7));
        sections[TALL_SECTIONS] = at(MIN_SECTION_Y + TALL_SECTIONS, stoneUpTo(15));

        var truncated = drainTall(columnWire(sections), false, true);
        assertEquals(TALL_SECTIONS, truncated.size(), "premise: the ninth section was cut off");
        for (var s : truncated.values()) assertNull(s.skyLight(), "no light is derived from a truncated column");
        assertEquals(0, processor.getSkyEstimatedColumns());

        // Control: the same eight sections, sent as a column of eight, are estimated.
        var whole = drainTall(columnWire(java.util.Arrays.copyOf(sections, TALL_SECTIONS)), false, true);
        assertLitSurface(whole.get(MIN_SECTION_Y + TALL_SECTIONS - 1));
        assertEquals(1, processor.getSkyEstimatedColumns());
    }

    @Test
    void decodeReportsWhetherTheSectionCountWasTruncated() {
        assertFalse(ClientColumnProcessor.decodeColumn(sectionWire(4, 4), LEVEL_SECTIONS, FACTORY).truncated());
        assertFalse(ClientColumnProcessor.decodeColumn(sectionWire(0, 0), LEVEL_SECTIONS, FACTORY).truncated());
        assertFalse(ClientColumnProcessor.decodeColumn(sectionWire(-3, 0), LEVEL_SECTIONS, FACTORY).truncated());
        var cut = ClientColumnProcessor.decodeColumn(sectionWire(6, 6), LEVEL_SECTIONS, FACTORY);
        assertTrue(cut.truncated(), "more sections claimed than the level has");
        assertEquals(LEVEL_SECTIONS, cut.sections().length);
        assertTrue(ClientColumnProcessor.decodeColumn(sectionWire(1_000_000, LEVEL_SECTIONS), LEVEL_SECTIONS, FACTORY)
                .truncated(), "a hostile count is a truncation too");
    }

    @Test
    void theSkyEstimateRunsBeforeTheImplicitSkyFillAndTheResyncAirFill() {
        // A floating platform in section -2, nothing else, re-served (resync). Order pinned:
        //  1. estimate: lights section -2, adds the air sections directly above (-1) and below (-3);
        //  2. implicit-sky fill: everything above the top present section (0..3) full-bright;
        //  3. resync air-fill: what is still absent (-4) becomes DARK air.
        // Run after (2) the estimate would see the fill's light and not fire; run after (3)
        // it would treat the fill's dark air as part of the column and light section -4.
        var byY = drainTall(columnWire(at(-2, halfPlatform())), true, true);

        assertEquals(java.util.Set.of(-4, -3, -2, -1, 0, 1, 2, 3), byY.keySet());
        var platform = byY.get(-2).skyLight();
        assertNotNull(platform, "the platform's section is estimated");
        assertEquals(15, platform.get(3, 9, 5), "on top of the platform");
        assertEquals(0, platform.get(3, 8, 5), "the platform itself");
        assertEquals(15, platform.get(12, 3, 5), "beside it, open to the sky");
        assertEquals(14, platform.get(7, 3, 5), "under its edge, lit from the side");
        assertEquals(7, platform.get(0, 3, 5), "eight blocks in");
        assertFullBright(byY.get(-1), "the air section above the platform");
        var below = byY.get(-3);
        assertTrue(below.section().hasOnlyAir());
        assertNotNull(below.skyLight(), "the air section directly below the platform is added with its light");
        assertEquals(15, below.skyLight().get(12, 5, 5));
        assertEquals(14, below.skyLight().get(7, 5, 5));
        for (int y = 0; y <= 3; y++) assertFullBright(byY.get(y), "implicit sky, section " + y);
        assertTrue(byY.get(-4).section().hasOnlyAir());
        assertNull(byY.get(-4).skyLight(), "the resync fill below the estimated band stays dark air");
        assertEquals(1, processor.getSkyEstimatedColumns());

        // A first serve has no air-fill: the section the estimate did not add stays absent.
        var first = drainTall(columnWire(at(-2, halfPlatform())), false, true);
        assertEquals(java.util.Set.of(-3, -2, -1, 0, 1, 2, 3), first.keySet());
    }

    @Test
    void theSkyEstimateKeepsBlockLightAsSent() {
        byte[] blockLight = new byte[2048];
        for (int i = 0; i < blockLight.length; i++) blockLight[i] = (byte) (i * 31);
        var wire = columnWire(new WireSection(-3, stoneUpTo(7), blockLight, null));
        var byY = drainTall(wire, false, true);

        assertLitSurface(byY.get(-3));
        assertArrayEquals(blockLight, byY.get(-3).blockLight().getData());
        assertNull(byY.get(-2).blockLight(), "the added air section has no block light");
    }

    // ---- review fold (2026-11-12): robustness of the estimate ----

    @Test
    void aSectionWithBlocksAndAZeroBlockCountShadesTheColumnInTheDrain() {
        // The block count in a section's header comes from the wire. A solid layer whose
        // header says "empty" must still be a roof: the terrain under it stays dark.
        var roof = new LevelChunkSection(FACTORY);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) roof.setBlockState(x, 5, z, stone, false);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        byte[] wire;
        try {
            buf.writeVarInt(3);
            for (var s : new WireSection[]{at(-4, stoneUpTo(15)), at(-3, stoneUpTo(7)), at(-1, roof)}) {
                buf.writeByte(s.y());
                int header = buf.writerIndex();
                s.section().write(buf);
                if (s.section() == roof) buf.setShort(header, 0);   // the lie: no non-empty blocks
                buf.writeBoolean(false);
                buf.writeBoolean(false);
            }
            wire = drainToArray(buf);
        } finally {
            buf.release();
        }
        var byY = drainTall(wire, false, true);

        assertTrue(byY.get(-1).section().hasOnlyAir(), "premise: the decoded header says the roof section is empty");
        assertEquals(stone, byY.get(-1).section().getBlockState(4, 5, 4), "premise: its blocks are there");
        assertNull(byY.get(-3).skyLight(), "the surface under the roof receives no sky light");
        assertNull(byY.get(-4).skyLight());
        assertEquals(15, byY.get(-1).skyLight().get(4, 6, 4), "the roof section is lit above its blocks");
        assertEquals(0, byY.get(-1).skyLight().get(4, 4, 4), "and dark under them");
        assertFalse(byY.containsKey(-2), "no lit air section appears between roof and terrain");
        assertEquals(1, processor.getSkyEstimatedColumns());
    }

    @Test
    void aFailingSkyEstimateDeliversTheColumnAsItArrived() {
        // The estimate is an improvement, never a condition: left to the drain's general
        // catch, a throw would be an ingest failure, re-served and failed again until the
        // failure cap parks the position, and the terrain would be lost instead of dark.
        processor = new ClientColumnProcessor(
                (d, cx, cz) -> reports.add(new Report(d, cx, cz)), () -> null,
                (sections, levelSectionCount, minSectionY, factory) -> {
                    throw new IllegalStateException("estimate failed");
                });
        var byY = drainTall(columnWire(at(-4, stoneUpTo(15)), at(-3, stoneUpTo(7))), false, true);

        assertEquals(java.util.Set.of(-4, -3, -2, -1, 0, 1, 2, 3), byY.keySet(),
                "the column is dispatched: its two sections and the implicit sky above them");
        assertFalse(byY.get(-3).section().hasOnlyAir());
        assertNull(byY.get(-3).skyLight(), "as it arrived: without sky light");
        assertNull(byY.get(-4).skyLight());
        assertFullBright(byY.get(-2), "the implicit-sky fill still runs");
        assertEquals(List.of(), reports, "no ingest failure: the position must not be re-requested or parked");
        assertEquals(1, processor.getSkyEstimateFailures());
        assertEquals(0, processor.getSkyEstimatedColumns());
        assertTrue(processor.skyEstimateFailureLogged(), "the first failure of a session is logged");

        drainTall(columnWire(at(-3, stoneUpTo(7))), true, true);
        assertEquals(2, processor.getSkyEstimateFailures(), "every failed column is counted");
        assertEquals(List.of(), reports);
        processor.resetStats();
        assertEquals(0, processor.getSkyEstimateFailures());
        processor.shutdown();
        assertFalse(processor.skyEstimateFailureLogged(), "the next session logs its first failure again");
    }

    @Test
    void theLightDiagnosticsLineShowsTheFailureCounterOnlyWhenNonZero() {
        assertNull(ClientCommandActions.lightLine(0, 0), "no line on a server without lightless chunks");
        assertEquals("Light: sky_estimated=5", ClientCommandActions.lightLine(5, 0));
        assertEquals("Light: sky_estimated=5, sky_estimate_failed=2", ClientCommandActions.lightLine(5, 2));
        assertEquals("Light: sky_estimated=0, sky_estimate_failed=3", ClientCommandActions.lightLine(0, 3));
    }

    @Test
    void resetStatsClearsTheSkyEstimateCounter() {
        drainTall(columnWire(at(-3, stoneUpTo(7))), false, true);
        assertEquals(1, processor.getSkyEstimatedColumns());
        processor.resetStats();
        assertEquals(0, processor.getSkyEstimatedColumns());
    }

    /** Source-regex pin (review C1-6, the dialectFlipFor precedent): the PRODUCTION
     *  ClientLevel drain must route bodies through the v20 translation (with the v16
     *  native-body gate) — a refactor onto the 7-arg identity-overload compiles clean
     *  and silently ships untranslated columns to every consumer. */
    @org.junit.jupiter.api.Test
    void productionDrainRoutesThroughTheV20TranslationWithTheV16Gate() throws Exception {
        java.nio.file.Path src = dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientColumnProcessor.java");
        String text = java.nio.file.Files.readString(src);
        int prodDrain = text.indexOf("private void drainColumnQueue(ClientLevel level");
        assertTrue(prodDrain >= 0, "production drain entry missing");
        String body = text.substring(prodDrain, text.indexOf("}", text.indexOf("epoch);", prodDrain)));
        assertTrue(body.contains("resolver::toNative"),
                "the production drain must translate v20 bodies: " + body);
        // The gate moved INTO the inner drain as the per-payload decode-time stamp
        // (review MAJOR-2): the drain thread must never consult the live dialect flag.
        assertTrue(text.contains("payload.nativeBodyAtDecode()"),
                "the inner drain must gate translation on the per-payload decode-time stamp");
    }
}
