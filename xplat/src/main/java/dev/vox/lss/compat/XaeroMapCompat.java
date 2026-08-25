package dev.vox.lss.compat;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Xaero's World Map bridge (issue #223, docs/planning/xaero-map-bridge-plan.md):
 * writes LSS-delivered LOD columns into Xaero's World Map so the map records
 * terrain far beyond vanilla render distance. Pure reflection — zero compile-time
 * dependency, zero mixins (every member the bridge touches is public in Xaero WM
 * 1.45.0, verified 26.2 ≡ 1.21.1) — following the {@code VoxyCompat}/
 * {@code MoonriseReadCompat} interop discipline: any resolve failure disables the
 * bridge with one warn (diag shows {@code state=unavailable}); runtime failures
 * latch it dead for the session after {@value #THROW_LATCH} consecutive failures;
 * LOD delivery is NEVER affected — the consumer swallows every throwable
 * ({@code Error}s included: {@code LSSApi.dispatchColumn} converts ANY escape
 * into an ingest-failure report, and a map problem must not trigger re-serves).
 *
 * <p>Two-stage pipeline (plan §2.4): the registered {@link VoxelColumnConsumer}
 * extracts a {@link XaeroTileExtractor.PreparedTile} on the LSS decode thread and
 * offers it to a bounded (count AND bytes) latest-wins queue; {@link #pump()} —
 * the shared end-of-client-tick body, MAIN CLIENT THREAD (Xaero enforces it with
 * {@code isSameThread} throws) — re-runs the native writer's gate ladder
 * verbatim, then commits under Xaero's own locks in the decompiled
 * {@code MapWriter.writeChunk} sequence, including the mandatory
 * {@code requestLoad} dance for regions Xaero hasn't loaded (fresh regions never
 * self-promote) and the set-never-clear {@code setBeingWritten} lifecycle (the
 * save path owns the reset). The drain is REGION-BUCKETED with a MEMORYLESS
 * outstanding-load window (plan §14 as reshaped by the 3-Opus fold): entries
 * group by their Xaero map region (32×32 chunks — Xaero's consent granularity),
 * loaded regions commit in clusters, and load requests go to the pending regions
 * holding the most tiles, at most {@value #MAX_OUTSTANDING_LOADS} in flight —
 * where "in flight" is recognized fresh each pump from Xaero's OWN state
 * ({@code canRequestReload_unsynced()} is false exactly while a request is
 * queued/loading/refreshing), never from a bookkeeping set that could leak
 * against the loader's dead-end outcomes. Xaero's shared load-pacing surface is
 * deliberately untouched in BOTH directions: {@code shouldAllowAnotherRegionToLoad}
 * is never consulted (it synchronizes on its own — possibly BRANCH — region, a
 * lock-order inversion against Xaero's parent-then-leaf loader thread; review
 * MAJOR, a real client deadlock), and {@code setNextToLoadByViewing} is never
 * called (the loader itself never reads it — it is purely the pacing token of
 * the four native consumers, and pointing it at a far bridge region vetoed all
 * four; left alone, native requests front-insert AHEAD of our batch, the right
 * priority). Texture rebuilds ({@code MapTileChunk.updateBuffers} — the
 * expensive half of a native write) are OURS to run, exactly like the native
 * writer's: coalesced per tile chunk by {@link #flushPendingUpdates} under the
 * same gates (plan §15, the cache-not-prepared crash). The
 * {@code setToUpdateBuffers} flag is NEVER set: Xaero's preUpload sweep consumes
 * it with no {@code isResting()} check, i.e. possibly after the region was queued
 * for cache-saving on prepared textures — the saver then throws.
 *
 * <p><b>Registration lifecycle</b> (review MAJOR): the consumer is what holds the
 * handshake's CAPABILITY_VOXEL_COLUMNS bit ({@code LSSApi.hasVoxelConsumers()}),
 * so an Xaero-only install (no Voxy) legitimately subscribes to LOD data — that
 * IS the feature. But deregistering MID-SESSION would put every arriving column
 * through the no-consumer ingest-failure path (up to 4 re-serves per position
 * before parking — a whole-disc churn for a map problem), so: registration is
 * add-only while a session may be live (init + pump), a disabled or dead bridge
 * becomes a silent no-op consumer (offers are dropped), and deregistration
 * happens ONLY at {@link #onDisconnect()} — which is also where the death latch
 * re-arms (session-scoped: one bad session must not disable the feature for the
 * whole JVM; genuine Xaero drift re-latches within {@value #THROW_LATCH}
 * commits next session).
 */
final class XaeroMapCompat {

    static final int MAX_QUEUE = 8192;
    /** Byte gauge companion to the count cap (the ClientColumnProcessor discipline —
     *  a count cap alone admits ~0.5 GB of max-overlay tiles at ~68 KB each; plain
     *  tiles are ~4.7 KB but ocean tiles carry per-pixel overlay runs). Estimated,
     *  not exact. */
    static final long MAX_QUEUE_BYTES = 48L * 1024 * 1024;
    /** Safety ceiling only — the nanos budget below is the binding constraint
     *  (review MAJOR: 8 committed only 160 tiles/s against 300-1000 delivered
     *  columns/s, making every backfill drop most of the map). */
    static final int MAX_COMMITS_PER_PUMP = 64;
    static final long PUMP_NANOS_BUDGET = 2_000_000L;
    /** Ladder-ready deferrals (busy region, PBO download) before an entry drops. */
    static final int DEFER_CAP = 200;
    /** Our in-flight region-load window — the honest generalization of Xaero's own
     *  1-in-flight gauge (plan §14): the loader drains unlimited CHEAP (virgin)
     *  loads per cycle but only one expensive file load (~10/s), so a small fixed
     *  window self-clocks the request rate to the real drain rate. In-flight is
     *  derived fresh each pump from {@code canRequestReload_unsynced()} — see
     *  {@link #grantLoads}. Budget-truncated pumps may under-count in-flight
     *  regions (unprobed buckets are unknown), transiently over-granting by at
     *  most one window per pump; requests are idempotent (an already-queued
     *  region answers not-requestable), so the excess is bounded and harmless. */
    static final int MAX_OUTSTANDING_LOADS = 8;
    /** Buffer-update coalescing (plan §15). A committed tile chunk's texture rebuild
     *  ({@code MapTileChunk.updateBuffers} — a 64×64-pixel recolor, the expensive
     *  half of a native write) is deferred until its tiles stop arriving for this
     *  many pumps (~2 s), so the 16 spiral-delivered tiles of a tile chunk cost
     *  ~1-4 rebuilds instead of 16. Meanwhile the tile chunk sits in the native
     *  writer's own transient state (changed=true, no flag — the (3,3) rule leaves
     *  every non-final chunk write there too). */
    static final int UPDATE_IDLE_PUMPS = 40;
    /** Above this many owed rebuilds the oldest become due at once (bounds the
     *  stale-texture window under a flood). */
    static final int PENDING_UPDATES_SOFT_CAP = 256;
    /** At this many owed rebuilds COMMITS pause until the flush drains — a region
     *  that never rests (a stuck saver) must not grow the set without bound. */
    static final int PENDING_UPDATES_HARD_CAP = 1024;
    /** A due rebuild whose region stays not-ready this long (~60 s — not resting,
     *  writer-paused, or the player is in another dimension) is dropped, counted.
     *  Accepted residual: the tile chunk keeps its stale texture (changed=true,
     *  unflagged) until the region's next native write or reload. */
    static final int UPDATE_MAX_STALL_PUMPS = 1200;
    /** Absolute ceiling on coalescing: a tile chunk re-touched more often than the
     *  idle window (a slow trickle) still rebuilds this many pumps (~8 s) after its
     *  FIRST commit — the map must never show a written tile chunk blank
     *  indefinitely (review B). */
    static final int UPDATE_MAX_DEFER_PUMPS = 4 * UPDATE_IDLE_PUMPS;
    /** Rebuild budget for the TICK-side flush — the FALLBACK path only since the
     *  frame round (plan §17): rebuilds normally run on the per-frame hook
     *  ({@link #renderFrame} — review A's recorded lever, pulled after the fix's
     *  first live session stuttered), and a pump that saw a frame flush since the
     *  previous pump runs its flush with ZERO rebuilds (cheap drops/bookkeeping
     *  only). With no frames flushing (loading screens, hidden window, headless
     *  test JVMs) the tick flush rebuilds under this budget exactly as before:
     *  the first REMOVING outcome of a pump is exempt, so the set always drains
     *  — not-ready verdicts are memoized per region and cost no budget — and it
     *  BORROWS on top (review A: a budget that cannot keep up parks commits at
     *  the hard cap): the whole {@link #UPDATE_BORROW_NANOS} once the queue is
     *  empty (commits need nothing), half of it while the owed set is past the
     *  soft cap. */
    static final long UPDATE_NANOS_BUDGET = 2_000_000L;
    static final long UPDATE_BORROW_NANOS = PUMP_NANOS_BUDGET;
    /** Texture rebuilds per FRAME (plan §17 — the stutter round): a rebuild is a
     *  64×64-pixel recolor (~0.5-4 ms), and several per TICK bunched with the
     *  commit budget doubled the pump ceiling — visible stutter during map fills.
     *  One rebuild per frame is Xaero's own sweep grain: at 60-120 fps that is
     *  60-120 tile chunks/s ≈ 1000-2000 coalesced tiles/s of drain — above the
     *  serve rate. This is the BASE of the per-frame cap (§17.1 review fold): under
     *  backlog pressure while frames are SCARCE (≤1 since the last pump, fps ≲ 2×
     *  tick rate — a long frame absorbs a few recolors) it rises to 2 past the soft
     *  cap and 3 past half the hard cap, and the interval ALLOWANCE (the §15.2
     *  budget-with-borrow per pump, metered by measured recolor nanos) is the
     *  wall-rate ceiling either way — a 144 Hz client pays the same rate the tick
     *  fallback would, spread across frames. */
    static final int FRAME_MAX_REBUILDS = 1;
    /** Not-ready region probes exempt from the flush budget (§17.1): under the
     *  production 2 ms budget the floor never binds (a probe is ~µs); it exists so a
     *  fully-not-ready owed set cannot walk hundreds of region monitors per FRAME
     *  with the budget never consulted (no removing outcome = the §15 exemption
     *  never disarms). Under a degenerate zero budget, ready work behind more than
     *  this many not-ready regions waits for the tick fallback — accepted. */
    static final int FLUSH_PROBE_EXEMPT_FLOOR = 8;
    // ---- §12 ingest backpressure (hybrid-scan-plan.md §12.2; replaces the §18
    // ledger heal — the LEDGER machinery is deleted, the immediate DropReporter
    // path is KEPT: it is what makes dimension-switch drops self-heal) ----

    /** The halt occupancy: a full report ({@code INGEST_BACKLOG_HALT_SECTIONS})
     *  fires at this queue occupancy, AHEAD of the 100% drop point — the decode-
     *  queue halt's own doctrine — leaving ~25% of the queue (~750 columns) as
     *  landing room for the in-flight tail (already-admitted server work + the
     *  LSS decode queue keep landing ~1 s past a halt). */
    static final double BP_HALT_OCCUPANCY = 0.75;
    /** Consecutive undrainable pumps (~1 s) before the pump reads as gate-BLOCKED
     *  (the {@code (blocked)} diag suffix) — flap hysteresis for per-pump
     *  reflective gates that can oscillate. §12.8: a blocked pump no longer
     *  silences the report OR refuses offers — the queue absorbs the burst and
     *  its occupancy IS the pressure signal (live 2026-08-24: movement-driven
     *  Xaero contention held the old refusal latch for 56k offers while the -1
     *  report let the server stream 731/s into them; the halt time-box below is
     *  the anti-stall protection the old -1 doctrine tried to be). */
    static final int BP_PAUSE_PUMPS = 20;
    /** The staleness watchdog: without a pump inside this window the report is -1
     *  (a frozen mirror must never read as live backlog). */
    static final int BP_PUMP_STALE_MILLIS = 1000;
    /** The halt TIME-BOX: at a full report with zero commits for this long, the
     *  report degrades to -1 (warn once) — the bridge may PACE the stream, never
     *  STOP it (the #71 halt was designed for Voxy's unbounded-queue OOM
     *  emergency; this queue is bounded and self-shedding). §12.8: this window
     *  now also bounds STRUCTURAL pauses (cave layer, map locked, long gate
     *  blocks) — a movement burst holds the halt up to this long, then the fill
     *  resumes and the map wears the loss (doctrine (d)). */
    static final long BP_HALT_WEDGE_MILLIS = 7000;
    /** A wedge-degraded report re-arms once the queue drains below this. */
    static final double BP_WEDGE_REARM_OCCUPANCY = 0.5;
    /** Consecutive failures (commit-side or extraction-side) before the bridge
     *  latches dead for the SESSION (re-armed at disconnect). */
    static final int THROW_LATCH = 5;
    /** The surface layer — native {@code caveLayer} sentinel. */
    private static final int SURFACE_LAYER = Integer.MAX_VALUE;

    private static final LogThrottle EXTRACT_FAIL_WARN = new LogThrottle(60_000);
    private static final LogThrottle COMMIT_FAIL_WARN = new LogThrottle(60_000);

    // ---- test seams (the VoxyCompat discipline: default-wired to production) ----

    /** Resolves the reflected Xaero class names — test seam. */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /**
     * The two operations the pump needs on Xaero's world object. A seam because
     * {@code ClientLevel} is unconstructible under fabric-loader-junit — the stub
     * {@code MapProcessor.getWorld()} returns a plain marker object and tests map
     * it here; production casts.
     */
    interface LevelOps {
        Object dimension(Object world);
        boolean isChunkLoaded(Object world, int chunkX, int chunkZ);
    }

    /** The drop-report sink ({@code LSSApi.reportIngestFailure} in production — an
     *  injectable seam because the static API hops through Minecraft.getInstance()).
     *  KEPT through the §18→§12 transition (hybrid-scan-plan.md §12.1): the
     *  immediate report is what makes dimension-switch drops self-heal — client
     *  stamps persist per dimension, so only reportIngestFailure un-stamps. */
    @FunctionalInterface
    interface DropReporter {
        void report(Object dimension, int chunkX, int chunkZ);
    }

    static final LevelOps PRODUCTION_LEVEL_OPS = new LevelOps() {
        @Override
        public Object dimension(Object world) {
            return ((ClientLevel) world).dimension();
        }

        @Override
        public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
            var chunk = ((ClientLevel) world).getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return chunk != null && !(chunk instanceof EmptyLevelChunk);
        }
    };

    // ---- static facade (production wiring; ModCompat owns the instance) ----

    private static volatile XaeroMapCompat instance;
    /** Xaero present but its internal surface unrecognized — drives the
     *  {@code state=unavailable} diag line (without it a drifted Xaero would be
     *  indistinguishable from "not installed", hiding the plan's top risk). */
    private static volatile boolean resolveFailed;

    /** Client init, Xaero present: resolve + register the consumer (if enabled). */
    static boolean init() {
        return initWith(Class::forName);
    }

    /** The init body with an injectable resolver (the resolve-failure path's test seam). */
    static boolean initWith(ClassResolver resolver) {
        try {
            var h = Handles.resolve(resolver);
            var bridge = new XaeroMapCompat(h, PRODUCTION_LEVEL_OPS,
                    () -> LSSClientConfig.CONFIG.enableXaeroMapBridge,
                    LSSApi::isServerEnabled,
                    LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer,
                    // §12.2: the bridge switch COMPOSES UNDER the global #71 switch —
                    // with enableIngestBackpressure off the manager never polls the
                    // report, so the refusal/report halves must go dark too (review
                    // MAJOR: an armed reporter with no taper behind it restores the
                    // §18.1 churn regime).
                    () -> LSSClientConfig.CONFIG.enableIngestBackpressure
                            && LSSClientConfig.CONFIG.enableXaeroMapBackpressure,
                    XaeroMapCompat::reportDroppedProduction);
            bridge.maybeRegister();
            instance = bridge;
            LSSLogger.info(LSSClientConfig.CONFIG.enableXaeroMapBridge
                    ? "Xaero's World Map detected — LOD map bridge active"
                    : "Xaero's World Map detected — LOD map bridge ready"
                            + " (disabled by enableXaeroMapBridge)");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                 | IllegalAccessException e) {
            resolveFailed = true;
            LSSLogger.warn("Xaero map bridge: this Xaero's World Map version has a different"
                    + " internal surface (the bridge needs 1.42.0 or newer) — bridge off:", e);
            return false;
        } catch (Throwable e) {
            resolveFailed = true;
            LSSLogger.error("Failed to initialize the Xaero map bridge", e);
            return false;
        }
    }

    /** End-of-client-tick body (main client thread). */
    static void clientTick() {
        var bridge = instance;
        if (bridge != null) bridge.pump();
    }

    /** Per-frame body (render thread) — the rebuild phase's scheduler (plan §17). */
    static void renderFrame() {
        var bridge = instance;
        if (bridge != null) bridge.frameFlush();
    }

    /** Production {@link DropReporter}: forgets the client stamp so the position
     *  re-serves — safe from any thread, bounded by the client's per-position
     *  ingest-failure cap (the runaway-loop belt). */
    @SuppressWarnings("unchecked")
    private static void reportDroppedProduction(Object dimension, int chunkX, int chunkZ) {
        LSSApi.reportIngestFailure((ResourceKey<Level>) dimension, chunkX, chunkZ);
    }

    /** Disconnect body — session teardown (queue, latches, registration). */
    static void onDisconnect() {
        var bridge = instance;
        if (bridge != null) bridge.onSessionEnd();
    }

    /** The conditional {@code /lss diag} line, or null when Xaero was never detected. */
    static String diagLine() {
        var bridge = instance;
        if (bridge != null) return bridge.describe();
        return resolveFailed
                ? "XaeroMap: state=unavailable (unrecognized Xaero internals — bridge off)"
                : null;
    }

    /** Test seam: forget the static facade state. */
    static void resetFacadeForTest() {
        instance = null;
        resolveFailed = false;
    }

    // ---- instance ----

    private final Handles h;
    private final LevelOps levelOps;
    private final BooleanSupplier enabled;
    /** An LSS session is live — offers outside one are dropped (closes the
     *  disconnect-drain race that could carry one stale tile into the NEXT
     *  server's — or a singleplayer world's — persistent map). */
    private final BooleanSupplier sessionActive;
    private final java.util.function.Consumer<VoxelColumnConsumer> registrar;
    private final java.util.function.Consumer<VoxelColumnConsumer> deregistrar;
    /** The §12 backpressure kill switch (client config
     *  {@code enableXaeroMapBackpressure}); composes UNDER the global
     *  {@code enableIngestBackpressure} (#71 owns the signal path manager-side). */
    private final BooleanSupplier backpressureEnabled;
    /** Reports a dropped position back to LSS for its bounded re-serve — test seam. */
    private final DropReporter dropReporter;
    private final VoxelColumnConsumer consumer;
    /** Whether the consumer is currently registered with LSSApi. Main thread only. */
    private boolean registered;

    private final Object queueLock = new Object();
    /** Packed chunk pos → entry; insertion-ordered, latest tile wins in place. */
    private final LinkedHashMap<Long, Entry> queue = new LinkedHashMap<>();
    private long queuedBytes; // under queueLock
    /** Queue occupancy in [0,1] — max of the byte and count fractions, mirrored
     *  under {@link #queueLock} at every mutation for the lock-free 20 Hz
     *  backpressure poll (§12.2). */
    private volatile double occupancy;

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong skippedNative = new AtomicLong();
    private final AtomicLong deferEvents = new AtomicLong();
    private final AtomicLong droppedOverflow = new AtomicLong();
    private final AtomicLong droppedStale = new AtomicLong();
    private final AtomicLong droppedExpired = new AtomicLong();
    private final AtomicLong commitFailures = new AtomicLong();
    private final AtomicLong loadRequests = new AtomicLong();
    private volatile boolean dead;
    /** A session end was signalled (possibly off-thread); its main-thread half is owed. */
    private volatile boolean sessionEndPending;
    private int consecutiveFailures; // main thread only
    /** Decode-thread twin of the commit-side latch: a permanently-throwing
     *  extractor must not burn CPU + hold the capability subscription forever. */
    private final AtomicInteger consecutiveExtractFailures = new AtomicInteger();
    /** The per-pump time budget — a field so tests can neutralize MethodHandle warmup. */
    long pumpNanosBudget = PUMP_NANOS_BUDGET;
    /** The rebuild-phase seams (plan §15) — fields so tests can drive the windows. */
    long updateNanosBudget = UPDATE_NANOS_BUDGET;
    int updateIdlePumps = UPDATE_IDLE_PUMPS;
    int pendingUpdatesSoftCap = PENDING_UPDATES_SOFT_CAP;
    int pendingUpdatesHardCap = PENDING_UPDATES_HARD_CAP;
    int updateMaxStallPumps = UPDATE_MAX_STALL_PUMPS;
    int updateMaxDeferPumps = UPDATE_MAX_DEFER_PUMPS;
    long updateBorrowNanos = UPDATE_BORROW_NANOS;
    int frameMaxRebuilds = FRAME_MAX_REBUILDS;
    int maxQueue = MAX_QUEUE;
    int deferCap = DEFER_CAP;
    long maxQueueBytes = MAX_QUEUE_BYTES;
    int bpPausePumps = BP_PAUSE_PUMPS;
    int bpPumpStaleMillis = BP_PUMP_STALE_MILLIS;
    long bpHaltWedgeMillis = BP_HALT_WEDGE_MILLIS;
    /** Tile chunks committed but not yet texture-rebuilt, keyed by tile-chunk
     *  coords and ordered by LAST TOUCH (a re-touch re-inserts at the tail, so
     *  idle-due entries are always a prefix). Main thread only. */
    private final LinkedHashMap<PendingKey, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
    private long pumpCount; // main thread only
    private final AtomicLong bufferUpdates = new AtomicLong();
    /** Frame flushes that passed the gate ladder — the per-frame scheduler is alive
     *  (its absence in a live diag means the render hook is not firing and the tick
     *  fallback is doing the rebuilds). */
    private final AtomicLong frameFlushes = new AtomicLong();
    /** Total nanos inside {@code MapTileChunk.updateBuffers} + the single worst call
     *  — the live stutter instruments (diag {@code rebuild_ms=}/{@code rebuild_max_us=}). */
    private final AtomicLong rebuildNanos = new AtomicLong();
    private volatile long rebuildNanosMax;
    /** A frame flush ran (or fast-out-armed) since the last pump — consumed into
     *  {@link #frameActiveThisPump} at the TOP of {@code pump()} (§17.1: a pump that
     *  returns at a ladder gate must not leave it armed for a later one). Main
     *  thread only (frames and ticks share the render thread). */
    private boolean frameFlushRan;
    /** The marker's per-pump snapshot — the value {@code tickFlush} acts on. */
    private boolean frameActiveThisPump;
    /** Nanos of {@code updateBuffers} the frame slice spent since the last pump —
     *  the interval's allowance meter (§17.1): frames stop recoloring once it
     *  reaches the budget-with-borrow, so a high-fps client pays the same wall
     *  rate the tick fallback would. Main thread only. */
    private long rebuildSpentSinceLastPumpNanos;
    /** Frames seen since the last pump — the per-frame cap's pressure bumps apply
     *  only while frames are SCARCE (≤1 per tick). Main thread only. */
    private int framesSinceLastPump;
    private final AtomicLong droppedUpdates = new AtomicLong();
    /** Owed rebuilds whose region/tile chunk Xaero unloaded, parked or replaced
     *  first — its own counter (review A) so the live test can tell a parking race
     *  from the stall/dimension/session drops. */
    private final AtomicLong droppedUnloaded = new AtomicLong();
    /** Entries the user's own Xaero map-writing switches refused (plan §16): "Load New
     *  Chunks" off for a new tile, "Update Chunks" off for an existing one, or both off. */
    private final AtomicLong skippedSettings = new AtomicLong();
    /** Pumps that waited because Xaero was rendering a cave layer (diag). */
    private final AtomicLong caveLayerWaits = new AtomicLong();
    /** Pump-side reports collected INSIDE the ladder (which runs under Xaero's
     *  renderThreadPauseSync monitor) and drained by {@link #pump} AFTER the
     *  ladder returns — up to a whole queue's worth on a world-id change, and an
     *  un-stamp burst must not run under a Xaero monitor (review ×2). Main
     *  thread only. Object[]{dimension, chunkX, chunkZ}. */
    private final java.util.ArrayList<Object[]> deferredReports = new java.util.ArrayList<>();
    /** Drops reported back to LSS for their bounded re-serve (the kept reporter
     *  path — stale-dimension drops always, governed drops under §12). */
    private final AtomicLong dropsReported = new AtomicLong();
    // §12 backpressure state. The drainable latch is DERIVED, never enumerated:
    // pumpLadder's outcome sets it, so every early return — present and future —
    // reads as not-draining by construction (review MAJOR). §12.8: the latch is
    // now DIAGNOSTIC + hysteresis only — a blocked pump keeps reporting off the
    // queue's occupancy (and keeps accepting offers), so the taper/halt engages
    // exactly during the contention the old -1-on-paused doctrine went silent for.
    private volatile boolean pumpDrainable = true;
    private volatile long lastPumpMillis;
    private int undrainablePumps; // main thread only (hysteresis counter)
    // Halt time-box (main thread only — the poll runs on the client tick).
    private long haltSinceMillis;
    private long writtenAtHaltStart;
    private volatile boolean haltWedged;
    /** The settings read threw once this session: both switches read as ON from then on
     *  (warned once). Session-scoped like the other latches; reset at session end. */
    private volatile boolean settingsGateBroken;
    /** Xaero's CrashHandler holds a crash — the native writer's first gate. It is a
     *  ONE-TICK shield: Xaero's worker died mid-tick, and {@code checkForCrashes} at
     *  the next tick start nulls the field and re-throws it on the client thread (the
     *  client is about to crash). The bridge must not touch Xaero in that window.
     *  Diag-visible while it holds; session-scoped. */
    private volatile boolean xaeroCrashed;
    /** Xaero's {@code getCurrentWorldId()} the last pump saw — a server-initiated
     *  reconfiguration (play → configuration) fires neither loader's disconnect event,
     *  so a world-id change is the ONE signal that the queue's tiles belong to a
     *  previous world (reviewer: the owed-rebuild map already carries the id; the
     *  queue did not). Main thread only. */
    private String lastWorldId;
    private volatile int pendingUpdatesGauge;
    /** Rotating drain start (the IncomingRequestRouter M4 precedent): without it a
     *  permanently-deferring queue prefix starves committable entries forever. */
    private int drainRotation; // main thread only
    /** Regions with queued tiles awaiting their Xaero load, as PROBED by the last
     *  pump — a diag gauge, and a lower bound under budget truncation (buckets the
     *  commit loop never reached are unknown). */
    private volatile int regionsWaiting;

    /** Owed-rebuild key: the DIMENSION is part of it (sweep B m2 — the End/Nether
     *  reuse the Overworld's tile-chunk coords around the origin; a coords-only key
     *  silently evicted the other dimension's entry). ResourceKeys are interned. */
    private record PendingKey(Object dimension, long tileChunk) {}

    /** A committed tile chunk owed its texture rebuild (plan §15). Bound to the
     *  Xaero session that produced it (processor identity + world id — review B:
     *  a server-initiated reconfiguration skips the disconnect event, and a
     *  {@code ResourceKey} alone is identity-stable across servers). */
    private static final class PendingUpdate {
        final Object processor;
        final String worldId;
        final Object dimension;
        final Object region;
        final Object tileChunk;
        final int localTcX;
        final int localTcZ;
        final long firstTouchPump;
        long lastTouchPump;
        /** Pump at which a DUE rebuild first found its region not ready; -1 = never. */
        long stalledSincePump = -1;

        PendingUpdate(Object processor, String worldId, Object dimension, Object region,
                      Object tileChunk, int localTcX, int localTcZ, long pump) {
            this.processor = processor;
            this.worldId = worldId;
            this.dimension = dimension;
            this.region = region;
            this.tileChunk = tileChunk;
            this.localTcX = localTcX;
            this.localTcZ = localTcZ;
            this.firstTouchPump = pump;
            this.lastTouchPump = pump;
        }
    }

    private static final class Entry {
        volatile XaeroTileExtractor.PreparedTile tile; // replaced under queueLock (latest wins)
        final Object dimension;
        int bytes; // under queueLock
        /** Pump-side (++) with a decode-side reset on tile replace — the race is
         *  benign (one deferral tick lost or kept; the cap is approximate). */
        int ladderReadyDeferrals;

        Entry(Object dimension, XaeroTileExtractor.PreparedTile tile, int bytes) {
            this.dimension = dimension;
            this.tile = tile;
            this.bytes = bytes;
        }
    }

    XaeroMapCompat(Handles h, LevelOps levelOps, BooleanSupplier enabled,
                   BooleanSupplier sessionActive,
                   java.util.function.Consumer<VoxelColumnConsumer> registrar,
                   java.util.function.Consumer<VoxelColumnConsumer> deregistrar,
                   BooleanSupplier backpressureEnabled, DropReporter dropReporter) {
        this.h = h;
        this.levelOps = levelOps;
        this.enabled = enabled;
        this.sessionActive = sessionActive;
        this.registrar = registrar;
        this.deregistrar = deregistrar;
        this.backpressureEnabled = backpressureEnabled;
        this.dropReporter = dropReporter;
        this.consumer = buildConsumer();
    }

    /**
     * ADD-only registration reconcile (init + every pump): a mid-session enable
     * starts feeding the map (when a stream exists — an Xaero-only install that
     * joined disabled has no capability bit until rejoin, which the tooltip's
     * wording tolerates). Deregistration is deliberately NOT here — see the class
     * javadoc's registration-lifecycle rule and {@link #onSessionEnd()}.
     */
    void maybeRegister() {
        if (!this.dead && this.enabled.getAsBoolean() && !this.registered) {
            this.registrar.accept(this.consumer);
            this.registered = true;
        }
    }

    /**
     * Session teardown (the loaders' disconnect events): drop the session's queue,
     * re-arm the death latches (session-scoped — one bad session must not disable
     * the feature until restart), and settle registration for the NEXT handshake
     * (a disabled bridge releases the capability bit here, never mid-session).
     */
    void onSessionEnd() {
        // ANY thread: Fabric fires DISCONNECT from netty's channelInactive on an abrupt
        // close (read timeout, reset, server death — sweep C MAJOR), while the main thread
        // may be inside pump(). Only the thread-safe half runs here; the main-thread-only
        // state (owed rebuilds, registration, the failure count) settles at the top of
        // the next pump, which runs on the title screen too.
        clearQueue();
        this.consecutiveExtractFailures.set(0);
        this.dead = false;
        this.settingsGateBroken = false;
        this.xaeroCrashed = false;
        // §12: session-scoped backpressure state re-arms with the session.
        this.pumpDrainable = true;
        this.haltWedged = false;
        this.sessionEndPending = true;
    }

    /** The main-thread half of {@link #onSessionEnd()}. */
    private void settleSessionEnd() {
        this.sessionEndPending = false;
        // The old world's tile chunks are never touched again; the rebuilds they were
        // owed are lost (counted — Xaero's world is going away under us, so no
        // best-effort flush here). The last ≤2 s of commits before a disconnect can
        // thus reach the region cache with a stale texture (review A N5, accepted).
        this.droppedUpdates.addAndGet(this.pendingUpdates.size());
        this.pendingUpdates.clear();
        this.pendingUpdatesGauge = 0;
        this.regionsWaiting = 0;
        this.consecutiveFailures = 0;
        this.frameFlushRan = false;
        this.frameActiveThisPump = false;
        this.rebuildSpentSinceLastPumpNanos = 0;
        this.framesSinceLastPump = 0;
        this.rebuildNanosMax = 0; // session-scoped worst recolor; the total stays lifetime
        this.lastWorldId = null;
        // §12 (review m5): the main-thread half re-clears the latches — an
        // in-flight ladder's finally can re-latch AFTER onSessionEnd's off-thread
        // clear; this half runs at the next pump top, after any such ladder.
        this.undrainablePumps = 0;
        this.haltSinceMillis = 0;
        this.pumpDrainable = true;
        this.haltWedged = false;
        this.deferredReports.clear(); // stale un-stamps must not cross sessions
        if (this.registered && !this.enabled.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        } else {
            maybeRegister();
        }
    }

    /** The registered consumer — a thin shell over {@link #offerColumn}, plus the
     *  §12 backpressure report. An ANONYMOUS CLASS, not a lambda, so it can
     *  override the default {@code pendingIngestBacklog()} — the exact VoxyCompat
     *  trap, documented there (a lambda silently keeps the -1 default and the
     *  taper never engages; the wiring pin catches a regression). */
    private VoxelColumnConsumer buildConsumer() {
        return new VoxelColumnConsumer() {
            @Override
            public int pendingIngestBacklog() {
                return reportBackpressure();
            }

            @Override
            public void onVoxelColumnReceived(net.minecraft.client.multiplayer.ClientLevel level,
                                              ResourceKey<Level> dimension,
                                              int chunkX, int chunkZ,
                                              VoxelColumnData columnData) {
            try {
                offerColumn(dimension, chunkX, chunkZ,
                        level.getMinY(), level.getMaxY() + 1, columnData);
                XaeroMapCompat.this.consecutiveExtractFailures.set(0);
            } catch (Throwable t) {
                // Swallow EVERYTHING, Errors included: LSSApi.dispatchColumn converts
                // any escape into reportIngestFailure — a re-serve loop for a map
                // problem (review MAJOR). A VM-fatal Error will resurface on a frame
                // that can afford it; here it would cost LOD correctness.
                long n = EXTRACT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.warn("Xaero map bridge: tile extraction failed (" + n
                            + " failure(s) since the last report)", t);
                }
                if (XaeroMapCompat.this.consecutiveExtractFailures.incrementAndGet() >= THROW_LATCH
                        && !XaeroMapCompat.this.dead) {
                    XaeroMapCompat.this.dead = true;
                    clearQueue();
                    LSSLogger.error("Xaero map bridge: " + THROW_LATCH + " consecutive"
                            + " extraction failures — disabling the bridge for this session"
                            + " (LODs are unaffected)", t);
                }
            }
            }
        };
    }

    /**
     * The §12 backpressure report (hybrid-scan-plan.md §12.2): a governor signal
     * dressed in the halt domain — {@code round(HALT × min(1, occupancy/0.75))} —
     * NOT a section count (the queue holds ~72k sections; a raw count would
     * hard-halt at 8% fill). -1 (no signal) only when the signal would be a LIE:
     * kill switch off, bridge disabled/dead/no session, the staleness watchdog
     * (a frozen pump = frozen mirror), or a wedge-degraded halt. §12.8: a
     * gate-BLOCKED pump with a live watchdog REPORTS — the queue is the pressure
     * gauge during exactly the contention episodes the old -1-on-blocked
     * doctrine went silent for; the halt time-box below (progress-rebased) is
     * what keeps a long structural block from stopping the fill for good.
     * Main client thread (the LSSApi poll).
     */
    int reportBackpressure() {
        if (!this.backpressureEnabled.getAsBoolean()) return noSignal();
        if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
            return noSignal();
        }
        if (this.haltWedged) {
            // The wedge re-arms only once the queue has actually drained back down.
            if (this.occupancy < BP_WEDGE_REARM_OCCUPANCY) {
                this.haltWedged = false;
            } else {
                return noSignal();
            }
        }
        if (nowMillis() - this.lastPumpMillis > this.bpPumpStaleMillis) return noSignal();
        int halt = dev.vox.lss.networking.client.LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        int report = (int) Math.round(halt * Math.min(1.0, this.occupancy / BP_HALT_OCCUPANCY));
        if (report >= halt) {
            // The halt TIME-BOX: pacing is the bridge's right, stopping is not. A
            // full report with no commits ACROSS the whole window means the writer
            // is stuck — truly wedged, or gate-blocked past any movement-burst
            // length (§12.8) — degrade to -1 (warn once) and let the fill
            // continue; the map wears the loss. PROGRESS RE-BASES
            // the window (review MAJOR ×3: an equality-against-the-opening-value
            // check was permanently disarmed by the first commit inside the
            // window), and every -1 exit CLEARS it (review MAJOR: a timer
            // surviving a cave-layer pause fired a false wedge on the first
            // governing poll back).
            long now = nowMillis();
            long w = this.written.get();
            if (this.haltSinceMillis == 0 || w != this.writtenAtHaltStart) {
                this.haltSinceMillis = now;
                this.writtenAtHaltStart = w;
            } else if (now - this.haltSinceMillis > this.bpHaltWedgeMillis) {
                this.haltWedged = true;
                this.haltSinceMillis = 0;
                LSSLogger.warn("Xaero map bridge: the map writer made no progress through a "
                        + (this.bpHaltWedgeMillis / 1000) + " s backpressure halt — releasing"
                        + " the LOD fill (map tiles may be dropped until the writer recovers)");
                return -1;
            }
        } else {
            this.haltSinceMillis = 0;
        }
        return report;
    }

    /** Every no-signal exit clears the halt time-box — the halt is "not currently
     *  in effect" in a -1 state, and a stale window fires false wedges. */
    private int noSignal() {
        this.haltSinceMillis = 0;
        return -1;
    }

    /** Clock seam (tests drive the watchdog + time-box without sleeping — the
     *  class is final, so the seam is a field, not an override). */
    java.util.function.LongSupplier bpClock = System::currentTimeMillis;

    private long nowMillis() {
        return this.bpClock.getAsLong();
    }

    /** Decode-thread entry: extract + enqueue (latest-wins, bounded, oldest drops). */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData) {
        if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
            return;
        }
        // §12.8: offers are ACCEPTED during pump pauses — the queue is the
        // movement-burst buffer AND the pressure gauge (the deleted §12.1(b)
        // refusal shed 56k tiles into silent permanent holes on the first live
        // session while the -1 report kept the stream at full rate). The cap
        // check below is the only shed point, and it reports (blocked-not-wedged
        // drops re-serve after the burst — the halt defers the re-declaration,
        // so there is no churn loop).
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        boolean overflowed = false;
        synchronized (this.queueLock) {
            // Don't pay the 256-pixel extraction for a tile the full queue would
            // evict on arrival (sustained-overflow CPU on the LOD decode thread). Count
            // cap only: the byte cap (which binds first on overlay-heavy tiles — sweep C
            // N3) needs the tile's size, unknown before extraction, and past it the
            // enqueue evicts the OLDEST entry, so that extraction is not wasted.
            if (this.queue.size() >= this.maxQueue && !this.queue.containsKey(key)) {
                this.droppedOverflow.incrementAndGet();
                overflowed = true;
            }
        }
        if (overflowed) {
            // Governed overflow is structurally ~0 (the halt fires at 75%); a stray
            // one self-heals via the reporter. OUTSIDE the lock (§18.1 discipline).
            reportDroppedIfGoverned(dimension, chunkX, chunkZ);
            return;
        }
        var tile = XaeroTileExtractor.extract(chunkX, chunkZ, worldBottomY, worldTopY, columnData);
        offerPrepared(dimension, tile);
    }

    /** Approximate retained bytes for the byte gauge (shallow arrays + overlay runs). */
    static int approxBytes(XaeroTileExtractor.PreparedTile tile) {
        int bytes = 4800;
        for (var runs : tile.overlays()) {
            if (runs != null) bytes += 24 + runs.length * 32;
        }
        return bytes;
    }

    /** Enqueue seam (tests build {@link XaeroTileExtractor.PreparedTile}s directly). */
    void offerPrepared(Object dimension, XaeroTileExtractor.PreparedTile tile) {
        int chunkX = tile.chunkX();
        int chunkZ = tile.chunkZ();
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        int bytes = approxBytes(tile);
        java.util.ArrayList<Object[]> evictedOut = null;
        synchronized (this.queueLock) {

            // Re-check under the lock (sweep C MAJOR): a decode-thread tile that passed
            // offerColumn's gate before the session ended must not land in the queue AFTER
            // onSessionEnd's clear — it would wait through the title screen and commit one
            // tile of the previous server into the next server's saved map (the dimension
            // key is the same interned object on both). The gate flips before the clear
            // and both serialize on this monitor, so this closes it.
            if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
                return;
            }
                var existing = this.queue.get(key);
            if (existing != null) {
                if (existing.dimension == dimension) {
                    this.queuedBytes += bytes - existing.bytes;
                    existing.tile = tile;
                    existing.bytes = bytes;
                    existing.ladderReadyDeferrals = 0; // fresh serve = fresh patience
                    updateOccupancyLocked();
                    return;
                }
                // Stale-dimension entry: the new serve replaces it (fresh Entry, so
                // an in-flight pump pass's compare-and-remove cannot delete it).
                // Counted + reported (§12 review: an uncounted silent foreign-dim
                // drop broke the §12.1(c) self-heal claim at portals) — collected
                // for the outside-the-lock report like the evictions.
                this.queuedBytes -= existing.bytes;
                this.queue.remove(key);
                this.droppedStale.incrementAndGet();
                if (evictedOut == null) evictedOut = new java.util.ArrayList<>();
                evictedOut.add(new Object[]{existing.dimension, key, Boolean.TRUE});
            }
            while (!this.queue.isEmpty()
                    && (this.queue.size() >= this.maxQueue
                        || this.queuedBytes + bytes > this.maxQueueBytes)) {
                var it = this.queue.entrySet().iterator();
                var evicted = it.next();
                this.queuedBytes -= evicted.getValue().bytes;
                it.remove();
                this.droppedOverflow.incrementAndGet();
                // Collect for the OUTSIDE-the-lock report (§18.1 discipline: never
                // report under queueLock).
                if (evictedOut == null) evictedOut = new java.util.ArrayList<>();
                evictedOut.add(new Object[]{evicted.getValue().dimension, evicted.getKey()});
            }
            this.queuedBytes += bytes;
            this.queue.put(key, new Entry(dimension, tile, bytes));
            updateOccupancyLocked();
        }
        if (evictedOut != null) {
            for (var e : evictedOut) {
                long k = (Long) e[1];
                if (e.length > 2) {
                    // stale-dimension replacement: unconditional (correctness)
                    reportDropped(e[0], (int) (k >> 32), (int) k);
                } else {
                    reportDroppedIfGoverned(e[0], (int) (k >> 32), (int) k);
                }
            }
        }
    }

    /** Recompute the occupancy mirror. Caller holds {@link #queueLock}. */
    private void updateOccupancyLocked() {
        double byBytes = this.maxQueueBytes <= 0 ? 1.0
                : (double) this.queuedBytes / this.maxQueueBytes;
        double byCount = this.maxQueue <= 0 ? 1.0
                : (double) this.queue.size() / this.maxQueue;
        this.occupancy = Math.min(1.0, Math.max(byBytes, byCount));
    }

    /** A same-dimension drop's report, gated on §12 governance: with backpressure
     *  OFF (the COMPOSED switch — global #71 and the bridge key) or WEDGE-degraded
     *  (stream flowing, writer stuck — a report would churn re-serves into the
     *  same drop), drops stay silent. §12.8 dropped the old {@code pumpDrainable}
     *  conjunct: a blocked-not-wedged overflow IS reported — the halt the blocked
     *  pump is now reporting defers the re-declaration until after the burst, so
     *  the re-serve lands in a draining queue instead of a churn loop. */
    private void reportDroppedIfGoverned(Object dimension, int chunkX, int chunkZ) {
        if (!this.backpressureEnabled.getAsBoolean() || this.haltWedged) {
            return;
        }
        reportDropped(dimension, chunkX, chunkZ);
    }

    /** Drop the whole queue, unreported (teardowns, toggles, paused-state clears —
     *  §12.1: reporting those would either race a teardown or churn re-serves into
     *  a refusal). @return how many entries were dropped. */
    int clearQueue() {
        synchronized (this.queueLock) {
            int n = this.queue.size();
            this.queue.clear();
            this.queuedBytes = 0;
            updateOccupancyLocked();
            return n;
        }
    }

    /** The WORLD-ID-change clear (§12.1(c)): the queued tiles belong to a previous
     *  world, and the player may return — each position's report is COLLECTED into
     *  {@link #deferredReports} (the caller sits inside Xaero's renderPause
     *  monitor; the pump drains outside it) so its stamp is forgotten and a
     *  return re-declares it. Accepted churn: a same-dimension world change
     *  un-stamps up to a queue's worth (~3k) and re-downloads it — the new map
     *  needs those tiles; ~4 s of re-serves, rare event (recorded §12.6). */
    int clearQueueCollectingReports() {
        synchronized (this.queueLock) {
            int n = this.queue.size();
            for (var e : this.queue.entrySet()) {
                long k = e.getKey();
                this.deferredReports.add(new Object[]{e.getValue().dimension,
                        (int) (k >> 32), (int) k});
            }
            this.queue.clear();
            this.queuedBytes = 0;
            updateOccupancyLocked();
            return n;
        }
    }

    /**
     * Remove only if the entry AND its tile are still the ones this pump pass
     * examined — a plain remove would silently delete a fresher tile (or a
     * replacement Entry) the decode thread installed mid-commit (review MINOR:
     * the latest-wins guarantee must survive the commit window). Returns whether
     * the removal actually happened, so drop counters count DROPS, not attempts
     * (3-Opus fold: a survived entry must not re-count every pump).
     */
    private boolean removeIfCurrent(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile) {
        synchronized (this.queueLock) {
            var current = this.queue.get(key);
            if (current == entry && entry.tile == tile) {
                this.queuedBytes -= entry.bytes;
                this.queue.remove(key);
                updateOccupancyLocked();
                return true;
            }
            return false;
        }
    }

    int queuedForTest() {
        synchronized (this.queueLock) {
            return this.queue.size();
        }
    }

    boolean hasQueuedForTest(int chunkX, int chunkZ) {
        synchronized (this.queueLock) {
            return this.queue.containsKey(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }
    }

    long queuedBytesForTest() {
        synchronized (this.queueLock) {
            return this.queuedBytes;
        }
    }

    boolean deadForTest() {
        return this.dead;
    }

    boolean drainableForTest() {
        return this.pumpDrainable;
    }

    int regionsWaitingForTest() {
        return this.regionsWaiting;
    }

    boolean registeredForTest() {
        return this.registered;
    }

    long counterForTest(String name) {
        return switch (name) {
            case "written" -> this.written.get();
            case "skipped_native" -> this.skippedNative.get();
            case "defer_events" -> this.deferEvents.get();
            case "dropped_overflow" -> this.droppedOverflow.get();
            case "dropped_stale" -> this.droppedStale.get();
            case "dropped_expired" -> this.droppedExpired.get();
            case "commit_failures" -> this.commitFailures.get();
            case "load_requests" -> this.loadRequests.get();
            case "buffer_updates" -> this.bufferUpdates.get();
            case "frame_flushes" -> this.frameFlushes.get();
            case "rebuild_nanos_total" -> this.rebuildNanos.get();
            case "rebuild_nanos_max" -> this.rebuildNanosMax;
            case "drops_reported" -> this.dropsReported.get();
            case "dropped_updates" -> this.droppedUpdates.get();
            case "dropped_unloaded" -> this.droppedUnloaded.get();
            case "skipped_settings" -> this.skippedSettings.get();
            case "cave_layer_waits" -> this.caveLayerWaits.get();
            case "xaero_crashed" -> this.xaeroCrashed ? 1 : 0;
            case "pending_updates" -> this.pendingUpdatesGauge;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** The §12 tri-state governance token: {@code off} (kill switch),
     *  {@code -1(reason)} (no signal — paused/wedged/stale), or the live
     *  occupancy fraction (governing). */
    private String bpToken() {
        if (!this.backpressureEnabled.getAsBoolean()) return "off";
        if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
            return "-1(inactive)";
        }
        if (this.haltWedged) return "-1(wedged)";
        if (nowMillis() - this.lastPumpMillis > this.bpPumpStaleMillis) return "-1(stale)";
        String f = String.format(java.util.Locale.ROOT, "%.2f", this.occupancy);
        return this.pumpDrainable ? f : f + "(blocked)"; // §12.8: blocked still governs
    }

    String describe() {
        String state = this.dead ? "dead" : this.enabled.getAsBoolean() ? "active" : "disabled";
        long dropped = this.droppedOverflow.get() + this.droppedStale.get()
                + this.droppedExpired.get();
        return "XaeroMap: state=" + state + ", queued=" + queuedForTest()
                + ", written=" + this.written.get()
                + ", skipped_native=" + this.skippedNative.get()
                + ", defer_events=" + this.deferEvents.get()
                + ", dropped=" + dropped
                + ", dropped_overflow=" + this.droppedOverflow.get()
                + ", dropped_stale=" + this.droppedStale.get()
                + ", dropped_expired=" + this.droppedExpired.get()
                + ", commit_failures=" + this.commitFailures.get()
                + ", load_requests=" + this.loadRequests.get()
                + ", regions_waiting=" + this.regionsWaiting
                + ", buffer_updates=" + this.bufferUpdates.get()
                + ", frame_flushes=" + this.frameFlushes.get()
                + ", rebuild_ms=" + (this.rebuildNanos.get() / 1_000_000)
                + ", rebuild_max_us=" + (this.rebuildNanosMax / 1_000)
                + ", pending_updates=" + this.pendingUpdatesGauge
                + ", dropped_updates=" + this.droppedUpdates.get()
                + ", dropped_unloaded=" + this.droppedUnloaded.get()
                + ", skipped_settings=" + this.skippedSettings.get()
                + ", cave_layer_waits=" + this.caveLayerWaits.get()
                + ", drops_reported=" + this.dropsReported.get()
                + ", bp=" + bpToken()
                + (this.xaeroCrashed ? ", xaero_crashed=true" : "")
                + (this.settingsGateBroken ? ", settings_gate=broken" : "")
                + (this.h.optionalMissing != null ? ", optional_unbound=" + this.h.optionalMissing : "");
    }

    // ---- the pump (main client thread) ----

    void pump() {
        if (this.sessionEndPending) settleSessionEnd();
        maybeRegister();
        if (this.dead) {
            // A dead bridge must not pin Xaero's regions/tile chunks (each leaf
            // texture holds a direct buffer) for the rest of the session (review B).
            if (!this.pendingUpdates.isEmpty()) {
                this.pendingUpdates.clear();
                this.pendingUpdatesGauge = 0;
            }
            this.undrainablePumps = 0; // no ladder ran: the consecutive chain breaks
            return;
        }
        this.pumpCount++;
        this.lastPumpMillis = nowMillis(); // §12 watchdog: the pump machinery is alive
        // §17.1 (review fold): the frame marker is consumed HERE, once per pump — and
        // the interval allowance / frame-scarcity meters re-arm.
        this.frameActiveThisPump = this.frameFlushRan;
        this.frameFlushRan = false;
        this.rebuildSpentSinceLastPumpNanos = 0;
        this.framesSinceLastPump = 0;
        if (!this.enabled.getAsBoolean()) {
            clearQueue(); // the live toggle: flipping off drops the backlog immediately
            // ...but rebuilds already OWED to committed tile chunks still flush —
            // dropping them would leave written tiles invisible until a reload.
            if (this.pendingUpdates.isEmpty()) return;
        } else if (this.pumpDrainable) {
            // §12 deadlock guard (rekeyed by §12.8 on the drainable latch): while
            // the latch is down, the LADDER run is the only thing that can observe
            // "drainable again" and clear it — the idle fast-out must not bypass
            // it (the ladder is ~10 reflective reads; cheap at 20 Hz).
            synchronized (this.queueLock) {
                if (this.queue.isEmpty() && this.pendingUpdates.isEmpty()) {
                    this.regionsWaiting = 0;
                    this.undrainablePumps = 0; // idle: gate flaps here are meaningless
                    return;
                }
            }
        }
        try {
            // No blanket failure-count reset here: commit failures are contained per
            // entry inside the drain, so the ladder returning normally proves nothing —
            // only a successful COMMIT resets the death-latch count.
            pumpLadder();
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
        } finally {
            drainDeferredReports(); // outside every Xaero monitor
        }
    }

    /** Drain the ladder-collected reports (main thread, no monitors held). */
    private void drainDeferredReports() {
        if (this.deferredReports.isEmpty()) return;
        for (var e : this.deferredReports) {
            reportDropped(e[0], (Integer) e[1], (Integer) e[2]);
        }
        this.deferredReports.clear();
    }

    /**
     * The native {@code MapWriter.onRender} gate ladder, verbatim (plan §2.7). Any
     * not-ready gate returns — entries stay queued (deferral, not deletion; the
     * bounded queue is the TTL). The {@code mainStuffSync} dimension equality is
     * THE anti-wrong-dimension binding: like Xaero's own writer, commits pause
     * while the user browses another dimension's map.
     */
    private void pumpLadder() throws Throwable {
        boolean reached = false;
        try {
            reached = pumpLadderInner();
        } finally {
            // §12: the drainable latch is DERIVED from the outcome — every early
            // return (and any future one) reads as not-draining by construction; a
            // throwing ladder too. Hysteresis absorbs per-pump gate flaps; the two
            // thresholds separate governance (-1 fast) from refusal (slow).
            if (reached) {
                this.undrainablePumps = 0;
                this.pumpDrainable = true;
            } else {
                int n = ++this.undrainablePumps;
                if (n >= this.bpPausePumps) this.pumpDrainable = false;
            }
        }
    }

    private boolean pumpLadderInner() throws Throwable {
        Object session = this.h.getCurrentSession.invoke();
        if (session == null || !(boolean) this.h.sessionIsUsable.invoke(session)) return false;
        Object mp = this.h.getMapProcessor.invoke(session);
        if (mp == null) return false;
        if (this.h.crashGate != null) {
            // The native writer's FIRST gate (MapWriter.onRender pc 4-10): never touch a
            // Xaero that has latched an internal crash (plan §16, sweep A).
            Object handler = this.h.crashGate.crashHandler().invoke();
            if (handler != null && this.h.crashGate.getCrashedBy().invoke(handler) != null) {
                this.xaeroCrashed = true;
                return false;
            }
        }
        this.xaeroCrashed = false;
        Object renderPause = this.h.renderThreadPauseSync.invoke(mp);
        synchronized (renderPause) {
            if ((boolean) this.h.isWritingPaused.invoke(mp)) return false;
            if ((boolean) this.h.isWaitingForWorldUpdate.invoke(mp)) return false;
            Object saveLoad = this.h.getMapSaveLoad.invoke(mp);
            if (!(boolean) this.h.isRegionDetectionComplete.invoke(saveLoad)) return false;
            if (!(boolean) this.h.isCurrentMultiworldWritable.invoke(mp)) return false;
            Object world = this.h.getWorld.invoke(mp);
            Object mapWorld = this.h.getMapWorld.invoke(mp);
            if (world == null || (boolean) this.h.isCurrentMapLocked.invoke(mp)
                    || (boolean) this.h.isCacheOnlyMode.invoke(mapWorld)) {
                return false;
            }
            String worldId = (String) this.h.getCurrentWorldId.invoke(mp);
            if (worldId == null || (boolean) this.h.ignoreWorld.invoke(mp, world)) {
                return false;
            }
            if (this.lastWorldId != null && !this.lastWorldId.equals(worldId)) {
                // Xaero moved to another world under a live LSS session (the
                // reconfiguration residual): the queued tiles are the OLD world's.
                // §12.1(c): reported — the stamps must be forgotten or a return to
                // that world never re-declares them.
                this.droppedStale.addAndGet(clearQueueCollectingReports());
            }
            this.lastWorldId = worldId;
            Object dimensionId;
            Object mainSync = this.h.mainStuffSync.invoke(mp);
            synchronized (mainSync) {
                if (this.h.mainWorld.invoke(mp) != world) return false;
                dimensionId = this.h.getCurrentDimensionId.invoke(mapWorld);
                if (this.levelOps.dimension(world) != dimensionId) return false;
            }
            // The user's own map-writing switches, read exactly as the native ladder reads
            // them (plan §16, sweep A): "Load New Chunks" gates NEW tiles, "Update Chunks"
            // gates rewrites of EXISTING ones (writeChunk's per-chunk checks); both off =
            // the native ladder returns — ours drops the backlog (the toggle semantics; a
            // re-serve refills it when writing is switched back on). Unresolvable on this
            // Xaero (optional surface), an unexpected value shape, or a throwing read = both
            // OPEN, i.e. the pre-§16 behavior — a gate must never fail closed or latch the
            // bridge dead (review). Sits AFTER the dimension gates: the both-off flush below
            // must run under the same anti-wrong-dimension binding as every other pump.
            boolean loadNew = true;
            boolean update = true;
            if (this.h.settingsGate != null && !this.settingsGateBroken) {
                try {
                    var g = this.h.settingsGate;
                    Object manager = g.getClientConfigManager().invoke(g.getConfigs().invoke(g.instance().invoke()));
                    Object l = g.getEffective().invoke(manager, g.loadNewChunks().invoke());
                    Object u = g.getEffective().invoke(manager, g.updateChunks().invoke());
                    // Native ORs the dimension's world-save mode (singleplayer — LSS
                    // reaches it through the LAN hook) into BOTH switches (onRender
                    // pc 679-733) and its both-off return excludes it too.
                    boolean worldSave = (boolean) g.isUsingWorldSave().invoke(
                            g.getCurrentDimension().invoke(mapWorld));
                    loadNew = !(l instanceof Boolean b) || b || worldSave;
                    update = !(u instanceof Boolean b) || b || worldSave;
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                    this.settingsGateBroken = true;
                    loadNew = true;
                    update = true;
                    LSSLogger.warn("Xaero map bridge: reading Xaero's map-writing switches failed —"
                            + " treating both as on for this session", t);
                }
                if (!loadNew && !update) {
                    // §12.1(b): a paused-state clear, unreported (reporting would
                    // churn re-serves into the refusal that follows) — the paused
                    // latch + offer refusal take over from here.
                    this.skippedSettings.addAndGet(clearQueue());
                    this.regionsWaiting = 0;
                    tickFlush(mp, dimensionId);
                    return false;
                }
            }
            tickFlush(mp, dimensionId);
            if (this.dead) return false;
            if (this.h.getCurrentCaveLayer != null
                    && (int) this.h.getCurrentCaveLayer.invoke(mp) != SURFACE_LAYER) {
                // The map is showing a cave layer: our surface-layer writes would be
                // invisible and still cost regions/loads/saves — wait (entries retained;
                // the bounded queue is the TTL), owed rebuilds above still ran.
                this.caveLayerWaits.incrementAndGet();
                this.regionsWaiting = 0;
                return false;
            }
            drainEntries(mp, saveLoad, world, dimensionId, loadNew, update);
            return true;
        }
    }

    /**
     * Per-frame flush (plan §17/§17.1): the texture-rebuild phase runs at FRAME
     * cadence — Xaero's own sweep scheduling — instead of bunched on the client
     * tick, at most a pressure-capped few recolors per call (base
     * {@link #frameMaxRebuilds}; scarce-frame bumps; allowance-bounded). Mirrors the
     * pump ladder's gate envelope exactly down to the dimension equality (a rebuild
     * must never run under weaker gates than the pump's flush did); the settings and
     * cave-layer gates sit BELOW the flush in the pump ladder (rebuilds are owed
     * debt to already-committed tile chunks, not new writes) and are skipped here
     * for the same reason. Never drains commits, never grants loads, never visits
     * regions (tick-side — the 1 s park guard needs only pump cadence), and defers
     * the world-change queue drop to the pump (it returns instead). Shares the
     * pump's containment + death latch.
     */
    void frameFlush() {
        if (this.dead || this.sessionEndPending || this.pendingUpdates.isEmpty()) return;
        this.framesSinceLastPump++;
        // §17.1 fast-outs, both arming the stand-down marker WITHOUT the reflective
        // ladder (safe: a tick flush with nothing due — or after this interval's
        // allowance was spent on real recolors — is a no-op either way):
        // (1) nothing can be due yet;
        if (nothingDueAtHead()) {
            this.frameFlushRan = true;
            return;
        }
        // (2) the interval's allowance is spent — but only after a REAL recolor this
        // interval (spent == 0 must fall through, or a degenerate zero budget would
        // stand the tick down forever and void the always-drains exemption).
        if (this.rebuildSpentSinceLastPumpNanos > 0
                && this.rebuildSpentSinceLastPumpNanos >= rebuildBudgetWithBorrow()) {
            this.frameFlushRan = true;
            return;
        }
        try {
            frameLadder();
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
        }
    }

    /** True when no owed rebuild can be due this pump, judged from the HEAD entry
     *  (touch order makes it the oldest last-touch, so its idle window binds first)
     *  — the frame slice's cheap pre-ladder skip for the ~2 s coalescing window
     *  (§17.1). The age/stall legs also read only the head: a non-head entry due by
     *  age or stall waits at most one idle window extra — accepted slack. */
    private boolean nothingDueAtHead() {
        if (this.pendingUpdates.size() > this.pendingUpdatesSoftCap) return false;
        var head = this.pendingUpdates.values().iterator().next();
        return this.pumpCount - head.lastTouchPump < this.updateIdlePumps
                && this.pumpCount - head.firstTouchPump < this.updateMaxDeferPumps
                && head.stalledSincePump < 0;
    }

    private void frameLadder() throws Throwable {
        Object session = this.h.getCurrentSession.invoke();
        if (session == null || !(boolean) this.h.sessionIsUsable.invoke(session)) return;
        Object mp = this.h.getMapProcessor.invoke(session);
        if (mp == null) return;
        if (this.h.crashGate != null) {
            Object handler = this.h.crashGate.crashHandler().invoke();
            if (handler != null && this.h.crashGate.getCrashedBy().invoke(handler) != null) {
                return; // never touch a crashed Xaero; the pump owns the diag flag
            }
        }
        Object renderPause = this.h.renderThreadPauseSync.invoke(mp);
        synchronized (renderPause) {
            if ((boolean) this.h.isWritingPaused.invoke(mp)) return;
            if ((boolean) this.h.isWaitingForWorldUpdate.invoke(mp)) return;
            if (!(boolean) this.h.isRegionDetectionComplete.invoke(this.h.getMapSaveLoad.invoke(mp))) return;
            if (!(boolean) this.h.isCurrentMultiworldWritable.invoke(mp)) return;
            Object world = this.h.getWorld.invoke(mp);
            Object mapWorld = this.h.getMapWorld.invoke(mp);
            if (world == null || (boolean) this.h.isCurrentMapLocked.invoke(mp)
                    || (boolean) this.h.isCacheOnlyMode.invoke(mapWorld)) {
                return;
            }
            String worldId = (String) this.h.getCurrentWorldId.invoke(mp);
            if (worldId == null || (boolean) this.h.ignoreWorld.invoke(mp, world)) return;
            if (this.lastWorldId != null && !this.lastWorldId.equals(worldId)) return;
            Object dimensionId;
            Object mainSync = this.h.mainStuffSync.invoke(mp);
            synchronized (mainSync) {
                if (this.h.mainWorld.invoke(mp) != world) return;
                dimensionId = this.h.getCurrentDimensionId.invoke(mapWorld);
                if (this.levelOps.dimension(world) != dimensionId) return;
            }
            // Past every gate the pump's flush would have run under: the tick's
            // rebuild fallback stands down until the next pump. §17.1: the per-frame
            // cap grows under backlog pressure ONLY while frames are scarce (a long
            // frame absorbs a few recolors; at high fps one per frame already outruns
            // the serve rate), and the flush budget is the interval allowance's
            // remainder, so a multi-rebuild frame stays inside the wall rate the
            // tick fallback would have paid.
            this.frameFlushRan = true;
            this.frameFlushes.incrementAndGet();
            int pending = this.pendingUpdates.size();
            boolean scarce = this.framesSinceLastPump <= 1;
            int cap = this.frameMaxRebuilds
                    + (scarce && pending > this.pendingUpdatesSoftCap ? 1 : 0)
                    + (scarce && pending > this.pendingUpdatesHardCap / 2 ? 1 : 0);
            long remaining = Math.max(1L,
                    rebuildBudgetWithBorrow() - this.rebuildSpentSinceLastPumpNanos);
            long rebuildNanosBefore = this.rebuildNanos.get();
            flushPendingUpdates(mp, dimensionId, remaining, cap, false);
            this.rebuildSpentSinceLastPumpNanos += this.rebuildNanos.get() - rebuildNanosBefore;
        }
    }

    /** One queue entry paired with its key for the bucketed drain. */
    private record Pending(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile) {}

    /** A region probed this pump whose bucket is awaiting its Xaero load — the
     *  verdict is Xaero's own state, read inside the probe's region monitor. */
    private record WaitingRegion(long regionKey, int tiles, Outcome verdict) {}

    /**
     * The bucketed drain (the region-throughput round, plan §14 as reshaped by the
     * 3-Opus fold). ONE queue-lock snapshot, then a pure-arithmetic grouping by
     * Xaero MAP REGION (32×32 chunks — Xaero's consent granularity: no tile may
     * commit until its region's save file is loaded; the old per-entry re-fetch
     * paid a lock acquisition per queued entry and ran the chunk-lookup filters
     * OUTSIDE the nanos budget — the live-lock MAJOR). Then: COMMIT phase over
     * region buckets (rotated — the IncomingRequestRouter M4 precedent), the
     * stale-dimension/natively-writable filters running per entry INSIDE the
     * budgeted loop, probing each region ONCE per pump and short-circuiting its
     * whole bucket on a region-scoped not-ready outcome (at large radius a spiral
     * ring crosses ~r/4 regions, and per-entry probing burned the budget on
     * thousands of identical awaiting-load answers); then the GRANT phase
     * ({@link #grantLoads}). The budget check is skipped until the pump has made
     * at least ONE unit of progress (a drop or a commit attempt), so even a
     * degenerate budget drains the queue over pumps instead of live-locking.
     */
    private void drainEntries(Object mp, Object saveLoad,
                              Object world, Object dimensionId,
                              boolean loadNew, boolean update) throws Throwable {
        long start = System.nanoTime();

        List<Pending> snapshot;
        synchronized (this.queueLock) {
            snapshot = new ArrayList<>(this.queue.size());
            for (var e : this.queue.entrySet()) {
                snapshot.add(new Pending(e.getKey(), e.getValue(), e.getValue().tile));
            }
        }
        var buckets = new LinkedHashMap<Long, List<Pending>>(); // keeps spiral locality
        for (var pending : snapshot) {
            long regionKey = (((long) (pending.tile().chunkX() >> 5)) << 32)
                    | ((pending.tile().chunkZ() >> 5) & 0xFFFFFFFFL);
            buckets.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(pending);
        }

        var bucketKeys = new ArrayList<>(buckets.keySet());
        var waiting = new ArrayList<WaitingRegion>();
        int commits = 0;
        boolean progressed = false;
        int size = bucketKeys.size();
        int startIndex = size == 0 ? 0 : Math.floorMod(this.drainRotation++, size);
        boolean capped = false;
        bucketLoop:
        for (int n = 0; n < size; n++) {
            Long regionKey = bucketKeys.get((startIndex + n) % size);
            var bucket = buckets.get(regionKey);
            for (var pending : bucket) {
                if (this.pendingUpdates.size() >= this.pendingUpdatesHardCap) {
                    // Owed rebuilds at the hard cap (plan §15): commits pause until
                    // the flush drains — the set must never grow without bound.
                    capped = true;
                    break bucketLoop;
                }
                if (progressed && (commits >= MAX_COMMITS_PER_PUMP
                        || System.nanoTime() - start > this.pumpNanosBudget)) {
                    break bucketLoop;
                }
                if (pending.entry().dimension != dimensionId) {
                    // Can never become valid — the pump-side stale-dimension drop (§2.5).
                    if (removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        this.droppedStale.incrementAndGet();
                        // §12.1(c): report (deferred out of the monitor) — the
                        // re-serve lands after the player returns to that dimension.
                        this.deferredReports.add(new Object[]{pending.entry().dimension,
                                pending.tile().chunkX(), pending.tile().chunkZ()});
                    }
                    progressed = true;
                    continue;
                }
                if (nativelyWritable(world, pending.tile().chunkX(), pending.tile().chunkZ())) {
                    // The native writer owns these chunks and rewrites them on its
                    // clean-flag anyway — never fight it (plan §2.6).
                    if (removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        this.skippedNative.incrementAndGet();
                    }
                    progressed = true;
                    continue;
                }
                progressed = true;
                var outcome = commitEntry(mp, dimensionId, pending.tile(), loadNew, update);
                switch (outcome) {
                    case COMMITTED -> {
                        removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        this.written.incrementAndGet();
                        this.consecutiveFailures = 0;
                        commits++;
                    }
                    case DEFERRED_TILE -> {
                        // TILE-CHUNK-scoped busy (its 4×4 loadState / PBO download):
                        // the region is fine, so siblings keep committing and only
                        // THIS entry's patience burns. Expiry is SILENT (counted —
                        // §12 review MAJOR: reporting a defer expiry burns the
                        // client's 3 ingest strikes at ~one per DEFER_CAP interval
                        // against a stalled resource and parks the position — the
                        // deleted §18 header's own indictment; the silent hole
                        // heals by revisit or clearcache).
                        this.deferEvents.incrementAndGet();
                        if (++pending.entry().ladderReadyDeferrals > this.deferCap
                                && removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                            this.droppedExpired.incrementAndGet();
                        }
                    }
                    case DEFERRED -> {
                        // REGION-scoped busy (being saved / not resting): CAP-EXEMPT
                        // like the AWAITING_* flavors (§12 review MAJOR — the
                        // ledger's hold-until-committable semantic, via the queue
                        // itself): the bucket is RETAINED until the region rests;
                        // a genuinely stuck region freezes occupancy and flows
                        // into the halt time-box's wedge machinery, which is
                        // DESIGNED for it. Foreign-dimension entries still exit
                        // via the per-entry stale filter above each pump.
                        this.deferEvents.incrementAndGet();
                        continue bucketLoop;
                    }
                    case AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT -> {
                        // The whole bucket waits on this region's load: one defer
                        // event per BUCKET per pump, entries stay queued (awaiting-
                        // load is exempt from the deferral cap), and the verdict
                        // feeds the grant phase's memoryless window.
                        this.deferEvents.incrementAndGet();
                        waiting.add(new WaitingRegion(regionKey, bucket.size(), outcome));
                        continue bucketLoop;
                    }
                    case SKIPPED_SETTINGS -> {
                        // The user's Xaero switch refused this tile (new vs existing) —
                        // dropped, counted; a re-serve brings it back when switched on.
                        removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        this.skippedSettings.incrementAndGet();
                    }
                    case FAILED -> {
                        // Possibly entry-specific (a hostile state) — drop it and keep
                        // trying the bucket's siblings unless the latch fired.
                        removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        if (this.dead) return;
                    }
                }
            }
        }
        if (!capped) {
            this.regionsWaiting = waiting.size(); // a capped pass probed nothing: keep the last gauge
        }
        grantLoads(mp, saveLoad, waiting);
    }

    /**
     * The GRANT phase: request Xaero loads for waiting regions, at most
     * {@value #MAX_OUTSTANDING_LOADS} in flight. The window is MEMORYLESS —
     * in-flight regions are recognized each pump from Xaero's own
     * {@code canRequestReload_unsynced()} (false exactly while a request is
     * queued/loading/refreshing), read under the region monitor by the commit
     * probe. No bookkeeping set to leak (3-Opus fold MAJORs): the loader's
     * dead-end load outcomes all come back requestable by themselves — a failed
     * or empty load ends in {@code removeMapRegion}, and the next probe's
     * {@code getLeafMapRegion(create=true)} hands back a FRESH loadState-0
     * region; a cache-only load parks at loadState 3 and is revived via Xaero's
     * own 3→4 transition (the {@code clearRegion} idiom) in
     * {@link #requestRegionLoad}. Requests go to the largest pending clusters,
     * ISSUED smallest-first: the loader drains {@code toLoad.get(0)} against our
     * priority front-inserts (LIFO), so the largest cluster must be the FINAL
     * front-insert to drain first. Cost is bounded: ≤{@value #MAX_OUTSTANDING_LOADS}
     * requestLoad calls per pump (each runs Xaero's main-thread highlight
     * prepare), and in steady state the window self-clocks near the loader's
     * real expensive-load drain rate (~10/s at the 100 ms MapRunner cadence).
     */
    private void grantLoads(Object mp, Object saveLoad, List<WaitingRegion> waiting) {
        int inFlight = 0;
        var candidates = new ArrayList<WaitingRegion>();
        for (var w : waiting) {
            if (w.verdict() == Outcome.AWAITING_IN_FLIGHT) inFlight++;
            else candidates.add(w);
        }
        int budget = MAX_OUTSTANDING_LOADS - inFlight;
        if (budget <= 0 || candidates.isEmpty()) return;
        candidates.sort((a, b) -> Integer.compare(b.tiles(), a.tiles()));
        var chosen = candidates.subList(0, Math.min(budget, candidates.size()));
        for (int i = chosen.size() - 1; i >= 0; i--) {
            if (this.dead) return;
            if (requestRegionLoad(mp, saveLoad, chosen.get(i).regionKey())) {
                this.loadRequests.incrementAndGet();
            }
        }
    }

    /**
     * The native writer's load-request dance for one region (MapWriter:340-348 —
     * region monitor only): setBeingWritten-BEFORE-request is load-bearing (it stops
     * the load drain demoting an empty fresh region), and requestLoad front-inserts
     * with priority (verified: the 2-arg overload passes prioritize=true, which also
     * bypasses the loader's mid-drain add guard). A cache-parked region (loadState 3
     * — the loader's cache-only dead end, where isResting AND canRequestReload are
     * both false forever) is first revived via Xaero's own 3→4 transition (the
     * {@code clearRegion} idiom, the SP-bridge-proven revival), and RESTORED to 3 if
     * the guards still refuse — pending native work owns it. {@code
     * setNextToLoadByViewing} is deliberately NOT called (3-Opus fold): the loader
     * never reads it — it is purely the pacing token of Xaero's four native
     * consumers (writer/minimap/GUI/reloader), and pointing it at a far bridge
     * region vetoed all four for multi-second stretches after each granted region's
     * save; left alone, the native writer's own requests front-insert AHEAD of our
     * batch, which is the right priority. NB: requestLoad is main-thread-only
     * despite its queue-add look — its tail runs a highlight prepare that
     * hard-throws off Minecraft.isSameThread().
     */
    private boolean requestRegionLoad(Object mp, Object saveLoad, long regionKey) {
        try {
            int regionX = (int) (regionKey >> 32);
            int regionZ = (int) regionKey;
            Object region = this.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                    regionX, regionZ, true);
            if (region == null) return false;
            synchronized (region) {
                byte loadState = (byte) this.h.getLoadState.invoke(region);
                if (loadState == 2) return false;
                boolean revived = false;
                if (loadState == 3) {
                    this.h.setLoadState.invoke(region, (byte) 4);
                    revived = true;
                }
                if (!(boolean) this.h.isResting.invoke(region)
                        || !(boolean) this.h.canRequestReload.invoke(region)) {
                    if (revived) this.h.setLoadState.invoke(region, (byte) 3);
                    return false;
                }
                this.h.setBeingWritten.invoke(region, true);
                this.h.requestLoad.invoke(saveLoad, region, "lss-xaero-bridge");
                return true;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return false;
        }
    }

    /**
     * Will the NATIVE writer actually write this chunk? Its edge rule (decompiled
     * {@code writeChunk}) requires the chunk AND all 8 neighbors loaded — so the
     * outermost ring of loaded vanilla chunks is never natively written. Skipping
     * on "loaded" alone left that ring written by NOBODY: a 1-chunk black circle
     * at the vanilla/LOD boundary around every join point (field-tested 2026-08-23
     * — the columns had been served during the join window, and the broad skip
     * threw the tiles away). A loaded-but-edge chunk is bridge-written instead;
     * the native writer reclaims it on its clean-flag once fully surrounded.
     */
    private boolean nativelyWritable(Object world, int chunkX, int chunkZ) {
        if (!this.levelOps.isChunkLoaded(world, chunkX, chunkZ)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0)
                        && !this.levelOps.isChunkLoaded(world, chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- the kept drop reporter (§12.1: the §18 ledger heal is deleted; the
    // immediate report path below is what makes dimension-switch and governed
    // drops self-heal — client stamps persist per dimension, and only
    // reportIngestFailure un-stamps) ----

    /** Report one dropped position for its bounded re-serve. Contained per report
     *  (an LSS-side throw must never feed the XAERO bridge's death latch); never
     *  called under {@link #queueLock}. */
    private void reportDropped(Object dimension, int chunkX, int chunkZ) {
        try {
            this.dropReporter.report(dimension, chunkX, chunkZ);
            this.dropsReported.incrementAndGet();
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            long n = COMMIT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) LSSLogger.warn("Xaero map bridge: a drop report threw (contained)", t);
        }
    }

    private enum Outcome {
        COMMITTED, DEFERRED, DEFERRED_TILE,
        AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT,
        SKIPPED_SETTINGS, FAILED
    }

    /**
     * One entry against its region — the decompiled {@code MapWriter.writeChunk}
     * region discipline: {@code writerThreadPauseSync} + {@code !isWritingPaused()}
     * (the save-race exclusion), the region monitor for load-state/visit/resting,
     * {@code setBeingWritten(true)} set and NEVER cleared by us (save-eligibility —
     * the save path owns the reset), tile-chunk creation with its cache flags, then
     * the pixel commit. Load REQUESTS live in the grant phase
     * ({@link #requestRegionLoad}) — an unloaded region answers an AWAITING flavor
     * here, classified from {@code canRequestReload_unsynced()} + loadState in the
     * same monitor read.
     */
    private Outcome commitEntry(Object mp, Object dimensionId,
                                XaeroTileExtractor.PreparedTile tile,
                                boolean loadNew, boolean update) {
        try {
            int chunkX = tile.chunkX();
            int chunkZ = tile.chunkZ();
            int tileChunkX = chunkX >> 2;
            int tileChunkZ = chunkZ >> 2;
            int localTcX = tileChunkX & 7;
            int localTcZ = tileChunkZ & 7;
            Object region = this.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                    tileChunkX >> 3, tileChunkZ >> 3, true);
            if (region == null) return Outcome.DEFERRED; // detection-completeness race
            Object writerPause = this.h.writerThreadPauseSync.invoke(region);
            synchronized (writerPause) {
                if ((boolean) this.h.regionIsWritingPaused.invoke(region)) return Outcome.DEFERRED;
                boolean resting;
                boolean createdTileChunk = false;
                Object tileChunk = null;
                synchronized (region) {
                    byte loadState = (byte) this.h.getLoadState.invoke(region);
                    boolean proper = loadState == 2;
                    if (proper) this.h.registerVisit.invoke(region);
                    resting = (boolean) this.h.isResting.invoke(region);
                    if (resting) {
                        this.h.setBeingWritten.invoke(region, true);
                        if (proper) {
                            tileChunk = this.h.regionGetChunk.invoke(region, localTcX, localTcZ);
                            if (tileChunk == null) {
                                tileChunk = this.h.newMapTileChunk.invoke(region, tileChunkX, tileChunkZ);
                                this.h.regionSetChunk.invoke(region, localTcX, localTcZ, tileChunk);
                                this.h.tileChunkSetLoadState.invoke(tileChunk, (byte) 2);
                                this.h.setAllCachePrepared.invoke(region, false);
                                createdTileChunk = true;
                            }
                        }
                    }
                    if (!proper) {
                        // Fresh regions NEVER self-promote to loadState 2 — the grant
                        // phase requests the load; this entry (and its whole bucket)
                        // just waits, classified from Xaero's own state right here in
                        // the region monitor (the memoryless window's input):
                        // requestable now, cache-parked (needs the 3→4 revival), or
                        // genuinely in flight (queued/loading/refreshing — occupies a
                        // window slot).
                        if ((boolean) this.h.canRequestReload.invoke(region)) {
                            return Outcome.AWAITING_REQUESTABLE;
                        }
                        return loadState == 3 ? Outcome.AWAITING_PARKED
                                : Outcome.AWAITING_IN_FLIGHT;
                    }
                }
                if (!resting || tileChunk == null) return Outcome.DEFERRED;
                if ((int) this.h.tileChunkGetLoadState.invoke(tileChunk) != 2) {
                    return Outcome.DEFERRED_TILE;
                }
                Object leafTexture = this.h.getLeafTexture.invoke(tileChunk);
                if ((boolean) this.h.shouldDownloadFromPBO.invoke(leafTexture)) {
                    return Outcome.DEFERRED_TILE;
                }

                if (commitPixels(mp, dimensionId, region, tileChunk, createdTileChunk,
                        localTcX, localTcZ, tile, loadNew, update)) {
                    return Outcome.COMMITTED;
                }
                if (createdTileChunk) {
                    // The native rollback (writeChunk pc 1526-1537): a tile chunk created
                    // for a write the switches then refused must not stay installed empty
                    // — it would be ~13 KB of texture state, never terrain-marked, and
                    // poisoned for every later pump (sweep B m3).
                    synchronized (region) {
                        this.h.regionSetChunk.invoke(region, localTcX, localTcZ, null);
                    }
                }
                return Outcome.SKIPPED_SETTINGS;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return Outcome.FAILED;
        }
    }

    /** The decompiled per-tile commit sequence, verbatim order (plan §1); false when the
     *  native per-chunk switches refuse the tile (writeChunk pc 577-604: a NEW tile needs
     *  "Load New Chunks", an EXISTING one "Update Chunks" — the tile chunk creation before
     *  this point mirrors the native order too). */
    private boolean commitPixels(Object mp, Object dimensionId, Object region, Object tileChunk,
                                 boolean createdTileChunk, int localTcX, int localTcZ,
                                 XaeroTileExtractor.PreparedTile tile,
                                 boolean loadNew, boolean update) throws Throwable {
        int insideX = tile.chunkX() & 3;
        int insideZ = tile.chunkZ() & 3;
        Object mapTile = this.h.getTile.invoke(tileChunk, insideX, insideZ);
        if (mapTile == null ? !loadNew : !update) {
            return false;
        }
        if (mapTile == null) {
            Object pool = this.h.getTilePool.invoke(mp);
            String dimensionToken = (String) this.h.getCurrentDimension.invoke(mp);
            mapTile = this.h.poolGet.invoke(pool, dimensionToken, tile.chunkX(), tile.chunkZ());
            this.h.tileChunkSetChanged.invoke(tileChunk, true);
        }
        Object overlayManager = this.h.getOverlayManager.invoke(mp);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = x * 16 + z;
                Object block = this.h.newMapBlock.invoke();
                this.h.prepareForWriting.invoke(block, tile.worldBottomY());
                var runs = tile.overlays()[i];
                if (runs != null) {
                    for (var run : runs) {
                        // Overlay.getParametres packs light << 4 unmasked too (sweep B m7).
                        byte runLight = (byte) Math.max(0, Math.min(15, run.light()));
                        Object overlay = this.h.newOverlay.invoke(run.state(), runLight, run.glowing());
                        this.h.increaseOpacity.invoke(overlay, run.opacity());
                        Object original = this.h.getOriginal.invoke(overlayManager, overlay);
                        this.h.addOverlay.invoke(block, original);
                    }
                }
                // Light must stay 0..15: MapBlock.getParametres packs it UNMASKED next to
                // the height bits and the loader masks on read — an out-of-range value
                // would be a one-way file corruption (sweep B m7). The extractor already
                // delivers a nibble; the clamp is the belt.
                byte light = (byte) Math.max(0, Math.min(15, tile.light()[i]));
                this.h.blockWrite.invoke(block, tile.floorState()[i],
                        (int) tile.floorY()[i], (int) tile.topY()[i],
                        tile.biome()[i], light, tile.glowing()[i], false);
                this.h.setBlock.invoke(mapTile, x, z, block);
            }
        }
        this.h.setWorldInterpretationVersion.invoke(mapTile, this.h.interpretationVersion);
        this.h.setWrittenCave.invoke(mapTile, SURFACE_LAYER,
                (int) this.h.getCaveModeDepthConfig.invoke(mp));
        this.h.tileChunkSetChanged.invoke(tileChunk, true);
        this.h.setTile.invoke(tileChunk, insideX, insideZ, mapTile,
                this.h.getBlockStateShortShapeCache.invoke(mp), mp);
        this.h.setWrittenOnce.invoke(mapTile, true);
        this.h.setLoaded.invoke(mapTile, true);
        if (createdTileChunk) {
            if ((boolean) this.h.includeInSave.invoke(tileChunk)) {
                this.h.setHasHadTerrain.invoke(tileChunk);
            }
            Object highlights = this.h.getMapRegionHighlightsPreparer.invoke(mp);
            this.h.highlightsPrepare.invoke(highlights, region, localTcX, localTcZ, false);
        }
        // The native writer ends a write by rebuilding the tile chunk's texture
        // INSIDE this gate (updateBuffers, then setChanged(false)); ours is
        // coalesced per tile chunk (plan §15) — the change stays marked and the
        // rebuild runs from flushPendingUpdates under these same gates. NEVER the
        // setToUpdateBuffers flag: Xaero's preUpload sweep consumes it with no
        // isResting() check, i.e. possibly after the region was queued for
        // cache-saving on prepared textures — the saver then throws ("Trying to
        // save cache for a region with cache not prepared", 3 crashes/hour live).
        notePendingUpdate(mp, dimensionId, region, tileChunk, localTcX, localTcZ,
                tile.chunkX() >> 2, tile.chunkZ() >> 2);
        return true;
    }

    // ---- the rebuild phase (plan §15) ----

    private void notePendingUpdate(Object mp, Object dimensionId, Object region, Object tileChunk,
                                   int localTcX, int localTcZ, int tileChunkX, int tileChunkZ)
            throws Throwable {
        var key = new PendingKey(dimensionId, ((long) tileChunkX << 32) | (tileChunkZ & 0xFFFFFFFFL));
        var existing = this.pendingUpdates.remove(key); // re-insert at the tail = last touch
        if (existing != null && existing.tileChunk == tileChunk) {
            existing.lastTouchPump = this.pumpCount;
            existing.stalledSincePump = -1; // the commit gate just passed: the stall ended
            this.pendingUpdates.put(key, existing);
        } else {
            // A replaced tile chunk (Xaero reloaded the region) gets a FRESH entry —
            // the old object's rebuild would fail its identity check and drop; count
            // the old one now (a reload rebuilds its own textures).
            if (existing != null) this.droppedUnloaded.incrementAndGet();
            this.pendingUpdates.put(key, new PendingUpdate(mp,
                    (String) this.h.getCurrentWorldId.invoke(mp), dimensionId, region, tileChunk,
                    localTcX, localTcZ, this.pumpCount));
        }
        this.pendingUpdatesGauge = this.pendingUpdates.size();
    }

    private enum UpdateResult { DONE, NOT_READY, DROPPED, FAILED }

    /** The per-flush rebuild inputs, resolved once (the native onRender builds one
     *  {@code MapUpdateFastConfig} per pass the same way), plus the per-flush memo
     *  of regions already found not ready — up to 64 tile chunks share one region
     *  and one verdict, and re-taking the writer-pause + region monitors per tile
     *  chunk is the per-entry-probe pattern plan §14 removed (review B MAJOR). */
    private static final class RebuildArgs {
        Object tint;
        Object overlayManager;
        Object shapeCache;
        Object fastConfig;
        int rebuilt; // actual updateBuffers calls this flush (the frame cap's meter)
        int probes; // rebuildTileChunk entries past the memo — the scan-bound meter
        final java.util.Set<Object> notReadyRegions =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    /**
     * Run the owed rebuilds that are DUE — idle for {@link #updateIdlePumps}, or
     * older than {@link #updateMaxDeferPumps} (the trickle ceiling), or the oldest
     * beyond the soft cap, or previously stalled — oldest-touch first, within the
     * caller's {@code budget} and {@code maxRebuilds} (plan §17: the FRAME slice
     * passes {@link #frameMaxRebuilds} with no region visits; the tick fallback
     * passes the borrow-topped budget with no rebuild cap; a frames-active tick
     * passes ZERO rebuilds — cheap drops/bookkeeping only — and the first removing
     * outcome is budget-exempt, so the set always drains). Each rebuild re-runs the writer's region gates
     * ({@code writerThreadPauseSync} + {@code !isWritingPaused()}, the region
     * monitor, {@code isResting()}) — ONCE per region per flush for a not-ready
     * verdict (memoized, no budget): a not-resting region (being saved / cache
     * pending — exactly the state the flag-consuming sweep ignored) keeps its
     * entries for a later pump, as does another dimension's region (the pixel
     * recipe reads the CURRENT dimension's shading; the entries wait for the
     * player's return); either drops after {@link #updateMaxStallPumps}. An
     * unloaded or replaced tile chunk, or an entry from a previous Xaero session
     * (processor identity / world id), drops at once — a reload rebuilds its own
     * textures. A tile chunk the native writer already consumed
     * ({@code !wasChanged()}) needs nothing.
     */
    /** The interval's rebuild allowance: the §15.2 budget-with-borrow math, shared
     *  by the tick fallback and the frame slice's allowance ceiling (§17.1). */
    private long rebuildBudgetWithBorrow() {
        boolean queueEmpty;
        synchronized (this.queueLock) {
            queueEmpty = this.queue.isEmpty();
        }
        long borrow = queueEmpty ? this.updateBorrowNanos
                : this.pendingUpdates.size() > this.pendingUpdatesSoftCap ? this.updateBorrowNanos / 2 : 0;
        return borrow > Long.MAX_VALUE - this.updateNanosBudget
                ? Long.MAX_VALUE : this.updateNanosBudget + borrow; // saturating (the seams take MAX)
    }

    /** The tick pump's flush call: with frames flushing (the marker consumed at the
     *  top of {@link #pump}) the tick runs ZERO rebuilds — drops/bookkeeping only,
     *  never a recolor bunched onto the tick; with no frame since the last pump
     *  (loading screens, hidden window, headless test JVMs) it falls back to the
     *  full §15 budget-with-borrow rebuild behavior. */
    private void tickFlush(Object mp, Object dimensionId) {
        boolean frameActive = this.frameActiveThisPump;
        long budget = frameActive ? this.updateNanosBudget // bounds the cheap-class scan only
                : rebuildBudgetWithBorrow();
        flushPendingUpdates(mp, dimensionId, budget, frameActive ? 0 : Integer.MAX_VALUE, true);
    }

    private void flushPendingUpdates(Object mp, Object dimensionId, long budget,
                                     int maxRebuilds, boolean keepVisited) {
        if (this.pendingUpdates.isEmpty()) {
            this.pendingUpdatesGauge = 0;
            return;
        }
        long start = System.nanoTime();
        var args = new RebuildArgs();
        String worldId;
        try {
            worldId = (String) this.h.getCurrentWorldId.invoke(mp);
            if (keepVisited) keepOwedRegionsVisited(mp, worldId, dimensionId);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return;
        }
        int removed = 0;
        int overflow = this.pendingUpdates.size() - this.pendingUpdatesSoftCap;
        var it = this.pendingUpdates.values().iterator();
        while (it.hasNext()) {
            // The §15 exemption: removing outcomes arm the budget check, and not-ready
            // probes stay FREE up to a small floor (memoized per region, so the floor
            // is distinct regions — the pin: ready work behind a not-ready region must
            // not starve). Past the floor the budget applies even with zero removals
            // (§17.1, review B m4: an all-not-ready set must not walk hundreds of
            // region monitors unbounded at frame cadence on the render thread).
            if ((removed > 0 || args.probes > FLUSH_PROBE_EXEMPT_FLOOR)
                    && System.nanoTime() - start > budget) break;
            var pu = it.next();
            boolean due = overflow-- > 0
                    || this.pumpCount - pu.lastTouchPump >= this.updateIdlePumps
                    || this.pumpCount - pu.firstTouchPump >= this.updateMaxDeferPumps
                    || pu.stalledSincePump >= 0;
            if (!due) continue; // touch order makes idle-due a prefix, but the age ceiling is not
            UpdateResult result;
            if (pu.processor != mp || !java.util.Objects.equals(pu.worldId, worldId)) {
                result = UpdateResult.DROPPED; // a previous Xaero session's objects
            } else if (pu.dimension != dimensionId) {
                result = UpdateResult.NOT_READY;
            } else if (maxRebuilds == 0) {
                continue; // frames own the rebuilds while they flush — cheap classes only
            } else {
                result = rebuildTileChunk(mp, pu, args);
            }
            switch (result) {
                case DONE -> {
                    it.remove();
                    removed++;
                }
                case DROPPED -> {
                    it.remove();
                    removed++;
                    if (pu.processor != mp || !java.util.Objects.equals(pu.worldId, worldId)) {
                        this.droppedUpdates.incrementAndGet(); // the session-identity drop
                    } // else: the rebuild counted dropped_unloaded itself
                }
                case NOT_READY -> {
                    if (pu.stalledSincePump < 0) {
                        pu.stalledSincePump = this.pumpCount;
                    } else if (this.pumpCount - pu.stalledSincePump >= this.updateMaxStallPumps) {
                        it.remove();
                        removed++;
                        this.droppedUpdates.incrementAndGet();
                    }
                }
                case FAILED -> {
                    it.remove();
                    removed++;
                    this.droppedUpdates.incrementAndGet(); // owed, never rebuilt
                }
            }
            if (this.dead) break;
            if (maxRebuilds > 0 && args.rebuilt >= maxRebuilds) break;
        }
        this.pendingUpdatesGauge = this.pendingUpdates.size();
    }

    /**
     * The park guard the flag used to be (review A MAJOR): {@code LeafRegionTexture.
     * postUpload} parks a region — loadState 3, tile chunks {@code clean()}ed, their
     * tiles released — once it is not being written, ONE second has passed since its
     * last visit, and no tile chunk is flagged {@code toUpdateBuffers}. The native
     * writer's flag held that off until the texture was built; ours is never set, so
     * the bridge keeps every region with an owed rebuild VISITED each pump (the
     * writer's own "someone is working here" signal, {@code registerVisit} — what the
     * commit does too), once per region, under the region monitor. Same-session
     * entries only; foreign ones are skipped.
     */
    private void keepOwedRegionsVisited(Object mp, String worldId, Object dimensionId) throws Throwable {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var pu : this.pendingUpdates.values()) {
            if (pu.processor != mp || !java.util.Objects.equals(pu.worldId, worldId)
                    || pu.dimension != dimensionId || !seen.add(pu.region)) {
                continue;
            }
            synchronized (pu.region) {
                if ((byte) this.h.getLoadState.invoke(pu.region) == 2) {
                    this.h.registerVisit.invoke(pu.region);
                }
            }
        }
    }

    private UpdateResult rebuildTileChunk(Object mp, PendingUpdate pu, RebuildArgs args) {
        if (args.notReadyRegions.contains(pu.region)) return UpdateResult.NOT_READY;
        args.probes++; // §17.1: probes past FLUSH_PROBE_EXEMPT_FLOOR arm the budget check
        try {
            Object writerPause = this.h.writerThreadPauseSync.invoke(pu.region);
            synchronized (writerPause) {
                if ((boolean) this.h.regionIsWritingPaused.invoke(pu.region)) {
                    args.notReadyRegions.add(pu.region);
                    return UpdateResult.NOT_READY;
                }
                synchronized (pu.region) {
                    // Region unloaded/parked, tile chunk replaced, or a tile-chunk-only
                    // teardown (deleteTexturesAndBuffers sets ITS loadState 0 without
                    // touching the region's — review A N3): a reload rebuilds its own.
                    if ((byte) this.h.getLoadState.invoke(pu.region) != 2
                            || this.h.regionGetChunk.invoke(pu.region, pu.localTcX, pu.localTcZ)
                            != pu.tileChunk
                            || (int) this.h.tileChunkGetLoadState.invoke(pu.tileChunk) != 2) {
                        this.droppedUnloaded.incrementAndGet();
                        return UpdateResult.DROPPED;
                    }
                    if (!(boolean) this.h.isResting.invoke(pu.region)) {
                        args.notReadyRegions.add(pu.region);
                        return UpdateResult.NOT_READY;
                    }
                    if ((boolean) this.h.tileChunkWasChanged.invoke(pu.tileChunk)) {
                        // A save may have reset beingWritten since the commit; the
                        // rebuilt texture must still reach the region's cache, and
                        // the save path is what requests it (set-never-clear, as
                        // in the commit).
                        this.h.setBeingWritten.invoke(pu.region, true);
                        if (args.fastConfig == null) {
                            args.tint = this.h.getWorldBlockTintProvider.invoke(mp);
                            args.overlayManager = this.h.getOverlayManager.invoke(mp);
                            args.shapeCache = this.h.getBlockStateShortShapeCache.invoke(mp);
                            args.fastConfig = this.h.newMapUpdateFastConfig.invoke(mp);
                        }
                        // The boolean is the writer's detailed-debug flag (log-only).
                        long rebuildStart = System.nanoTime();
                        this.h.tileChunkUpdateBuffers.invoke(pu.tileChunk, mp, args.tint,
                                args.overlayManager, false, args.shapeCache, args.fastConfig);
                        long rebuildTook = System.nanoTime() - rebuildStart;
                        this.rebuildNanos.addAndGet(rebuildTook);
                        if (rebuildTook > this.rebuildNanosMax) this.rebuildNanosMax = rebuildTook;
                        args.rebuilt++;
                        this.h.tileChunkSetChanged.invoke(pu.tileChunk, false);
                        this.bufferUpdates.incrementAndGet();
                    }
                    return UpdateResult.DONE;
                }
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return UpdateResult.FAILED;
        }
    }

    private void noteFailure(Throwable t) {
        this.commitFailures.incrementAndGet();
        long n = COMMIT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (n > 0) {
            LSSLogger.warn("Xaero map bridge: commit failed (" + n
                    + " failure(s) since the last report)", t);
        }
        if (++this.consecutiveFailures >= THROW_LATCH) {
            this.dead = true;
            clearQueue();
            // pendingUpdates is NOT cleared here: noteFailure runs inside the flush's own
            // iteration (a throwing rebuild) — the next pump's dead path clears it.
            LSSLogger.error("Xaero map bridge: " + THROW_LATCH + " consecutive failures — "
                    + "disabling the bridge for this session (LODs are unaffected)", t);
        }
    }

    // ---- the reflective surface (plan §4; all members verified public) ----

    /**
     * Resolve-once handle set. All-or-nothing: any missing member throws and the
     * bridge stays off. Xaero-typed members resolve with exact types from the
     * resolved classes; the three {@code ClientLevel}-typed members
     * ({@code getWorld}, {@code mainWorld}, {@code ignoreWorld}) resolve by
     * name-scan (the {@code MoonriseReadCompat} shape-scan precedent) and are
     * handled as Objects behind {@link LevelOps}, because tests cannot construct
     * a {@code ClientLevel}.
     */
    static final class Handles {
        final MethodHandle getCurrentSession;
        final MethodHandle sessionIsUsable;
        final MethodHandle getMapProcessor;
        final MethodHandle renderThreadPauseSync;
        final MethodHandle mainStuffSync;
        final MethodHandle mainWorld;
        final MethodHandle isWritingPaused;
        final MethodHandle isWaitingForWorldUpdate;
        final MethodHandle isCurrentMapLocked;
        final MethodHandle isCurrentMultiworldWritable;
        final MethodHandle getCurrentWorldId;
        final MethodHandle getCurrentDimension;
        final MethodHandle getWorld;
        final MethodHandle ignoreWorld;
        final MethodHandle getMapWorld;
        final MethodHandle getMapSaveLoad;
        final MethodHandle getLeafMapRegion;
        final MethodHandle getTilePool;
        final MethodHandle getOverlayManager;
        final MethodHandle getBlockStateShortShapeCache;
        final MethodHandle getMapRegionHighlightsPreparer;
        final MethodHandle getCaveModeDepthConfig;
        final MethodHandle isCacheOnlyMode;
        final MethodHandle getCurrentDimensionId;
        final MethodHandle isRegionDetectionComplete;
        final MethodHandle requestLoad;
        final MethodHandle writerThreadPauseSync;
        final MethodHandle regionIsWritingPaused;
        final MethodHandle getLoadState;
        final MethodHandle setLoadState;
        final MethodHandle isResting;
        final MethodHandle registerVisit;
        final MethodHandle setBeingWritten;
        final MethodHandle canRequestReload;
        final MethodHandle setAllCachePrepared;
        final MethodHandle regionGetChunk;
        final MethodHandle regionSetChunk;
        final MethodHandle newMapTileChunk;
        final MethodHandle tileChunkGetLoadState;
        final MethodHandle tileChunkSetLoadState;
        // ---- OPTIONAL surface (plan §16, the compatibility sweep): each group resolves
        // best-effort and is null when this Xaero lacks it — a miss never raises the
        // 1.42.0 floor, it just leaves that gate open (the pre-§16 behavior). ----
        /** {@code WorldMap.crashHandler} + {@code CrashHandler.getCrashedBy()}. */
        record CrashGate(MethodHandle crashHandler, MethodHandle getCrashedBy) {}
        /** The native ladder's settings read: {@code WorldMap.INSTANCE.getConfigs()
         *  .getClientConfigManager().getEffective(WorldMapProfiledConfigOptions.X)} —
         *  the channel/manager classes live in the jarjar'd xaerolib, bound by
         *  name+arity because its version differs per line. */
        record SettingsGate(MethodHandle instance, MethodHandle getConfigs,
                            MethodHandle getClientConfigManager, MethodHandle getEffective,
                            MethodHandle loadNewChunks, MethodHandle updateChunks,
                            MethodHandle getCurrentDimension, MethodHandle isUsingWorldSave) {}
        final CrashGate crashGate;
        final SettingsGate settingsGate;
        /** {@code MapProcessor.getCurrentCaveLayer()} — the layer the map RENDERS
         *  (Integer.MAX_VALUE = the surface). The bridge only ever writes the surface
         *  layer; while Xaero shows a cave layer (auto cave mode underground, the Nether
         *  by default) a write would go to a layer nobody renders yet still create
         *  regions, request loads and force saves (sweeps B m1 + C N1). Null = unbound. */
        final MethodHandle getCurrentCaveLayer;
        /** {@code MapTile.CURRENT_WORLD_INTERPRETATION_VERSION}, read live (a javac literal
         *  would silently lag a bump); 1 when unreadable. */
        final int interpretationVersion;
        /** Which optional groups did not bind, for the diag line; null = all bound. */
        final String optionalMissing;

        final MethodHandle tileChunkSetChanged;
        final MethodHandle tileChunkWasChanged;
        final MethodHandle tileChunkUpdateBuffers;
        final MethodHandle getWorldBlockTintProvider;
        final MethodHandle newMapUpdateFastConfig;
        final MethodHandle setHasHadTerrain;
        final MethodHandle includeInSave;
        final MethodHandle getLeafTexture;
        final MethodHandle shouldDownloadFromPBO;
        final MethodHandle getTile;
        final MethodHandle setTile;
        final MethodHandle poolGet;
        final MethodHandle setBlock;
        final MethodHandle setWorldInterpretationVersion;
        final MethodHandle setWrittenCave;
        final MethodHandle setWrittenOnce;
        final MethodHandle setLoaded;
        final MethodHandle newMapBlock;
        final MethodHandle prepareForWriting;
        final MethodHandle blockWrite;
        final MethodHandle addOverlay;
        final MethodHandle newOverlay;
        final MethodHandle increaseOpacity;
        final MethodHandle getOriginal;
        final MethodHandle highlightsPrepare;

        static Handles resolve(ClassResolver resolver) throws ClassNotFoundException,
                NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
            return new Handles(resolver, MethodHandles.lookup());
        }

        private Handles(ClassResolver resolver, MethodHandles.Lookup lookup)
                throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException,
                IllegalAccessException {
            Class<?> sessionClass = resolver.resolve("xaero.map.WorldMapSession");
            Class<?> processorClass = resolver.resolve("xaero.map.MapProcessor");
            Class<?> saveLoadClass = resolver.resolve("xaero.map.file.MapSaveLoad");
            Class<?> mapWorldClass = resolver.resolve("xaero.map.world.MapWorld");
            Class<?> regionClass = resolver.resolve("xaero.map.region.MapRegion");
            Class<?> tileChunkClass = resolver.resolve("xaero.map.region.MapTileChunk");
            Class<?> tileClass = resolver.resolve("xaero.map.region.MapTile");
            Class<?> blockClass = resolver.resolve("xaero.map.region.MapBlock");
            Class<?> overlayClass = resolver.resolve("xaero.map.region.Overlay");
            Class<?> overlayManagerClass = resolver.resolve("xaero.map.region.OverlayManager");
            Class<?> poolClass = resolver.resolve("xaero.map.pool.MapTilePool");
            Class<?> leafTextureClass = resolver.resolve("xaero.map.region.texture.LeafRegionTexture");
            Class<?> shapeCacheClass = resolver.resolve("xaero.map.cache.BlockStateShortShapeCache");
            Class<?> highlightsClass = resolver.resolve("xaero.map.highlight.MapRegionHighlightsPreparer");
            Class<?> tintProviderClass = resolver.resolve("xaero.map.biome.BlockTintProvider");
            Class<?> fastConfigClass = resolver.resolve("xaero.map.region.MapUpdateFastConfig");

            this.getCurrentSession = lookup.findStatic(sessionClass, "getCurrentSession",
                    MethodType.methodType(sessionClass)).asType(MethodType.methodType(Object.class));
            this.sessionIsUsable = virtual(lookup, sessionClass, "isUsable",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getMapProcessor = virtual(lookup, sessionClass, "getMapProcessor",
                    MethodType.methodType(processorClass), Object.class);

            this.renderThreadPauseSync = getter(lookup, processorClass, "renderThreadPauseSync");
            this.mainStuffSync = getter(lookup, processorClass, "mainStuffSync");
            this.mainWorld = getterByName(lookup, processorClass, "mainWorld");
            this.isWritingPaused = virtual(lookup, processorClass, "isWritingPaused",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isWaitingForWorldUpdate = virtual(lookup, processorClass, "isWaitingForWorldUpdate",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isCurrentMapLocked = virtual(lookup, processorClass, "isCurrentMapLocked",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isCurrentMultiworldWritable = virtual(lookup, processorClass,
                    "isCurrentMultiworldWritable",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getCurrentWorldId = virtual(lookup, processorClass, "getCurrentWorldId",
                    MethodType.methodType(String.class), Object.class);
            this.getCurrentDimension = virtual(lookup, processorClass, "getCurrentDimension",
                    MethodType.methodType(String.class), String.class);
            this.getWorld = methodByName(lookup, processorClass, "getWorld", 0);
            this.ignoreWorld = methodByName(lookup, processorClass, "ignoreWorld", 1);
            this.getMapWorld = virtual(lookup, processorClass, "getMapWorld",
                    MethodType.methodType(mapWorldClass), Object.class);
            this.getMapSaveLoad = virtual(lookup, processorClass, "getMapSaveLoad",
                    MethodType.methodType(saveLoadClass), Object.class);
            this.getLeafMapRegion = lookup.findVirtual(processorClass, "getLeafMapRegion",
                            MethodType.methodType(regionClass, int.class, int.class, int.class, boolean.class))
                    .asType(MethodType.methodType(Object.class, Object.class,
                            int.class, int.class, int.class, boolean.class));
            this.getTilePool = virtual(lookup, processorClass, "getTilePool",
                    MethodType.methodType(poolClass), Object.class);
            this.getOverlayManager = virtual(lookup, processorClass, "getOverlayManager",
                    MethodType.methodType(overlayManagerClass), Object.class);
            this.getBlockStateShortShapeCache = virtual(lookup, processorClass,
                    "getBlockStateShortShapeCache",
                    MethodType.methodType(shapeCacheClass), Object.class);
            this.getMapRegionHighlightsPreparer = virtual(lookup, processorClass,
                    "getMapRegionHighlightsPreparer",
                    MethodType.methodType(highlightsClass), Object.class);
            this.getCaveModeDepthConfig = virtual(lookup, processorClass, "getCaveModeDepthConfig",
                    MethodType.methodType(int.class), int.class);
            this.getWorldBlockTintProvider = virtual(lookup, processorClass,
                    "getWorldBlockTintProvider",
                    MethodType.methodType(tintProviderClass), Object.class);
            this.newMapUpdateFastConfig = lookup.findConstructor(fastConfigClass,
                            MethodType.methodType(void.class, processorClass))
                    .asType(MethodType.methodType(Object.class, Object.class));

            this.isCacheOnlyMode = virtual(lookup, mapWorldClass, "isCacheOnlyMode",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getCurrentDimensionId = virtual(lookup, mapWorldClass, "getCurrentDimensionId",
                    MethodType.methodType(ResourceKey.class), Object.class);

            this.isRegionDetectionComplete = virtual(lookup, saveLoadClass, "isRegionDetectionComplete",
                    MethodType.methodType(boolean.class), boolean.class);
            this.requestLoad = lookup.findVirtual(saveLoadClass, "requestLoad",
                            MethodType.methodType(void.class, regionClass, String.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, String.class));

            this.writerThreadPauseSync = getter(lookup, regionClass, "writerThreadPauseSync");
            this.regionIsWritingPaused = virtual(lookup, regionClass, "isWritingPaused",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getLoadState = virtual(lookup, regionClass, "getLoadState",
                    MethodType.methodType(byte.class), byte.class);
            this.setLoadState = lookup.findVirtual(regionClass, "setLoadState",
                            MethodType.methodType(void.class, byte.class))
                    .asType(MethodType.methodType(void.class, Object.class, byte.class));
            this.isResting = virtual(lookup, regionClass, "isResting",
                    MethodType.methodType(boolean.class), boolean.class);
            this.registerVisit = virtual(lookup, regionClass, "registerVisit",
                    MethodType.methodType(void.class), void.class);
            this.setBeingWritten = lookup.findVirtual(regionClass, "setBeingWritten",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.canRequestReload = virtual(lookup, regionClass, "canRequestReload_unsynced",
                    MethodType.methodType(boolean.class), boolean.class);
            this.setAllCachePrepared = lookup.findVirtual(regionClass, "setAllCachePrepared",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.regionGetChunk = lookup.findVirtual(regionClass, "getChunk",
                            MethodType.methodType(tileChunkClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.regionSetChunk = lookup.findVirtual(regionClass, "setChunk",
                            MethodType.methodType(void.class, int.class, int.class, tileChunkClass))
                    .asType(MethodType.methodType(void.class, Object.class,
                            int.class, int.class, Object.class));

            this.newMapTileChunk = lookup.findConstructor(tileChunkClass,
                            MethodType.methodType(void.class, regionClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.tileChunkGetLoadState = virtual(lookup, tileChunkClass, "getLoadState",
                    MethodType.methodType(int.class), int.class);
            this.tileChunkSetLoadState = lookup.findVirtual(tileChunkClass, "setLoadState",
                            MethodType.methodType(void.class, byte.class))
                    .asType(MethodType.methodType(void.class, Object.class, byte.class));
            this.tileChunkSetChanged = lookup.findVirtual(tileChunkClass, "setChanged",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.tileChunkWasChanged = virtual(lookup, tileChunkClass, "wasChanged",
                    MethodType.methodType(boolean.class), boolean.class);
            this.tileChunkUpdateBuffers = lookup.findVirtual(tileChunkClass, "updateBuffers",
                            MethodType.methodType(void.class, processorClass, tintProviderClass,
                                    overlayManagerClass, boolean.class, shapeCacheClass,
                                    fastConfigClass))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class,
                            Object.class, Object.class, boolean.class, Object.class, Object.class));
            this.setHasHadTerrain = virtual(lookup, tileChunkClass, "setHasHadTerrain",
                    MethodType.methodType(void.class), void.class);
            this.includeInSave = virtual(lookup, tileChunkClass, "includeInSave",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getLeafTexture = virtual(lookup, tileChunkClass, "getLeafTexture",
                    MethodType.methodType(leafTextureClass), Object.class);
            this.shouldDownloadFromPBO = virtual(lookup, leafTextureClass, "shouldDownloadFromPBO",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getTile = lookup.findVirtual(tileChunkClass, "getTile",
                            MethodType.methodType(tileClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.setTile = lookup.findVirtual(tileChunkClass, "setTile",
                            MethodType.methodType(void.class, int.class, int.class, tileClass,
                                    shapeCacheClass, processorClass))
                    .asType(MethodType.methodType(void.class, Object.class, int.class, int.class,
                            Object.class, Object.class, Object.class));

            this.poolGet = lookup.findVirtual(poolClass, "get",
                            MethodType.methodType(tileClass, String.class, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class,
                            String.class, int.class, int.class));
            this.setBlock = lookup.findVirtual(tileClass, "setBlock",
                            MethodType.methodType(void.class, int.class, int.class, blockClass))
                    .asType(MethodType.methodType(void.class, Object.class,
                            int.class, int.class, Object.class));
            this.setWorldInterpretationVersion = lookup.findVirtual(tileClass,
                            "setWorldInterpretationVersion",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.setWrittenCave = lookup.findVirtual(tileClass, "setWrittenCave",
                            MethodType.methodType(void.class, int.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class, int.class));
            this.setWrittenOnce = lookup.findVirtual(tileClass, "setWrittenOnce",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.setLoaded = lookup.findVirtual(tileClass, "setLoaded",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));

            this.newMapBlock = lookup.findConstructor(blockClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            this.prepareForWriting = lookup.findVirtual(blockClass, "prepareForWriting",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.blockWrite = lookup.findVirtual(blockClass, "write",
                            MethodType.methodType(void.class, BlockState.class, int.class, int.class,
                                    ResourceKey.class, byte.class, boolean.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, BlockState.class,
                            int.class, int.class, ResourceKey.class, byte.class,
                            boolean.class, boolean.class));
            this.addOverlay = lookup.findVirtual(blockClass, "addOverlay",
                            MethodType.methodType(void.class, overlayClass))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));

            this.newOverlay = lookup.findConstructor(overlayClass,
                            MethodType.methodType(void.class, BlockState.class, byte.class, boolean.class))
                    .asType(MethodType.methodType(Object.class, BlockState.class,
                            byte.class, boolean.class));
            this.increaseOpacity = lookup.findVirtual(overlayClass, "increaseOpacity",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.getOriginal = lookup.findVirtual(overlayManagerClass, "getOriginal",
                            MethodType.methodType(overlayClass, overlayClass))
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class));
            this.highlightsPrepare = lookup.findVirtual(highlightsClass, "prepare",
                            MethodType.methodType(void.class, regionClass, int.class, int.class,
                                    boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class,
                            int.class, int.class, boolean.class));

            // ---- optional groups ----
            StringBuilder missing = new StringBuilder();
            CrashGate crash = null;
            try {
                Class<?> worldMapClass = resolver.resolve("xaero.map.WorldMap");
                Class<?> crashHandlerClass = resolver.resolve("xaero.map.CrashHandler");
                crash = new CrashGate(
                        lookup.unreflectGetter(worldMapClass.getField("crashHandler"))
                                .asType(MethodType.methodType(Object.class)),
                        methodByName(lookup, crashHandlerClass, "getCrashedBy", 0));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.append("crash-gate ");
            }
            SettingsGate settings = null;
            try {
                Class<?> worldMapClass = resolver.resolve("xaero.map.WorldMap");
                Class<?> channelClass = resolver.resolve("xaero.lib.common.config.channel.ConfigChannel");
                Class<?> managerClass = resolver.resolve("xaero.lib.client.config.ClientConfigManager");
                Class<?> optionsClass = resolver.resolve("xaero.map.common.config.option.WorldMapProfiledConfigOptions");
                Class<?> dimensionClass = resolver.resolve("xaero.map.world.MapDimension");
                settings = new SettingsGate(
                        lookup.unreflectGetter(worldMapClass.getField("INSTANCE"))
                                .asType(MethodType.methodType(Object.class)),
                        methodByName(lookup, worldMapClass, "getConfigs", 0),
                        methodByName(lookup, channelClass, "getClientConfigManager", 0),
                        methodByName(lookup, managerClass, "getEffective", 1),
                        lookup.unreflectGetter(optionsClass.getField("LOAD_NEW_CHUNKS"))
                                .asType(MethodType.methodType(Object.class)),
                        lookup.unreflectGetter(optionsClass.getField("UPDATE_CHUNKS"))
                                .asType(MethodType.methodType(Object.class)),
                        virtual(lookup, mapWorldClass, "getCurrentDimension",
                                MethodType.methodType(dimensionClass), Object.class),
                        virtual(lookup, dimensionClass, "isUsingWorldSave",
                                MethodType.methodType(boolean.class), boolean.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.append("settings-gate ");
            }
            int version = 1;
            try {
                version = tileClass.getField("CURRENT_WORLD_INTERPRETATION_VERSION").getInt(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.append("interpretation-version ");
            }
            MethodHandle caveLayer = null;
            try {
                caveLayer = virtual(lookup, processorClass, "getCurrentCaveLayer",
                        MethodType.methodType(int.class), int.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.append("cave-layer ");
            }
            this.getCurrentCaveLayer = caveLayer;
            this.crashGate = crash;
            this.settingsGate = settings;
            this.interpretationVersion = version;
            this.optionalMissing = missing.isEmpty() ? null : missing.toString().trim();
            if (this.optionalMissing != null) {
                LSSLogger.warn("Xaero map bridge: optional Xaero surface not bound on this version ("
                        + this.optionalMissing + ") — those gates stay open");
            }
        }

        /** Exact-typed no-arg virtual, adapted to an Object receiver. */
        private static MethodHandle virtual(MethodHandles.Lookup lookup, Class<?> owner,
                                            String name, MethodType type, Class<?> genericReturn)
                throws NoSuchMethodException, IllegalAccessException {
            return lookup.findVirtual(owner, name, type)
                    .asType(MethodType.methodType(genericReturn, Object.class));
        }

        private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name)
                throws NoSuchFieldException, IllegalAccessException {
            return lookup.findGetter(owner, name, Object.class)
                    .asType(MethodType.methodType(Object.class, Object.class));
        }

        /** Field getter tolerant of the declared type (mainWorld is ClientLevel-typed). */
        private static MethodHandle getterByName(MethodHandles.Lookup lookup, Class<?> owner,
                                                 String name)
                throws NoSuchFieldException, IllegalAccessException {
            var field = owner.getField(name);
            return lookup.unreflectGetter(field)
                    .asType(MethodType.methodType(Object.class, Object.class));
        }

        /**
         * Name+arity scan for the ClientLevel-typed boundary methods — the exact
         * parameter/return types stay whatever the class declares, so the stub
         * classes can declare them as Object (tests cannot construct a ClientLevel).
         */
        private static MethodHandle methodByName(MethodHandles.Lookup lookup, Class<?> owner,
                                                 String name, int paramCount)
                throws NoSuchMethodException, IllegalAccessException {
            Method found = null;
            for (Method m : owner.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount
                        && !m.isSynthetic() && !m.isBridge()) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new NoSuchMethodException(owner.getName() + "." + name + "/" + paramCount);
            }
            var handle = lookup.unreflect(found);
            var generic = MethodType.genericMethodType(paramCount + 1);
            if (found.getReturnType() == boolean.class) {
                generic = generic.changeReturnType(boolean.class);
            }
            return handle.asType(generic);
        }
    }
}
