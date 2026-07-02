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
import net.minecraft.resources.Identifier;
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
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
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
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
    }

    /** A real manager configured like the production factory wires it, scanning {@code dim}. */
    private LodRequestManager managedManager() {
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, 100, 100, true), "lss-test");
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

    // ---- WS3 authoritative air-fill: a resync clears ghost terrain for absent sections ----

    @Test
    void airFillAddsAllAirSectionsForEveryAbsentYOnResync() {
        var present = new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(-4, new LevelChunkSection(FACTORY), null, null)};
        var filled = ClientColumnProcessor.withAirFilledAbsentSections(present, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY);

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

    @Test
    void resyncColumnAirFillsAbsentSectionsButFirstServeDoesNot() {
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:processor"));
        // sectionWire writes sections at Y=0,1,..., so use minSectionY=0 -> range {0,1,2,3}.
        byte[] wire = sectionWire(1, 1); // one section at Y=0

        processor.offer(new VoxelColumnS2CPayload(0, 0, dim, 5000L, wire), true); // resync
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, 0, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
        assertEquals(LEVEL_SECTIONS, dispatches.get(dispatches.size() - 1).sectionCount(),
                "a resync fills every absent section-Y with air (present + air = full level range)");

        processor.offer(new VoxelColumnS2CPayload(1, 1, dim, 5000L, wire), false); // first serve
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, 0, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
        assertEquals(1, dispatches.get(dispatches.size() - 1).sectionCount(),
                "a first serve dispatches only the carried section — no air-fill (absent == air == no-op)");
    }

    @Test
    void reportUndispatchedUnstampsQueuedColumnsBeforeTheCacheFlush() {
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:processor"));
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, 100, 100, true), "lss-test");
        manager.setLastDimensionForTest(dim);
        long packed = PositionUtil.packPosition(0, 0);
        manager.onColumnReceived(packed, 5000L, dim); // stamped at arrival, decode still pending

        offer(1);
        processor.reportUndispatched(manager);

        assertEquals(0, processor.getQueuedCount(), "undispatched queue must be drained");
        assertEquals(-1L, manager.columnsForTest().classify(packed, true),
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
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY, teardownMidDispatch, epoch);

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

    // ---- CL-044: the three clear paths report instead of silently dropping (D2) ----

    @Test
    void receiveServerLodsDisableFlipReportsClearedBacklog() {
        processor.offer(new VoxelColumnS2CPayload(1, 2, dim, 1L, sectionWire(1, 1)), false);
        processor.offer(new VoxelColumnS2CPayload(3, 4, dim, 1L, sectionWire(1, 1)), false);
        boolean prior = LSSClientConfig.CONFIG.receiveServerLods;
        LSSClientConfig.CONFIG.receiveServerLods = false;
        try {
            processor.scheduleProcessing(true); // serverEnabled stays true — the config flip clears
        } finally {
            LSSClientConfig.CONFIG.receiveServerLods = prior;
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
        boolean prior = LSSClientConfig.CONFIG.receiveServerLods;
        LSSClientConfig.CONFIG.receiveServerLods = true;
        try {
            processor.scheduleProcessing(true); // no consumer registered — the dereg clear path
        } finally {
            LSSClientConfig.CONFIG.receiveServerLods = prior;
        }

        assertEquals(0, processor.getQueuedCount());
        assertEquals(List.of(new Report(dim, 8, 9)), reports,
                "columns cleared because the last consumer deregistered must still be reported");
    }

    @Test
    void levelNullRaceReportsClearedBacklog() {
        VoxelColumnConsumer consumer = (level, d, cx, cz, data) -> {};
        boolean prior = LSSClientConfig.CONFIG.receiveServerLods;
        LSSClientConfig.CONFIG.receiveServerLods = true;
        LSSApi.registerColumnConsumer(consumer);
        try {
            processor.offer(new VoxelColumnS2CPayload(3, 3, dim, 1L, sectionWire(1, 1)), false);
            processor.scheduleProcessing(true); // levelSupplier yields null: the disconnect race window
        } finally {
            LSSApi.removeColumnConsumer(consumer);
            LSSClientConfig.CONFIG.receiveServerLods = prior;
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
        var gate = new ClientSessionGate(processor, () -> {}, cfg -> new OrderRecordingManager(events));
        gate.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, 100, 100, true), true);
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

        var processorField = LSSClientNetworking.class.getDeclaredField("columnProcessor");
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

        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY, recordingDispatcher, staleEpoch);

        assertEquals(List.of(), dispatches,
                "a drain scheduled before teardown must not dispatch into the new session");
        assertEquals(1, processor.getQueuedCount(),
                "the straggler is left for shutdown, not consumed by the stale drain");

        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY, recordingDispatcher,
                processor.sessionEpochForTest());
        assertEquals(List.of(new Dispatched(2, 2, 1)), dispatches,
                "only the epoch gates the drain — a current-epoch drain still serves");
    }

    // ---- CL-047: a decode-failure unstamp survives the disconnect cache flush ----

    @Test
    void decodeFailureUnstampSurvivesDisconnectCachePersistence() {
        String serverAddr = "lss-test-cl047-" + System.nanoTime();
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, 100, 100, true), serverAddr);
        manager.setLastDimensionForTest(dim);
        manager.onColumnReceived(PositionUtil.packPosition(10, 10), 5000L, dim);
        manager.onColumnReceived(PositionUtil.packPosition(11, 11), 6000L, dim);

        // Reporter routes straight to the manager — the production LSSClientNetworking
        // hop (mc.execute) collapsed to a synchronous call.
        var proc = new ClientColumnProcessor(
                (d, cx, cz) -> manager.onIngestFailure(d, PositionUtil.packPosition(cx, cz)),
                () -> null);
        var gate = new ClientSessionGate(proc, () -> {}, cfg -> manager);
        gate.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, 100, 100, true), true);
        try {
            proc.offer(new VoxelColumnS2CPayload(11, 11, dim, 6000L, truncatedColumnWire()), false);
            proc.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, FACTORY, (d, cx, cz, data) -> {},
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
}
