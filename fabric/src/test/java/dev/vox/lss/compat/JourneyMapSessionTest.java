package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.networking.client.LodRequestManager;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import journeymap.client.JourneymapClient;
import journeymap.client.JourneymapClientWithoutSuspend;
import journeymap.client.cartography.ChunkRenderController;
import journeymap.client.data.DataCache;
import journeymap.client.io.FileHandler;
import journeymap.client.model.chunk.ChunkMD;
import journeymap.client.model.chunk.SnapshotChunkMD;
import journeymap.client.model.map.MapType;
import journeymap.client.model.region.RegionImageCache;
import journeymap.client.task.multi.TaskController;
import journeymap.common.chunk.JMChunkSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JourneyMap map-ingestion session (map-ingestion plan §3.4-§3.8, §11) against the
 * real-package stubs: identity settle, box-sized multi-region batches on JourneyMap's task
 * thread with the pure decode run ahead on workers, the receipt doctrine (accepted only when the day map rendered under an unchanged identity;
 * everything else reported or requeued, never silently lost), the edge sweep, the
 * inert states that never hold the stream, the late-world-id replay, the watchdog, the
 * death latch, and the backpressure report.
 */
class JourneyMapSessionTest {
    static {
        // Before the constants below: touching Level initializes registries, which need the
        // bootstrap — run alone (no earlier suite in the JVM) the class failed to initialize.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> END = Level.END;
    private static final ResourceKey<Level> NETHER = Level.NETHER;

    private final Object levelMarker = new Object();
    private Object level;
    private ResourceKey<Level> dimension;
    private boolean ceiling;
    private boolean enabled;
    private boolean sessionActive;
    /** The connected server allows map writes (map-policy plan A4). */
    private boolean serverAllows;
    private boolean backpressure;
    private boolean failDecode;
    /** The host's level read throws (JourneyMap or Minecraft internals drifting at call time). */
    private boolean levelThrows;
    private long clock;
    /** Long.MIN_VALUE (no player) keeps edge sweeps off in the tests that are not about them. */
    private long playerChunk;
    private final List<long[]> reports = new ArrayList<>();
    /** The client's own loaded chunks (the edge sweep's "already drawn" key), by packed position. */
    private final java.util.Map<Long, Object> clientChunks = new java.util.HashMap<>();
    private final List<VoxelColumnConsumer> registered = new ArrayList<>();
    /** The decode-ahead "workers" under test control: jobs wait here and run only when a test
     *  runs them — by default never, so every column is claimed unstarted and decodes inline
     *  exactly as without decode-ahead. */
    private final List<Runnable> aheadQueue = new ArrayList<>();
    /** Jobs submitted to the decode-ahead executor (whatever happened to them). */
    private int aheadSubmitted;
    /** The executor rejects every job (a full or shut-down pool). */
    private boolean aheadRejects;
    /** Each job runs at once, as a worker that is always ahead (on the test thread). */
    private boolean aheadDirect;
    /** True while a direct job runs: "on a worker". */
    private boolean inWorker;
    private JourneymapClient jm;
    private JourneyMapSession session;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        this.jm = new JourneymapClient();
        JourneymapClient.INSTANCE = this.jm;
        DataCache.INSTANCE = new DataCache();
        RegionImageCache.INSTANCE = new RegionImageCache();
        MapType.DENIED.clear();
        FileHandler.worldDir = new File("jm-test/mp/Server_A");
        this.level = this.levelMarker;
        this.dimension = OVERWORLD;
        this.ceiling = false;
        this.enabled = true;
        this.sessionActive = true;
        this.serverAllows = true;
        this.backpressure = true;
        this.failDecode = false;
        this.levelThrows = false;
        this.clock = 1_000_000L;
        this.playerChunk = Long.MIN_VALUE;
        this.reports.clear();
        this.clientChunks.clear();
        this.registered.clear();
        this.aheadQueue.clear();
        this.aheadSubmitted = 0;
        this.aheadRejects = false;
        this.aheadDirect = false;
        this.inWorker = false;
        this.host = new JourneyMapSession.Host() {
            @Override public Object minecraft() { return null; }
            @Override public Object level() {
                if (levelThrows) throw new IllegalStateException("drifted");
                return level;
            }
            @Override public boolean playerInLevel() { return level != null; }
            @Override public Object dimension(Object l) { return dimension; }
            @Override public boolean hasCeiling(Object l) { return ceiling; }
            @Override public long playerChunk() { return playerChunk; }
            @Override public Object liveChunk(Object l, int x, int z) {
                return clientChunks.get(JourneyMapSession.packed(x, z));
            }
        };
        newSession(JourneyMapBindings.resolve(Class::forName));
    }

    private JourneyMapSession.Host host;

    private void newSession(JourneyMapBindings bindings) {
        this.session = new JourneyMapSession(bindings, this.host,
                () -> this.enabled, () -> this.sessionActive, this.registered::add, this.registered::remove,
                () -> this.backpressure,
                (dim, x, z) -> this.reports.add(new long[]{dimIndex(dim), x, z}),
                () -> this.clock, () -> this.serverAllows);
        this.session.decoder = (bytes, l) -> {
            if (this.failDecode) return null;
            var buf = ByteBuffer.wrap(bytes);
            return new JMChunkSnapshot(buf.getInt(), buf.getInt());
        };
        this.session.wrapper = (l, snapshot) -> new SnapshotChunkMD(null, (JMChunkSnapshot) snapshot, true);
        this.session.decodeAheadExecutor = job -> {
            if (this.aheadRejects) throw new java.util.concurrent.RejectedExecutionException("test pool full");
            this.aheadSubmitted++;
            if (!this.aheadDirect) {
                this.aheadQueue.add(job);
                return;
            }
            this.inWorker = true;
            try {
                job.run();
            } finally {
                this.inWorker = false;
            }
        };
        this.session.maybeRegister();
    }

    @AfterEach
    void tearDown() {
        synchronized (this.session.queueLock) {
            assertEquals(this.session.queue.size(), this.session.queuedGauge,
                    "the diag queue gauge must match membership after every scenario");
        }
        JourneyMapSession.resetFacadeForTest();
    }

    private static long dimIndex(Object dim) {
        return dim == OVERWORLD ? 0 : dim == END ? 1 : dim == NETHER ? 2 : -1;
    }

    // ---- helpers ----

    private static byte[] frame(int x, int z) {
        return ByteBuffer.allocate(64).putInt(x).putInt(z).array();
    }

    private void offer(ResourceKey<Level> dim, int x, int z) {
        this.session.offerEncoded(new JourneyMapSession.Entry(dim, x, z, frame(x, z), this.session.new Receipt()));
    }

    private void offer(int x, int z) {
        offer(OVERWORLD, x, z);
    }

    /** Through the consumer-side gate; the gate drops before the (never-reached) encoder. */
    private void gatedOffer(int x, int z) {
        this.session.offerColumn(null, OVERWORLD, x, z, null);
    }

    /** A delivery's ingest handle: counts the acceptance leases its receipts take, their
     *  releases, and its re-serve reports (with a handle, a report goes here, never to the
     *  session's drop reporter). */
    private static final class Handle implements LSSApi.IngestFailureHandle {
        int leases;
        int released;
        int reports;
        int written;
        /** A "written" after the lease was released would arrive after the delivery ended,
         *  which is when the map's debt is decided. */
        boolean writtenTooLate;

        @Override
        public void report() {
            this.reports++;
        }

        @Override
        public void written() {
            this.written++;
            if (this.leases > 0 && this.released == this.leases) this.writtenTooLate = true;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public Runnable deferAcceptance() {
            this.leases++;
            var done = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (done.compareAndSet(false, true)) this.released++;
            };
        }
    }

    /** {@link #offer} inside a delivery that carries {@code handle}. */
    private void offerWith(Handle handle, int x, int z) {
        LSSApi.withIngestFailureHandle(OVERWORLD, x, z, handle, () -> offer(x, z));
    }

    /** {@link #gatedOffer} inside a delivery that carries {@code handle}. */
    private void gatedOfferWith(Handle handle, int x, int z) {
        LSSApi.withIngestFailureHandle(OVERWORLD, x, z, handle, () -> gatedOffer(x, z));
    }

    /** Every lease the handle's receipts took was released, and none was reported. */
    private static void assertClosedUnreported(Handle handle, int receipts, String message) {
        assertEquals(receipts, handle.leases, "premise: one lease per receipt");
        assertEquals(receipts, handle.released, message + ": every receipt closes");
        assertEquals(0, handle.reports, message + ": none is reported for a re-serve");
    }

    /** Observe the identity, then wait out the settle window: the next pump may submit. */
    private void settle() {
        this.session.pump();
        assertEquals("waiting(settle)", this.session.state());
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
    }

    /** Pump (submit), run the queued batch on the "task thread", pump again (reconcile). */
    private int cycle() {
        this.session.pump();
        int ran = this.jm.runQueued();
        this.session.pump();
        return ran;
    }

    private long counter(String name) {
        return this.session.counterForTest(name);
    }

    private List<ChunkRenderController.Render> renders() {
        return this.jm.renderController.renders;
    }

    // ---- the happy path ----

    @Test
    void aSettledIdentityRendersABatchAndAcceptsIt() {
        offer(0, 0);
        offer(1, 0);
        offer(0, 1);
        offer(40, 0); // region (1, 0)
        this.session.pump();
        assertEquals(0, this.jm.queued.size(), "nothing is written before the identity settles");
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        assertEquals(1, cycle());
        assertEquals(12, renders().size(), "day + topo + biome for each column of both regions");
        assertEquals(java.util.Set.of(0, 1), renders().stream().map(r -> r.region().regionX)
                .collect(java.util.stream.Collectors.toSet()), "one batch draws from both regions");
        assertEquals(FileHandler.worldDir, renders().getFirst().region().worldDir);
        assertEquals(4, counter("rendered"));
        assertEquals(1, RegionImageCache.INSTANCE.updates);
        assertEquals(4, DataCache.INSTANCE.added.size());
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "every snapshot wrapper leaves JourneyMap's cache");
        assertTrue(this.reports.isEmpty());
        assertEquals(0, this.session.queuedGauge);
        assertEquals(4, this.session.replay.size());
        assertTrue(renders().stream().allMatch(r -> ((SnapshotChunkMD) r.chunk()).forceRetain),
                "the wrapper retains its chunk strongly for the whole render");
        assertEquals(4, renders().stream().map(ChunkRenderController.Render::chunk).distinct()
                .mapToInt(md -> md.getBlockData().clears).sum(), "block data is cleared once per column");

        offer(2, 2);
        this.session.pump();
        assertEquals(0, this.jm.queued.size(), "the duty gate idles half a renderDelay after a batch");
        this.clock += 500;
        assertEquals(1, cycle());
        assertEquals(5, counter("rendered"));
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void onlyTheEnabledAndAllowedMapTypesRender() {
        MapType.DENIED.add("topo");
        this.jm.properties.mapBiome.value = false;
        offer(0, 0);
        settle();
        cycle();
        assertEquals(1, renders().size());
        assertEquals("day", renders().getFirst().type().name);
    }

