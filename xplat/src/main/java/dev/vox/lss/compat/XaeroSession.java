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
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static dev.vox.lss.compat.XaeroRebuildScheduler.*;
import static dev.vox.lss.compat.XaeroAcquisitionQueue.*;
import static dev.vox.lss.compat.XaeroTileWriter.*;

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
final class XaeroSession {
    final XaeroRebuildScheduler rebuilds = new XaeroRebuildScheduler(this);
    final XaeroAcquisitionQueue acquisition = new XaeroAcquisitionQueue(this);
    final XaeroTileWriter writer = new XaeroTileWriter(this);

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
    /** §12.9 (fix for the one-way-wedge MAJOR): the wedge ALSO re-arms on this
     *  clock regardless of occupancy — under sustained arrival ≥ drain the
     *  occupancy floor is unreachable (731/s in vs ~680/s out pins it at 1.0)
     *  and the old latch was a one-way exit from governance for the rest of the
     *  fill. With the clock, a persistent structural pause becomes a bounded
     *  duty cycle (≤7 s halted / this long released) instead of a latch. */
    static final long BP_WEDGE_REARM_MILLIS = 10_000;
    /** §12.9 (fix for the trickle-writer MAJOR): the halt window re-bases only
     *  when occupancy has RECEDED from its in-window peak by at least this —
     *  "progress" means the queue is genuinely draining, not that {@code written}
     *  ticked (a 1-tile-per-6-s trickle re-based the old written-delta predicate
     *  forever, holding the LOD fill at a dead stop for hours — "may pace,
     *  never stop" violated with the sign flipped). 5% of the queue per 7 s ≈
     *  a ~59 col/s floor in the count regime / ~5/s in the ocean byte regime. */
    static final double BP_HALT_PROGRESS_EPS = 0.05;
    // ---- the OWED set (xaero-scatter-remediation-plan.md WI-3, 2026-09-05; amends
    // hybrid-scan-plan.md §12.8 bullet 3 as §12.10). Under a multi-region scatter
    // (other players' far generation re-broadcast into this client's held columns)
    // the queue fills with tiles whose Xaero REGION cannot take them yet — awaiting
    // its file load, or not resting through the 60 s save/recache window — and the
    // evictions that follow either burned ingest strikes (governed: four reports
    // park the position for the session) or were SILENT permanent holes (wedged
    // stream, deferred-tile expiry). The owed set keeps the DEBT without the bytes:
    // an evicted position whose region the last pump saw awaiting is owed, and
    // reported for its re-serve only when that region is loaded AND resting (or
    // once at the TTL), so the re-serve lands when the writer can take it. ----

    /** Owed REGIONS cap (insertion-ordered: the oldest debt — closest to its TTL —
     *  goes first past the cap). A region is 32×32 chunks = 1024 tiles, so the
     *  per-region axis never binds; the number of regions is the unbounded axis. */
    static final int MAX_OWED_REGIONS = 256;
    /** A region that never loads reports its debt ONCE at this age (bounded: at most
     *  one re-serve per owed position per TTL — versus four strikes and a park). */
    static final long OWED_TTL_MILLIS = 10 * 60_000L;
    /** Owed positions RELEASED (reported) per pump — each report is one
     *  {@code mc.execute} task and one want-set entry; an unbounded release of a
     *  whole region (1024) would spike past {@code WANT_SET_BUDGET}. */
    static final int OWED_REPORTS_PER_PUMP = 64;
    /** Owed regions PROBED per pump (region monitor + three reflective reads each —
     *  µs; the floor keeps a large owed set from walking every monitor per pump). */
    static final int OWED_PROBES_PER_PUMP = FLUSH_PROBE_EXEMPT_FLOOR;
    /** Consecutive failures (commit-side or extraction-side) before the bridge
     *  latches dead for the SESSION (re-armed at disconnect). */
    static final int THROW_LATCH = 5;
    /** The surface layer — native {@code caveLayer} sentinel. */
    static final int SURFACE_LAYER = Integer.MAX_VALUE;

    static final LogThrottle EXTRACT_FAIL_WARN = new LogThrottle(60_000);
    static final LogThrottle COMMIT_FAIL_WARN = new LogThrottle(60_000);

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

    static volatile XaeroSession instance;
    /** Xaero present but its internal surface unrecognized — drives the
     *  {@code state=unavailable} diag line (without it a drifted Xaero would be
     *  indistinguishable from "not installed", hiding the plan's top risk). */
    static volatile boolean resolveFailed;

    /** Client init, Xaero present: resolve + register the consumer (if enabled). */
    static boolean init() {
        return initWith(Class::forName);
    }

