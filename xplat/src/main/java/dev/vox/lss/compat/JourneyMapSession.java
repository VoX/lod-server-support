package dev.vox.lss.compat;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.config.LSSClientConfig;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The JourneyMap map-ingestion backend (map-ingestion plan §3): writes received LSS columns
 * into JourneyMap's map through JourneyMap's OWN chunk-snapshot render path — the sequence
 * JourneyMap's own (unused) debug code runs: snapshot bytes → {@code JMChunkSnapshot.decode}
 * → {@code SnapshotChunkMD} (a chunk wrapper that reads no world state) →
 * {@code ChunkRenderController.renderChunk} per map type → {@code RegionImageCache
 * .updateTextures}. JourneyMap then saves its region PNGs, uploads textures and refreshes the
 * minimap and fullscreen map itself. Reflection only (JourneyMap is All Rights Reserved).
 *
 * <p>Threads: the consumer ENCODES on the LSS decode thread and queues the bytes; the PUMP
 * (main client thread) observes JourneyMap's mapping identity, settles, and submits ONE batch
 * at a time to JourneyMap's own single task thread ({@code queueOneOff}, under the
 * {@code JourneymapClient} monitor {@code stopMapping} holds); the BATCH renders and publishes
 * on that thread and always ends in a terminal result the pump reconciles. Only the pure
 * {@code JMChunkSnapshot.decode} (bytes → snapshot; no JourneyMap state, see the bridge
 * record's purity evidence) may run ahead of the batch on two LSS DECODE-AHEAD workers; the
 * {@code SnapshotChunkMD} wrap, every {@code DataCache} touch and every render stay on
 * JourneyMap's thread.
 *
 * <p>Doctrine (Xaero bridge parity): a column is accepted only when its DAY map rendered under
 * an unchanged identity (topography and biome are best effort, as in JourneyMap's own
 * pipeline); anything else is reported for its bounded re-serve or requeued, never silently
 * lost. Inert states (JourneyMap disabled or its mapping switched off, mapping not allowed,
 * dimensions with a ceiling) drop offers before encoding and report no backpressure, so a map
 * that cannot take terrain never holds the stream. The edge sweep renders JourneyMap's own
 * live chunks in the ring between its render range and the LOD disc.
 */
final class JourneyMapSession {
    static final int MAX_QUEUE = 8192;
    static final long MAX_QUEUE_BYTES = 48L * 1024 * 1024;
    /** A batch's column pick before any batch has been measured (the old fixed batch size). */
    static final int INITIAL_BATCH_PICK = 256;
    /** The adaptive pick's range: the box over the measured per-column cost, clamped. */
    static final int MIN_BATCH_PICK = 16;
    static final int MAX_BATCH_PICK = 2048;
    /** The pick aims at this share of the batch box, so the box rarely cuts a tail (a cut tail
     *  is requeued and its decode-ahead wasted); the rest absorbs terrain-cost variance. */
    static final double BATCH_BOX_FILL = 0.85;
    /** The newest batch's weight in the per-column cost average. */
    static final double COST_EMA_ALPHA = 0.25;
    /** A batch informs the cost average only when it processed this many columns (or the box
     *  cut it): a handful of columns is below the millisecond clock's resolution. */
    static final int COST_SAMPLE_MIN_COLUMNS = 32;
    /** Distinct regions one batch may draw from. Each region a batch renders opens JourneyMap's
     *  off-heap region images (about 1.3 MiB per map type), kept until its 30 s access expiry —
     *  a scattered queue must not open hundreds per batch. JourneyMap's own mapping pass spans
     *  up to 9 regions (a 32-chunk render distance). */
    static final int MAX_BATCH_REGIONS = 8;
    /** Decode-ahead: LSS-owned daemon workers running ONLY the pure snapshot decode. */
    static final int DECODE_AHEAD_THREADS = 2;
    /** Snapshots the workers may decode past the one JourneyMap's thread needs now: a box-cut
     *  tail wastes at most this many decodes. */
    static final int DECODE_AHEAD_WINDOW = 32;
    /** The pool's queue bound (one batch submits at most a window at a time; a full queue is a
     *  rejection, and a rejected column decodes inline). */
    static final int DECODE_AHEAD_QUEUE = 256;
    /** Idle decode-ahead workers exit after this long: no thread outlives the map work. */
    static final long DECODE_AHEAD_KEEP_ALIVE_SECONDS = 30;
    /** JourneyMap's thread waits at most this long for a decode a worker started (it takes
     *  about a millisecond), then decodes the column itself. */
    static final long DECODE_AHEAD_WAIT_MILLIS = 5_000;
    /** Dev-only A/B kill switch, NOT a setting: {@code -Dlss.journeymap.decodeAhead=false}
     *  decodes every column inline on JourneyMap's thread, as before 0.19.0. */
    static final String DECODE_AHEAD_PROPERTY = "lss.journeymap.decodeAhead";
    /** A JourneyMap server sends its world id after mapping starts and JourneyMap then
     *  restarts mapping in a new directory: writes wait for the identity to settle. */
    static final long SETTLE_MILLIS = 3000;
    /** {@code getJMWorldDir} is static-synchronized and creates directories. */
    static final long IDENTITY_POLL_MILLIS = 1000;
    static final long START_TIMEOUT_MILLIS = 30_000;
    static final long SLOW_BATCH_MILLIS = 10_000;
    /** A batch STARTED this long ago without finishing means JourneyMap's task thread is stuck
     *  inside it: the session latches dead (its queue would otherwise hold the stream and churn
     *  re-serves for the rest of the process). The slot is never reclaimed — the thread may
     *  still write its result. */
    static final long WEDGED_BATCH_MILLIS = 6 * SLOW_BATCH_MILLIS;
    /** A not-mapping / no-world-directory hold inside an LSS session that outlasts this is no
     *  longer a switch in progress: it turns inert, so it stops holding the stream. */
    static final long STUCK_HOLD_MILLIS = 30_000;
    static final long DEFAULT_RENDER_DELAY_MILLIS = 500;
    /** A batch runs for at most this many of JourneyMap's renderDelays (its own mapping
     *  tasks may run three), then idles half of one: JourneyMap's queued tasks run first,
     *  so its own mapping waits at most one batch. The pick is sized to fill the box (it
     *  binds; a batch of one region's columns used to end long before it), so this pace bounds
     *  the map — and, maps pacing the download (U2), the LOD fill. */
    static final int BATCH_BOX_RENDER_DELAYS = 2;
    static final int DEATH_LATCH = 5;
    /** Recently rendered columns kept as the next batches' seam context: about the last two
     *  batches at the largest pick (they were 2 × 256 when a batch was one region's 256), bound
     *  first by {@link #CONTEXT_MAX_BYTES}, which is unchanged. */
    static final int CONTEXT_CAPACITY = 2 * MAX_BATCH_PICK;
    static final long CONTEXT_MAX_BYTES = 16L * 1024 * 1024;
    /** A late world id re-requests what was rendered into the abandoned directory in the last
     *  this-many milliseconds (plan §3.5). */
    static final long REPLAY_WINDOW_MILLIS = 30_000;
    /** The replay ring's hard cap: the whole window at one full pick per batch cycle (the box
     *  plus the gap) at the default renderDelay, 24 × 2048 = 49 152 columns, about 1 640
     *  columns/s. The measured rate is 550 (26.2 restage); the cap binds only if JourneyMap's
     *  thread averages under about 0.47 ms per column, half the fastest measured render.
     *  Renders older than the window leave the ring first, so it holds the window at the real
     *  rate: about 0.7 MiB at 550 columns/s, about 2 MiB at the cap. (4096 entries covered only
     *  about 7 s at 550 columns/s: a later world id left permanent holes, O5 review.) */
    static final int REPLAY_CAPACITY = (int) (REPLAY_WINDOW_MILLIS
            / (DEFAULT_RENDER_DELAY_MILLIS * BATCH_BOX_RENDER_DELAYS + DEFAULT_RENDER_DELAY_MILLIS / 2))
            * MAX_BATCH_PICK;
    /** The edge sweep's cadence and reach beyond JourneyMap's own mapping range (chunks). */
    static final long EDGE_SWEEP_MILLIS = 2000;
    static final int EDGE_BAND_CHUNKS = 3;
    /** JourneyMap cache lookups one edge sweep may make for chunks it has not drawn yet: each
     *  lookup can add an entry to JourneyMap's bounded chunk cache, and a low surface cap makes
     *  the band thousands of chunks wide — pace the first fill instead of flushing its working
     *  set every sweep (re-review). */
    static final int EDGE_LOOKUPS_PER_SWEEP = 256;
    /** Consecutive null cache lookups (our wrapper evicted before its render) a column survives
     *  before it is reported for a re-serve instead of requeued again. */
    static final int NULL_LOOKUP_LIMIT = 8;
    /** A failed-batch streak also needs this many failed columns before it latches: steady-state
     *  batches are often one or two dirty re-serves (re-review). */
    static final int DEATH_LATCH_MIN_COLUMNS = 32;

    static final LogThrottle FAILURE_WARN = new LogThrottle(60_000);

    /** The Minecraft operations the session needs — a seam: {@code ClientLevel} and
     *  {@code Minecraft} are unconstructible under fabric-loader-junit. */
    interface Host {
        Object minecraft();
        Object level();
        boolean playerInLevel();
        Object dimension(Object level);
        boolean hasCeiling(Object level);
        /** The player's packed chunk position, or {@link Long#MIN_VALUE} without a player. */
        long playerChunk();
        /** The client's loaded chunk at a position (the stable {@code LevelChunk}), or null —
         *  read from the client level, never through JourneyMap's cache. */
        Object liveChunk(Object level, int chunkX, int chunkZ);
    }

    /** Encoded snapshot bytes → JourneyMap's {@code JMChunkSnapshot}, or null when JourneyMap
     *  refuses the bytes. PURE: it runs on a decode-ahead worker or inline on JourneyMap's
     *  thread. A seam: production decodes through the bindings, which need a real
     *  {@code ClientLevel}; Tier 1 substitutes the stub (the real decoder has its own
     *  parity test against the real jar). */
    @FunctionalInterface
    interface SnapshotDecoder {
        Object decode(byte[] bytes, Object level) throws Throwable;
    }

    /** A decoded snapshot → its {@code SnapshotChunkMD} wrapper. JourneyMap's task thread
     *  ONLY. A seam for the same reason as {@link SnapshotDecoder}. */
    @FunctionalInterface
    interface SnapshotWrapper {
        Object wrap(Object level, Object snapshot) throws Throwable;
    }

    static final Host PRODUCTION_HOST = new Host() {
        @Override public Object minecraft() { return Minecraft.getInstance(); }

        @Override public Object level() {
            var mc = Minecraft.getInstance();
            return mc == null ? null : mc.level;
        }

        @Override public boolean playerInLevel() {
            var mc = Minecraft.getInstance();
            return mc != null && mc.player != null && mc.level != null && mc.player.level() == mc.level;
        }

        @Override public Object dimension(Object level) { return ((ClientLevel) level).dimension(); }

        @Override public boolean hasCeiling(Object level) { return ((ClientLevel) level).dimensionType().hasCeiling(); }

        @Override public long playerChunk() {
            var mc = Minecraft.getInstance();
            var player = mc == null ? null : mc.player;
            return player == null ? Long.MIN_VALUE : packed(player.getBlockX() >> 4, player.getBlockZ() >> 4);
        }

        @Override public Object liveChunk(Object level, int chunkX, int chunkZ) {
            return ((ClientLevel) level).getChunkSource().getChunk(chunkX, chunkZ,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        }
    };

    // ---- static facade (ModCompat owns it through JourneyMapCompat) ----

    static volatile JourneyMapSession instance;
    /** Installed but this JourneyMap predates the snapshot layer (the 1.21.10 beta). */
    static volatile boolean unsupported;
    /** Installed, has the snapshot layer, but a member did not resolve: drift. */
    static volatile String unavailableReason;

    /**
     * Resolves JourneyMap's classes WITHOUT initializing them. Binding runs at LSS's client
     * init, before JourneyMap has necessarily initialized itself: running a JourneyMap
     * static initializer there (DataCache's reads JourneyMap's not-yet-loaded config)
     * throws, poisons the class for good, and JourneyMap then crashes on its first chunk
     * load (found by the live smoke). Classes initialize on first use instead — on the pump
     * or JourneyMap's own thread, once JourneyMap is mapping.
     */
    static final XaeroSession.ClassResolver PRODUCTION_RESOLVER =
            name -> Class.forName(name, false, JourneyMapSession.class.getClassLoader());

    static boolean init() {
        return initWith(PRODUCTION_RESOLVER, PRODUCTION_HOST);
    }

    static boolean initWith(XaeroSession.ClassResolver resolver, Host host) {
        try {
            var h = JourneyMapBindings.resolve(resolver);
            var session = new JourneyMapSession(h, host,
                    () -> LSSClientConfig.CONFIG.mapIngestionEnabled(),
                    LSSApi::isServerEnabled,
                    LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer,
                    () -> LSSClientConfig.CONFIG.enableIngestBackpressure()
                            && LSSClientConfig.CONFIG.mapIngestionBackpressure(),
                    XaeroSession::reportDroppedProduction,
                    System::currentTimeMillis,
                    // Map-policy plan A4: the server's map-ingestion request (write paths only).
                    dev.vox.lss.networking.client.ClientNetGlue::serverAllowsMapIngestion);
            session.memory = MapMemoryGuard.PRODUCTION;
            session.connected = XaeroSession::productionConnected;
            session.maybeRegister();
            instance = session;
            LSSLogger.info(LSSClientConfig.CONFIG.mapIngestionEnabled()
                    ? "JourneyMap detected — LOD map ingestion active"
                    : "JourneyMap detected — LOD map ingestion ready (map ingestion is disabled in the client settings)");
            return true;
        } catch (JourneyMapBindings.Unsupported e) {
            unsupported = true;
            LSSLogger.info("JourneyMap detected, but " + e.getMessage() + " — LOD map ingestion is off for it");
            return false;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            unavailableReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LSSLogger.warn("JourneyMap map ingestion: this JourneyMap version has a different internal surface"
                    + " — map ingestion is off for it:", e);
            return false;
        } catch (Throwable e) {
            unavailableReason = e.getClass().getSimpleName();
            LSSLogger.error("Failed to initialize JourneyMap map ingestion", e);
            return false;
        }
    }

    /** End-of-client-tick body (main client thread; also runs on the title screen). */
    static void clientTick() {
        var session = instance;
        if (session != null) session.pump();
    }

    /** Disconnect body — any thread. */
    static void onDisconnect() {
        var session = instance;
        if (session != null) session.onSessionEnd();
    }

    /** Receive OFF / a replaced request manager. */
    static void retireClientAcquisition() {
        var session = instance;
        if (session != null) session.retireAcquisition();
    }

    /** Resolved — the menu's "a supported map mod is installed" fact. */
    static boolean isSupported() {
        return instance != null;
    }

    /** ARMED = flag ∧ installed ∧ resolved ∧ not dead: JourneyMap can write its store
     *  (the alias corroboration's gate input — its store is keyed per server-list entry). */
    static boolean isArmed() {
        var session = instance;
        return session != null && !session.dead && LSSClientConfig.CONFIG.mapIngestionEnabled();
    }

    /** Whether a column arriving now is written to JourneyMap: the consumer is registered,
     *  the session is active and not inert, and the server allows it. Client thread. */
    static boolean isWriting() {
        var session = instance;
        return session != null && session.registered && session.active()
                && session.serverAllowsWrites.getAsBoolean();
    }

    /** The conditional {@code /lss diag} line, or null when JourneyMap is not installed. */
    static String diagLine() {
        var session = instance;
        if (session != null) return session.describe();
        if (unsupported) return "JourneyMap: state=unsupported (" + JourneyMapLineSurface.UNSUPPORTED_HINT + ")";
        String reason = unavailableReason;
        return reason != null
                ? "JourneyMap: state=unavailable(" + reason + ") (unrecognized JourneyMap internals — map ingestion off)"
                : null;
    }

    /** The soak/benchmark client snapshot's {@code journeymap} group (live gates). */
    static java.util.Map<String, Object> metrics() {
        var session = instance;
        var out = new java.util.LinkedHashMap<String, Object>();
        if (session == null) {
            out.put("state", unsupported ? "unsupported" : unavailableReason != null ? "unavailable" : "absent");
            return out;
        }
        out.put("state", session.state());
        out.put("server_blocked", !session.serverAllowsWrites.getAsBoolean()); // map-policy plan A5
        out.put("queued", session.queuedGauge);
        for (String name : new String[]{"batches", "rendered", "skipped_live", "render_failed", "decode_failures",
                "column_failures", "abandoned", "replayed", "requeued", "dropped_overflow", "dropped_stale",
                "dropped_inert", "drops_reported", "optional_refusals", "edge_sweeps", "edge_rendered"}) {
            out.put(name, session.counterForTest(name));
        }
        out.put("slow_batches", session.slowBatches.get());
        out.put("encode_refused", session.encodeRefused.get());
        out.put("heap_holds", session.heapHolds.get());
        out.put("last_batch_ms", session.lastBatchMillis);
        long columns = session.encodedColumns.get();
        long rendered = session.rendered.get();
        out.put("encode_us_per_column", columns == 0 ? 0L : session.encodeNanos.get() / columns / 1000);
        out.put("render_us_per_column", rendered == 0 ? 0L : session.renderNanos.get() / rendered / 1000);
        long decoded = session.decodedColumns.get();
        // JourneyMap's thread's time to get a decoded column (waits, inline decodes, context, the wrap).
        out.put("decode_us_per_column", decoded == 0 ? 0L : session.decodeNanos.get() / decoded / 1000);
        out.put("decode_ahead", session.decodeAhead);
        out.put("decoded_ahead", session.decodedAhead.get());
        long predecoded = session.predecodedColumns.get();
        // A decode-ahead worker's time per snapshot it decoded (batch and context columns).
        out.put("predecode_us_per_column", predecoded == 0 ? 0L : session.predecodeNanos.get() / predecoded / 1000);
        out.put("batch_pick", session.batchPick);
        out.put("occupancy", session.occupancy);
        return out;
    }

    static ModCompat.JourneyMapStatus cachedStatus(boolean discoveryComplete) {
        var session = instance;
        if (session == null) {
            return new ModCompat.JourneyMapStatus(unsupported || unavailableReason != null ? "unavailable"
                    : discoveryComplete ? "absent" : "unknown", false, 0);
        }
        var batch = session.inFlight;
        return new ModCompat.JourneyMapStatus("available", session.dead,
                session.sessionEndPending ? 0 : session.queuedGauge + (batch != null ? batch.entries.size() : 0));
    }

    /** The backend's column consumer, whether or not it is registered right now (ModCompat's
     *  "is a map the only reader" checks compare against it); null without an instance. */
    static VoxelColumnConsumer mapConsumer() {
        var session = instance;
        return session == null ? null : session.consumer;
    }

    static void resetFacadeForTest() {
        instance = null;
        unsupported = false;
        unavailableReason = null;
    }

    // ---- instance ----

    final JourneyMapBindings h;
    final Host host;
    final BooleanSupplier enabled;
    final BooleanSupplier sessionActive;
    final Consumer<VoxelColumnConsumer> registrar;
    final Consumer<VoxelColumnConsumer> deregistrar;
    final BooleanSupplier backpressureEnabled;
    /** The server allows map writes on this connection (map-policy plan A4) — composed ONLY
     *  on the write paths (offerColumn, offerEncoded, pump, reportBackpressure), never into
     *  {@link #enabled} (registration settling, diag state). A bare constructor allows. */
    final BooleanSupplier serverAllowsWrites;
    final XaeroSession.DropReporter dropReporter;
    final IngestBackpressureGovernor governor;
    final VoxelColumnConsumer consumer;
    MapMemoryGuard memory = MapMemoryGuard.NEVER;
    SnapshotDecoder decoder = this::decodeWithBindings;
    SnapshotWrapper wrapper = this::wrapWithBindings;
    /** Decode-ahead on (the {@value #DECODE_AHEAD_PROPERTY} dev kill switch, read once per
     *  process: production builds this object once, at LSS's client init, and keeps it). */
    boolean decodeAhead = decodeAheadProperty();
    /** Where decode-ahead jobs run: the shared LSS pool, created on first use (a test seam). */
    java.util.concurrent.Executor decodeAheadExecutor = JourneyMapSession::executeDecodeAhead;
    /** JourneyMap's thread's wait for a started decode ({@value #DECODE_AHEAD_WAIT_MILLIS}; a
     *  test seam, so the timeout fallback is pinned without a 5 s wait). */
    long decodeAheadWaitMillis = DECODE_AHEAD_WAIT_MILLIS;
    /** JourneyMap's snapshot class was initialized on JourneyMap's own thread (JM thread only). */
    boolean snapshotClassReady;
    /** A connection exists (the title screen has none): a disabled backend releases its
     *  consumer at once only without one. Production reads the client's connection. */
    BooleanSupplier connected = () -> true;
    boolean registered; // main thread only

    // ---- queue (decode thread offers, main thread drains) ----
    final Object queueLock = new Object();
    final LinkedHashMap<Long, Entry> queue = new LinkedHashMap<>();
    long queuedBytes;
    volatile double occupancy;
    volatile int queuedGauge;
    int maxQueue = MAX_QUEUE;
    long maxQueueBytes = MAX_QUEUE_BYTES;
    final AtomicLong acquisitionGeneration = new AtomicLong();
    final ConcurrentLinkedQueue<Entry> pendingReports = new ConcurrentLinkedQueue<>();

    /** Non-null while offers drop before encoding (inert): the reason, for diag. */
    volatile String inertReason;
    /** Non-null while the pump holds the queue for a transient reason, for diag. */
    volatile String waitingReason = "not_mapping";
    volatile boolean dead;
    volatile boolean sessionEndPending;
    boolean heapHolding; // main thread only
    int consecutiveFailedBatches; // main thread only
    int consecutiveFailedColumns; // main thread only
    int consecutivePumpFailures; // main thread only
    /** When the current not-mapping / no-world-directory hold began (0 = not holding). */
    long holdSinceMillis; // main thread only
    /** The client level's dimension at the last pump: a change sweeps other dimensions' entries. */
    Object lastLevelDimension; // main thread only
    /** Bumped at every settled session end: a batch from an older session applies nothing. */
    long sessionEpoch; // main thread only
    boolean wedgeWarned; // main thread only

    // ---- identity + batches (main thread) ----
    Identity observed;
    long identitySinceMillis;
    long lastIdentityPollMillis = Long.MIN_VALUE;
    Batch inFlight;
    long nextSubmitAfterMillis;
    long renderDelayMillis = DEFAULT_RENDER_DELAY_MILLIS;
    /** The region the last batch drew from last (it may have columns left): the next batch
     *  continues it first. */
    long lastRegionKey = Long.MIN_VALUE;
    /** Average wall time per batch column on JourneyMap's thread, ms (NaN until a batch is
     *  measured). Main thread. */
    double costPerColumnMillis = Double.NaN;
    /** The last batch's column pick (main thread writes; diag reads). */
    volatile int batchPick = INITIAL_BATCH_PICK;
    final ArrayDeque<Rendered> replay = new ArrayDeque<>();
    /** Recently rendered encoded columns: the next batch's slope/biome-blend neighbours.
     *  Tagged with the map they were rendered into — a batch racing an identity change
     *  must never lend one map's terrain to another as context. */
    final LinkedHashMap<Long, ContextColumn> context = new LinkedHashMap<>(16, 0.75f, true);
    long contextBytes; // guarded by context

    // ---- counters ----
    final AtomicLong batches = new AtomicLong();
    final AtomicLong rendered = new AtomicLong();
    final AtomicLong skippedLive = new AtomicLong();
    final AtomicLong renderFailed = new AtomicLong();
    final AtomicLong decodeFailures = new AtomicLong();
    final AtomicLong columnFailures = new AtomicLong();
    final AtomicLong abandoned = new AtomicLong();
    final AtomicLong replayed = new AtomicLong();
    final AtomicLong requeued = new AtomicLong();
    final AtomicLong slowBatches = new AtomicLong();
    final AtomicLong droppedOverflow = new AtomicLong();
    final AtomicLong droppedStale = new AtomicLong();
    final AtomicLong droppedInert = new AtomicLong();
    final AtomicLong encodeRefused = new AtomicLong();
    final AtomicLong encodeNanos = new AtomicLong();
    final AtomicLong encodedColumns = new AtomicLong();
    final AtomicLong renderNanos = new AtomicLong();
    final AtomicLong decodeNanos = new AtomicLong();
    final AtomicLong decodedColumns = new AtomicLong();
    /** Batch columns whose snapshot JourneyMap's thread took already decoded from a worker. */
    final AtomicLong decodedAhead = new AtomicLong();
    /** Worker-side: snapshots decoded ahead (batch and context columns) and their time. */
    final AtomicLong predecodedColumns = new AtomicLong();
    final AtomicLong predecodeNanos = new AtomicLong();
    final AtomicLong dropsReported = new AtomicLong();
    final AtomicLong heapHolds = new AtomicLong();
    /** Optional map types (topography, biome) JourneyMap refused for an accepted column. */
    final AtomicLong optionalRefusals = new AtomicLong();
    final AtomicLong edgeSweeps = new AtomicLong();
    final AtomicLong edgeRendered = new AtomicLong();
    volatile long lastBatchMillis;
    long lastEdgeSweepMillis; // main thread
    /** Live chunks the edge sweep rendered, by position → the client's LevelChunk instance,
     *  held weakly so a disconnect never pins the old level (JM thread). */
    final java.util.HashMap<Long, java.lang.ref.WeakReference<Object>> edgeDone = new java.util.HashMap<>();
    /** The map the edge sweep last drew into — weakly: a disconnect must never pin the old
     *  level (and its whole chunk cache) or JourneyMap's old task controller (JM thread). */
    EdgeKey edgeKey; // JM thread

    JourneyMapSession(JourneyMapBindings h, Host host, BooleanSupplier enabled, BooleanSupplier sessionActive,
                      Consumer<VoxelColumnConsumer> registrar, Consumer<VoxelColumnConsumer> deregistrar,
                      BooleanSupplier backpressureEnabled, XaeroSession.DropReporter dropReporter, LongSupplier clock) {
        this(h, host, enabled, sessionActive, registrar, deregistrar, backpressureEnabled, dropReporter, clock,
                () -> true);
    }

    JourneyMapSession(JourneyMapBindings h, Host host, BooleanSupplier enabled, BooleanSupplier sessionActive,
                      Consumer<VoxelColumnConsumer> registrar, Consumer<VoxelColumnConsumer> deregistrar,
                      BooleanSupplier backpressureEnabled, XaeroSession.DropReporter dropReporter, LongSupplier clock,
                      BooleanSupplier serverAllowsWrites) {
        this.serverAllowsWrites = serverAllowsWrites;
        this.h = h;
        this.host = host;
        this.enabled = enabled;
        this.sessionActive = sessionActive;
        this.registrar = registrar;
        this.deregistrar = deregistrar;
        this.backpressureEnabled = backpressureEnabled;
        this.dropReporter = dropReporter;
        this.governor = new IngestBackpressureGovernor("JourneyMap map ingestion", clock);
        this.consumer = buildConsumer();
    }

    long now() {
        return this.governor.now();
    }

    /** One queued column: its encoded snapshot bytes and its delivery receipt. */
    static final class Entry {
        final ResourceKey<Level> dimension;
        final int chunkX;
        final int chunkZ;
        final byte[] bytes;
        final Receipt receipt;
        int nullLookups; // JourneyMap's task thread only (batches run there one at a time)

        Entry(ResourceKey<Level> dimension, int chunkX, int chunkZ, byte[] bytes, Receipt receipt) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.bytes = bytes;
            this.receipt = receipt;
        }

        long key() { return packed(this.chunkX, this.chunkZ); }
    }

    /** JourneyMap's mapping identity: a change means writes would land elsewhere. */
    record Identity(Object controller, Object level, Object dimension, File worldDir, String worldId) {
        @Override public boolean equals(Object o) {
            return o instanceof Identity i && i.controller == this.controller && i.level == this.level
                    && Objects.equals(i.dimension, this.dimension) && Objects.equals(i.worldDir, this.worldDir)
                    && Objects.equals(i.worldId, this.worldId);
        }

        @Override public int hashCode() { return Objects.hash(this.dimension, this.worldDir, this.worldId); }
    }

    /** {@link Identity} without strong references to the level and controller. */
    record EdgeKey(java.lang.ref.WeakReference<Object> controller, java.lang.ref.WeakReference<Object> level,
                   Object dimension, File worldDir, String worldId) {
        static EdgeKey of(Identity id) {
            return new EdgeKey(new java.lang.ref.WeakReference<>(id.controller()),
                    new java.lang.ref.WeakReference<>(id.level()), id.dimension(), id.worldDir(), id.worldId());
        }

        boolean matches(Identity id) {
            return this.controller.get() == id.controller() && this.level.get() == id.level()
                    && Objects.equals(this.dimension, id.dimension()) && Objects.equals(this.worldDir, id.worldDir())
                    && Objects.equals(this.worldId, id.worldId());
        }
    }

    record Rendered(Object dimension, int chunkX, int chunkZ, File worldDir, long atMillis) {}

    record ContextColumn(Object dimension, File worldDir, byte[] bytes) {}

    /** The delivery's acceptance lease (the Xaero {@code Origin} contract): released exactly
     *  once — accepted, reported or discarded. */
    final class Receipt {
        final long generation = acquisitionGeneration.get();
        final LSSApi.IngestFailureHandle handle = LSSApi.captureIngestFailureHandle();
        final Runnable release = this.handle == null ? () -> {} : this.handle.deferAcceptance();
        final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean written;

        boolean active() {
            return !this.closed.get() && this.generation == acquisitionGeneration.get()
                    && (this.handle == null || this.handle.isActive());
        }

        /** The column is on JourneyMap's map, or JourneyMap draws these chunks itself. */
        void markWritten() {
            this.written = true;
        }

        void close() {
            if (!this.closed.compareAndSet(false, true)) return;
            if (this.handle != null && this.written) {
                // Issue #380: the delivery learns that the column is on the map. Said here,
                // before the lease below is released, and only for a column that was
                // rendered: a delivery that ends without it leaves the map OWED the column,
                // so every path that lets one go (switched off, not allowed here, inert,
                // out of memory, a cleared queue) is covered without saying anything.
                try {
                    this.handle.written();
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                }
            }
            this.release.run();
        }

        /** Report for a bounded re-serve (main thread, no locks held); always closes. */
        void report(Object dimension, int chunkX, int chunkZ) {
            try {
                if (!active()) return;
                if (this.handle != null) this.handle.report();
                else dropReporter.report(dimension, chunkX, chunkZ);
                dropsReported.incrementAndGet();
            } catch (Throwable t) {
                if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                warn("a drop report threw (contained)", t);
            } finally {
                close();
            }
        }
    }

    static long packed(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Minecraft's own chunk-position encoding (x low, z high) — JourneyMap's cache key. */
    static long mcChunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    static long regionKeyOf(int chunkX, int chunkZ) {
        return XaeroSession.regionKeyOf(chunkX, chunkZ);
    }

    // ---- registration ----

    void maybeRegister() {
        if (!this.registered && !this.dead && this.enabled.getAsBoolean()) {
            this.registrar.accept(this.consumer);
            this.registered = true;
        }
    }

    /** Disabled with no connection (the title screen): release the consumer now, so the next
     *  handshake does not advertise LOD reception for a map the player just turned off
     *  (review). With a connection registration stays add-only until the session ends. */
    void settleOfflineRegistration() {
        if (this.registered && !this.enabled.getAsBoolean() && !this.connected.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        }
    }

    /** Any thread: the thread-safe half; the pump settles the rest. */
    void onSessionEnd() {
        this.acquisitionGeneration.incrementAndGet(); // every outstanding receipt goes inactive
        this.sessionEndPending = true;
    }

    /** A replaced request manager / receive OFF: queued work belongs to an old acquisition. */
    void retireAcquisition() {
        this.acquisitionGeneration.incrementAndGet();
        clearQueue();
    }

    void settleSessionEnd() {
        this.sessionEndPending = false;
        clearQueue();
        var batch = this.inFlight;
        if (batch != null && batch.state.compareAndSet(Batch.QUEUED, Batch.ABANDONED)) {
            batch.entries.forEach(e -> e.receipt.close());
            this.inFlight = null;
        }
        this.observed = null;
        this.waitingReason = "not_mapping";
        this.inertReason = null;
        this.governor.reset();
        this.heapHolding = false;
        this.dead = false; // the death latch is session-scoped
        this.consecutiveFailedBatches = 0;
        this.consecutiveFailedColumns = 0;
        this.consecutivePumpFailures = 0;
        this.holdSinceMillis = 0;
        this.lastLevelDimension = null;
        this.sessionEpoch++; // a still-running batch of this session applies nothing later
        this.wedgeWarned = false;
        this.lastRegionKey = Long.MIN_VALUE;
        this.replay.clear();
        synchronized (this.context) {
            this.context.clear();
            this.contextBytes = 0;
        }
        if (this.registered && !this.enabled.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        } else {
            maybeRegister();
        }
    }

    // ---- the consumer (decode thread) ----

    VoxelColumnConsumer buildConsumer() {
        // An ANONYMOUS class, never a lambda: a lambda cannot override the default
        // pendingIngestBacklog() and the taper would silently never engage.
        return new VoxelColumnConsumer() {
            @Override
            public int pendingIngestBacklog() {
                return reportBackpressure();
            }

            @Override
            public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                              int chunkX, int chunkZ, VoxelColumnData columnData) {
                try {
                    offerColumn(JourneyMapLineSurface.facts(level), dimension, chunkX, chunkZ, columnData);
                } catch (Throwable t) {
                    // Swallow EVERYTHING: an escape becomes an LSS ingest failure — a re-serve
                    // loop for a map problem (the Xaero bridge's review MAJOR).
                    warn("column encoding failed", t);
                }
            }
        };
    }

    void offerColumn(JourneyMapLineSurface.LevelFacts facts, ResourceKey<Level> dimension,
                     int chunkX, int chunkZ, VoxelColumnData data) {
        var receipt = new Receipt();
        if (!receipt.active() || this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()
                || !this.serverAllowsWrites.getAsBoolean()) { // map-policy plan A4: no write, no report
            receipt.close();
            return;
        }
        if (this.inertReason != null) {
            // Inert (JourneyMap disabled, mapping not allowed here, a ceiling dimension):
            // dropped before encoding, silently — re-serving cannot help.
            this.droppedInert.incrementAndGet();
            receipt.close();
            return;
        }
        long start = System.nanoTime();
        byte[] bytes;
        try {
            bytes = JourneyMapSnapshotEncoder.encode(chunkX, chunkZ, data, facts);
        } catch (Throwable t) {
            receipt.close(); // never leak the acceptance lease
            throw t;
        }
        this.encodeNanos.addAndGet(System.nanoTime() - start);
        this.encodedColumns.incrementAndGet();
        if (bytes == null) {
            this.encodeRefused.incrementAndGet();
            receipt.close();
            return;
        }
        offerEncoded(new Entry(dimension, chunkX, chunkZ, bytes, receipt));
    }

    /** Latest-wins by position, oldest-evicting within the count and byte bounds. */
    void offerEncoded(Entry entry) {
        var evicted = new ArrayList<Entry>(0);
        var stale = new ArrayList<Entry>(0);
        synchronized (this.queueLock) {
            if (!entry.receipt.active()) {
                // The session ended while this column was encoding: it must never reach the
                // next session's (maybe another server's) map.
                entry.receipt.close();
                return;
            }
            if (!this.serverAllowsWrites.getAsBoolean()) {
                // Map-policy plan A4: the server's deny arrived while this column encoded —
                // the pump's clear already ran; queued now it would be written later.
                entry.receipt.close();
                return;
            }
            if (this.inertReason != null || this.dead || !this.enabled.getAsBoolean()) {
                // Re-checked under the lock: the pump went inert (or died, or was switched
                // off) while this column was encoding, and its clear already ran — queued
                // now, the entry would strand its acceptance lease (review).
                this.droppedInert.incrementAndGet();
                entry.receipt.close();
                return;
            }
            var previous = this.queue.remove(entry.key());
            if (previous != null) {
                this.queuedBytes -= previous.bytes.length;
                if (Objects.equals(previous.dimension, entry.dimension)) {
                    previous.receipt.close(); // superseded by the newer delivery of the same column
                } else {
                    // Same position, another dimension: the old column was never written and
                    // its dimension's stamps persist — report it (the Xaero queue's rule).
                    this.droppedStale.incrementAndGet();
                    stale.add(previous);
                }
            }
            this.queue.put(entry.key(), entry);
            this.queuedBytes += entry.bytes.length;
            Iterator<Entry> it = this.queue.values().iterator();
            while ((this.queue.size() > this.maxQueue || this.queuedBytes > this.maxQueueBytes) && it.hasNext()) {
                var oldest = it.next();
                it.remove();
                this.queuedBytes -= oldest.bytes.length;
                evicted.add(oldest);
            }
            updateOccupancyLocked();
        }
        this.pendingReports.addAll(stale);
        for (var e : evicted) {
            this.droppedOverflow.incrementAndGet();
            // The Xaero overflow rule: reported for its re-serve only while governed and not
            // wedged (a wedged stream's report would churn re-serves into the same drop).
            if (this.backpressureEnabled.getAsBoolean() && !this.governor.wedged) this.pendingReports.add(e);
            else e.receipt.close();
        }
    }

    private void updateOccupancyLocked() {
        this.queuedGauge = this.queue.size();
        this.occupancy = Math.max((double) this.queue.size() / this.maxQueue,
                (double) this.queuedBytes / this.maxQueueBytes);
    }

    void clearQueue() {
        List<Entry> cleared;
        synchronized (this.queueLock) {
            cleared = new ArrayList<>(this.queue.values());
            this.queue.clear();
            this.queuedBytes = 0;
            updateOccupancyLocked();
        }
        cleared.forEach(e -> e.receipt.close());
    }

    // ---- backpressure (main thread) ----

    int reportBackpressure() {
        // Map-policy plan A4: a server-blocked map reports no signal — it never throttles Voxy.
        return this.governor.report(this.backpressureEnabled.getAsBoolean(),
                active() && this.serverAllowsWrites.getAsBoolean(), this.occupancy, false, this.heapHolding);
    }

    boolean active() {
        return !this.dead && this.enabled.getAsBoolean() && this.sessionActive.getAsBoolean() && this.inertReason == null;
    }

    // ---- the pump (main client thread) ----

    void pump() {
        if (this.sessionEndPending) settleSessionEnd();
        maybeRegister();
        settleOfflineRegistration();
        this.governor.notePump();
        try {
            if (this.dead) return;
            if (!this.enabled.getAsBoolean() || !this.serverAllowsWrites.getAsBoolean()) {
                // (Map-policy plan A4: a server deny takes the local-off branch.)
                if (!this.serverAllowsWrites.getAsBoolean()) abandonQueuedForDeny();
                reconcileInFlight(); // a finished batch's receipts still close
                clearQueue();
                return;
            }
            pumpInner();
            this.consecutivePumpFailures = 0;
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            warn("pump failed (contained)", t);
            // A pump that throws every tick (JourneyMap internals drifting at call time, not
            // bind time) would hold the queue and duty-cycle the governor for the whole
            // session: it latches like the Xaero bridge's commit failures (review).
            if (++this.consecutivePumpFailures >= DEATH_LATCH && !this.dead) {
                this.dead = true;
                clearQueue();
                LSSLogger.error("JourneyMap map ingestion: " + DEATH_LATCH + " consecutive pump failures —"
                        + " disabling it for this session (LODs are unaffected)", t);
            }
        } finally {
            drainPendingReports();
        }
    }

    /** Map-policy plan A4: a batch still QUEUED on JourneyMap's task thread when the server's
     *  deny lands never writes — its columns are not owed to a blocked map, so the receipts
     *  close unreported. (A batch already STARTED is past this point; {@link #render} checks
     *  the gate before it touches JourneyMap, so only a batch mid-write finishes.) */
    void abandonQueuedForDeny() {
        var batch = this.inFlight;
        if (batch != null && batch.state.compareAndSet(Batch.QUEUED, Batch.ABANDONED)) {
            batch.entries.forEach(e -> e.receipt.close());
            this.inFlight = null;
        }
    }

    void pumpInner() throws Throwable {
        reconcileInFlight();
        // Set again below only if this pump reaches the heap check: a stale hold must not
        // keep the long pace box while the session waits on something else (re-review).
        this.heapHolding = false;
        // Likewise the not-mapping / no-world-directory hold clock: only an uninterrupted
        // run of holding pumps ages it.
        long holdSince = this.holdSinceMillis;
        this.holdSinceMillis = 0;
        long now = now();
        Object client = this.h.getInstance.invoke();
        Object level = this.host.level();
        if (client == null || level == null || !this.host.playerInLevel()) {
            this.waitingReason = "no_world";
            return;
        }
        Object levelDimension = this.host.dimension(level);
        if (this.lastLevelDimension != null && !Objects.equals(levelDimension, this.lastLevelDimension)) {
            // The client changed dimension: JourneyMap stops mapping for the switch, so the
            // identity change that would report the departed dimension's queued columns may
            // come late or never — report them now, whatever JourneyMap is doing (review).
            sweepForeignDimensions(levelDimension);
        }
        this.lastLevelDimension = levelDimension;
        if (!(boolean) this.h.enabled.invoke(client)) {
            inert("journeymap_disabled", null);
            return;
        }
        Object props = this.h.getCoreProperties.invoke(client);
        if (props != null && Boolean.FALSE.equals(this.h.booleanFieldGet.invoke(this.h.mappingEnabled.invoke(props)))) {
            // JourneyMap's own "mapping disabled" toggle: it will not map again until the
            // user turns it back on — inert, never a transient hold (review MAJOR).
            inert("mapping_disabled", null);
            return;
        }
        if (this.host.hasCeiling(level)) {
            inert("ceiling_dimension", this.host.dimension(level));
            return;
        }
        boolean mapping = Boolean.TRUE.equals(this.h.isMapping.invoke(client))
                && (this.h.isMappingSuspended == null || !(boolean) this.h.isMappingSuspended.invoke(client));
        if (!mapping) {
            if (holdStuck(now, holdSince)) {
                // Not a dimension or world switch any more (JourneyMap never started, an
                // import suspended it): stop holding the stream.
                inert("not_mapping", null);
                return;
            }
            this.inertReason = null;
            this.waitingReason = "not_mapping";
            this.observed = null;
            return;
        }
        if (this.observed == null || this.observed.level() != level
                || now - this.lastIdentityPollMillis >= IDENTITY_POLL_MILLIS) {
            this.lastIdentityPollMillis = now;
            Object dimension = this.host.dimension(level);
            Object day = this.h.mapTypeDay.invoke(dimension);
            if (!(boolean) this.h.isAllowed.invoke(day)) {
                inert("mapping_not_allowed", dimension);
                return;
            }
            this.inertReason = null;
            var identity = new Identity(this.h.getMultithreadTaskController.invoke(client), level, dimension,
                    (File) this.h.getJMWorldDir.invoke(this.host.minecraft()),
                    (String) this.h.getCurrentWorldId.invoke(client));
            if (!identity.equals(this.observed)) {
                onIdentityChange(this.observed, identity);
                this.observed = identity;
                this.identitySinceMillis = now;
            }
            Object delay = props == null ? null : this.h.integerFieldGet.invoke(this.h.renderDelay.invoke(props));
            this.renderDelayMillis = delay instanceof Integer d && d > 0 ? d : DEFAULT_RENDER_DELAY_MILLIS;
        }
        if (this.observed.worldDir() == null) {
            if (holdStuck(now, holdSince)) {
                inert("no_world_dir", null, false); // keep the identity: the poll stays at 1 Hz
                return;
            }
            this.waitingReason = "no_world_dir";
            return;
        }
        if (now - this.identitySinceMillis < SETTLE_MILLIS) {
            this.waitingReason = "settle";
            return;
        }
        this.waitingReason = null;
        boolean heapHalt = this.backpressureEnabled.getAsBoolean() && this.memory.halted();
        this.heapHolding = heapHalt;
        if (heapHalt) {
            this.heapHolds.incrementAndGet();
            return;
        }
        if (this.inFlight != null || now < this.nextSubmitAfterMillis) return;
        Batch batch = null;
        if (now - this.lastEdgeSweepMillis >= EDGE_SWEEP_MILLIS && this.sessionActive.getAsBoolean()) {
            long player = this.host.playerChunk();
            if (player != Long.MIN_VALUE) {
                this.lastEdgeSweepMillis = now;
                batch = new Batch(List.of(), this.observed, this.renderDelayMillis, true, player);
                this.edgeSweeps.incrementAndGet();
            }
        }
        if (batch == null) {
            batch = buildBatch(this.observed);
            if (batch == null) return;
            this.batches.incrementAndGet();
        }
        this.inFlight = batch;
        batch.submittedAtMillis = now;
        synchronized (client) {
            // Under the monitor stopMapping holds: a submit can never race the executor's
            // shutdown into resurrecting it. A null controller is a silent no-op — the
            // start watchdog reclaims the batch.
            this.h.queueOneOff.invoke(client, (Runnable) batch::run);
        }
    }

    /**
     * Enter (or stay in) an inert state. Queued columns of {@code dimension} can never be
     * written and are released silently; with a dimension-scoped reason, columns of OTHER
     * dimensions are reported for their re-serve instead — client stamps persist per
     * dimension, so a silent drop there would leave a permanent hole on the way back.
     */
    private void inert(String reason, Object dimension) {
        inert(reason, dimension, true);
    }

    private void inert(String reason, Object dimension, boolean forgetIdentity) {
        if (!reason.equals(this.inertReason)) {
            this.inertReason = reason;
            List<Entry> cleared;
            synchronized (this.queueLock) {
                cleared = new ArrayList<>(this.queue.values());
                this.queue.clear();
                this.queuedBytes = 0;
                updateOccupancyLocked();
            }
            for (var e : cleared) {
                if (dimension != null && !Objects.equals(e.dimension, dimension)) {
                    this.droppedStale.incrementAndGet();
                    this.pendingReports.add(e);
                } else {
                    e.receipt.close();
                }
            }
        }
        this.waitingReason = null;
        if (forgetIdentity) this.observed = null;
    }

    /** True once a not-mapping / no-world-directory hold has lasted {@link #STUCK_HOLD_MILLIS}
     *  inside an LSS session (outside one nothing is offered, so nothing is held).
     *  {@code holdSince} is the previous pump's clock, cleared by any pump that did not hold. */
    private boolean holdStuck(long now, long holdSince) {
        if (!this.sessionActive.getAsBoolean()) return false;
        this.holdSinceMillis = holdSince == 0 ? now : holdSince;
        return now - this.holdSinceMillis >= STUCK_HOLD_MILLIS;
    }

    /** Reports queued columns of any dimension but {@code dimension}: they can never be
     *  written here, and client stamps persist per dimension (a silent drop is a hole). */
    private void sweepForeignDimensions(Object dimension) {
        var foreign = new ArrayList<Entry>();
        synchronized (this.queueLock) {
            var it = this.queue.values().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (!Objects.equals(e.dimension, dimension)) {
                    it.remove();
                    this.queuedBytes -= e.bytes.length;
                    foreign.add(e);
                }
            }
            updateOccupancyLocked();
        }
        this.droppedStale.addAndGet(foreign.size());
        this.pendingReports.addAll(foreign);
    }

    /** A late world id (JourneyMap restarted mapping in a new directory): columns rendered
     *  into the abandoned directory in the last 30 s are re-requested for the new one. */
    void onIdentityChange(Identity previous, Identity next) {
        var batch = this.inFlight;
        if (batch != null && batch.state.compareAndSet(Batch.QUEUED, Batch.ABANDONED)) {
            this.abandoned.incrementAndGet();
            batch.entries.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
            this.inFlight = null;
        }
        if (next.worldDir() != null) {
            // JourneyMap restarts mapping in a new directory when the world id arrives late:
            // stopMapping at once, a new start up to a second later — the pump sees the gap
            // (observed = null), so the replay keys on the DIRECTORY the column went to, never
            // on the previous identity (review MAJOR).
            long now = now();
            var it = this.replay.iterator();
            while (it.hasNext()) {
                var r = it.next();
                if (r.worldDir().equals(next.worldDir())) continue;
                it.remove();
                if (now - r.atMillis() > REPLAY_WINDOW_MILLIS) continue;
                this.dropReporter.report(r.dimension(), r.chunkX(), r.chunkZ());
                this.replayed.incrementAndGet();
            }
        }
        synchronized (this.context) {
            this.context.clear();
            this.contextBytes = 0;
        }
        // Columns of another dimension can never be written here: report them for the return.
        if (previous == null || !Objects.equals(previous.dimension(), next.dimension())) {
            sweepForeignDimensions(next.dimension());
        }
    }

    /**
     * Up to {@link #pickFor the pick} columns from at most {@value #MAX_BATCH_REGIONS} regions: first
     * the region the last batch drew from last, while it still has columns, then whole regions
     * in the order of their OLDEST queued entry (no region starves), each region's columns in
     * queue order. The render orders the batch (z, x) across its regions, so neighbours across
     * a region seam shade each other.
     */
    Batch buildBatch(Identity identity) {
        long box = this.renderDelayMillis * BATCH_BOX_RENDER_DELAYS;
        int pick = pickFor(box);
        this.batchPick = pick;
        var picked = new ArrayList<Entry>();
        var foreign = new ArrayList<Entry>();
        var inactive = new ArrayList<Entry>(0);
        synchronized (this.queueLock) {
            if (this.queue.isEmpty()) return null;
            // Each region's live same-dimension columns in queue order, the regions in the
            // order of their oldest entry; dead and foreign entries leave on the way.
            var regions = new LinkedHashMap<Long, List<Entry>>();
            var it = this.queue.values().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (!e.receipt.active()) {
                    it.remove();
                    this.queuedBytes -= e.bytes.length;
                    inactive.add(e);
                    continue;
                }
                if (!Objects.equals(e.dimension, identity.dimension())) {
                    it.remove();
                    this.queuedBytes -= e.bytes.length;
                    foreign.add(e);
                    continue;
                }
                regions.computeIfAbsent(regionKeyOf(e.chunkX, e.chunkZ), k -> new ArrayList<>()).add(e);
            }
            var order = new ArrayList<Long>(MAX_BATCH_REGIONS);
            if (regions.containsKey(this.lastRegionKey)) order.add(this.lastRegionKey);
            for (long region : regions.keySet()) {
                if (order.size() >= MAX_BATCH_REGIONS) break;
                if (region != this.lastRegionKey) order.add(region);
            }
            long last = this.lastRegionKey;
            for (long region : order) {
                if (picked.size() >= pick) break;
                last = region;
                for (var e : regions.get(region)) {
                    if (picked.size() >= pick) break;
                    this.queue.remove(e.key());
                    this.queuedBytes -= e.bytes.length;
                    picked.add(e);
                }
            }
            updateOccupancyLocked();
            this.lastRegionKey = last;
        }
        inactive.forEach(e -> e.receipt.close());
        this.droppedStale.addAndGet(foreign.size());
        this.pendingReports.addAll(foreign);
        if (picked.isEmpty()) return null;
        var batch = new Batch(picked, identity, box);
        batch.pick = pick;
        return batch;
    }

    /** The column pick that fills {@value #BATCH_BOX_FILL} of the box at the measured cost
     *  per column, within [{@value #MIN_BATCH_PICK}, {@value #MAX_BATCH_PICK}]; the old fixed
     *  {@value #INITIAL_BATCH_PICK} until a batch has been measured. */
    int pickFor(long boxMillis) {
        double cost = this.costPerColumnMillis;
        if (Double.isNaN(cost)) return INITIAL_BATCH_PICK;
        double fit = cost <= 0 ? MAX_BATCH_PICK : boxMillis * BATCH_BOX_FILL / cost;
        return (int) Math.max(MIN_BATCH_PICK, Math.min(MAX_BATCH_PICK, fit));
    }

    /** Main thread: a finished batch's wall time per processed column (JourneyMap's thread, the
     *  box's own clock) feeds the pick's average. Abandoned, failed and refused batches carry no
     *  usable measurement, nor does a SMALL batch the box did not cut (below the clock's
     *  resolution) — but a batch that took its whole pick always does, so a pick at the floor
     *  still measures its way back up. A sample is capped at the box (a clock jump). */
    void noteBatchCost(Batch batch, Result r) {
        if (batch.edge || r == null || r.failure != null || r.abandoned || r.notAllowed || r.processed == 0) return;
        if (r.boxCut == 0 && r.processed < Math.min(COST_SAMPLE_MIN_COLUMNS, batch.pick)) return;
        double sample = Math.min(batch.timeBoxMillis, Math.max(0L, r.loopMillis) / (double) r.processed);
        double cost = this.costPerColumnMillis;
        this.costPerColumnMillis = Double.isNaN(cost) ? sample : cost + COST_EMA_ALPHA * (sample - cost);
    }

    void reconcileInFlight() {
        var batch = this.inFlight;
        if (batch == null) return;
        long now = now();
        int state = batch.state.get();
        if (state == Batch.QUEUED) {
            if (now - batch.submittedAtMillis > START_TIMEOUT_MILLIS
                    && batch.state.compareAndSet(Batch.QUEUED, Batch.ABANDONED)) {
                // Never started: JourneyMap's task controller was replaced or shut down.
                this.abandoned.incrementAndGet();
                batch.entries.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
                this.inFlight = null;
            }
            return;
        }
        if (state == Batch.STARTED) {
            if (!batch.slowCounted && batch.startedAtMillis != 0 && now - batch.startedAtMillis > SLOW_BATCH_MILLIS) {
                batch.slowCounted = true;
                this.slowBatches.incrementAndGet();
            }
            if (batch.startedAtMillis != 0 && now - batch.startedAtMillis > WEDGED_BATCH_MILLIS && !this.dead) {
                // JourneyMap's task thread is stuck inside our batch: no batch can follow, so a
                // live session would hold the stream and churn re-serves forever (review). The
                // slot stays occupied — the thread may still finish and write its result.
                this.dead = true;
                clearQueue();
                if (!this.wedgeWarned) {
                    this.wedgeWarned = true;
                    LSSLogger.error("JourneyMap map ingestion: a map batch has run for "
                            + (now - batch.startedAtMillis) / 1000 + " s — JourneyMap's task thread looks"
                            + " stuck; disabling map ingestion for this session (LODs are unaffected)");
                }
            }
            return;
        }
        if (state != Batch.DONE) return;
        this.inFlight = null;
        applyResult(batch, batch.result);
        noteBatchCost(batch, batch.result);
        long wall = Math.max(0, batch.finishedAtMillis - batch.startedAtMillis);
        boolean idle = batch.edge && (batch.result == null || batch.result.edgeRendered == 0);
        if (!batch.edge) this.lastBatchMillis = wall;
        // Half a renderDelay between batches (JourneyMap's queued tasks run first) — and a
        // sweep that drew nothing costs none.
        this.nextSubmitAfterMillis = idle ? now : now + this.renderDelayMillis / 2;
    }

    void applyResult(Batch batch, Result r) {
        if (batch.epoch != this.sessionEpoch) {
            // A batch that outlived its session: its receipts are dead, and its renders went
            // to the old session's map — never replay them into the next server's (review).
            batch.entries.forEach(e -> e.receipt.close());
            return;
        }
        if (r != null) {
            this.optionalRefusals.addAndGet(r.optionalRefusals);
            this.edgeRendered.addAndGet(r.edgeRendered);
        }
        if (batch.edge) {
            // No receipts ride an edge sweep: only a throw counts, toward the death latch.
            if (r == null || r.failure != null) noteBatchFailure(r == null ? null : r.failure);
            return;
        }
        if (r == null || r.failure != null || r.abandoned) {
            if (r != null && r.abandoned) this.abandoned.incrementAndGet();
            // A throw mid-batch or an identity change: nothing is known-good — re-serve all.
            batch.entries.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
            if (r == null || r.failure != null) noteBatchFailure(r == null ? null : r.failure);
            return;
        }
        if (r.notAllowed) {
            // Not allowed here (anymore): these columns cannot be written. The next pump
            // re-polls at once and enters the inert state, which also clears the queue.
            batch.entries.forEach(e -> e.receipt.close());
            this.observed = null;
            return;
        }
        long now = now();
        // Only renders inside the window can replay: older ones leave first, so the ring holds
        // the window's renders at the real rate and the cap only backstops it.
        while (!this.replay.isEmpty() && now - this.replay.peekFirst().atMillis() > REPLAY_WINDOW_MILLIS) {
            this.replay.removeFirst();
        }
        for (var e : r.rendered) {
            e.receipt.markWritten();
            e.receipt.close();
            if (this.replay.size() >= REPLAY_CAPACITY) this.replay.removeFirst();
            this.replay.addLast(new Rendered(e.dimension, e.chunkX, e.chunkZ, batch.identity.worldDir(), now));
        }
        this.rendered.addAndGet(r.rendered.size());
        r.skippedLive.forEach(e -> { // JourneyMap renders live chunks itself: the map has them
            e.receipt.markWritten();
            e.receipt.close();
        });
        this.skippedLive.addAndGet(r.skippedLive.size());
        r.renderFailed.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
        this.renderFailed.addAndGet(r.renderFailed.size());
        r.columnFailures.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
        this.columnFailures.addAndGet(r.columnFailures.size());
        // A decode failure is a format mismatch: a re-serve would fail identically.
        r.decodeFailures.forEach(e -> e.receipt.close());
        this.decodeFailures.addAndGet(r.decodeFailures.size());
        this.renderNanos.addAndGet(r.renderNanos);
        this.decodeNanos.addAndGet(r.decodeNanos);
        this.decodedColumns.addAndGet(r.decoded);
        this.decodedAhead.addAndGet(r.decodedAhead);
        requeue(r.requeued);
        // A batch that rendered nothing while columns failed to decode or render counts
        // toward the death latch: a renderer refusing wholesale would otherwise re-serve
        // the whole disc forever (review).
        if (r.rendered.isEmpty() && (!r.decodeFailures.isEmpty() || !r.renderFailed.isEmpty())) {
            noteBatchFailure(null, r.decodeFailures.size() + r.renderFailed.size());
        } else if (!r.rendered.isEmpty()) {
            this.consecutiveFailedBatches = 0;
            this.consecutiveFailedColumns = 0;
        }
    }

    /** Time-boxed remainder: back into the queue unless a newer delivery replaced it, within
     *  the queue's bounds (an entry that no longer fits is an overflow drop). */
    void requeue(List<Entry> entries) {
        if (entries.isEmpty()) return;
        this.requeued.addAndGet(entries.size());
        var stale = new ArrayList<Entry>(0);
        var overflow = new ArrayList<Entry>(0);
        synchronized (this.queueLock) {
            for (var e : entries) {
                if (!e.receipt.active()) {
                    e.receipt.close();
                    continue;
                }
                var newer = this.queue.get(e.key());
                if (newer != null) {
                    if (Objects.equals(newer.dimension, e.dimension)) {
                        e.receipt.close(); // a newer delivery of the same column replaced it
                    } else {
                        stale.add(e); // the position, but another dimension: report (the offer rule)
                    }
                    continue;
                }
                if (this.queue.size() >= this.maxQueue || this.queuedBytes + e.bytes.length > this.maxQueueBytes) {
                    overflow.add(e);
                    continue;
                }
                this.queue.put(e.key(), e);
                this.queuedBytes += e.bytes.length;
            }
            updateOccupancyLocked();
        }
        this.droppedStale.addAndGet(stale.size());
        this.pendingReports.addAll(stale);
        for (var e : overflow) {
            this.droppedOverflow.incrementAndGet();
            if (this.backpressureEnabled.getAsBoolean() && !this.governor.wedged) this.pendingReports.add(e);
            else e.receipt.close();
        }
    }

    /** A thrown batch: systemic, so it counts as a full minimum of failed columns. */
    void noteBatchFailure(Throwable failure) {
        noteBatchFailure(failure, DEATH_LATCH_MIN_COLUMNS);
    }

    void noteBatchFailure(Throwable failure, int failedColumns) {
        if (failure != null) warn("a map batch failed", failure);
        this.consecutiveFailedColumns += failedColumns;
        if (++this.consecutiveFailedBatches >= DEATH_LATCH
                && this.consecutiveFailedColumns >= DEATH_LATCH_MIN_COLUMNS && !this.dead) {
            this.dead = true;
            clearQueue();
            LSSLogger.error("JourneyMap map ingestion: " + DEATH_LATCH + " consecutive failed map batches —"
                    + " disabling it for this session (LODs are unaffected)", failure);
        }
    }

    void drainPendingReports() {
        Entry e;
        while ((e = this.pendingReports.poll()) != null) e.receipt.report(e.dimension, e.chunkX, e.chunkZ);
    }

    // ---- the batch (JourneyMap's task thread) ----

    static final class Result {
        final List<Entry> rendered = new ArrayList<>();
        final List<Entry> skippedLive = new ArrayList<>();
        final List<Entry> renderFailed = new ArrayList<>();
        final List<Entry> decodeFailures = new ArrayList<>();
        final List<Entry> columnFailures = new ArrayList<>();
        final List<Entry> requeued = new ArrayList<>();
        boolean abandoned;
        boolean notAllowed;
        Throwable failure;
        long renderNanos;
        long optionalRefusals;
        long edgeRendered;
        long decodeNanos;
        long decoded;
        long decodedAhead;
        /** Columns that passed the box check (whatever their outcome), and those it cut. */
        int processed;
        int boxCut;
        /** Batch start to the end of the column loop, on the box's clock. */
        long loopMillis;
    }

    final class Batch {
        static final int QUEUED = 0, STARTED = 1, DONE = 2, ABANDONED = 3;
        final List<Entry> entries;
        final Identity identity;
        final long timeBoxMillis;
        /** An edge sweep (no entries): renders JourneyMap's own live chunks past its range. */
        final boolean edge;
        final long playerChunk;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        /** The session the batch was built in (main thread). */
        final long epoch = sessionEpoch;
        long submittedAtMillis; // main thread
        /** The column pick it was built with (main thread; 0 for an edge sweep). */
        int pick;
        volatile long startedAtMillis;
        volatile long finishedAtMillis;
        volatile Result result;
        boolean slowCounted; // main thread

        Batch(List<Entry> entries, Identity identity, long timeBoxMillis) {
            this(entries, identity, timeBoxMillis, false, 0);
        }

        Batch(List<Entry> entries, Identity identity, long timeBoxMillis, boolean edge, long playerChunk) {
            this.entries = entries;
            this.identity = identity;
            this.timeBoxMillis = timeBoxMillis;
            this.edge = edge;
            this.playerChunk = playerChunk;
        }

        /** JourneyMap's task thread: always ends in DONE with a result (a lost slot wedges). */
        void run() {
            if (!this.state.compareAndSet(QUEUED, STARTED)) return;
            this.startedAtMillis = now();
            var result = new Result();
            Error fatal = null;
            try {
                render(this, result);
            } catch (Throwable t) {
                result.failure = t;
                if (t instanceof Error err && !(t instanceof AssertionError)) fatal = err;
            } finally {
                this.finishedAtMillis = now();
                this.result = result;
                this.state.set(DONE);
            }
            if (fatal != null) throw fatal;
        }
    }

    boolean identityStillMatches(Identity identity) throws Throwable {
        Object client = this.h.getInstance.invoke();
        if (client == null || !Boolean.TRUE.equals(this.h.isMapping.invoke(client))) return false;
        if (this.h.getMultithreadTaskController.invoke(client) != identity.controller()) return false;
        Object level = this.host.level();
        if (level != identity.level() || !Objects.equals(this.host.dimension(level), identity.dimension())) return false;
        Object dir = this.h.getJMWorldDir.invoke(this.host.minecraft());
        return dir != null && dir.equals(identity.worldDir());
    }

    /** The map types to render, day first; null when the day map is not allowed here. */
    private List<Object> mapTypes(Object client, Object dimension) throws Throwable {
        Object day = this.h.mapTypeDay.invoke(dimension);
        if (!(boolean) this.h.isAllowed.invoke(day)) return null;
        Object props = this.h.getCoreProperties.invoke(client); // replaced on every startMapping
        var types = new ArrayList<Object>(3);
        types.add(day); // the surface renderer draws day and night together
        if (props != null && Boolean.TRUE.equals(this.h.booleanFieldGet.invoke(this.h.mapTopography.invoke(props)))) {
            Object topo = this.h.mapTypeTopo.invoke(dimension);
            if ((boolean) this.h.isAllowed.invoke(topo)) types.add(topo);
        }
        if (props != null && Boolean.TRUE.equals(this.h.booleanFieldGet.invoke(this.h.mapBiome.invoke(props)))) {
            Object biome = this.h.mapTypeBiome.invoke(dimension);
            if ((boolean) this.h.isAllowed.invoke(biome)) types.add(biome);
        }
        return types;
    }

    /**
     * Renders one chunk into every map type. The DAY map decides: it is what the column is
     * accepted or re-served on. Topography and biome are best effort, exactly as in
     * JourneyMap's own pipeline, which ignores their results — its topography renderer
     * refuses every chunk until something loads its palettes (live smoke), so requiring them
     * turned every column into a re-serve.
     */
    private boolean renderTypes(Object controller, Identity id, List<Object> types, int chunkX, int chunkZ,
                                Object md, Result r) throws Throwable {
        Object day = types.getFirst();
        if (!(boolean) this.h.renderChunk.invoke(controller,
                this.h.fromChunkPos.invoke(id.worldDir(), day, chunkX, chunkZ), day, md)) return false;
        for (int t = 1; t < types.size(); t++) {
            Object type = types.get(t);
            if (!(boolean) this.h.renderChunk.invoke(controller,
                    this.h.fromChunkPos.invoke(id.worldDir(), type, chunkX, chunkZ), type, md)) r.optionalRefusals++;
        }
        return true;
    }

    void render(Batch batch, Result r) throws Throwable {
        Identity id = batch.identity;
        if (!this.serverAllowsWrites.getAsBoolean()) {
            // Map-policy plan A4: the deny landed after the batch was queued and the task
            // thread won the race to start it — write nothing; the receipts close unreported.
            r.notAllowed = true;
            return;
        }
        if (!identityStillMatches(id)) {
            r.abandoned = true;
            return;
        }
        Object client = this.h.getInstance.invoke();
        Object controller = this.h.getChunkRenderController.invoke(client);
        var types = mapTypes(client, id.dimension());
        if (types == null) {
            r.notAllowed = true;
            return;
        }
        if (batch.edge) {
            renderEdge(batch, r, client, controller, types);
            return;
        }
        Object dataCache = this.h.dataCache.invoke();
        Object allowCached = this.h.allowCached.invoke();
        long deadline = batch.startedAtMillis + batch.timeBoxMillis;
        // One column at a time — add, render — under ONE time box: a decode-everything-first
        // batch spent the whole box decoding and requeued the rest, forever (the throughput
        // live gate). North and west first, and each column's east and south batch neighbours
        // prepared one step ahead: their wrappers stay in JourneyMap's cache for the batch, so
        // day slopes (north and west), topography's slope and shore checks (south and east) and
        // biome blending (all around) see them. A look-ahead the box cuts off is released with
        // the rest. The pure snapshot decodes run on the decode-ahead workers a window ahead of
        // that order (DecodeAhead); a column no worker has started decodes inline here.
        var ordered = new ArrayList<>(batch.entries);
        ordered.sort(java.util.Comparator.comparingInt((Entry e) -> e.chunkZ).thenComparingInt(e -> e.chunkX));
        var batchKeys = new java.util.HashSet<Long>();
        var byKey = new java.util.HashMap<Long, Entry>();
        for (var e : ordered) {
            batchKeys.add(e.key());
            byKey.put(e.key(), e);
        }
        var present = new java.util.HashSet<Long>();
        var ours = new ArrayList<Object>();
        var oursKeys = new ArrayList<Long>();
        var prepared = new java.util.HashMap<Long, Object>();
        DecodeAhead ahead = null;
        boolean stopped = false;
        try {
            ahead = planDecodeAhead(id, ordered, byKey, batchKeys);
            if (ahead != null) ahead.topUp(-1);
            var work = new Prepare(id, dataCache, batchKeys, present, ours, oursKeys, prepared, r, ahead);
            for (var e : ordered) {
                if (!stopped && stopRequested(client, id)) stopped = true;
                if (stopped || !this.sessionActive.getAsBoolean()) {
                    r.requeued.add(e); // a finished session's receipts close
                    continue;
                }
                if (now() > deadline) {
                    // JourneyMap's own pacing: about one renderDelay of work per batch, then the
                    // pump idles it.
                    r.requeued.add(e);
                    r.boxCut++;
                    continue;
                }
                r.processed++;
                long key = mcChunkKey(e.chunkX, e.chunkZ);
                try {
                    Object md = prepare(e, work);
                    if (md == PREPARED_LIVE) {
                        r.skippedLive.add(e); // JourneyMap maps its live chunks itself
                        continue;
                    }
                    if (md == PREPARED_FAILED) {
                        r.decodeFailures.add(e);
                        continue;
                    }
                    for (int[] d : AHEAD) {
                        var next = byKey.get(packed(e.chunkX + d[0], e.chunkZ + d[1]));
                        if (next == null) continue;
                        try {
                            prepare(next, work);
                        } catch (Throwable t) {
                            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                            // its own turn retries the decode and classifies the failure
                        }
                    }
                    long t1 = System.nanoTime();
                    Object current = this.h.getChunkMDWithIntent.invoke(dataCache, key, allowCached);
                    if (current != md) {
                        // A live chunk replaced ours (JourneyMap owns it), or JourneyMap's cache
                        // dropped our wrapper already (an invalidation, its size cap): requeue
                        // — a null here is never "live" (review MAJOR) — a bounded number of
                        // times, then report it for a re-serve (re-review).
                        if (current != null) r.skippedLive.add(e);
                        else (++e.nullLookups >= NULL_LOOKUP_LIMIT ? r.columnFailures : r.requeued).add(e);
                        continue;
                    }
                    boolean ok = renderTypes(controller, id, types, e.chunkX, e.chunkZ, md, r);
                    this.h.clearAll.invoke(this.h.getBlockData.invoke(md));
                    r.renderNanos += System.nanoTime() - t1;
                    (ok ? r.rendered : r.renderFailed).add(e);
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                    r.columnFailures.add(e);
                }
            }
            r.loopMillis = now() - batch.startedAtMillis;
        } finally {
            // Unstarted decode-ahead work is cancelled and nothing of it outlives the batch.
            if (ahead != null) ahead.close();
            for (int i = 0; i < ours.size(); i++) release(dataCache, allowCached, oursKeys.get(i), ours.get(i));
        }
        if (stopped) {
            // JourneyMap is stopping this mapping (its stopMapping blocks the main thread on this
            // task): hand the thread back at once and leave its renderer to the next mapping —
            // every column re-serves into it.
            r.abandoned = true;
            return;
        }
        this.h.updateTextures.invoke(this.h.regionImageCache.invoke(), false, true);
        if (!identityStillMatches(id)) {
            // Rendered under an identity that ended mid-batch (a dimension switch, a late
            // world id): those pixels may have gone to the wrong map — re-serve them all.
            r.abandoned = true;
            return;
        }
        synchronized (this.context) {
            for (var e : r.rendered) {
                var previous = this.context.put(e.key(), new ContextColumn(id.dimension(), id.worldDir(), e.bytes));
                this.contextBytes += e.bytes.length - (previous == null ? 0 : previous.bytes().length);
                var it = this.context.values().iterator();
                while ((this.context.size() > CONTEXT_CAPACITY || this.contextBytes > CONTEXT_MAX_BYTES) && it.hasNext()) {
                    this.contextBytes -= it.next().bytes().length;
                    it.remove();
                }
            }
        }
    }

    /** JourneyMap is shutting this mapping down (it interrupts its task thread from
     *  {@code stopMapping}, then waits up to 5 s on the main thread) or already replaced its
     *  task controller: a batch must stop at the next column (review). */
    private boolean stopRequested(Object client, Identity id) throws Throwable {
        return Thread.currentThread().isInterrupted()
                || this.h.getMultithreadTaskController.invoke(client) != id.controller();
    }

    /** East, south-west, south and south-east: the batch neighbours a column's render reads
     *  that the (z, x) order has not reached yet. */
    private static final int[][] AHEAD = {{1, 0}, {-1, 1}, {0, 1}, {1, 1}};
    /** North, north-west and west: the context neighbours a column borrows from recent renders. */
    private static final int[][] CONTEXT_NEIGHBOURS = {{0, -1}, {-1, -1}, {-1, 0}};
    private static final Object PREPARED_LIVE = new Object();
    private static final Object PREPARED_FAILED = new Object();

    /** One batch's decode state (JourneyMap's task thread). {@code ahead} is null without
     *  decode-ahead. */
    private record Prepare(Identity id, Object dataCache, java.util.Set<Long> batchKeys, java.util.Set<Long> present,
                           List<Object> ours, List<Long> oursKeys, java.util.Map<Long, Object> prepared, Result r,
                           DecodeAhead ahead) {}

    /** Decodes and adds one batch column (with its context) once: its wrapper, or
     *  {@link #PREPARED_LIVE} when JourneyMap holds a live chunk there, or
     *  {@link #PREPARED_FAILED} when JourneyMap refused the bytes. */
    private Object prepare(Entry e, Prepare w) throws Throwable {
        Object done = w.prepared().get(e.key());
        if (done != null) return done;
        long key = mcChunkKey(e.chunkX, e.chunkZ);
        Object live = this.h.getChunkMD.invoke(w.dataCache(), key);
        if (live != null && (boolean) this.h.hasLevelChunk.invoke(live)) {
            if (w.ahead() != null) w.ahead().drop(e.key()); // JourneyMap maps it: no decode needed
            w.prepared().put(e.key(), PREPARED_LIVE);
            return PREPARED_LIVE;
        }
        // The time JourneyMap's thread spends getting the decoded column: context, waiting for
        // (or doing) the decode, and the wrap (decode_us_per_column).
        long t0 = System.nanoTime();
        addContext(e, w);
        Object md = decodeAndWrap(e.key(), e.bytes, w, true);
        w.r().decodeNanos += System.nanoTime() - t0;
        w.r().decoded++;
        if (md == null) {
            w.prepared().put(e.key(), PREPARED_FAILED);
            return PREPARED_FAILED;
        }
        this.h.addChunkMD.invoke(w.dataCache(), md);
        w.ours().add(md);
        w.oursKeys().add(key);
        w.present().add(e.key());
        w.prepared().put(e.key(), md);
        return md;
    }

    /** Decodes and adds the column's north, north-west and west neighbours from recent renders
     *  (context only, never rendered) unless the batch, a live chunk, or an earlier context
     *  wrapper already covers them. */
    private void addContext(Entry e, Prepare w) {
        Identity id = w.id();
        for (int[] d : CONTEXT_NEIGHBOURS) {
            int x = e.chunkX + d[0], z = e.chunkZ + d[1];
            long key = packed(x, z);
            if (w.batchKeys().contains(key) || !w.present().add(key)) continue;
            ContextColumn column;
            synchronized (this.context) {
                column = this.context.get(key);
            }
            if (column == null || !Objects.equals(column.dimension(), id.dimension())
                    || !Objects.equals(column.worldDir(), id.worldDir())) {
                if (w.ahead() != null) w.ahead().drop(key);
                continue;
            }
            try {
                long mcKey = mcChunkKey(x, z);
                Object live = this.h.getChunkMD.invoke(this.h.dataCache.invoke(), mcKey);
                if (live != null && (boolean) this.h.hasLevelChunk.invoke(live)) {
                    if (w.ahead() != null) w.ahead().drop(key);
                    continue;
                }
                Object md = decodeAndWrap(key, column.bytes(), w, false);
                if (md == null) continue;
                this.h.addChunkMD.invoke(w.dataCache(), md);
                w.ours().add(md);
                w.oursKeys().add(mcKey);
            } catch (Throwable t) {
                if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            }
        }
    }

    /**
     * JourneyMap's thread: the column's {@code SnapshotChunkMD}, or null when JourneyMap refused
     * the bytes. The snapshot comes from a decode-ahead worker when one started it (waiting for
     * it, about a millisecond at most), else — no decode-ahead, a job no worker has started (it
     * is cancelled), a rejected job, or bytes that changed since the plan — it is decoded inline,
     * exactly as before. A worker's decode that THREW is rethrown here: the same outcome as the
     * same throw inline. The wrap always runs here.
     */
    private Object decodeAndWrap(long key, byte[] bytes, Prepare w, boolean batchColumn) throws Throwable {
        Object level = w.id().level();
        Object snapshot = w.ahead() == null ? NOT_AHEAD : w.ahead().take(key, bytes);
        if (snapshot == NOT_AHEAD) {
            snapshot = this.decoder.decode(bytes, level);
        } else if (batchColumn) {
            w.r().decodedAhead++;
        }
        return snapshot == null ? null : this.wrapper.wrap(level, snapshot);
    }

    // ---- decode-ahead (map-ingestion plan §11; the purity evidence is in the bridge record) ----

    /** {@link DecodeAhead#take}'s "decode it inline" answer (a snapshot may itself be null). */
    private static final Object NOT_AHEAD = new Object();

    static boolean decodeAheadProperty() {
        return Boolean.parseBoolean(System.getProperty(DECODE_AHEAD_PROPERTY, "true"));
    }

    private static java.util.concurrent.ThreadPoolExecutor decodeAheadPool; // guarded by the class
    private static final AtomicInteger DECODE_AHEAD_THREAD_IDS = new AtomicInteger();

    static void executeDecodeAhead(Runnable job) {
        decodeAheadPool().execute(job);
    }

    /** The shared pool: {@value #DECODE_AHEAD_THREADS} daemon threads, created on first use;
     *  idle threads exit after {@value #DECODE_AHEAD_KEEP_ALIVE_SECONDS} s. A full queue
     *  rejects (the column then decodes inline). */
    static synchronized java.util.concurrent.ThreadPoolExecutor decodeAheadPool() {
        if (decodeAheadPool == null) {
            var pool = new java.util.concurrent.ThreadPoolExecutor(DECODE_AHEAD_THREADS, DECODE_AHEAD_THREADS,
                    DECODE_AHEAD_KEEP_ALIVE_SECONDS, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(DECODE_AHEAD_QUEUE), runnable -> {
                        // Created on JourneyMap's thread: inherit none of its thread locals.
                        var t = new Thread(null, runnable, dev.vox.lss.common.Brand.shortName() + "-JM-DecodeAhead-"
                                + DECODE_AHEAD_THREAD_IDS.incrementAndGet(), 0, false);
                        t.setDaemon(true);
                        t.setContextClassLoader(JourneyMapSession.class.getClassLoader());
                        return t;
                    });
            pool.allowCoreThreadTimeOut(true);
            decodeAheadPool = pool;
        }
        return decodeAheadPool;
    }

    /**
     * Plans the batch's pure decodes (JourneyMap's thread, batch start) in the order the column
     * loop will need them — each column's context neighbours, the column, then its look-ahead
     * neighbours; null without decode-ahead. The caller hands the first window to the workers.
     * JourneyMap's snapshot class is initialized HERE first, on JourneyMap's own thread, as when
     * every decode ran on it: its static initializer never runs on an LSS worker.
     */
    private DecodeAhead planDecodeAhead(Identity id, List<Entry> ordered, java.util.Map<Long, Entry> byKey,
                                        java.util.Set<Long> batchKeys) {
        if (!this.decodeAhead || ordered.isEmpty()) return null;
        if (!this.snapshotClassReady) {
            try {
                Class<?> snapshot = this.h.decode.type().returnType();
                Class.forName(snapshot.getName(), true, snapshot.getClassLoader());
            } catch (LinkageError | ClassNotFoundException e) {
                return null; // the inline decode surfaces it exactly as before
            }
            this.snapshotClassReady = true;
        }
        var plan = new ArrayList<AheadJob>();
        var planned = new java.util.HashSet<Long>();
        Object level = id.level();
        for (var e : ordered) {
            planColumn(e, id, level, batchKeys, planned, plan);
            for (int[] d : AHEAD) {
                var next = byKey.get(packed(e.chunkX + d[0], e.chunkZ + d[1]));
                if (next != null) planColumn(next, id, level, batchKeys, planned, plan);
            }
        }
        return new DecodeAhead(plan, this.decodeAheadExecutor);
    }

    private void planColumn(Entry e, Identity id, Object level, java.util.Set<Long> batchKeys,
                            java.util.Set<Long> planned, List<AheadJob> plan) {
        if (!planned.add(e.key())) return;
        for (int[] d : CONTEXT_NEIGHBOURS) {
            long key = packed(e.chunkX + d[0], e.chunkZ + d[1]);
            if (batchKeys.contains(key) || planned.contains(key)) continue;
            ContextColumn column;
            synchronized (this.context) {
                column = this.context.get(key);
            }
            if (column == null || !Objects.equals(column.dimension(), id.dimension())
                    || !Objects.equals(column.worldDir(), id.worldDir())) continue;
            planned.add(key);
            plan.add(new AheadJob(plan.size(), key, column.bytes(), level, this.decoder));
        }
        plan.add(new AheadJob(plan.size(), e.key(), e.bytes, level, this.decoder));
    }

    /**
     * One batch's decode-ahead (JourneyMap's task thread only; a local of {@link #render}, so
     * it never outlives the batch). The workers run at most {@value #DECODE_AHEAD_WINDOW} jobs
     * past the one JourneyMap's thread needs now; {@link #close} cancels every job no worker
     * has started and drops them all.
     */
    final class DecodeAhead {
        private final List<AheadJob> plan;
        private final java.util.HashMap<Long, AheadJob> pending = new java.util.HashMap<>();
        private final java.util.concurrent.Executor executor;
        private int submitted;
        private boolean rejected;

        DecodeAhead(List<AheadJob> plan, java.util.concurrent.Executor executor) {
            this.plan = plan;
            this.executor = executor;
            for (var job : plan) this.pending.put(job.key, job);
        }

        /** Hands the jobs up to a window past plan index {@code index} to the workers. */
        void topUp(int index) {
            int limit = Math.min(this.plan.size(), index + 1 + DECODE_AHEAD_WINDOW);
            while (this.submitted < limit && !this.rejected) {
                try {
                    this.executor.execute(this.plan.get(this.submitted));
                    this.submitted++;
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    this.rejected = true; // this batch's remaining columns decode inline
                }
            }
        }

        /** The snapshot decoded ahead for {@code key} (null = JourneyMap refused the bytes), or
         *  {@link #NOT_AHEAD} when JourneyMap's thread must decode inline. A worker's throw is
         *  rethrown. Each job is taken at most once: a retry decodes inline. */
        Object take(long key, byte[] bytes) throws Throwable {
            var job = this.pending.remove(key);
            if (job == null) return NOT_AHEAD;
            topUp(job.index);
            if (job.source != bytes) { // a context column replaced since the plan
                job.claim();
                return NOT_AHEAD;
            }
            if (job.claim()) return NOT_AHEAD; // no worker started it: cancelled, decoded inline
            try {
                // A started decode takes about a millisecond; the bound is armour only.
                if (!job.finished.await(decodeAheadWaitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return NOT_AHEAD;
                }
            } catch (InterruptedException interrupted) {
                // JourneyMap is stopping this mapping: keep the flag (the loop stops at the next
                // column) and finish this column without waiting.
                Thread.currentThread().interrupt();
                return NOT_AHEAD;
            }
            if (job.failure != null) throw job.failure;
            return job.snapshot;
        }

        /** A planned decode the batch no longer needs (a live chunk, no context column). */
        void drop(long key) {
            var job = this.pending.remove(key);
            if (job != null) job.claim();
        }

        /** Batch end: every unstarted job is cancelled (it pins nothing in the pool's queue),
         *  and the batch drops them all. A job a worker is running finishes alone. */
        void close() {
            for (var job : this.pending.values()) job.claim();
            this.pending.clear();
        }
    }

    /**
     * One snapshot decode for the workers. Exactly one side wins it: a worker that STARTS it
     * (JourneyMap's thread then waits for the result), or JourneyMap's thread CLAIMING it
     * unstarted (a cancel: the thread decodes inline, the worker skips it). The winner drops
     * the bytes and the level, so a job left in the pool's queue never pins the level.
     */
    final class AheadJob implements Runnable {
        static final int QUEUED = 0, RUNNING = 1, DONE = 2, CLAIMED = 3;
        final int index;
        final long key;
        /** The bytes planned, for an identity re-check only. */
        final byte[] source;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        private final SnapshotDecoder snapshots;
        private byte[] bytes;
        private Object level;
        private Object snapshot; // published by finished
        private Throwable failure; // published by finished

        AheadJob(int index, long key, byte[] bytes, Object level, SnapshotDecoder snapshots) {
            this.index = index;
            this.key = key;
            this.source = bytes;
            this.bytes = bytes;
            this.level = level;
            this.snapshots = snapshots;
        }

        @Override
        public void run() {
            if (!this.state.compareAndSet(QUEUED, RUNNING)) return; // claimed: cancelled
            byte[] in = this.bytes;
            Object at = this.level;
            this.bytes = null;
            this.level = null;
            long t0 = System.nanoTime();
            try {
                this.snapshot = this.snapshots.decode(in, at);
            } catch (Throwable t) {
                this.failure = t; // rethrown on JourneyMap's thread, as an inline throw would be
            } finally {
                predecodeNanos.addAndGet(System.nanoTime() - t0);
                predecodedColumns.incrementAndGet();
                this.state.set(DONE);
                this.finished.countDown();
            }
        }

        /** JourneyMap's thread: true when it took the job unstarted (it then decodes inline). */
        boolean claim() {
            if (!this.state.compareAndSet(QUEUED, CLAIMED)) return false;
            this.bytes = null;
            this.level = null;
            return true;
        }

        boolean holdsLevelForTest() {
            return this.level != null;
        }
    }

    /** Releases one of our wrappers: drops its strong chunk reference (so a wrapper JourneyMap
     *  could not hand back — no level after a disconnect — never pins the old level), then
     *  removes it unless a live chunk replaced it. */
    private void release(Object dataCache, Object allowCached, long mcKey, Object md) {
        try {
            this.h.stopChunkRetention.invoke(md);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
        }
        removeIfOurs(dataCache, allowCached, mcKey, md);
    }

    /**
     * The edge sweep: JourneyMap maps only chunks within its own render range (about the
     * client's render distance minus one), while vanilla sends — and LSS therefore leaves to
     * vanilla — a ring a few chunks wider. A stationary player would see a permanent unmapped
     * ring between JourneyMap's area and the LOD disc (live smoke). Every
     * {@value #EDGE_SWEEP_MILLIS} ms, while an LSS session is active, this renders
     * JourneyMap's OWN live chunks in the {@value #EDGE_BAND_CHUNKS}-chunk band past its range
     * — its data, its renderer, its thread — each client chunk instance once, at most
     * {@value #EDGE_LOOKUPS_PER_SWEEP} new ones per sweep.
     */
    void renderEdge(Batch batch, Result r, Object client, Object controller, List<Object> types) throws Throwable {
        Identity id = batch.identity;
        if (this.edgeKey == null || !this.edgeKey.matches(id)) {
            this.edgeDone.clear();
            this.edgeKey = EdgeKey.of(id);
        }
        // JourneyMap's own surface range: the game render distance, capped by its surface
        // maximum (0 = uncapped). The band reaches past the VANILLA area, whatever JourneyMap's
        // cap: that is the ring LSS leaves to vanilla.
        int game = (int) this.h.getGameRenderDistance.invoke(client);
        Object props = this.h.getCoreProperties.invoke(client);
        Object cap = props == null ? null : this.h.integerFieldGet.invoke(this.h.renderDistanceSurfaceMax.invoke(props));
        int range = cap instanceof Integer c && c > 0 ? Math.min(game, c) : game;
        int px = (int) (batch.playerChunk >> 32);
        int pz = (int) batch.playerChunk;
        int reach = game + EDGE_BAND_CHUNKS;
        double inside = range + 0.5; // JourneyMap's own circle test (RenderSpec.inRange)
        Object dataCache = this.h.dataCache.invoke();
        long deadline = batch.startedAtMillis + batch.timeBoxMillis;
        var keep = new java.util.HashMap<Long, java.lang.ref.WeakReference<Object>>();
        int lookups = 0;
        boolean stopped = false;
        sweep:
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                if (Math.sqrt(dx * dx + dz * dz) <= inside) continue;
                int x = px + dx, z = pz + dz;
                long key = packed(x, z);
                // The client's own chunk decides "already drawn": a done chunk costs no
                // JourneyMap cache lookup, so a wide band (a low surface cap) neither re-renders
                // nor flushes JourneyMap's cache every sweep (re-review).
                Object chunk = this.host.liveChunk(id.level(), x, z);
                if (chunk == null) continue;
                var done = this.edgeDone.get(key);
                if (done != null && done.get() == chunk) {
                    keep.put(key, done);
                    continue;
                }
                if (now() > deadline || lookups >= EDGE_LOOKUPS_PER_SWEEP) continue; // a later sweep
                if (stopRequested(client, id)) {
                    stopped = true;
                    break sweep;
                }
                lookups++;
                Object live = this.h.getChunkMD.invoke(dataCache, mcChunkKey(x, z));
                if (live == null || !(boolean) this.h.hasLevelChunk.invoke(live)) continue;
                // During a dimension switch JourneyMap can still be mapping the OLD dimension
                // while its cache already serves the new one: never paint across (review).
                if (!Objects.equals(this.h.getDimension.invoke(live), id.dimension())) continue;
                try {
                    boolean ok = renderTypes(controller, id, types, x, z, live, r);
                    this.h.clearAll.invoke(this.h.getBlockData.invoke(live));
                    if (ok) {
                        keep.put(key, new java.lang.ref.WeakReference<>(chunk));
                        r.edgeRendered++;
                    }
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                    // a vanilla chunk JourneyMap cannot render now; the next sweep retries
                }
            }
        }
        this.edgeDone.clear();
        this.edgeDone.putAll(keep);
        if (stopped) {
            this.edgeDone.clear(); // redo under the next mapping
            r.abandoned = true;
            return;
        }
        if (r.edgeRendered > 0) this.h.updateTextures.invoke(this.h.regionImageCache.invoke(), false, true);
        if (!identityStillMatches(id)) {
            this.edgeDone.clear(); // rendered into a map that just ended: redo under the new one
            r.abandoned = true;
        }
    }

    /** The pure stage: JourneyMap's static {@code JMChunkSnapshot.decode} over a private heap
     *  buffer (any thread; it returns null for bytes it refuses). */
    private Object decodeWithBindings(byte[] bytes, Object level) throws Throwable {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return this.h.decode.invoke(buf, level);
        } finally {
            buf.release();
        }
    }

    /** JourneyMap's thread only: the wrapper that holds its chunk until it leaves the cache. */
    private Object wrapWithBindings(Object level, Object snapshot) throws Throwable {
        return this.h.newSnapshotChunkMD.invoke(level, snapshot, true);
    }

    /** Removes our snapshot wrapper unless a live chunk replaced it (JourneyMap's to keep). */
    private void removeIfOurs(Object dataCache, Object allowCached, long mcKey, Object md) {
        try {
            Object current = this.h.getChunkMDWithIntent.invoke(dataCache, mcKey, allowCached);
            if (current == md) this.h.removeChunkMD.invoke(dataCache, md);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
        }
    }

    // ---- diagnostics ----

    String state() {
        if (this.dead) return "dead";
        if (!this.enabled.getAsBoolean()) return "disabled";
        if (!this.serverAllowsWrites.getAsBoolean()) return "blocked_by_server";
        if (this.inertReason != null) return "inert(" + this.inertReason + ")";
        String waiting = this.waitingReason;
        return waiting != null ? "waiting(" + waiting + ")" : "active";
    }

    String describe() {
        long columns = this.encodedColumns.get();
        long rendered = this.rendered.get();
        int contextSize;
        synchronized (this.context) {
            contextSize = this.context.size();
        }
        return "JourneyMap: state=" + state()
                + ", queued=" + this.queuedGauge
                + ", batches=" + this.batches.get()
                + ", rendered=" + rendered
                + ", skipped_live=" + this.skippedLive.get()
                + ", render_failed=" + this.renderFailed.get()
                + ", optional_refusals=" + this.optionalRefusals.get()
                + ", edge_rendered=" + this.edgeRendered.get()
                + ", decode_failures=" + this.decodeFailures.get()
                + ", column_failures=" + this.columnFailures.get()
                + ", abandoned=" + this.abandoned.get()
                + ", replayed=" + this.replayed.get()
                + ", requeued=" + this.requeued.get()
                + ", slow_batches=" + this.slowBatches.get()
                + inFlightAge()
                + ", dropped_overflow=" + this.droppedOverflow.get()
                + ", dropped_stale=" + this.droppedStale.get()
                + ", dropped_inert=" + this.droppedInert.get()
                + ", encode_refused=" + this.encodeRefused.get()
                + ", drops_reported=" + this.dropsReported.get()
                + ", context=" + contextSize
                + ", last_batch_ms=" + this.lastBatchMillis
                + ", batch_pick=" + this.batchPick
                + ", encode_us_per_column=" + (columns == 0 ? 0 : this.encodeNanos.get() / columns / 1000)
                + ", decode_us_per_column=" + (this.decodedColumns.get() == 0 ? 0 : this.decodeNanos.get() / this.decodedColumns.get() / 1000)
                + ", decode_ahead=" + (this.decodeAhead ? "on" : "off")
                + ", decoded_ahead=" + this.decodedAhead.get()
                + ", predecode_us_per_column=" + (this.predecodedColumns.get() == 0 ? 0 : this.predecodeNanos.get() / this.predecodedColumns.get() / 1000)
                + ", render_us_per_column=" + (rendered == 0 ? 0 : this.renderNanos.get() / rendered / 1000)
                + ", bp=" + this.governor.token(this.backpressureEnabled.getAsBoolean(),
                        active() && this.serverAllowsWrites.getAsBoolean(), this.occupancy)
                + ", heap=" + this.memory.token()
                + (this.h.version != null ? ", version=" + this.h.version : "");
    }

    /** {@code , inflight_ms=<age>} while a batch runs on JourneyMap's thread, else empty. */
    private String inFlightAge() {
        var batch = this.inFlight;
        if (batch == null || batch.state.get() != Batch.STARTED || batch.startedAtMillis == 0) return "";
        return ", inflight_ms=" + Math.max(0, now() - batch.startedAtMillis);
    }

    long counterForTest(String name) {
        return switch (name) {
            case "batches" -> this.batches.get();
            case "rendered" -> this.rendered.get();
            case "skipped_live" -> this.skippedLive.get();
            case "render_failed" -> this.renderFailed.get();
            case "decode_failures" -> this.decodeFailures.get();
            case "column_failures" -> this.columnFailures.get();
            case "abandoned" -> this.abandoned.get();
            case "replayed" -> this.replayed.get();
            case "requeued" -> this.requeued.get();
            case "dropped_overflow" -> this.droppedOverflow.get();
            case "dropped_stale" -> this.droppedStale.get();
            case "dropped_inert" -> this.droppedInert.get();
            case "drops_reported" -> this.dropsReported.get();
            case "optional_refusals" -> this.optionalRefusals.get();
            case "edge_sweeps" -> this.edgeSweeps.get();
            case "edge_rendered" -> this.edgeRendered.get();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void warn(String what, Throwable t) {
        long n = FAILURE_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (n > 0) LSSLogger.warn("JourneyMap map ingestion: " + what + " (" + n + " since the last report)", t);
    }
}