    @Test
    void aDayMapThatIsNotAllowedIsInertAndNeverHoldsTheStream() {
        MapType.DENIED.add("day");
        offer(0, 0);
        this.session.pump();
        assertEquals("inert(mapping_not_allowed)", this.session.state());
        assertEquals(0, this.session.queuedGauge, "same-dimension columns are released");
        assertTrue(this.reports.isEmpty(), "re-serving cannot help a map that is not allowed here");
        assertEquals(-1, this.session.reportBackpressure());
        gatedOffer(1, 1);
        assertEquals(1, counter("dropped_inert"), "dropped before encoding");
        assertEquals(0, this.session.queuedGauge);
    }

    // ---- live chunks belong to JourneyMap ----

    @Test
    void aLiveChunkIsSkippedAndAcceptedNotReported() {
        long key = JourneyMapSession.mcChunkKey(1, 1);
        DataCache.INSTANCE.live.put(key, new ChunkMD(1, 1, true));
        offer(1, 1);
        offer(2, 2);
        settle();
        cycle();
        assertEquals(1, counter("skipped_live"));
        assertEquals(1, counter("rendered"));
        assertTrue(this.reports.isEmpty());
        assertTrue(renders().stream().noneMatch(r -> r.chunk().chunkX == 1 && r.chunk().chunkZ == 1));
    }

    @Test
    void aLiveChunkArrivingMidBatchWinsAndKeepsItsCacheEntry() {
        long key = JourneyMapSession.mcChunkKey(3, 3);
        var live = new ChunkMD(3, 3, true);
        DataCache.INSTANCE.onAllowCachedQuery = () -> DataCache.INSTANCE.live.put(key, live);
        offer(3, 3);
        settle();
        cycle();
        assertEquals(1, counter("skipped_live"));
        assertEquals(0, counter("rendered"));
        assertEquals(live, DataCache.INSTANCE.cache.get(key), "removal touches only our own wrapper");
    }

    // ---- the map's debt (issue #380): which deliveries learn that the map has the column ----

    /** A rendered column and one JourneyMap draws itself from the live chunk are on the map;
     *  each says so once, before its lease is released. */
    @Test
    void aRenderedColumnAndALiveChunkTellTheirDeliveries() {
        DataCache.INSTANCE.live.put(JourneyMapSession.mcChunkKey(1, 1), new ChunkMD(1, 1, true));
        var rendered = new Handle();
        var live = new Handle();
        offerWith(rendered, 2, 2);
        offerWith(live, 1, 1);
        assertEquals(0, rendered.written, "nothing is said for a column that is only queued");
        settle();
        cycle();
        assertEquals(1, counter("rendered"), "premise");
        assertEquals(1, counter("skipped_live"), "premise");
        assertEquals(1, rendered.written);
        assertEquals(1, live.written);
        assertFalse(rendered.writtenTooLate || live.writtenTooLate);
        assertEquals(1, rendered.released);
        assertEquals(1, live.released);
    }

    /** A column the bridge lets go says nothing: its delivery ends without "written", which
     *  is what leaves the map owed it. */
    @Test
    void aColumnTheBridgeLetsGoNeverSaysItIsOnTheMap() {
        // A deny that lands while the column is queued clears the queue.
        var denied = new Handle();
        offerWith(denied, 0, 0);
        this.serverAllows = false;
        this.session.pump();
        assertClosedUnreported(denied, 1, "the cleared queue");
        assertEquals(0, denied.written);
        // An offer while denied is dropped before it is queued.
        var refused = new Handle();
        gatedOfferWith(refused, 3, 3);
        assertEquals(0, refused.written);
        assertEquals(refused.leases, refused.released);
    }

    /** A column that cannot be decoded is closed without a re-serve (it would fail the same
     *  way again), and it is not on the map. */
    @Test
    void anUndecodableColumnIsNotOnTheMap() {
        this.failDecode = true;
        settle();
        var undecodable = new Handle();
        offerWith(undecodable, 5, 5);
        assertEquals(1, cycle());
        assertEquals(1, counter("decode_failures"), "premise");
        assertClosedUnreported(undecodable, 1, "the decode failure");
        assertEquals(0, undecodable.written);
    }

    /** A column whose day map was refused is sent again, as before; it is not on the map. */
    @Test
    void aReportedColumnIsNotOnTheMap() {
        this.jm.renderController.result = (type, md) -> !type.name.equals("day");
        var handle = new Handle();
        offerWith(handle, 0, 0);
        settle();
        cycle();
        assertEquals(1, handle.reports, "premise: reported");
        assertEquals(0, handle.written);
        assertEquals(1, handle.released);
    }

    // ---- failures are reported, never silently lost ----

    @Test
    void aFailedDayMapReportsTheColumn() {
        this.jm.renderController.result = (type, md) -> !type.name.equals("day");
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(0, counter("rendered"));
        assertEquals(2, counter("render_failed"));
        assertEquals(2, this.reports.size(), "the day map decides: a refused day render re-serves");
        assertEquals(2, renders().size(), "optional types are not attempted after a refused day render");
    }

    @Test
    void refusedOptionalTypesAreCountedNotReserved() {
        // JourneyMap's own topography renderer refuses every chunk until its palettes load;
        // its own pipeline ignores the result, so a re-serve could never fix it.
        this.jm.renderController.result = (type, md) -> !type.name.equals("topo");
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"));
        assertEquals(2, counter("optional_refusals"));
        assertTrue(this.reports.isEmpty());
    }

    @Test
    void aThrowingColumnIsReportedAndTheBatchCompletes() {
        this.jm.renderController.throwing = new IllegalStateException("render bug");
        offer(0, 0);
        settle();
        cycle();
        assertEquals(1, counter("column_failures"));
        assertEquals(1, this.reports.size());
        assertNull(this.session.inFlight, "the batch still reached its terminal state");
        assertTrue(DataCache.INSTANCE.cache.isEmpty());
    }