    /** The init body with an injectable resolver (the resolve-failure path's test seam). */
    static boolean initWith(ClassResolver resolver) {
        try {
            var h = XaeroBindings.resolve(resolver);
            var bridge = new XaeroSession(h, PRODUCTION_LEVEL_OPS,
                    () -> LSSClientConfig.CONFIG.enableXaeroMapBridge,
                    LSSApi::isServerEnabled,
                    LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer,
                    // §12.2: the bridge switch COMPOSES UNDER the global #71 switch —
                    // with enableIngestBackpressure off the manager never polls the
                    // report, so the report half must go dark too (review
                    // MAJOR: an armed reporter with no taper behind it restores the
                    // §18.1 churn regime).
                    () -> LSSClientConfig.CONFIG.enableIngestBackpressure
                            && LSSClientConfig.CONFIG.enableXaeroMapBackpressure,
                    XaeroSession::reportDroppedProduction);
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
    static void reportDroppedProduction(Object dimension, int chunkX, int chunkZ) {
        LSSApi.reportIngestFailure((ResourceKey<Level>) dimension, chunkX, chunkZ);
    }

    /** Disconnect body — session teardown (queue, latches, registration). */
    static void onDisconnect() {
        var bridge = instance;
        if (bridge != null) bridge.onSessionEnd();
    }

    /** ARMED = flag ∧ installed ∧ resolved (a live {@code instance} implies the
     *  latter two) — the alias corroboration's gate input (cache-alias-keying plan
     *  §2.2): while the bridge can write map tiles, the cache must stay per-address. */
    static boolean isArmed() {
        return instance != null && LSSClientConfig.CONFIG.enableXaeroMapBridge;
    }

    /** The conditional {@code /lss diag} line, or null when Xaero was never detected. */
    static String diagLine() {
        var bridge = instance;
        if (bridge != null) return bridge.describe();
        return resolveFailed
                ? "XaeroMap: state=unavailable (unrecognized Xaero internals — bridge off)"
                : null;
    }

    static ModCompat.XaeroStatus cachedStatus(boolean discoveryComplete) {
        var bridge = instance;
        if (bridge == null) return new ModCompat.XaeroStatus(
                resolveFailed ? "unavailable" : discoveryComplete ? "absent" : "unknown",
                false, false, 0, 0);
        boolean retiring = bridge.sessionEndPending || bridge.acquisition.retiringAcquisition;
        return new ModCompat.XaeroStatus("available",
                bridge.dead, retiring, retiring ? 0 : bridge.acquisition.queuedGauge,
                bridge.sessionEndPending ? 0 : bridge.rebuilds.pendingUpdatesGauge);
    }

    /** Test seam: forget the static facade state. */
    static void resetFacadeForTest() {
        instance = null;
        resolveFailed = false;
    }

    // ---- instance ----

    final XaeroBindings h;
    final LevelOps levelOps;
    final BooleanSupplier enabled;
    /** An LSS session is live — offers outside one are dropped (closes the
     *  disconnect-drain race that could carry one stale tile into the NEXT
     *  server's — or a singleplayer world's — persistent map). */
    final BooleanSupplier sessionActive;
    final java.util.function.Consumer<VoxelColumnConsumer> registrar;
    final java.util.function.Consumer<VoxelColumnConsumer> deregistrar;
    /** The §12 backpressure kill switch (client config
     *  {@code enableXaeroMapBackpressure}); composes UNDER the global
     *  {@code enableIngestBackpressure} (#71 owns the signal path manager-side). */
    final BooleanSupplier backpressureEnabled;
    /** Reports a dropped position back to LSS for its bounded re-serve — test seam. */
    final DropReporter dropReporter;
    final VoxelColumnConsumer consumer;
    /** Whether the consumer is currently registered with LSSApi. Main thread only. */
    boolean registered;

    final AtomicLong written = new AtomicLong();
    final AtomicLong skippedNative = new AtomicLong();
    final AtomicLong deferEvents = new AtomicLong();
    final AtomicLong droppedOverflow = new AtomicLong();
    final AtomicLong droppedStale = new AtomicLong();
    final AtomicLong droppedExpired = new AtomicLong();
    final AtomicLong commitFailures = new AtomicLong();
    final AtomicLong loadRequests = new AtomicLong();
    volatile boolean dead;
    /** A session end was signalled (possibly off-thread); its main-thread half is owed. */
    volatile boolean sessionEndPending;
    int consecutiveFailures; // main thread only
    /** Decode-thread twin of the commit-side latch: a permanently-throwing
     *  extractor must not burn CPU + hold the capability subscription forever. */
    final AtomicInteger consecutiveExtractFailures = new AtomicInteger();
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
    /** Entries the user's own Xaero map-writing switches refused (plan §16): "Load New
     *  Chunks" off for a new tile, "Update Chunks" off for an existing one, or both off. */
    final AtomicLong skippedSettings = new AtomicLong();
    /** Pumps that waited because Xaero was rendering a cave layer (diag). */
    final AtomicLong caveLayerWaits = new AtomicLong();
    // §12 backpressure state. The drainable latch is DERIVED, never enumerated:
    // pumpLadder's outcome sets it, so every early return — present and future —
    // reads as not-draining by construction (review MAJOR). §12.8: the latch is
    // now DIAGNOSTIC + hysteresis only — a blocked pump keeps reporting off the
    // queue's occupancy (and keeps accepting offers), so the taper/halt engages
    // exactly during the contention the old -1-on-paused doctrine went silent for.
    volatile boolean pumpDrainable = true;
    volatile long lastPumpMillis;
    int undrainablePumps; // main thread only (hysteresis counter)
    int blockedIdleSkips; // main thread only (§12.9: ~1 Hz idle recovery ladder)
    // Halt time-box (main thread only — the poll runs on the client tick).
    long haltSinceMillis;
    double haltPeakOccupancy; // in-window peak; re-base = recede from THIS
    volatile boolean haltWedged;
    long wedgeSinceMillis;       // main thread only (duty-cycle clock)
    long lastWedgeWarnMillis;    // rate-limits the cyclical wedge warn
    /** The pump's settings-both-off observation (§12.9 — restores the deleted
     *  refusal's one legitimate job): while Xaero's own "Load New Chunks" AND
     *  "Update Chunks" are off the ladder clears the queue every pump, so paying
     *  the 256-pixel extraction per offer is pure decode-thread waste — offers
     *  drop pre-extraction, counted {@code skipped_settings}, silent (the map is
     *  off by the USER's choice; not holes). Volatile: decode-thread read. */
    volatile boolean settingsWritesOff;
    /** The settings read threw once this session: both switches read as ON from then on
     *  (warned once). Session-scoped like the other latches; reset at session end. */
    volatile boolean settingsGateBroken;
    /** Xaero's CrashHandler holds a crash — the native writer's first gate. It is a
     *  ONE-TICK shield: Xaero's worker died mid-tick, and {@code checkForCrashes} at
     *  the next tick start nulls the field and re-throws it on the client thread (the
     *  client is about to crash). The bridge must not touch Xaero in that window.
     *  Diag-visible while it holds; session-scoped. */
    volatile boolean xaeroCrashed;
    /** Xaero's {@code getCurrentWorldId()} the last pump saw — a server-initiated
     *  reconfiguration (play → configuration) fires neither loader's disconnect event,
     *  so a world-id change is the ONE signal that the queue's tiles belong to a
     *  previous world (reviewer: the owed-rebuild map already carries the id; the
     *  queue did not). Main thread only. */
    String lastWorldId; // main thread only (owed-only pumps probe at ~5 Hz)
    // Test seams.
    long owedTtlMillis = OWED_TTL_MILLIS;
    int maxOwedRegions = MAX_OWED_REGIONS;
    int owedReportsPerPump = OWED_REPORTS_PER_PUMP;
    int owedProbesPerPump = OWED_PROBES_PER_PUMP;

    final class Origin {
        final long generation = acquisition.acquisitionGeneration.get();
        final LSSApi.IngestFailureHandle handle = LSSApi.captureIngestFailureHandle();
        final Runnable release = this.handle == null ? () -> {} : this.handle.deferAcceptance();
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        boolean active() {
            return !this.closed.get() && !acquisition.retiringAcquisition && this.generation == acquisition.acquisitionGeneration.get()
                    && (this.handle == null || this.handle.isActive());
        }

        void close() {
            if (this.closed.compareAndSet(false, true)) this.release.run();
        }
    }

    /** Acquisition OFF retains the native world: committed texture rebuilds still run. */
    void onAcquisitionEnd() {
        this.acquisition.retireAcquisitionWork();
        this.acquisition.discardDeferredReports(); // main thread only; no native-world disconnect here
    }

    static void retireClientAcquisition() {
        var bridge = instance;
        if (bridge != null) bridge.onAcquisitionEnd();
    }

    XaeroSession(XaeroBindings h, LevelOps levelOps, BooleanSupplier enabled,
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
        this.acquisition.retireAcquisitionWork();
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
    void settleSessionEnd() {
        this.sessionEndPending = false;
        // The old world's tile chunks are never touched again; the rebuilds they were
        // owed are lost (counted — Xaero's world is going away under us, so no
        // best-effort flush here). The last ≤2 s of commits before a disconnect can
        // thus reach the region cache with a stale texture (review A N5, accepted).
        this.rebuilds.droppedUpdates.addAndGet(this.rebuilds.pendingUpdates.size());
        this.rebuilds.pendingUpdates.clear();
        this.rebuilds.pendingUpdatesGauge = 0;
        this.acquisition.regionsWaiting = 0;
        this.consecutiveFailures = 0;
        this.rebuilds.frameFlushRan = false;
        this.rebuilds.frameActiveThisPump = false;
        this.rebuilds.rebuildSpentSinceLastPumpNanos = 0;
        this.rebuilds.framesSinceLastPump = 0;
        this.rebuilds.rebuildNanosMax = 0; // session-scoped worst recolor; the total stays lifetime
        this.lastWorldId = null;
        // §12 (review m5): the main-thread half re-clears the latches — an
        // in-flight ladder's finally can re-latch AFTER onSessionEnd's off-thread
        // clear; this half runs at the next pump top, after any such ladder.
        this.undrainablePumps = 0;
        this.haltSinceMillis = 0;
        this.pumpDrainable = true;
        this.haltWedged = false;
        this.wedgeSinceMillis = 0;
        this.settingsWritesOff = false; // a new session re-observes the switches
        this.acquisition.discardDeferredReports(); // stale un-stamps must not cross sessions
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
    VoxelColumnConsumer buildConsumer() {
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
                XaeroSession.this.consecutiveExtractFailures.set(0);
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
                if (XaeroSession.this.consecutiveExtractFailures.incrementAndGet() >= THROW_LATCH
                        && !XaeroSession.this.dead) {
                    XaeroSession.this.dead = true;
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
            // §12.9: two re-arm paths. Fast — the queue actually drained back down.
            // Clock — the duty cycle: under sustained arrival ≥ drain the occupancy
            // floor never comes (the one-way-exit MAJOR), so after
            // BP_WEDGE_REARM_MILLIS governance re-enters through the normal path
            // (the halt re-engages at once on a still-full queue, arrivals stop,
            // and the writer finally out-drains a silenced stream).
            if (this.acquisition.occupancy < BP_WEDGE_REARM_OCCUPANCY
                    || nowMillis() - this.wedgeSinceMillis > BP_WEDGE_REARM_MILLIS) {
                this.haltWedged = false;
            } else {
                return noSignal();
            }
        }
        if (nowMillis() - this.lastPumpMillis > this.bpPumpStaleMillis) return noSignal();
        int halt = dev.vox.lss.networking.client.LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        int report = (int) Math.round(halt * Math.min(1.0, this.acquisition.occupancy / BP_HALT_OCCUPANCY));
        if (report >= halt) {
            // The halt TIME-BOX: pacing is the bridge's right, stopping is not. A
            // full report with the queue not RECEDING across the whole window means
            // the writer is stuck — truly wedged, or gate-blocked past any
            // movement-burst length (§12.8) — degrade to -1 and let the fill
            // continue; the map wears the loss. §12.9: the window re-bases on an
            // OCCUPANCY RECESSION from its in-window peak (≥ BP_HALT_PROGRESS_EPS),
            // never on a written delta — a 1-tile-per-6-s trickle (a parked
            // DEFERRED region + the drain rotation's strays) re-based the old
            // predicate forever, holding the fill at a dead stop for hours. The
            // in-flight tail RAISING occupancy only raises the peak (never a
            // recession); every -1 exit still CLEARS the window (review MAJOR: a
            // timer surviving a no-signal interval fired a false wedge on the
            // first governing poll back).
            long now = nowMillis();
            double occ = this.acquisition.occupancy;
            if (this.haltSinceMillis == 0) {
                this.haltSinceMillis = now;
                this.haltPeakOccupancy = occ;
            } else if (occ < this.haltPeakOccupancy - BP_HALT_PROGRESS_EPS) {
                this.haltSinceMillis = now; // genuinely draining: a fresh window
                this.haltPeakOccupancy = occ;
            } else if (occ > this.haltPeakOccupancy) {
                this.haltPeakOccupancy = occ; // the landing tail: recession is from the peak
            } else if (now - this.haltSinceMillis > this.bpHaltWedgeMillis) {
                this.haltWedged = true;
                this.wedgeSinceMillis = now;
                this.haltSinceMillis = 0;
                if (now - this.lastWedgeWarnMillis > 60_000) { // cyclical now: rate-limit
                    this.lastWedgeWarnMillis = now;
                    LSSLogger.warn("Xaero map bridge: the map writer made no progress through"
                            + " a " + (this.bpHaltWedgeMillis / 1000) + " s backpressure halt —"
                            + " releasing the LOD fill for " + (BP_WEDGE_REARM_MILLIS / 1000)
                            + " s (map tiles may be dropped until the writer recovers)");
                }
                return -1;
            }
        } else {
            this.haltSinceMillis = 0;
        }
        return report;
    }

    /** Every no-signal exit clears the halt time-box — the halt is "not currently
     *  in effect" in a -1 state, and a stale window fires false wedges. */
    int noSignal() {
        this.haltSinceMillis = 0;
        return -1;
    }

    /** Clock seam (tests drive the watchdog + time-box without sleeping — the
     *  class is final, so the seam is a field, not an override). */
    java.util.function.LongSupplier bpClock = System::currentTimeMillis;

    long nowMillis() {
        return this.bpClock.getAsLong();
    }

    /** Decode-thread entry: extract + enqueue (latest-wins, bounded, oldest drops). */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData) {
        Origin origin = new Origin();
        try {
            if (!origin.active() || this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
                origin.close();
                return;
            }
            // §12.9: the one pre-extraction refusal that survives — Xaero's own
            // map-writing switches both off (the ladder clears the queue every pump;
            // extracting for it is pure decode-thread waste). Counted, silent: the
            // map is off by the user's choice, not dropped work.
            if (this.settingsWritesOff && this.backpressureEnabled.getAsBoolean()) {
                this.skippedSettings.incrementAndGet();
                origin.close();
                return;
            }
            // §12.8: offers are ACCEPTED during pump pauses — the queue is the
            // movement-burst buffer AND the pressure gauge (the deleted §12.1(b)
            // refusal shed 56k tiles into silent permanent holes on the first live
            // session while the -1 report kept the stream at full rate). The count
            // pre-gate below and offerPrepared's byte/count evict loop are the shed
            // points; same-dimension sheds report when governed (blocked-not-wedged
            // drops re-serve after the burst — the halt defers the re-declaration,
            // so there is no churn loop).
            long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            boolean overflowed = false;
            synchronized (this.acquisition.queueLock) {
                // Don't pay the 256-pixel extraction for a tile the full queue would
                // evict on arrival (sustained-overflow CPU on the LOD decode thread). Count
                // cap only: the byte cap (which binds first on overlay-heavy tiles — sweep C
                // N3) needs the tile's size, unknown before extraction, and past it the
                // enqueue evicts the OLDEST entry, so that extraction is not wasted.
                if (this.acquisition.queue.size() >= this.maxQueue && !this.acquisition.queue.containsKey(key)) {
                    this.droppedOverflow.incrementAndGet();
                    overflowed = true;
                }
            }
            if (overflowed) {
                // Governed overflow is structurally ~0 (the halt fires at 75%); a stray
                // one self-heals via the reporter. OUTSIDE the lock (§18.1 discipline).
                // WI-3: a tile whose region the last pump saw awaiting is OWED instead —
                // its re-serve lands once the region loads (an immediate report would
                // re-serve into the same shed).
                if (!this.acquisition.shedToOwed(dimension, key, origin)) this.acquisition.reportDroppedIfGoverned(dimension, chunkX, chunkZ, origin);
                return;
            }
            var tile = XaeroTileExtractor.extract(chunkX, chunkZ, worldBottomY, worldTopY, columnData);
            offerPrepared(dimension, tile, origin);
        } catch (Throwable failure) {
            origin.close();
            throw failure;
        }
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
        this.acquisition.offerPrepared(dimension, tile);
    }

    void offerPrepared(Object dimension, XaeroTileExtractor.PreparedTile tile, Origin origin) {
        this.acquisition.offerPrepared(dimension, tile, origin);
    }

    // ---- WI-3: the owed set ----

    static long regionKeyOf(int chunkX, int chunkZ) {
        return (((long) (chunkX >> 5)) << 32) | ((chunkZ >> 5) & 0xFFFFFFFFL);
    }

    static long regionKeyOfPacked(long packedChunk) {
        return regionKeyOf((int) (packedChunk >> 32), (int) packedChunk);
    }

    int owedForTest() {
        return this.acquisition.owedGauge;
    }

    java.util.Set<Long> awaitingRegionsForTest() {
        return this.acquisition.awaitingRegions;
    }

    /** Drop the whole queue, unreported (teardowns, toggles, settings-off clears —
     *  reporting those would either race a teardown or re-serve into a state the
     *  user turned off; §12.8 deleted the refusal these clears used to hand over
     *  to). @return how many entries were dropped. */
    int clearQueue() {
        return this.acquisition.clearQueue();
    }

    /** The WORLD-ID-change clear (§12.1(c)): the queued tiles belong to a previous
     *  world, and the player may return — each position's report is COLLECTED into
     *  {@link #deferredReports} (the caller sits inside Xaero's renderPause
     *  monitor; the pump drains outside it) so its stamp is forgotten and a
     *  return re-declares it. Accepted churn: a same-dimension world change
     *  un-stamps up to a queue's worth (~3k) and re-downloads it — the new map
     *  needs those tiles; ~4 s of re-serves, rare event (recorded §12.6). */
    int clearQueueCollectingReports() {
        return this.acquisition.clearQueueCollectingReports();
    }

    int queuedForTest() {
        synchronized (this.acquisition.queueLock) {
            return this.acquisition.queue.size();
        }
    }

    boolean hasQueuedForTest(int chunkX, int chunkZ) {
        synchronized (this.acquisition.queueLock) {
            return this.acquisition.queue.containsKey(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }
    }

    long queuedBytesForTest() {
        synchronized (this.acquisition.queueLock) {
            return this.acquisition.queuedBytes;
        }
    }

    boolean deadForTest() {
        return this.dead;
    }

    boolean drainableForTest() {
        return this.pumpDrainable;
    }

    int undrainablePumpsForTest() {
        return this.undrainablePumps;
    }

    int regionsWaitingForTest() {
        return this.acquisition.regionsWaiting;
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
            case "buffer_updates" -> this.rebuilds.bufferUpdates.get();
            case "frame_flushes" -> this.rebuilds.frameFlushes.get();
            case "rebuild_nanos_total" -> this.rebuilds.rebuildNanos.get();
            case "rebuild_nanos_max" -> this.rebuilds.rebuildNanosMax;
            case "drops_reported" -> this.acquisition.dropsReported.get();
            case "owed" -> this.acquisition.owedGauge;
            case "owed_regions" -> this.acquisition.owedRegionsGauge;
            case "owed_reported" -> this.acquisition.owedReported.get();
            case "owed_evicted" -> this.acquisition.owedEvicted.get();
            case "dropped_updates" -> this.rebuilds.droppedUpdates.get();
            case "dropped_unloaded" -> this.rebuilds.droppedUnloaded.get();
            case "skipped_settings" -> this.skippedSettings.get();
            case "cave_layer_waits" -> this.caveLayerWaits.get();
            case "xaero_crashed" -> this.xaeroCrashed ? 1 : 0;
            case "pending_updates" -> this.rebuilds.pendingUpdatesGauge;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** The §12 governance token: {@code off} (kill switch), {@code -1(reason)}
     *  (no signal — inactive/wedged/stale), or the live occupancy fraction —
     *  suffixed {@code (blocked)} while the pump's drainable latch is down
     *  (§12.8: blocked still governs). Check order mirrors
     *  {@link #reportBackpressure} so the token never contradicts the report. */
    String bpToken() {
        if (!this.backpressureEnabled.getAsBoolean()) return "off";
        if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
            return "-1(inactive)";
        }
        if (this.haltWedged) return "-1(wedged)";
        if (nowMillis() - this.lastPumpMillis > this.bpPumpStaleMillis) return "-1(stale)";
        String f = String.format(java.util.Locale.ROOT, "%.2f", this.acquisition.occupancy);
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
                + ", regions_waiting=" + this.acquisition.regionsWaiting
                + ", buffer_updates=" + this.rebuilds.bufferUpdates.get()
                + ", frame_flushes=" + this.rebuilds.frameFlushes.get()
                + ", rebuild_ms=" + (this.rebuilds.rebuildNanos.get() / 1_000_000)
                + ", rebuild_max_us=" + (this.rebuilds.rebuildNanosMax / 1_000)
                + ", pending_updates=" + this.rebuilds.pendingUpdatesGauge
                + ", dropped_updates=" + this.rebuilds.droppedUpdates.get()
                + ", dropped_unloaded=" + this.rebuilds.droppedUnloaded.get()
                + ", skipped_settings=" + this.skippedSettings.get()
                + ", cave_layer_waits=" + this.caveLayerWaits.get()
                + ", drops_reported=" + this.acquisition.dropsReported.get()
                + ", owed=" + this.acquisition.owedGauge
                + ", owed_regions=" + this.acquisition.owedRegionsGauge
                + ", owed_reported=" + this.acquisition.owedReported.get()
                + ", owed_evicted=" + this.acquisition.owedEvicted.get()
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
            if (!this.rebuilds.pendingUpdates.isEmpty()) {
                this.rebuilds.pendingUpdates.clear();
                this.rebuilds.pendingUpdatesGauge = 0;
            }
            this.undrainablePumps = 0; // no ladder ran: the consecutive chain breaks
            return;
        }
        this.rebuilds.pumpCount++;
        this.lastPumpMillis = nowMillis(); // §12 watchdog: the pump machinery is alive
        // §17.1 (review fold): the frame marker is consumed HERE, once per pump — and
        // the interval allowance / frame-scarcity meters re-arm.
        this.rebuilds.frameActiveThisPump = this.rebuilds.frameFlushRan;
        this.rebuilds.frameFlushRan = false;
        this.rebuilds.rebuildSpentSinceLastPumpNanos = 0;
        this.rebuilds.framesSinceLastPump = 0;
        if (!this.enabled.getAsBoolean()) {
            clearQueue(); // the live toggle: flipping off drops the backlog immediately
            this.acquisition.clearOwed();  // …and the debt (WI-3): a disabled bridge must not un-stamp anything
            // ...but rebuilds already OWED to committed tile chunks still flush —
            // dropping them would leave written tiles invisible until a reload.
            if (this.rebuilds.pendingUpdates.isEmpty()) {
                this.undrainablePumps = 0; // a ladder-skipping exit breaks the chain (§12.7)
                return;
            }
        } else if (this.pumpDrainable) {
            // §12 deadlock guard (rekeyed by §12.8 on the drainable latch): while
            // the latch is down, the LADDER run is the only thing that can observe
            // "drainable again" and clear it — the idle fast-out must not bypass
            // it entirely.
            synchronized (this.acquisition.queueLock) {
                if (this.acquisition.queue.isEmpty() && this.rebuilds.pendingUpdates.isEmpty()) {
                    if (this.acquisition.owedRegionsGauge == 0) {
                        this.acquisition.regionsWaiting = 0;
                        this.undrainablePumps = 0; // idle: gate flaps here are meaningless
                        return;
                    }
                    // WI-3: an owed debt keeps the ladder running (its regions have no
                    // queued bytes — only the probe pass can observe them becoming
                    // ready) — at ~5 Hz, not 20: Xaero's loader drains one file per
                    // ≥100 ms pass, and every ladder run takes its render monitors
                    // (§12.9's contention finding).
                    if (this.acquisition.owedIdleSkips++ % 4 != 0) return;
                }
            }
        } else {
            // §12.9 (review NIT): blocked + idle runs the recovery ladder at ~1 Hz,
            // not 20 Hz — each run acquires Xaero's renderPause/mainStuff monitors,
            // contending with the very render work the block is waiting on. The
            // FIRST blocked-idle pump always runs (the deadlock-guard pin's shape);
            // any queued work re-forces every pump via the emptiness check.
            synchronized (this.acquisition.queueLock) {
                // (WI-3: an owed-only pump shares this ~1 Hz throttle — the debt cannot
                // drain while blocked anyway, and §12.9's contention finding stands.)
                if (this.acquisition.queue.isEmpty() && this.rebuilds.pendingUpdates.isEmpty()
                        && this.blockedIdleSkips++ % 20 != 0) {
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
    void drainDeferredReports() {
        this.acquisition.drainDeferredReports();
    }

    /**
     * The native {@code MapWriter.onRender} gate ladder, verbatim (plan §2.7). Any
     * not-ready gate returns — entries stay queued (deferral, not deletion; the
     * bounded queue is the TTL). The {@code mainStuffSync} dimension equality is
     * THE anti-wrong-dimension binding: like Xaero's own writer, commits pause
     * while the user browses another dimension's map.
     */
    void pumpLadder() throws Throwable {
        boolean reached = false;
        try {
            reached = pumpLadderInner();
        } finally {
            // §12: the drainable latch is DERIVED from the outcome — every early
            // return (and any future one) reads as not-draining by construction; a
            // throwing ladder too. Hysteresis absorbs per-pump gate flaps (§12.8:
            // the latch is diagnostics + the idle-guard key — a blocked pump still
            // governs off the queue and offers still land).
            if (reached) {
                this.undrainablePumps = 0;
                this.blockedIdleSkips = 0;
                this.pumpDrainable = true;
            } else {
                int n = ++this.undrainablePumps;
                if (n >= this.bpPausePumps) this.pumpDrainable = false;
            }
        }
    }

    boolean pumpLadderInner() throws Throwable {
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
                this.acquisition.clearOwedCollectingReports(); // WI-3: the old world's debts are reported like its tiles
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
                    // Both of Xaero's own map-writing switches are off: the USER
                    // turned the map's writes off, so the queue clears unreported
                    // (not holes — a choice) and the §12.9 flag makes offerColumn
                    // drop pre-extraction (the deleted refusal's one legitimate
                    // job: no 256-pixel extraction per column for tiles this
                    // clear would discard 50 ms later).
                    this.settingsWritesOff = true;
                    this.skippedSettings.addAndGet(clearQueue());
                    this.acquisition.regionsWaiting = 0;
                    this.rebuilds.tickFlush(mp, dimensionId);
                    return false;
                }
                this.settingsWritesOff = false;
            }
            this.rebuilds.tickFlush(mp, dimensionId);
            if (this.dead) return false;
            if (this.h.getCurrentCaveLayer != null
                    && (int) this.h.getCurrentCaveLayer.invoke(mp) != SURFACE_LAYER) {
                // The map is showing a cave layer: our surface-layer writes would be
                // invisible and still cost regions/loads/saves — wait (entries retained;
                // the bounded queue is the TTL), owed rebuilds above still ran.
                this.caveLayerWaits.incrementAndGet();
                this.acquisition.regionsWaiting = 0;
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
        this.rebuilds.frameFlush();
    }

    /** One queue entry paired with its key for the bucketed drain. */
    record Pending(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile, Origin origin) {}

    /** A region probed this pump whose bucket is awaiting its Xaero load — the
     *  verdict is Xaero's own state, read inside the probe's region monitor. */
    record WaitingRegion(long regionKey, int tiles, Outcome verdict) {}

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
    void drainEntries(Object mp, Object saveLoad,
                              Object world, Object dimensionId,
                              boolean loadNew, boolean update) throws Throwable {
        long generation = this.acquisition.acquisitionGeneration.get();
        long start = System.nanoTime();

        List<Pending> snapshot;
        synchronized (this.acquisition.queueLock) {
            snapshot = new ArrayList<>(this.acquisition.queue.size());
            for (var e : this.acquisition.queue.entrySet()) {
                snapshot.add(new Pending(e.getKey(), e.getValue(), e.getValue().tile, e.getValue().origin));
            }
        }
        var buckets = new LinkedHashMap<Long, List<Pending>>(); // keeps spiral locality
        for (var pending : snapshot) {
            long regionKey = regionKeyOf(pending.tile().chunkX(), pending.tile().chunkZ());
            buckets.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(pending);
        }

        var bucketKeys = new ArrayList<>(buckets.keySet());
        var waiting = new ArrayList<WaitingRegion>();
        int commits = 0;
        boolean progressed = false;
        int size = bucketKeys.size();
        int startIndex = size == 0 ? 0 : Math.floorMod(this.acquisition.drainRotation++, size);
        boolean capped = false;
        bucketLoop:
        for (int n = 0; n < size; n++) {
            Long regionKey = bucketKeys.get((startIndex + n) % size);
            var bucket = buckets.get(regionKey);
            for (var pending : bucket) {
                if (!pending.origin().active()) {
                    this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                    pending.origin().close();
                    continue;
                }
                if (this.rebuilds.pendingUpdates.size() >= this.pendingUpdatesHardCap) {
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
                    if (this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        this.droppedStale.incrementAndGet();
                        // §12.1(c): report (deferred out of the monitor) — the
                        // re-serve lands after the player returns to that dimension.
                        this.acquisition.deferredReports.add(new Object[]{pending.entry().dimension,
                                pending.tile().chunkX(), pending.tile().chunkZ(), pending.origin()});
                    }
                    progressed = true;
                    continue;
                }
                if (this.writer.nativelyWritable(world, pending.tile().chunkX(), pending.tile().chunkZ())) {
                    // The native writer owns these chunks and rewrites them on its
                    // clean-flag anyway — never fight it (plan §2.6).
                    if (this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        pending.origin().close();
                        this.skippedNative.incrementAndGet();
                    }
                    progressed = true;
                    continue;
                }
                progressed = true;
                var outcome = this.writer.commitEntry(mp, dimensionId, pending.tile(), loadNew, update);
                switch (outcome) {
                    case COMMITTED -> {
                        this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        pending.origin().close();
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
                                && this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                            this.droppedExpired.incrementAndGet();
                            // WI-3: the silent hole becomes a DEBT — released once the
                            // region (whose tile chunk was busy) is loaded and resting,
                            // never a strike against the stalled resource.
                            oweExpired(pending.entry().dimension, pending.key(), regionKey, pending.origin());
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
                        this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        pending.origin().close();
                        this.skippedSettings.incrementAndGet();
                    }
                    case FAILED -> {
                        // Possibly entry-specific (a hostile state) — drop it and keep
                        // trying the bucket's siblings unless the latch fired.
                        this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        pending.origin().close();
                        if (this.dead) return;
                    }
                }
            }
        }
        if (generation != this.acquisition.acquisitionGeneration.get()) return;
        this.acquisition.probeOwed(mp, dimensionId, waiting);
        if (generation != this.acquisition.acquisitionGeneration.get()) return;
        if (!capped) {
            // A capped pass probed nothing: keep the last gauge + classifier. Published
            // AFTER the owed feed so regions_waiting= reports the whole grant input.
            this.acquisition.regionsWaiting = waiting.size();
            if (waiting.isEmpty()) {
                this.acquisition.awaitingRegions = java.util.Set.of();
            } else {
                var awaiting = new java.util.HashSet<Long>();
                for (var w : waiting) awaiting.add(w.regionKey());
                this.acquisition.awaitingRegions = awaiting;
            }
        }
        grantLoads(mp, saveLoad, waiting);
    }

    /** Owe a deferral-expired tile (pump side; governed only, like every owe) — a
     *  TILE-scoped debt: released once its own tile chunk is ready. */
    // Direct debt seam retained for the owed-set fixture; production transfers an existing origin.
    void oweExpired(Object dimension, long packedChunk, long regionKey) {
        this.acquisition.oweExpired(dimension, packedChunk, regionKey);
    }

    void oweExpired(Object dimension, long packedChunk, long regionKey, Origin origin) {
        this.acquisition.oweExpired(dimension, packedChunk, regionKey, origin);
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
    void grantLoads(Object mp, Object saveLoad, List<WaitingRegion> waiting) {
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
    boolean requestRegionLoad(Object mp, Object saveLoad, long regionKey) {
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

    enum Outcome {
        COMMITTED, DEFERRED, DEFERRED_TILE,
        AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT,
        SKIPPED_SETTINGS, FAILED
    }

    static PendingKey pendingKey(Object dimension, int tileChunkX, int tileChunkZ) {
        return new PendingKey(dimension, ((long) tileChunkX << 32) | (tileChunkZ & 0xFFFFFFFFL));
    }

    void noteFailure(Throwable t) {
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
}