    @Test
    void decodeFailuresCloseUnreportedAndFiveFailedBatchesKillTheSession() {
        this.failDecode = true;
        settle();
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH; i++) {
            for (int x = 0; x < 7; x++) offer(x, i); // 5 × 7 ≥ DEATH_LATCH_MIN_COLUMNS
            assertEquals(1, cycle());
            this.clock += 1000;
        }
        assertEquals(JourneyMapSession.DEATH_LATCH * 7, counter("decode_failures"));
        assertTrue(this.reports.isEmpty(), "a format mismatch would fail identically on a re-serve");
        assertEquals("dead", this.session.state());
        assertEquals(-1, this.session.reportBackpressure());
        gatedOffer(9, 9);
        assertEquals(0, this.session.queuedGauge, "a dead session drops offers");
        assertEquals(0, this.session.encodedColumns.get());
    }

    // ---- identity ----

    @Test
    void anIdentityChangeWhileQueuedAbandonsAndReports() {
        offer(0, 0);
        offer(1, 0);
        settle();
        this.session.pump();
        assertEquals(1, this.jm.queued.size());
        this.jm.controller = new TaskController(); // JourneyMap restarted mapping
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        assertEquals(1, counter("abandoned"));
        assertEquals(2, this.reports.size());
        assertEquals("waiting(settle)", this.session.state());
        this.jm.runQueued();
        assertEquals(0, renders().size(), "the abandoned runnable loses its start CAS and does nothing");
    }

    @Test
    void aBatchThatNeverStartsIsReclaimedByTheWatchdog() {
        offer(0, 0);
        settle();
        this.session.pump();
        this.jm.queued.clear(); // the controller was replaced: the runnable is gone
        this.clock += JourneyMapSession.START_TIMEOUT_MILLIS + 1;
        this.session.pump();
        assertEquals(1, counter("abandoned"));
        assertEquals(1, this.reports.size());
        assertNull(this.session.inFlight);
    }

    @Test
    void anIdentityChangeMidBatchReportsEvenTheRenderedColumns() {
        this.jm.renderController.result = (type, md) -> {
            FileHandler.worldDir = new File("jm-test/mp/Server_A_late-world-id");
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(0, counter("rendered"));
        assertEquals(1, counter("abandoned"));
        assertEquals(2, this.reports.size(), "pixels written under the wrong identity re-serve into the new map");
    }

    @Test
    void aLateWorldIdReplaysTheRecentRendersIntoTheNewDirectory() {
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"));
        assertTrue(this.reports.isEmpty());
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.jm.worldId = "abcdef";
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        assertEquals(2, counter("replayed"));
        assertEquals(2, this.reports.size());
        assertTrue(this.session.replay.isEmpty());
    }

    @Test
    void renderedColumnsOlderThanTheReplayWindowAreNotReplayed() {
        offer(0, 0);
        settle();
        cycle();
        this.clock += JourneyMapSession.REPLAY_WINDOW_MILLIS + 1;
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.session.pump();
        assertEquals(0, counter("replayed"));
        assertTrue(this.reports.isEmpty());
    }

    @Test
    void aLateWorldIdReplaysEveryColumnRenderedInTheWindowAtFullThroughput() {
        dayOnly();
        // 1 ms per column on JourneyMap's thread, faster than the measured ~1.2 ms: the adaptive
        // pick fills the 1000 ms box, about 770 columns/s with the gap (the 26.2 restage: 550).
        this.jm.renderController.result = (type, md) -> {
            this.clock += 1;
            return true;
        };
        int side = 90; // 8100 columns: about twice the 4096 entries the ring used to hold
        var offered = new java.util.HashSet<Long>();
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            offer(x, z);
            offered.add(JourneyMapSession.packed(x, z));
        }
        settle();
        long firstBatch = this.clock;
        for (int i = 0; i < 100 && this.session.queuedGauge > 0; i++) {
            cycle();
            this.clock += this.session.renderDelayMillis / 2; // the idle gap
        }
        assertEquals(side * side, counter("rendered"), "premise: every column rendered");
        assertEquals(0, counter("requeued"), "premise: measured picks fit the box");
        assertTrue(this.clock - firstBatch < JourneyMapSession.REPLAY_WINDOW_MILLIS,
                "premise: all of it inside the window: " + (this.clock - firstBatch) + " ms");
        assertTrue(this.reports.isEmpty());

        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef"); // the late world id
        this.jm.worldId = "abcdef";
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        assertEquals(side * side, counter("replayed"), "every column drawn into the id-less directory");
        var reported = new java.util.HashSet<Long>();
        for (long[] r : this.reports) {
            assertEquals(0, r[0], "the overworld");
            reported.add(JourneyMapSession.packed((int) r[1], (int) r[2]));
        }
        assertEquals(side * side, this.reports.size(), "each once");
        assertEquals(offered, reported, "no hole is left in the new directory's map");
    }

    @Test
    void theReplayRingHoldsTheWindowNotAFixedCount() {
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, this.session.replay.size());
        this.clock += JourneyMapSession.REPLAY_WINDOW_MILLIS + 1;
        offer(5, 5);
        cycle();
        assertEquals(1, this.session.replay.size(),
                "renders older than the window leave as new ones arrive: memory follows the window");
        assertEquals(new JourneyMapSession.Rendered(OVERWORLD, 5, 5, FileHandler.worldDir, this.clock),
                this.session.replay.peekFirst());
        assertEquals(49_152, JourneyMapSession.REPLAY_CAPACITY,
                "the hard cap: the window at one full pick per default batch cycle, 24 × 2048");
    }

    @Test
    void aDimensionChangeReportsTheOtherDimensionsColumns() {
        offer(0, 0);
        offer(1, 0);
        this.session.pump();
        this.dimension = END;
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        assertEquals(2, counter("dropped_stale"));
        assertEquals(2, this.reports.size(), "client stamps persist per dimension: only a report re-serves them");
        assertTrue(this.reports.stream().allMatch(r -> r[0] == 0));
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void aCeilingDimensionIsInertAndReportsOtherDimensionsColumns() {
        offer(OVERWORLD, 0, 0);
        offer(NETHER, 5, 5);
        this.dimension = NETHER;
        this.ceiling = true;
        this.session.pump();
        assertEquals("inert(ceiling_dimension)", this.session.state());
        assertEquals(1, this.reports.size(), "the overworld column re-serves; the nether one cannot be written");
        assertEquals(0, this.reports.getFirst()[0]);
        assertEquals(-1, this.session.reportBackpressure());
    }

    @Test
    void journeyMapDisabledIsInertAndDropsOffersBeforeEncoding() {
        offer(0, 0);
        this.jm.enabled = false;
        this.session.pump();
        assertEquals("inert(journeymap_disabled)", this.session.state());
        assertEquals(0, this.session.queuedGauge);
        assertTrue(this.reports.isEmpty());
        gatedOffer(1, 1);
        assertEquals(1, counter("dropped_inert"));
        this.jm.enabled = true;
        this.session.pump();
        assertEquals("waiting(settle)", this.session.state(), "re-enabled: a fresh identity settles");
    }

    @Test
    void notMappingHoldsTheQueueAndKeepsGoverning() {
        this.jm.mapping = Boolean.FALSE;
        offer(0, 0);
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state());
        assertEquals(1, this.session.queuedGauge, "a transient state holds: maps pace downloads");
        assertTrue(this.session.reportBackpressure() >= 0);
        this.jm.mapping = Boolean.TRUE;
        this.jm.suspended = true;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state(), "a suspended mapper holds too");
    }

    // ---- pacing ----

    @Test
    void theTimeBoxRequeuesTheRemainderForTheNextBatch() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        this.jm.properties.renderDelay.value = 100;
        this.jm.renderController.result = (type, md) -> {
            this.clock += 60;
            return true;
        };
        for (int i = 0; i < 5; i++) offer(i, 0);
        settle();
        cycle();
        assertEquals(4, counter("rendered"), "the box is two renderDelays: +0..+180 fit 200 ms, +240 does not");
        assertEquals(1, counter("requeued"));
        assertEquals(1, this.session.queuedGauge);
        assertTrue(this.reports.isEmpty(), "requeued, not reported");
        assertTrue(DataCache.INSTANCE.cache.isEmpty());
    }

    @Test
    void aNewerDeliveryReplacesARequeuedColumn() {
        this.jm.properties.renderDelay.value = 100;
        this.jm.renderController.result = (type, md) -> {
            this.clock += 200;
            if (md.chunkX == 0) offer(1, 0); // the newer delivery lands while rendering
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(1, this.session.queuedGauge, "one entry per position: the newer delivery wins");
    }

    @Test
    void theHeapBeltHoldsSubmitsAsAPacingHold() {
        this.session.memory = new MapMemoryGuard(() -> 0.95, () -> this.clock);
        offer(0, 0);
        settle();
        this.session.pump();
        assertEquals(0, this.jm.queued.size());
        assertTrue(this.session.heapHolding);
        this.backpressure = false;
        this.session.pump();
        assertEquals(1, this.jm.queued.size(), "the belt rides the backpressure kill switch");
    }

    @Test
    void aHeapHoldDoesNotOutliveAPumpThatNeverReachesTheBelt() {
        this.session.memory = new MapMemoryGuard(() -> 0.95, () -> this.clock);
        offer(0, 0);
        settle();
        this.session.pump();
        assertTrue(this.session.heapHolding);
        this.jm.mapping = false; // waiting on not_mapping: the belt is never consulted
        this.session.pump();
        assertFalse(this.session.heapHolding, "a stale hold must not keep the long pace box (re-review)");
    }

    @Test
    void edgeContextNeighboursJoinTheNextBatchButAreNeverRendered() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        offer(0, 0);
        settle();
        cycle();
        DataCache.INSTANCE.added.clear();
        renders().clear();
        this.clock += 500;
        offer(1, 0); // its west neighbour (0,0) is context
        cycle();
        assertEquals(List.of(1), renders().stream().map(r -> r.chunk().chunkX).toList());
        assertTrue(DataCache.INSTANCE.added.contains(JourneyMapSession.mcChunkKey(0, 0)),
                "the neighbour joins the cache for slope shading and biome blending");
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "and leaves it with the batch");
    }

    @Test
    void contextFromAnotherMapIsNeverLent() {
        offer(0, 0);
        settle();
        cycle();
        FileHandler.worldDir = new File("jm-test/mp/Server_B");
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump(); // identity change clears the context
        this.session.context.put(JourneyMapSession.packed(0, 0), new JourneyMapSession.ContextColumn(
                OVERWORLD, new File("jm-test/mp/Server_A"), frame(0, 0))); // a racing batch's late insert
        DataCache.INSTANCE.added.clear();
        offer(1, 0);
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        cycle();
        assertFalse(DataCache.INSTANCE.added.contains(JourneyMapSession.mcChunkKey(0, 0)));
    }

    // ---- review folds (J5) ----

    @Test
    void journeyMapsOwnMappingSwitchOffIsInertNotAHold() {
        offer(0, 0);
        this.jm.properties.mappingEnabled.value = false;
        this.jm.mapping = Boolean.FALSE; // JourneyMap stopped and will not restart
        this.session.pump();
        assertEquals("inert(mapping_disabled)", this.session.state());
        assertEquals(-1, this.session.reportBackpressure(), "never holds the LOD stream");
        assertEquals(0, this.session.queuedGauge);
        gatedOffer(1, 1);
        assertEquals(1, counter("dropped_inert"));
    }

    @Test
    void aLateWorldIdReplaysThroughJourneyMapsRealStopGapRestart() {
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"));
        // setCurrentWorldId: stopMapping at once ...
        this.jm.mapping = Boolean.FALSE;
        this.clock += 50;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state());
        // ... and up to a second later a NEW controller maps into the id-suffixed directory.
        this.jm.mapping = Boolean.TRUE;
        this.jm.controller = new TaskController();
        this.jm.worldId = "abcdef";
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.clock += 1000;
        this.session.pump();
        assertEquals(2, counter("replayed"), "the columns drawn into the id-less directory re-serve");
        assertEquals(2, this.reports.size());
    }

    @Test
    void anEvictedWrapperIsRequeuedNeverAcceptedUndrawn() {
        DataCache.INSTANCE.evictEveryAdd = true;
        offer(0, 0);
        settle();
        cycle();
        assertEquals(0, counter("rendered"));
        assertEquals(0, counter("skipped_live"), "a null lookup is not a live chunk");
        assertEquals(1, counter("requeued"));
        assertEquals(1, this.session.queuedGauge);
        assertTrue(renders().isEmpty());
    }

    @Test
    void wholesaleRenderRefusalsTripTheDeathLatch() {
        this.jm.renderController.result = (type, md) -> false;
        settle();
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH; i++) {
            for (int x = 0; x < 7; x++) offer(x, i);
            cycle();
            this.clock += 1000;
        }
        assertEquals("dead", this.session.state(), "a refusing renderer must not re-serve the disc forever");
    }

    @Test
    void fiveSmallFailedBatchesDoNotKillTheSession() {
        // Steady state: one or two dirty re-serves per batch (re-review) — five failing
        // single columns are not a renderer refusing wholesale.
        this.jm.renderController.result = (type, md) -> false;
        settle();
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH; i++) {
            offer(i, 0);
            cycle();
            this.clock += 1000;
        }
        assertEquals(JourneyMapSession.DEATH_LATCH, counter("render_failed"));
        assertNotEquals("dead", this.session.state());
        this.jm.renderController.result = (type, md) -> true;
        offer(40, 40);
        cycle();
        assertEquals(1, counter("rendered"), "a render resets the streak");
        assertEquals(0, this.session.consecutiveFailedColumns);
    }

    @Test
    void aWrapperEvictedEveryTimeIsReportedAfterTheLimitNotRequeuedForever() {
        DataCache.INSTANCE.evictEveryAdd = true;
        offer(0, 0);
        settle();
        for (int i = 0; i < JourneyMapSession.NULL_LOOKUP_LIMIT; i++) {
            cycle();
            this.clock += 1000;
        }
        assertEquals(JourneyMapSession.NULL_LOOKUP_LIMIT - 1, counter("requeued"));
        assertEquals(1, counter("column_failures"));
        assertEquals(1, this.reports.size(), "reported for a re-serve, its receipt closed");
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void eastAndSouthBatchNeighboursAreInTheCacheWhenAColumnRenders() {
        // Topography's slope and shore checks read south and east, biome blending all around
        // (re-review): the (z, x) order alone only puts north and west there.
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) offer(x, z);
        var seen = new java.util.HashMap<Long, java.util.Set<Long>>();
        this.jm.renderController.result = (type, md) -> {
            seen.put(md.getLongCoord(), new java.util.HashSet<>(DataCache.INSTANCE.cache.keySet()));
            return true;
        };
        settle();
        cycle();
        assertEquals(9, counter("rendered"));
        var atOrigin = seen.get(JourneyMapSession.mcChunkKey(0, 0));
        for (int[] d : new int[][]{{1, 0}, {0, 1}, {1, 1}}) {
            assertTrue(atOrigin.contains(JourneyMapSession.mcChunkKey(d[0], d[1])), d[0] + "," + d[1]);
        }
        var middle = seen.get(JourneyMapSession.mcChunkKey(1, 1));
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            assertTrue(middle.contains(JourneyMapSession.mcChunkKey(1 + dx, 1 + dz)), "all eight around (1,1)");
        }
        assertEquals(9, this.session.decodedColumns.get(), "decoded once each, ahead or not");
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "every wrapper released after the batch");
    }

    @Test
    void aLookAheadTheBoxCutsOffIsReleasedAndRequeued() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) offer(x, z);
        this.jm.renderController.result = (type, md) -> {
            this.clock += 5_000; // the first render spends the whole box
            return true;
        };
        settle();
        cycle();
        assertEquals(1, counter("rendered"));
        assertEquals(3, counter("requeued"));
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "the look-ahead wrappers left JourneyMap's cache");
        assertEquals(3, this.session.queuedGauge);
    }

    @Test
    void aColumnEncodedAcrossASessionEndNeverReachesTheNextSession() {
        var receipt = this.session.new Receipt(); // captured while the old session was live
        this.session.onSessionEnd();
        this.session.offerEncoded(new JourneyMapSession.Entry(OVERWORLD, 0, 0, frame(0, 0), receipt));
        assertEquals(0, this.session.queuedGauge);
        assertTrue(receipt.closed.get());
    }

    @Test
    void ourWrappersReleaseTheirChunkEvenWhenTheCacheCannotHandThemBack() {
        offer(0, 0);
        settle();
        var wrappers = new ArrayList<SnapshotChunkMD>();
        var plain = this.session.wrapper;
        this.session.wrapper = (level, snapshot) -> {
            var md = (SnapshotChunkMD) plain.wrap(level, snapshot);
            wrappers.add(md);
            return md;
        };
        cycle();
        assertEquals(1, wrappers.size());
        assertFalse(wrappers.getFirst().retained, "stopChunkRetention runs for every wrapper we added");
    }

    @Test
    void decodingRealTerrainNeverStarvesRendering() {
        // The throughput gate's livelock: decoding everything first spent the whole box.
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            this.clock += 60; // decoding costs as much as rendering on real terrain
            return plain.decode(bytes, level);
        };
        for (int i = 0; i < 20; i++) offer(i, 0);
        settle();
        cycle();
        assertTrue(counter("rendered") >= 8, "most of the box renders: " + counter("rendered"));
        assertEquals(20, counter("rendered") + counter("requeued"));
    }

    @Test
    void theEdgeSweepNeverPaintsAnotherDimensionsChunk() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(9, 0).dimension = END; // JourneyMap's cache already serves the new dimension
        live(0, 9);
        settle();
        cycle();
        assertEquals(1, counter("edge_rendered"));
        assertEquals(0, renders().getFirst().chunk().chunkX);
    }

    @Test
    void theEdgeSweepFollowsJourneyMapsSurfaceCap() {
        this.jm.gameRenderDistance = 7;
        this.jm.properties.renderDistanceSurfaceMax.value = 4;
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(6, 0); // inside the game range, outside JourneyMap's capped surface range
        settle();
        cycle();
        assertEquals(1, counter("edge_rendered"), "the ring starts where JourneyMap stops mapping");
    }

    @Test
    void aSweepThatDrewNothingCostsNoIdleGap() {
        livePlayerAt(0, 0);
        offer(40, 40);
        settle();
        cycle(); // an empty sweep
        assertEquals(1, counter("edge_sweeps"));
        cycle(); // the LSS batch follows at once
        assertEquals(1, counter("batches"));
    }

    // ---- the edge sweep ----

    private void livePlayerAt(int chunkX, int chunkZ) {
        this.playerChunk = JourneyMapSession.packed(chunkX, chunkZ);
    }

    /** A chunk the client has loaded (a new instance each call — a reload) that JourneyMap
     *  serves live from its cache. */
    private ChunkMD live(int x, int z) {
        var md = new ChunkMD(x, z, true);
        DataCache.INSTANCE.live.put(JourneyMapSession.mcChunkKey(x, z), md);
        this.clientChunks.put(JourneyMapSession.packed(x, z), new Object());
        return md;
    }

    @Test
    void theEdgeSweepRendersJourneyMapsOwnLiveChunksPastItsRangeOnce() {
        this.jm.gameRenderDistance = 7;
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(100, 100);
        var inside = live(107, 100);  // distance 7: JourneyMap maps it itself
        var band = live(108, 100);    // 8 > 7.5: the ring JourneyMap never maps
        var diagonal = live(106, 106); // 8.5
        var far = live(111, 100);     // beyond range + 3
        settle();
        assertEquals(1, cycle(), "the first submission is the sweep");
        assertEquals(1, counter("edge_sweeps"));
        assertEquals(2, counter("edge_rendered"));
        var drawn = renders().stream().map(ChunkRenderController.Render::chunk).toList();
        assertTrue(drawn.contains(band) && drawn.contains(diagonal));
        assertFalse(drawn.contains(inside) || drawn.contains(far));
        assertEquals(0, counter("batches"), "no LSS batch, no receipts");
        assertEquals(1, band.getBlockData().clears, "block data is released like JourneyMap's own renders");
        renders().clear();
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        cycle();
        assertEquals(2, counter("edge_sweeps"));
        assertTrue(renders().isEmpty(), "an already-drawn live chunk instance is not drawn again");
        live(108, 100); // the chunk reloaded: a new instance
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        cycle();
        assertEquals(1, renders().size());
        assertEquals(3, counter("edge_rendered"));
    }

    @Test
    void aDrawnEdgeChunkCostsNoJourneyMapLookupAndLookupsArePaced() {
        // A low surface cap makes the band thousands of chunks wide: done chunks are keyed on
        // the client's own chunk, never re-looked-up in JourneyMap's cache, and first-fill
        // lookups are paced per sweep (re-review).
        this.jm.gameRenderDistance = 32;
        this.jm.properties.renderDistanceSurfaceMax.value = 4;
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        int band = 0;
        for (int z = -35; z <= 35; z++) for (int x = -35; x <= 35; x++) {
            if (Math.sqrt(x * x + z * z) > 4.5) {
                live(x, z);
                band++;
            }
        }
        assertTrue(band > 2 * JourneyMapSession.EDGE_LOOKUPS_PER_SWEEP);
        settle();
        cycle();
        assertEquals(JourneyMapSession.EDGE_LOOKUPS_PER_SWEEP, counter("edge_rendered"));
        int lookups = DataCache.INSTANCE.liveOnlyLookups;
        assertEquals(JourneyMapSession.EDGE_LOOKUPS_PER_SWEEP, lookups);
        for (int sweep = 2; counter("edge_rendered") < band; sweep++) {
            this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
            cycle();
            assertTrue(sweep < 100, "the band fills");
        }
        int filled = DataCache.INSTANCE.liveOnlyLookups;
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        cycle();
        assertEquals(filled, DataCache.INSTANCE.liveOnlyLookups, "a drawn band costs no lookup");
        assertEquals(band, counter("edge_rendered"), "and draws nothing again");
    }

    @Test
    void theEdgeSweepInterleavesWithLssBatchesAndNeedsASession() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(9, 0);
        offer(40, 40);
        settle();
        cycle(); // the sweep
        this.clock += 500;
        cycle(); // the LSS batch
        assertEquals(1, counter("edge_sweeps"));
        assertEquals(1, counter("batches"));
        assertEquals(1, counter("rendered"));
        this.sessionActive = false;
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        this.session.pump();
        assertEquals(1, counter("edge_sweeps"), "never touches JourneyMap's map without an LSS session");
    }

    @Test
    void anIdentityChangeRedrawsTheEdge() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(9, 0);
        settle();
        cycle();
        assertEquals(1, counter("edge_rendered"));
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        cycle();
        assertEquals(2, counter("edge_rendered"), "the new map gets the ring too");
    }

    // ---- queue and backpressure ----

    @Test
    void overflowEvictsTheOldestAndReportsItOnlyWhileGoverned() {
        this.session.maxQueue = 4;
        for (int i = 0; i < 6; i++) offer(i, 0);
        this.session.pump();
        assertEquals(2, counter("dropped_overflow"));
        assertEquals(2, this.reports.size());
        assertEquals(List.of(0L, 1L), this.reports.stream().map(r -> r[1]).toList());
        this.backpressure = false;
        offer(10, 0);
        this.session.pump();
        assertEquals(2, this.reports.size(), "ungoverned overflow stays silent (the Xaero rule)");
    }

    @Test
    void theByteBoundEvictsToo() {
        this.session.maxQueueBytes = 64 * 3;
        for (int i = 0; i < 5; i++) offer(i, 0);
        assertEquals(3, this.session.queuedGauge);
        assertEquals(2, counter("dropped_overflow"));
    }

    @Test
    void theReportScalesOccupancyOntoTheHalt() {
        this.session.maxQueue = 100;
        for (int i = 0; i < 30; i++) offer(i, 0);
        this.session.pump();
        int halt = LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        assertEquals(Math.round(halt * 0.30 / IngestBackpressureGovernor.HALT_OCCUPANCY), this.session.reportBackpressure());
        assertEquals(this.session.reportBackpressure(), this.session.consumer.pendingIngestBacklog());
        this.backpressure = false;
        assertEquals(-1, this.session.reportBackpressure());
        this.backpressure = true;
        this.clock += IngestBackpressureGovernor.PUMP_STALE_MILLIS + 1;
        assertEquals(-1, this.session.reportBackpressure(), "a stale pump has no signal");
    }

    @Test
    void theConsumerOverridesTheBacklogReport() throws Exception {
        assertTrue(this.session.consumer.getClass().isAnonymousClass());
        assertNotNull(this.session.consumer.getClass().getDeclaredMethod("pendingIngestBacklog"),
                "a lambda would silently inherit the no-signal default");
    }

    // ---- lifecycle ----

    @Test
    void sessionEndClearsTheQueueAndAbandonsAQueuedBatchWithoutReports() {
        offer(0, 0);
        settle();
        this.session.pump();
        offer(1, 1);
        this.session.onSessionEnd();
        this.session.pump();
        assertEquals(0, this.session.queuedGauge);
        assertNull(this.session.inFlight);
        assertTrue(this.reports.isEmpty(), "a finished session's deliveries are not re-requested");
        this.jm.runQueued();
        assertEquals(0, renders().size());
    }

    @Test
    void registrationIsAddOnlyMidSessionAndSettlesAtSessionEnd() {
        assertEquals(1, this.registered.size());
        this.enabled = false;
        this.session.pump();
        assertEquals(1, this.registered.size(), "never deregistered mid-session (the capability bit)");
        assertEquals(0, this.session.queuedGauge);
        this.session.onSessionEnd();
        this.session.pump();
        assertTrue(this.registered.isEmpty(), "a disabled backend releases its consumer at session end");
        this.enabled = true;
        this.session.pump();
        assertEquals(1, this.registered.size());
    }

    @Test
    void offersOutsideASessionOrWhileDisabledAreDroppedBeforeEncoding() {
        this.sessionActive = false;
        gatedOffer(0, 0);
        assertEquals(-1, this.session.reportBackpressure());
        this.sessionActive = true;
        this.enabled = false;
        gatedOffer(0, 0);
        assertEquals(0, this.session.queuedGauge);
        assertEquals(0, this.session.encodedColumns.get());
    }

    @Test
    void theDiagLineCarriesTheStateAndCounters() {
        offer(0, 0);
        settle();
        cycle();
        String line = this.session.describe();
        assertTrue(line.startsWith("JourneyMap: state=active, queued=0, batches=1, rendered=1"), line);
        for (String field : List.of("skipped_live=", "render_failed=", "decode_failures=", "abandoned=",
                "replayed=", "requeued=", "slow_batches=", "context=1", "last_batch_ms=", "batch_pick=256",
                "decode_us_per_column=", "decode_ahead=on", "decoded_ahead=0", "predecode_us_per_column=",
                "render_us_per_column=", "bp=", "heap=n/a")) {
            assertTrue(line.contains(field), field + " in " + line);
        }
        assertFalse(line.contains("jm-test"), "no paths in the diag line");
    }

    // ---- the static facade ----

    /** Written by {@link InitProbe}'s static initializer — a separate class, so reading it
     *  never initializes the probe. */
    static final class InitFlag {
        static volatile boolean probeInitialized;
    }

    /** Loaded, never initialized, by the production resolver. */
    static final class InitProbe {
        static {
            InitFlag.probeInitialized = true;
        }
    }

    @Test
    void theProductionResolverNeverInitializesJourneyMapsClasses() throws Exception {
        Class<?> probe = JourneyMapSession.PRODUCTION_RESOLVER.resolve(InitProbe.class.getName());
        assertEquals(InitProbe.class.getName(), probe.getName());
        assertFalse(InitFlag.probeInitialized,
                "binding at client init must not run another mod's static initializers (the live-smoke crash)");
        assertTrue(source().contains("initWith(PRODUCTION_RESOLVER, PRODUCTION_HOST)"),
                "init() must bind through the non-initializing resolver");
    }

    private static String source() throws java.io.IOException {
        return java.nio.file.Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/compat/JourneyMapSession.java"));
    }

    @Test
    void aJourneyMapWithoutTheSuspendedProbeStillMaps() throws Exception {
        // JourneyMap 6.0.0 (the 1.21.11 line) has no isMappingSuspended, so production always
        // takes the null-handle branch — which the stubs, carrying the member, never reached
        // (port review).
        var bindings = JourneyMapBindings.resolve(name -> name.equals("journeymap.client.JourneymapClient")
                ? JourneymapClientWithoutSuspend.class : Class.forName(name));
        assertNull(bindings.isMappingSuspended);
        newSession(bindings);
        this.jm.suspended = true; // nothing on 6.0.0 can report a suspension
        offer(0, 0);
        settle();
        cycle();
        assertEquals(1, counter("rendered"));
    }

    @Test
    void aJourneyMapWithoutTheSnapshotLayerIsUnsupported() {
        assertFalse(JourneyMapSession.initWith(name -> {
            if (name.equals("journeymap.client.model.chunk.SnapshotChunkMD")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }, JourneyMapSession.PRODUCTION_HOST));
        assertTrue(JourneyMapSession.unsupported);
        assertTrue(JourneyMapSession.diagLine().startsWith("JourneyMap: state=unsupported"));
        assertEquals("unavailable", JourneyMapSession.cachedStatus(true).resolution());
        assertFalse(JourneyMapSession.isSupported());
    }

    @Test
    void aDriftedSurfaceIsUnavailableAndNamesTheMember() {
        assertFalse(JourneyMapSession.initWith(name -> {
            if (name.equals("journeymap.client.io.FileHandler")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }, JourneyMapSession.PRODUCTION_HOST));
        assertFalse(JourneyMapSession.unsupported);
        assertTrue(JourneyMapSession.diagLine().startsWith("JourneyMap: state=unavailable(journeymap.client.io.FileHandler)"),
                JourneyMapSession.diagLine());
        assertNull(JourneyMapSession.instance);
    }

    // ---- the post-0.17 review fold ----

    @Test
    void aBackendDisabledOnTheTitleScreenReleasesItsConsumerAtOnce() {
        assertEquals(1, this.registered.size());
        boolean[] connected = {true};
        this.session.connected = () -> connected[0];
        this.enabled = false;
        this.session.pump();
        assertEquals(1, this.registered.size(), "connected: add-only until the session ends");
        connected[0] = false;
        this.session.pump();
        assertTrue(this.registered.isEmpty(), "no connection: the next handshake must not advertise LOD reception");
        this.enabled = true;
        this.session.pump();
        assertEquals(1, this.registered.size());
    }

    @Test
    void theHeapBeltReportsTheFullHalt() {
        this.session.memory = new MapMemoryGuard(() -> 0.95, () -> this.clock);
        offer(0, 0);
        settle();
        this.session.pump();
        assertTrue(this.session.heapHolding);
        assertEquals(LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS, this.session.reportBackpressure(),
                "plan §4.3: the belt stops the stream instead of filling the queue it protects");
    }

    @Test
    void anOfferEncodedAcrossAnInertTransitionIsReleasedNotStranded() {
        this.session.pump();
        this.dimension = NETHER;
        this.ceiling = true;
        this.session.pump();
        assertEquals("inert(ceiling_dimension)", this.session.state());
        var receipt = this.session.new Receipt();
        // Encoded before the pump went inert, queued after its clear: re-checked under the lock.
        this.session.offerEncoded(new JourneyMapSession.Entry(NETHER, 0, 0, frame(0, 0), receipt));
        assertEquals(0, this.session.queuedGauge);
        assertEquals(1, counter("dropped_inert"));
        assertTrue(receipt.closed.get(), "the acceptance lease is released, never stranded");
    }

    @Test
    void aBatchStuckOnJourneyMapsThreadLatchesTheSessionDead() {
        offer(0, 0);
        settle();
        this.session.pump();
        var batch = this.session.inFlight;
        assertNotNull(batch);
        batch.state.set(JourneyMapSession.Batch.STARTED); // JourneyMap's thread entered it and never returned
        batch.startedAtMillis = this.clock;
        this.clock += JourneyMapSession.SLOW_BATCH_MILLIS + 1;
        this.session.pump();
        assertFalse(this.session.dead, "slow is not stuck");
        assertTrue(this.session.describe().contains(", inflight_ms=" + (JourneyMapSession.SLOW_BATCH_MILLIS + 1)),
                this.session.describe());
        offer(1, 1);
        this.clock += JourneyMapSession.WEDGED_BATCH_MILLIS;
        this.session.pump();
        assertTrue(this.session.dead, "a stuck task thread would otherwise hold the stream all session");
        assertEquals(0, this.session.queuedGauge);
        assertEquals(-1, this.session.reportBackpressure());
        assertSame(batch, this.session.inFlight, "the slot stays taken: the thread may still write its result");
    }

    @Test
    void aPumpThatThrowsEveryTickLatchesButAnOccasionalThrowDoesNot() {
        offer(0, 0);
        this.levelThrows = true;
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH - 1; i++) this.session.pump();
        this.levelThrows = false;
        this.session.pump(); // a clean pump resets the streak
        this.levelThrows = true;
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH - 1; i++) this.session.pump();
        assertFalse(this.session.dead);
        this.session.pump();
        assertTrue(this.session.dead, "drift at call time would duty-cycle the governor all session");
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void aNotMappingHoldThatNeverEndsTurnsInert() {
        this.jm.mapping = Boolean.FALSE;
        offer(0, 0);
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state());
        this.clock += JourneyMapSession.STUCK_HOLD_MILLIS - 1;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state(), "a dimension or world switch still holds");
        assertEquals(1, this.session.queuedGauge);
        this.clock += 2;
        this.session.pump();
        assertEquals("inert(not_mapping)", this.session.state());
        assertEquals(0, this.session.queuedGauge);
        assertEquals(-1, this.session.reportBackpressure(), "it no longer holds the stream");
        this.jm.mapping = Boolean.TRUE;
        this.session.pump();
        assertEquals("waiting(settle)", this.session.state(), "mapping again: a fresh identity settles");
    }

    @Test
    void anInterruptedHoldRestartsItsClock() {
        this.jm.mapping = Boolean.FALSE;
        this.session.pump();
        this.clock += JourneyMapSession.STUCK_HOLD_MILLIS - 1;
        this.level = null; // between worlds: not a mapping hold
        this.session.pump();
        this.level = this.levelMarker;
        this.clock += 2;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state(), "only an uninterrupted hold ages");
    }

    @Test
    void aBatchStopsAtTheNextColumnWhenJourneyMapStopsItsMapping() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        this.jm.renderController.result = (type, md) -> {
            // stopMapping/startMapping: JourneyMap replaced its task controller mid-batch
            this.jm.controller = new TaskController();
            return true;
        };
        for (int i = 0; i < 4; i++) offer(i, 0);
        settle();
        int updates = RegionImageCache.INSTANCE.updates;
        cycle();
        assertEquals(1, renders().size(), "no column after the stop is rendered");
        assertEquals(updates, RegionImageCache.INSTANCE.updates, "JourneyMap's textures are left to the next mapping");
        assertEquals(1, counter("abandoned"));
        assertEquals(4, this.reports.size(), "every column re-serves into the next mapping");
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "our wrappers are still released");
    }

    @Test
    void aNewerColumnFromAnotherDimensionReportsTheOneItReplaces() {
        offer(OVERWORLD, 3, 3);
        offer(END, 3, 3);
        this.session.drainPendingReports();
        assertEquals(1, counter("dropped_stale"));
        assertEquals(1, this.reports.size(), "the overworld column was never written: it re-serves on the way back");
        assertEquals(0, this.reports.getFirst()[0]);
        assertEquals(1, this.session.queuedGauge);
    }

    @Test
    void aDimensionChangeReportsQueuedColumnsEvenWhileJourneyMapIsNotMapping() {
        offer(OVERWORLD, 0, 0);
        this.jm.mapping = Boolean.FALSE;
        this.session.pump();
        assertEquals(1, this.session.queuedGauge, "the switch hold keeps them");
        offer(END, 1, 1);
        this.dimension = END;
        this.session.pump();
        assertEquals(1, counter("dropped_stale"));
        assertEquals(1, this.reports.size());
        assertEquals(0, this.reports.getFirst()[0]);
        assertEquals(1, this.session.queuedGauge, "the new dimension's column stays");
    }

    @Test
    void aBatchFinishingAfterItsSessionAppliesNothing() {
        offer(0, 0);
        settle();
        this.session.pump();
        this.jm.runQueued(); // done on JourneyMap's thread...
        this.session.onSessionEnd(); // ...but the session ended before a pump reconciled it
        this.session.pump();
        assertNull(this.session.inFlight);
        assertEquals(0, counter("rendered"));
        assertTrue(this.session.replay.isEmpty(), "never replayed into the next server's map");
        assertTrue(this.reports.isEmpty());
    }

    @Test
    void aRequeueRespectsTheQueueBounds() {
        this.jm.properties.renderDelay.value = 100;
        this.jm.renderController.result = (type, md) -> {
            this.clock += 300;
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        this.session.pump(); // submits region (0, 0)
        this.session.maxQueue = 1;
        offer(40, 40); // fills the queue while the batch runs
        this.jm.runQueued(); // (1, 0) misses the box
        this.session.pump();
        assertEquals(1, this.session.queuedGauge, "the requeue never overshoots the bound");
        assertEquals(1, counter("dropped_overflow"));
        assertEquals(1, this.reports.size(), "governed: the overflowed column re-serves");
        assertEquals(1, this.reports.getFirst()[1]);
    }

    @Test
    void theEdgeSweepKeyHoldsTheLevelAndControllerWeakly() {
        for (var c : JourneyMapSession.EdgeKey.class.getRecordComponents()) {
            if (c.getName().equals("level") || c.getName().equals("controller")) {
                assertEquals(java.lang.ref.WeakReference.class, c.getType(), c.getName());
            }
        }
        assertTrue(java.util.Arrays.stream(JourneyMapSession.class.getDeclaredFields())
                        .noneMatch(f -> f.getType() == JourneyMapSession.Identity.class && f.getName().startsWith("edge")),
                "a disconnect must never pin the old level through the edge sweep");
    }

    @Test
    void productionInitRegistersTheConsumerAndTheTitleScreenReleasesIt() {
        JourneyMapSession.resetFacadeForTest();
        assertFalse(dev.vox.lss.api.LSSApi.hasVoxelConsumers(), "premise: no consumer leaked from another suite");
        boolean old = dev.vox.lss.config.LSSClientConfig.CONFIG.mapIngestionEnabled();
        try {
            dev.vox.lss.config.ClientConfigTestSupport.set("integrations.map_ingestion.enabled", true);
            assertTrue(JourneyMapSession.initWith(Class::forName, this.host));
            assertTrue(dev.vox.lss.api.LSSApi.hasVoxelConsumers(), "the capability bit's consumer is registered");
            assertTrue(JourneyMapSession.isArmed());
            assertTrue(JourneyMapSession.isSupported());
            assertEquals("available", JourneyMapSession.cachedStatus(true).resolution());
            JourneyMapSession.instance.connected = () -> false; // the title screen
            dev.vox.lss.config.ClientConfigTestSupport.set("integrations.map_ingestion.enabled", false);
            JourneyMapSession.clientTick();
            assertFalse(dev.vox.lss.api.LSSApi.hasVoxelConsumers(), "disabled with no connection: released at once");
        } finally {
            var instance = JourneyMapSession.instance;
            if (instance != null) dev.vox.lss.api.LSSApi.removeColumnConsumer(instance.consumer);
            dev.vox.lss.config.ClientConfigTestSupport.set("integrations.map_ingestion.enabled", old);
            JourneyMapSession.resetFacadeForTest();
        }
    }
    // ---- the server's map-ingestion request (map-policy plan A4) ----

    @Test
    void aServerDenyDropsOffersUnreportedAndNeverThrottlesTheStream() {
        this.session.pump(); // a fresh pump: the report below is a live signal, not a stale one
        assertEquals(0, this.session.reportBackpressure(), "premise: allowed, an empty queue reports 0");
        this.serverAllows = false;
        var handle = new Handle();
        gatedOfferWith(handle, 0, 0);
        assertEquals(0, this.session.queuedGauge);
        assertEquals(0, this.session.encodedColumns.get(), "dropped before encoding");
        offerWith(handle, 1, 1); // an encode that raced the deny lands in offerEncoded's re-check
        assertEquals(0, this.session.queuedGauge);
        assertClosedUnreported(handle, 2, "both offer paths");
        assertEquals(-1, this.session.reportBackpressure(),
                "a blocked map never paces Voxy's stream");
        assertTrue(this.reports.isEmpty(), "nothing is reported for re-serve");
        assertTrue(this.session.describe().contains("state=blocked_by_server"), this.session.describe());
        assertEquals(1, this.registered.size(),
                "the write gate is NOT composed into enabled: registration is untouched");
    }

    @Test
    void aDenyWithdrawsEvenTheHeapBeltsFullHaltAndAnAllowRestoresIt() {
        this.session.memory = new MapMemoryGuard(() -> 0.95, () -> this.clock);
        offer(0, 0);
        settle();
        this.session.pump();
        assertTrue(this.session.heapHolding);
        int halt = LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        assertEquals(halt, this.session.reportBackpressure(), "premise: allowed, the belt halts the stream");
        this.serverAllows = false; // the deny lands between pumps: the queue and the hold are still there
        assertEquals(1, this.session.queuedGauge);
        assertTrue(this.session.heapHolding);
        assertEquals(-1, this.session.reportBackpressure(), "a blocked map never throttles Voxy");
        assertEquals(-1, this.session.consumer.pendingIngestBacklog());
        this.session.pump();
        assertEquals(-1, this.session.reportBackpressure(), "nor after the deny's pump");
        this.serverAllows = true;
        this.session.pump();
        assertEquals(halt, this.session.reportBackpressure(), "an allow restores the signal");
    }

    @Test
    void aMidSessionDenyClearsTheQueueAndAnAllowResumesWriting() {
        var handle = new Handle();
        offerWith(handle, 0, 0);
        offerWith(handle, 1, 0);
        assertEquals(2, this.session.queuedGauge);
        this.serverAllows = false;
        this.session.pump();
        assertEquals(0, this.session.queuedGauge, "the local-off branch: queue dropped");
        assertClosedUnreported(handle, 2, "the dropped queue");
        assertTrue(this.reports.isEmpty(), "…unreported");
        assertEquals(0, this.jm.queued.size(), "nothing written while denied");
        this.session.onSessionEnd();
        this.session.pump();
        assertEquals(1, this.registered.size(), "a deny never deregisters at session end");

        this.serverAllows = true;
        offer(0, 0);
        this.session.pump();
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        assertEquals(1, cycle(), "an allow resumes writing for terrain arriving from now on");
    }

    @Test
    void aDenyAndAnAllowInsideOneSessionStopAndResumeWriting() {
        offer(0, 0);
        settle();
        assertEquals(1, cycle());
        assertEquals(1, counter("rendered"));
        this.clock += 500;
        var handle = new Handle();
        offerWith(handle, 1, 0);
        this.serverAllows = false; // the deny frame, mid-session
        this.session.pump();
        assertEquals("blocked_by_server", this.session.state());
        assertEquals(0, this.jm.queued.size(), "nothing is submitted while denied");
        assertEquals(0, this.session.queuedGauge);
        assertClosedUnreported(handle, 1, "the column queued at the deny");
        gatedOffer(2, 0);
        assertEquals(0, this.session.queuedGauge, "offers drop while denied");

        this.clock += 500;
        this.serverAllows = true; // the allow frame, on the same connection: no session end
        offer(3, 0);
        assertEquals(1, cycle(), "writing resumes at the next pump: the identity was kept, so no new settle");
        assertEquals(2, counter("rendered"));
        assertEquals(1, this.registered.size());
        assertTrue(this.reports.isEmpty());
        offer(4, 0);
        this.clock += 500;
        assertEquals(1, cycle(), "and keeps writing");
        assertEquals(3, counter("rendered"));
    }

    @Test
    void aBatchStillQueuedWhenTheDenyLandsNeverWrites() {
        var handle = new Handle();
        offerWith(handle, 0, 0);
        offerWith(handle, 1, 0);
        settle();
        this.session.pump();
        assertEquals(1, this.jm.queued.size(), "one batch handed to JourneyMap's task thread");
        assertNotNull(this.session.inFlight);
        this.serverAllows = false;
        this.session.pump(); // the deny branch abandons the still-QUEUED batch
        assertNull(this.session.inFlight, "the slot is reclaimed at once");
        assertClosedUnreported(handle, 2, "the abandoned batch");
        this.jm.runQueued();
        assertEquals(0, renders().size(), "the abandoned runnable loses its start CAS: nothing written");
        assertTrue(this.reports.isEmpty(), "a blocked map is owed nothing: receipts close unreported");
        assertEquals(0, counter("abandoned"), "not an identity abandon");
        JourneyMapSession.instance = this.session; // the exporter group (tearDown resets it)
        var metrics = JourneyMapSession.metrics();
        assertEquals("blocked_by_server", metrics.get("state"));
        assertEquals(true, metrics.get("server_blocked"), "the exporter group names the server's block");
        this.serverAllows = true;
        assertEquals(false, JourneyMapSession.metrics().get("server_blocked"));
    }

    @Test
    void aBatchTheTaskThreadStartsAfterTheDenyWritesNothing() {
        var handle = new Handle();
        offerWith(handle, 0, 0);
        offerWith(handle, 1, 0);
        settle();
        this.session.pump();
        assertEquals(1, this.jm.queued.size());
        this.serverAllows = false; // the deny lands; the task thread wins the race to start
        this.jm.runQueued();
        assertEquals(0, renders().size(), "render() checks the gate before touching JourneyMap");
        assertEquals(0, handle.released, "premise: the receipts close when the pump applies the result");
        this.session.pump();
        assertNull(this.session.inFlight);
        assertClosedUnreported(handle, 2, "the refused batch");
        assertTrue(this.reports.isEmpty(), "receipts close unreported");
        assertEquals(0, counter("rendered"));
    }

    // ---- box-sized multi-region batches (0.19.0 throughput round) ----

    private void dayOnly() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
    }

    /** The columns rendered so far, as packed positions. */
    private java.util.Set<Long> renderedPositions() {
        var out = new java.util.HashSet<Long>();
        for (var r : renders()) out.add(JourneyMapSession.packed(r.chunk().chunkX, r.chunk().chunkZ));
        return out;
    }

    private java.util.Set<Long> queuedPositions() {
        synchronized (this.session.queueLock) {
            return new java.util.HashSet<>(this.session.queue.keySet());
        }
    }

    @Test
    void aBatchDrawsWholeRegionsOldestFirstUpToTheRegionCap() {
        dayOnly();
        int regions = JourneyMapSession.MAX_BATCH_REGIONS + 4;
        for (int i = 0; i < regions; i++) {
            offer(32 * i, 0); // one column in each of regions (0,0) .. (11,0), oldest first
            offer(32 * i + 1, 0);
        }
        settle();
        cycle();
        assertEquals(2 * JourneyMapSession.MAX_BATCH_REGIONS, counter("rendered"), "the region cap binds, not the pick");
        var drawn = renders().stream().map(r -> r.region().regionX).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.stream.IntStream.range(0, JourneyMapSession.MAX_BATCH_REGIONS).boxed()
                .collect(java.util.stream.Collectors.toSet()), drawn, "the regions holding the oldest entries");
        this.clock += 500;
        cycle();
        assertEquals(2 * regions, counter("rendered"), "the rest follow in the next batch — no region starves");
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void theRegionDrawnLastContinuesBeforeAnOlderRegion() {
        dayOnly();
        this.session.costPerColumnMillis = 1_000; // a pick of MIN_BATCH_PICK
        int pick = JourneyMapSession.MIN_BATCH_PICK;
        // Region (0,0): pick + 8 columns, oldest. Batch 1 takes `pick` of them and stops there.
        var first = new ArrayList<long[]>();
        for (int z = 0; z < 2; z++) for (int x = 0; x < 32 && first.size() < pick + 8; x++) first.add(new long[]{x, z});
        for (var c : first) offer((int) c[0], (int) c[1]);
        settle();
        cycle();
        assertEquals(pick, counter("rendered"));
        assertEquals(pick, this.session.batchPick, "premise: the pick binds");
        // Region (2,0): 30 columns, queued now — then region (0,0)'s eight leftovers are
        // delivered again, so (2,0)'s oldest entry is OLDER than any of (0,0)'s.
        for (int x = 64; x < 94; x++) offer(x, 0);
        var leftovers = new ArrayList<Long>();
        for (var c : first.subList(pick, first.size())) {
            offer((int) c[0], (int) c[1]);
            leftovers.add(JourneyMapSession.packed((int) c[0], (int) c[1]));
        }
        renders().clear();
        this.clock += 500;
        cycle();
        assertTrue(renderedPositions().containsAll(leftovers), "the region worked last continues first");
        assertEquals(pick, renders().size());
        assertEquals(30 - (pick - leftovers.size()), this.session.queuedGauge, "the older region fills the rest");
        assertTrue(queuedPositions().stream().allMatch(k -> (int) (k >> 32) >= 64));
    }

    @Test
    void thePickAdaptsToTheMeasuredCostSoTheBoxRarelyCutsATail() {
        dayOnly();
        this.jm.properties.renderDelay.value = 100; // a 200 ms box
        this.jm.renderController.result = (type, md) -> {
            this.clock += 2; // 2 ms per column on JourneyMap's thread
            return true;
        };
        for (int z = 0; z < 30; z++) for (int x = 0; x < 30; x++) offer(x, z);
        settle();
        cycle();
        long cutFirst = counter("requeued");
        assertEquals(JourneyMapSession.INITIAL_BATCH_PICK, this.session.batchPick, "unmeasured: the old fixed pick");
        assertEquals(101, counter("rendered"), "premise: columns start at +0 .. +200 ms of the 200 ms box");
        assertEquals(JourneyMapSession.INITIAL_BATCH_PICK - 101, cutFirst, "premise: the unmeasured pick overran it");
        assertEquals(2.0, this.session.costPerColumnMillis, 0.05);
        for (int i = 0; i < 6; i++) {
            this.clock += 100;
            cycle();
        }
        int expected = (int) (200 * JourneyMapSession.BATCH_BOX_FILL / 2.0);
        assertTrue(expected > JourneyMapSession.MIN_BATCH_PICK, "premise: inside the clamp");
        assertEquals(expected, this.session.batchPick, "box × fill / measured cost");
        assertEquals(cutFirst, counter("requeued"), "measured picks fit the box: nothing more is cut");
        assertEquals(101 + 6L * expected, counter("rendered"));
        assertEquals(2.0, this.session.costPerColumnMillis, 0.05, "full batches keep measuring");
    }

    @Test
    void thePickStaysInsideItsClampAndSmallBatchesDoNotMoveIt() {
        assertEquals(JourneyMapSession.INITIAL_BATCH_PICK, this.session.pickFor(1000));
        this.session.costPerColumnMillis = 0;
        assertEquals(JourneyMapSession.MAX_BATCH_PICK, this.session.pickFor(1000));
        this.session.costPerColumnMillis = 10_000;
        assertEquals(JourneyMapSession.MIN_BATCH_PICK, this.session.pickFor(1000));
        this.session.costPerColumnMillis = Double.NaN;
        this.jm.renderController.result = (type, md) -> {
            this.clock += 7;
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertTrue(Double.isNaN(this.session.costPerColumnMillis),
                "two uncut columns are below the clock's resolution: no sample");
    }

    @Test
    void aPickAtTheFloorMeasuresItsWayBackUp() {
        dayOnly();
        this.session.costPerColumnMillis = 900; // one slow spell: the pick sits at the floor
        this.jm.renderController.result = (type, md) -> {
            this.clock += 1;
            return true;
        };
        for (int z = 0; z < 20; z++) for (int x = 0; x < 20; x++) offer(x, z);
        settle();
        cycle();
        assertEquals(JourneyMapSession.MIN_BATCH_PICK, this.session.batchPick);
        assertTrue(this.session.costPerColumnMillis < 900, "a batch that took its whole pick is a sample");
        for (int i = 0; i < 12; i++) {
            this.clock += 500;
            cycle();
        }
        assertTrue(this.session.batchPick > JourneyMapSession.MIN_BATCH_PICK, "recovered: " + this.session.batchPick);
        assertEquals(0, counter("requeued"));
    }

    @Test
    void aClockJumpCostsAtMostOneBoxPerColumn() {
        dayOnly();
        boolean[] jumped = {false};
        this.jm.renderController.result = (type, md) -> {
            if (!jumped[0]) this.clock += 3_600_000; // the wall clock steps an hour mid-batch
            jumped[0] = true;
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(1, counter("requeued"));
        assertEquals(1000.0, this.session.costPerColumnMillis, 0.001, "capped at the 1000 ms box");
    }

    // ---- decode-ahead ----

    @Test
    void decodeAheadHandsJourneyMapsThreadWorkerDecodedSnapshots() {
        dayOnly();
        this.aheadDirect = true;
        var onWorker = new ArrayList<Boolean>();
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            onWorker.add(this.inWorker);
            return plain.decode(bytes, level);
        };
        var wrappedOnWorker = new ArrayList<Boolean>();
        var plainWrap = this.session.wrapper;
        this.session.wrapper = (level, snapshot) -> {
            wrappedOnWorker.add(this.inWorker);
            return plainWrap.wrap(level, snapshot);
        };
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) offer(x, z);
        var seen = new java.util.HashMap<Long, java.util.Set<Long>>();
        this.jm.renderController.result = (type, md) -> {
            seen.put(md.getLongCoord(), new java.util.HashSet<>(DataCache.INSTANCE.cache.keySet()));
            return true;
        };
        settle();
        cycle();
        assertEquals(9, counter("rendered"));
        assertEquals(List.of(true, true, true, true, true, true, true, true, true), onWorker,
                "every pure decode ran on a worker, once");
        assertEquals(9, wrappedOnWorker.size());
        assertFalse(wrappedOnWorker.contains(true), "the SnapshotChunkMD wrap stays on JourneyMap's thread");
        assertEquals(9, this.session.decodedAhead.get());
        assertEquals(9, this.session.predecodedColumns.get());
        assertEquals(9, this.session.decodedColumns.get(), "decode_us_per_column still counts every column");
        var middle = seen.get(JourneyMapSession.mcChunkKey(1, 1));
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            assertTrue(middle.contains(JourneyMapSession.mcChunkKey(1 + dx, 1 + dz)), "the neighbour shading is unchanged");
        }
        assertTrue(DataCache.INSTANCE.cache.isEmpty());
    }

    @Test
    void aContextNeighbourIsDecodedAheadToo() {
        dayOnly();
        offer(0, 0);
        settle();
        cycle();
        this.aheadDirect = true;
        var workerFrames = new ArrayList<Integer>();
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            if (this.inWorker) workerFrames.add(ByteBuffer.wrap(bytes).getInt());
            return plain.decode(bytes, level);
        };
        DataCache.INSTANCE.added.clear();
        this.clock += 500;
        offer(1, 0); // its west neighbour (0,0) is context
        cycle();
        assertEquals(List.of(0, 1), workerFrames, "the context column first, then the batch column");
        assertTrue(DataCache.INSTANCE.added.contains(JourneyMapSession.mcChunkKey(0, 0)));
        assertEquals(1, this.session.decodedAhead.get(), "decoded_ahead counts batch columns only");
    }

    @Test
    void theWindowKeepsTheWorkersJustAheadAndTheBatchEndCancelsTheRest() {
        dayOnly();
        for (int z = 0; z < 10; z++) for (int x = 0; x < 10; x++) offer(x, z);
        this.jm.renderController.result = (type, md) -> {
            this.clock += 5_000; // the first render spends the whole box
            return true;
        };
        int[] decodes = {0};
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            decodes[0]++;
            return plain.decode(bytes, level);
        };
        settle();
        cycle();
        assertEquals(1, counter("rendered"));
        assertEquals(99, counter("requeued"));
        // (0,0) and its east/south look-ahead were needed: plan indices 0-3, a window past each.
        assertEquals(4 + JourneyMapSession.DECODE_AHEAD_WINDOW, this.aheadSubmitted,
                "a box-cut tail is never handed to the workers");
        assertEquals(4, decodes[0], "no worker started: the four columns needed decoded inline");
        for (var job : this.aheadQueue) {
            var j = (JourneyMapSession.AheadJob) job;
            assertEquals(JourneyMapSession.AheadJob.CLAIMED, j.state.get(), "every unstarted job is cancelled");
            assertFalse(j.holdsLevelForTest(), "a cancelled job left in the pool's queue pins no level");
        }
        this.aheadQueue.forEach(Runnable::run); // the workers get to them late
        assertEquals(4, decodes[0], "a cancelled job never decodes");
        assertEquals(0, this.session.predecodedColumns.get());
    }

    @Test
    void anUnstartedJobIsClaimedAndDecodedInline() {
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"));
        assertEquals(0, this.session.decodedAhead.get());
        assertEquals(2, this.aheadSubmitted);
        assertTrue(this.aheadQueue.stream().allMatch(j -> ((JourneyMapSession.AheadJob) j).state.get()
                == JourneyMapSession.AheadJob.CLAIMED));
    }

    @Test
    void aRejectedJobFallsBackToTheInlineDecode() {
        this.aheadRejects = true;
        offer(0, 0);
        offer(1, 0);
        offer(0, 1);
        settle();
        cycle();
        assertEquals(3, counter("rendered"));
        assertEquals(0, this.session.decodedAhead.get());
        assertEquals(0, counter("decode_failures"));
        assertTrue(this.reports.isEmpty());
    }

    /** One fixed scenario — (1,0) refused, (2,0) throwing — run with and without decode-ahead. */
    private List<Long> failureOutcome(boolean ahead) {
        dayOnly();
        this.aheadDirect = ahead;
        this.session.decodeAhead = ahead;
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            int x = ByteBuffer.wrap(bytes).getInt();
            if (x == 1) return null; // JourneyMap refuses the bytes
            if (x == 2) throw new IllegalStateException("decoder bug");
            return plain.decode(bytes, level);
        };
        offer(0, 0);
        offer(1, 0);
        offer(2, 0);
        settle();
        cycle();
        return List.of(counter("rendered"), counter("decode_failures"), counter("column_failures"),
                (long) this.reports.size(), this.session.decodedAhead.get());
    }

    @Test
    void aWorkerDecodeFailureMeansWhatTheInlineOneMeant() {
        var inline = failureOutcome(false);
        assertEquals(List.of(1L, 1L, 1L, 1L, 0L), inline,
                "refused: a decode failure, closed unreported; thrown: a column failure, reported");
        setUpQuietly();
        var ahead = failureOutcome(true);
        assertEquals(inline.subList(0, 4), ahead.subList(0, 4), "decode-ahead changes no outcome");
        assertEquals(2L, ahead.get(4), "the refused column's null came from a worker");
    }

    private void setUpQuietly() {
        try {
            setUp();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A real worker thread that STARTS the decode-ahead job of one column (it wins the job) and
     * parks inside that decode until {@link #finish} (bounded). The executor returns only once
     * the decode is running, so JourneyMap's thread always finds that job started: its wait is
     * reached deterministically. Every other job stays in {@link #aheadQueue}, never started.
     */
    private final class ParkedWorker {
        static final long PARK_BOUND_SECONDS = 20;
        final long key;
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        volatile Thread thread;

        ParkedWorker(int chunkX, int chunkZ) {
            this.key = JourneyMapSession.packed(chunkX, chunkZ);
            var queueing = session.decodeAheadExecutor;
            session.decodeAheadExecutor = job -> {
                if (((JourneyMapSession.AheadJob) job).key != this.key) {
                    queueing.execute(job);
                    return;
                }
                var t = new Thread(job, "test-parked-decode");
                t.setDaemon(true);
                this.thread = t;
                t.start();
                try {
                    assertTrue(this.started.await(PARK_BOUND_SECONDS, java.util.concurrent.TimeUnit.SECONDS),
                            "the worker started the decode");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            };
            var plain = session.decoder;
            session.decoder = (bytes, level) -> {
                if (Thread.currentThread() == this.thread) {
                    this.started.countDown();
                    try {
                        this.release.await(PARK_BOUND_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return plain.decode(bytes, level);
            };
        }

        /** Releases the parked decode and waits (bounded) for the worker to end. */
        void finish() throws InterruptedException {
            this.release.countDown();
            var t = this.thread;
            assertNotNull(t, "premise: the job was handed to the worker");
            t.join(PARK_BOUND_SECONDS * 1000);
            assertFalse(t.isAlive(), "the worker ended");
        }
    }

    @Test
    void aStartedDecodeThatOverrunsTheWaitIsDecodedInlineNeverDropped() throws Exception {
        dayOnly();
        this.session.decodeAheadWaitMillis = 20;
        var worker = new ParkedWorker(1, 0); // (0,0)'s east look-ahead
        try {
            offer(0, 0);
            offer(1, 0);
            settle();
            cycle(); // JourneyMap's thread waits 20 ms for (1,0), then decodes it itself
            assertEquals(2, counter("rendered"), "the column whose wait timed out renders and is accepted");
            assertEquals(0, counter("decode_failures"), "never a decode failure closed unreported");
            assertEquals(0, counter("column_failures"));
            assertTrue(this.reports.isEmpty());
            assertEquals(0, this.session.decodedAhead.get(), "the snapshot came from the inline decode");
            assertEquals(0, this.session.predecodedColumns.get(), "premise: the worker is still inside its decode");
            assertEquals(2, this.session.replay.size());
        } finally {
            worker.finish();
        }
        assertEquals(1, this.session.predecodedColumns.get(), "the worker finished alone");
        this.session.pump();
        assertEquals(2, counter("rendered"), "and its late snapshot changed nothing");
        assertTrue(DataCache.INSTANCE.cache.isEmpty());
    }

    /**
     * JourneyMap interrupts its task thread from {@code stopMapping} while that thread waits for
     * a started decode of (5,5): the interrupt arrives during the decode of (5,5)'s west context
     * column (4,5), after the column loop's stop check, so it lands in the wait itself. With
     * {@code next}, a later column (20,20) follows in the batch.
     */
    private void interruptedWhileWaitingForAWorker(boolean next) throws Exception {
        dayOnly();
        offer(4, 5);
        settle();
        cycle(); // (4,5) becomes context for the next batch
        assertEquals(1, counter("rendered"));
        this.session.decodeAheadWaitMillis = 60_000; // only the interrupt can end this wait early
        var worker = new ParkedWorker(5, 5);
        var parked = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            if (ByteBuffer.wrap(bytes).getInt() == 4) Thread.currentThread().interrupt(); // stopMapping
            return parked.decode(bytes, level);
        };
        offer(5, 5);
        if (next) offer(20, 20);
        this.clock += 500;
        boolean interruptKept;
        long waitedNanos;
        try {
            this.session.pump();
            long t0 = System.nanoTime();
            assertEquals(1, this.jm.runQueued());
            waitedNanos = System.nanoTime() - t0;
        } finally {
            interruptKept = Thread.interrupted(); // never leak the flag into the next test
            worker.finish();
        }
        this.session.pump();
        assertTrue(interruptKept, "the interrupt is kept for JourneyMap's thread");
        assertTrue(waitedNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(ParkedWorker.PARK_BOUND_SECONDS),
                "the wait ended at the interrupt, not when the worker finished");
        assertEquals(0, this.session.decodedAhead.get(), "the worker's snapshot was never taken");
        assertEquals(0, counter("decode_failures"));
        assertTrue(renderedPositions().contains(JourneyMapSession.packed(5, 5)),
                "the interrupted column finished from an inline decode");
    }

    @Test
    void anInterruptDuringTheWaitFinishesTheLastColumnInline() throws Exception {
        interruptedWhileWaitingForAWorker(false);
        assertEquals(2, counter("rendered"), "the batch's last column is accepted");
        assertEquals(0, counter("abandoned"));
        assertTrue(this.reports.isEmpty(), "never closed unreported, never re-served");
    }

    @Test
    void anInterruptDuringTheWaitStopsTheBatchAtTheNextColumn() throws Exception {
        interruptedWhileWaitingForAWorker(true);
        assertFalse(renderedPositions().contains(JourneyMapSession.packed(20, 20)), "no column after the stop renders");
        assertEquals(1, counter("rendered"), "nothing of the stopped batch is accepted");
        assertEquals(1, counter("abandoned"));
        assertEquals(2, this.reports.size(), "every column re-serves into the next mapping");
    }

    @Test
    void noDecodeAheadWorkOrReferenceOutlivesTheBatchOrTheSession() {
        dayOnly();
        for (int x = 0; x < 6; x++) offer(x, 0);
        this.jm.renderController.result = (type, md) -> {
            this.sessionActive = false; // the LSS session ends mid-batch
            return true;
        };
        settle();
        cycle();
        assertEquals(1, counter("rendered"));
        assertFalse(this.aheadQueue.isEmpty());
        for (var job : this.aheadQueue) {
            assertFalse(((JourneyMapSession.AheadJob) job).holdsLevelForTest(), "nothing pins the old level");
        }
        for (var f : JourneyMapSession.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(f.getType() == JourneyMapSession.DecodeAhead.class || f.getType() == JourneyMapSession.AheadJob.class,
                    "decode-ahead state lives only in its batch: " + f.getName());
        }
        assertTrue(DataCache.INSTANCE.cache.isEmpty());
    }

    @Test
    void theKillSwitchDecodesEveryColumnInline() {
        String old = System.getProperty(JourneyMapSession.DECODE_AHEAD_PROPERTY);
        try {
            System.setProperty(JourneyMapSession.DECODE_AHEAD_PROPERTY, "false");
            newSession(this.session.h);
            assertFalse(this.session.decodeAhead);
            this.aheadDirect = true;
            offer(0, 0);
            offer(1, 0);
            settle();
            cycle();
            assertEquals(2, counter("rendered"));
            assertEquals(0, this.aheadSubmitted, "nothing reaches the workers");
            assertTrue(this.session.describe().contains("decode_ahead=off"), this.session.describe());
            JourneyMapSession.instance = this.session;
            assertEquals(false, JourneyMapSession.metrics().get("decode_ahead"));
        } finally {
            if (old == null) System.clearProperty(JourneyMapSession.DECODE_AHEAD_PROPERTY);
            else System.setProperty(JourneyMapSession.DECODE_AHEAD_PROPERTY, old);
        }
        assertTrue(JourneyMapSession.decodeAheadProperty(), "on by default");
    }

    @Test
    void theRealPoolDecodesOnNamedDaemonWorkersAndWrapsOnJourneyMapsThread() {
        dayOnly();
        this.session.decodeAheadExecutor = JourneyMapSession::executeDecodeAhead;
        var decodeThreads = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            decodeThreads.add(Thread.currentThread());
            return plain.decode(bytes, level);
        };
        var wrapThreads = new java.util.HashSet<Thread>();
        var plainWrap = this.session.wrapper;
        this.session.wrapper = (level, snapshot) -> {
            wrapThreads.add(Thread.currentThread());
            return plainWrap.wrap(level, snapshot);
        };
        // The first render waits (bounded) until a worker has started a decode. Without it a
        // starved box could start the workers only after JourneyMap's thread had claimed every
        // job, and decoded_ahead would read 0 (O6 review: a possible flake).
        var workerDecoded = new java.util.concurrent.CountDownLatch(1);
        var plainDecode = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            if (Thread.currentThread().getName().contains("-JM-DecodeAhead-")) workerDecoded.countDown();
            return plainDecode.decode(bytes, level);
        };
        boolean[] noWorker = {false};
        this.jm.renderController.result = (type, md) -> {
            try {
                if (!workerDecoded.await(30, java.util.concurrent.TimeUnit.SECONDS)) noWorker[0] = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };
        for (int z = 0; z < 2; z++) for (int x = 0; x < 32; x++) offer(x, z);
        settle();
        cycle();
        assertFalse(noWorker[0], "a pool worker started a decode within 30 s");
        assertEquals(64, counter("rendered"));
        assertEquals(java.util.Set.of(Thread.currentThread()), wrapThreads, "every wrap on JourneyMap's thread");
        assertTrue(this.session.decodedAhead.get() > 0, "the workers decoded ahead: " + this.session.decodedAhead.get());
        for (var t : decodeThreads) {
            if (t == Thread.currentThread()) continue; // a column claimed before a worker started it
            assertTrue(t.isDaemon(), t.getName());
            assertTrue(t.getName().matches("[A-Z]+-JM-DecodeAhead-\\d+"), t.getName());
        }
        assertTrue(JourneyMapSession.decodeAheadPool().allowsCoreThreadTimeOut(), "idle workers exit");
        assertTrue(this.session.describe().contains("decode_ahead=on"));
        JourneyMapSession.instance = this.session;
        var metrics = JourneyMapSession.metrics();
        assertEquals(this.session.decodedAhead.get(), metrics.get("decoded_ahead"));
        long predecoded = this.session.predecodedColumns.get();
        assertTrue(predecoded > 0);
        assertEquals(this.session.predecodeNanos.get() / predecoded / 1000, metrics.get("predecode_us_per_column"));
        assertEquals(this.session.batchPick, metrics.get("batch_pick"));
    }
}
