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
    /** Fill-on-switch (ceiling dimensions): the column store, the layer-switch trigger and
     *  the fill worker. OFF for a bare constructor; production reads the levers. */
    final XaeroBandFill fill = new XaeroBandFill(this);
    /** Region-load latency and vanished-grant instrumentation. Main thread. */
    final XaeroLoadStats loadStats = new XaeroLoadStats();

    /** Why commits were deferred (the reliable queue's own diag): {@code not_resting} = a
     *  save's recache (or a refresh) is pending, {@code paused} = Xaero is saving the region,
     *  {@code capacity} = the owed-rebuild cap, {@code tile_busy} = a tile chunk is loading or
     *  downloading its texture. */
    static final class DeferReasons {
        final AtomicLong notResting = new AtomicLong();
        final AtomicLong paused = new AtomicLong();
        final AtomicLong capacity = new AtomicLong();
        final AtomicLong tileBusy = new AtomicLong();
        final AtomicLong other = new AtomicLong();

        String describe() {
            return "not_resting:" + this.notResting.get() + "/paused:" + this.paused.get()
                    + "/capacity:" + this.capacity.get() + "/tile_busy:" + this.tileBusy.get()
                    + "/other:" + this.other.get();
        }

        void putMetrics(java.util.Map<String, Object> out) {
            out.put("defer_not_resting", this.notResting.get());
            out.put("defer_paused", this.paused.get());
            out.put("defer_capacity", this.capacity.get());
            out.put("defer_tile_busy", this.tileBusy.get());
            out.put("defer_other", this.other.get());
        }
    }

    final DeferReasons defers = new DeferReasons();
    /** Columns whose cave tile was not written: outside the live cave-layer bound. */
    final AtomicLong caveOutOfRange = new AtomicLong();
    /** Queued tiles of a layer that is neither shown nor the surface, dropped silently after
     *  {@link #staleLayerPumps} held pumps. */
    final AtomicLong droppedStaleLayer = new AtomicLong();
    /** Held pumps after which a non-current, non-surface entry expires (~30 s at 20 Hz). */
    int staleLayerPumps = STALE_LAYER_PUMPS;
    static final int STALE_LAYER_PUMPS = 600;
    /** Xaero's save queue as the last pump sampled it (optional {@code save-queue} binding). */
    volatile int toSaveGauge;
    volatile int toSaveMax;
    /** Drains stopped by the owed-rebuild hard cap. */
    final AtomicLong cappedPumps = new AtomicLong();

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
     *  now also bounds STRUCTURAL pauses (map locked, long gate blocks; the
     *  cave-layer wait is gone — cave-layers plan §B5) — a movement burst holds
     *  the halt up to this long, then the fill resumes and the map wears the
     *  loss (doctrine (d)). */
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
    // ---- the open-region window (map-ingestion plan §4.1 — the unfocused-window OOM
    // fix, xaero-bridge-memory-plan.md approach A). A committed column allocates 256
    // MapBlocks (a full region ≈ 12 MB of source data), and Xaero frees them only from
    // a RENDERED frame (LeafRegionTexture.postUpload → MapTileChunk.clean, which flips
    // the region to loadState 3), at least 60 s after the last write (the save clears
    // beingWritten). Unfocused at Dynamic FPS's 1 FPS default, release starves while the
    // tick-driven bridge keeps writing. The window bounds the regions the bridge has
    // written into that Xaero still holds at loadState 2; when it is full, entries for
    // new regions stay queued and the report paces the download to Xaero's real drain. ----

    /** Heap bytes per window slot: worst-case source residency ≈ 12/96 of the heap. */
    static final long OPEN_REGION_HEAP_BYTES_PER_SLOT = 96L * 1024 * 1024;
    static final int OPEN_REGION_MIN = 12;
    static final int OPEN_REGION_MAX = 64;
    /** Window regions probed per pump (region monitor + one reflective read each — µs). */
    static final int OPEN_REGION_PROBES_PER_PUMP = 8;
    /** While the window or the heap belt holds the stream, the halt time-box widens to
     *  this — a legitimate pacing hold, not a stuck writer — and the §12.9 duty clock
     *  still releases the fill for {@link #BP_WEDGE_REARM_MILLIS} after it. */
    static final long BP_PACE_HOLD_MILLIS = 60_000;

    /** The window size for this heap; {@code override >= 0} (the dev lever
     *  {@code -Dlss.xaero.openRegionWindow}, 0 = off) wins. */
    static int openRegionCapFor(long maxHeapBytes, int override) {
        if (override >= 0) return override;
        long slots = maxHeapBytes / OPEN_REGION_HEAP_BYTES_PER_SLOT;
        return (int) Math.max(OPEN_REGION_MIN, Math.min(OPEN_REGION_MAX, slots));
    }

    /** The surface layer — native {@code caveLayer} sentinel. */
    static final int SURFACE_LAYER = XaeroTileExtractor.SURFACE;
    /** The native writer's cave-mode reach cap ({@code writeMap}: min(16, write distance)
     *  while it writes a cave layer outside the map screen). */
    static final int NATIVE_CAVE_WRITE_DISTANCE = 16;
    /** {@code getWriteDistance}'s own cap on the render distance. */
    static final int NATIVE_MAX_WRITE_DISTANCE = 32;

    /** A queue key: Xaero LAYER + packed chunk — every layer reuses the chunk coords, so a
     *  column's surface and band tiles are distinct entries (cave-layers plan §B5). */
    record TileKey(int layer, long chunk) {}

    /** A map region in one Xaero layer: each layer has its own regions (and files), so every
     *  region-scoped key — buckets, grants, debts, the window — carries the layer. */
    record RegionKey(int layer, long region) {}

    static RegionKey surfaceRegion(long regionKey) {
        return new RegionKey(SURFACE_LAYER, regionKey);
    }

    static TileKey tileKey(XaeroTileExtractor.PreparedTile tile) {
        return new TileKey(tile.layer(), ((long) tile.chunkX() << 32) | (tile.chunkZ() & 0xFFFFFFFFL));
    }

    /**
     * What Xaero shows, read by the pump at the TOP of every pump — before its gates — and
     * tagged with Xaero's current dimension, for the decode thread's band set (§B3). The mode
     * IS the current layer ({@code getCurrentCaveLayer} is the result of Xaero's own
     * {@code updateCaveStart}): {@code MAX_VALUE} surface, {@code MIN_VALUE} Full, else the
     * band. {@code worldSave}: Xaero writes this dimension from the world save (singleplayer /
     * LAN host) — cave writes are off there (§B5: native writes outdate the other layers and
     * rebuild them from the save).
     */
    record LayerPlan(Object dimension, int currentLayer, int depth, boolean ignoreHeightmaps,
                     boolean worldSave) {
        String mode() {
            return this.currentLayer == SURFACE_LAYER ? "surface"
                    : this.currentLayer == XaeroTileExtractor.FULL ? "full" : "layered";
        }

        String layerToken() {
            return this.currentLayer == SURFACE_LAYER ? "surface"
                    : this.currentLayer == XaeroTileExtractor.FULL ? "full" : Integer.toString(this.currentLayer);
        }
    }

    /** The local player's position, with the dimension it is in. */
    record PlayerPos(Object dimension, double x, double y, double z) {}

    /** Reads the local player (any thread: the decode thread for the live cave bound and the
     *  capture gate, the pump for the native-distance check and the fill) — test seam; null =
     *  unknown. */
    @FunctionalInterface
    interface PlayerProbe {
        PlayerPos position();
    }

    /**
     * The native writer's reach this pump (§B5): the layer it writes and the Chebyshev square
     * around the player it writes in ({@code writeChunk}'s own distance test) — min(16, write
     * distance) for a cave layer. A player of unknown position skips the distance leg (the
     * loaded-and-surrounded rule alone, the pre-cave behavior).
     */
    record NativeReach(int layer, int distance, PlayerPos player) {
        static final NativeReach SURFACE_ANYWHERE = new NativeReach(SURFACE_LAYER, Integer.MAX_VALUE, null);

        boolean reaches(int tileLayer, int chunkX, int chunkZ) {
            if (tileLayer != this.layer) return false;
            if (this.player == null || this.distance == Integer.MAX_VALUE) return true;
            int px = ((int) Math.floor(this.player.x())) >> 4;
            int pz = ((int) Math.floor(this.player.z())) >> 4;
            return Math.abs(chunkX - px) <= this.distance && Math.abs(chunkZ - pz) <= this.distance;
        }
    }

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

    static PlayerPos productionPlayer() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var player = mc == null ? null : mc.player;
        if (player == null) return null;
        return new PlayerPos(player.level().dimension(), player.getX(), player.getY(), player.getZ());
    }

    static int productionRenderDistance() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc == null || mc.options == null ? NATIVE_MAX_WRITE_DISTANCE : mc.options.getEffectiveRenderDistance();
    }

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
                    () -> LSSClientConfig.CONFIG.mapIngestionEnabled(),
                    LSSApi::isServerEnabled,
                    LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer,
                    // §12.2: the bridge switch COMPOSES UNDER the global #71 switch —
                    // with enableIngestBackpressure off the manager never polls the
                    // report, so the report half must go dark too (review
                    // MAJOR: an armed reporter with no taper behind it restores the
                    // §18.1 churn regime).
                    () -> LSSClientConfig.CONFIG.enableIngestBackpressure()
                            && LSSClientConfig.CONFIG.mapIngestionBackpressure(),
                    XaeroSession::reportDroppedProduction,
                    // Map-policy plan A4: the server's map-ingestion request (write paths only).
                    dev.vox.lss.networking.client.ClientNetGlue::serverAllowsMapIngestion);
            bridge.openRegionCap = openRegionCapFor(Runtime.getRuntime().maxMemory(),
                    Integer.getInteger("lss.xaero.openRegionWindow", -1));
            bridge.memory = MapMemoryGuard.PRODUCTION;
            bridge.connected = XaeroSession::productionConnected;
            bridge.playerProbe = XaeroSession::productionPlayer;
            bridge.renderDistance = XaeroSession::productionRenderDistance;
            bridge.serverLodDistance = LSSApi::getServerLodDistance;
            bridge.fillLevelProbe = XaeroSession::productionFillLevel;
            bridge.storeBucketProbe = XaeroSession::productionStoreBucket;
            bridge.acquisitionActive = () -> LSSClientConfig.CONFIG.receiveServerLods();
            bridge.singleplayer = XaeroSession::productionSingleplayer;
            bridge.fill.configureFromSystemProperties();
            bridge.storeMbSetting = XaeroSession::productionStoreMb;
            bridge.storeFactory = XaeroSession::productionColumnStore;
            bridge.applyStoreSetting();
            bridge.maybeRegister();
            instance = bridge;
            LSSLogger.info(LSSClientConfig.CONFIG.mapIngestionEnabled()
                    ? "Xaero's World Map detected — LOD map bridge active"
                    : "Xaero's World Map detected — LOD map bridge ready"
                            + " (map ingestion is disabled in the client settings)");
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
        var key = (ResourceKey<Level>) dimension;
        // Without a delivery to name, say that it is a map that dropped the column: one
        // that was sent for the map alone then keeps its stamp (issue #380).
        if (!LSSApi.reportThroughCurrentHandle(key, chunkX, chunkZ)) {
            dev.vox.lss.networking.client.ClientNetGlue.reportMapDrop(key, chunkX, chunkZ);
        }
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
        return instance != null && LSSClientConfig.CONFIG.mapIngestionEnabled();
    }

    /** Whether a column arriving now is written to Xaero's map: the consumer is registered,
     *  writes are open, and Xaero's own two map-writing switches are not both off (the
     *  offer path refuses those columns). Client thread. */
    static boolean isWriting() {
        var bridge = instance;
        return bridge != null && bridge.registered && bridge.writesOpen() && !bridge.settingsWritesOff;
    }

    /** The conditional {@code /lss diag} line, or null when Xaero was never detected. */
    static String diagLine() {
        var bridge = instance;
        if (bridge != null) return bridge.describe();
        return resolveFailed
                ? "XaeroMap: state=unavailable (unrecognized Xaero internals — bridge off)"
                : null;
    }

    /** The soak/benchmark client snapshot's {@code xaero} group (the memory live gate). */
    static java.util.Map<String, Object> metrics() {
        var bridge = instance;
        var out = new java.util.LinkedHashMap<String, Object>();
        if (bridge == null) {
            out.put("state", resolveFailed ? "unavailable" : "absent");
            return out;
        }
        out.put("state", bridge.dead ? "dead" : "available");
        out.put("queued", bridge.acquisition.queuedGauge);
        out.put("written", bridge.written.get());
        out.put("written_cave", bridge.writtenCave.get());
        out.put("layer", bridge.layerToken());
        out.put("mode", bridge.modeToken());
        out.put("server_blocked", !bridge.serverAllowsWrites.getAsBoolean()); // map-policy plan A5
        var stats = bridge.loadStats;
        out.put("load_count", stats.loads.total);
        out.put("load_ms_p50", stats.loads.quantileMillis(0.5));
        out.put("load_ms_p95", stats.loads.quantileMillis(0.95));
        out.put("region_vanished", stats.vanished.get());
        bridge.defers.putMetrics(out);
        out.put("capped_pumps", bridge.cappedPumps.get());
        out.put("pending_updates", bridge.rebuilds.pendingUpdatesGauge);
        out.put("buffer_updates", bridge.rebuilds.bufferUpdates.get());
        out.put("dropped_updates", bridge.rebuilds.droppedUpdates.get());
        out.put("to_save", bridge.toSaveGauge);
        out.put("to_save_max", bridge.toSaveMax);
        out.put("cave_out_of_range", bridge.caveOutOfRange.get());
        out.put("dropped_stale_layer", bridge.droppedStaleLayer.get());
        out.put("fill_queued", bridge.acquisition.fillQueuedGauge);
        bridge.fill.putMetrics(out);
        out.put("open_regions", bridge.openRegionsGauge);
        out.put("open_region_cap", bridge.openRegionCap);
        out.put("window_waits", bridge.windowWaits.get());
        out.put("heap_holds", bridge.heapHolds.get());
        double heap = bridge.memory.fraction();
        out.put("heap_fraction", Double.isNaN(heap) ? -1.0 : heap); // JSON has no NaN
        out.put("occupancy", bridge.acquisition.occupancy);
        return out;
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
    /** The bridge's column consumer, whether or not it is registered right now (ModCompat's
     *  "is a map the only reader" checks compare against it); null without an instance. */
    static VoxelColumnConsumer mapConsumer() {
        var bridge = instance;
        return bridge == null ? null : bridge.consumer;
    }

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
     *  {@code integrations.map_ingestion.backpressure}); composes UNDER the global
     *  {@code enableIngestBackpressure} (#71 owns the signal path manager-side). */
    final BooleanSupplier backpressureEnabled;
    /** The server allows map writes on this connection (map-policy plan A4) — composed
     *  ONLY on the write paths ({@link #offerColumn}, {@link #pump},
     *  {@link #reportBackpressure}), never into {@link #enabled}, which also drives
     *  registration settling and the diag state. A bare constructor allows. */
    final BooleanSupplier serverAllowsWrites;
    /** Reports a dropped position back to LSS for its bounded re-serve — test seam. */
    final DropReporter dropReporter;
    final VoxelColumnConsumer consumer;
    /** Whether the consumer is currently registered with LSSApi. Main thread only. */
    boolean registered;
    /** A connection exists (the title screen has none): a disabled bridge releases its
     *  consumer at once only without one. Production reads the client's connection. */
    java.util.function.BooleanSupplier connected = () -> true;

    final AtomicLong written = new AtomicLong();
    /** Commits into a cave layer (a band or Full) — the per-layer write diag (§B8). */
    final AtomicLong writtenCave = new AtomicLong();
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
    /** What Xaero shows, published at the top of every pump (§B3); null until read. */
    volatile LayerPlan layerPlan;
    /** Xaero's world id ({@code getCurrentWorldId}), published with the plan; null until read
     *  (and at session end). The fill store is keyed by it (O4-F5). */
    volatile String xaeroWorldId;
    /** The local player — test seam (unknown by default; production reads the client). */
    PlayerProbe playerProbe = () -> null;
    /** The client's effective render distance — test seam (production reads the options). */
    java.util.function.IntSupplier renderDistance = () -> NATIVE_MAX_WRITE_DISTANCE;
    /** The server's LOD distance for the current dimension (0 = unknown) — the fill radius's
     *  second bound. Test seam. */
    java.util.function.IntSupplier serverLodDistance = () -> 0;
    /** The client level fills and captures read (any thread; null = none). Test seam. */
    java.util.function.Supplier<XaeroBandFill.FillLevel> fillLevelProbe = () -> null;
    /** The column-cache bucket of this connection (main thread; null = none yet). Test seam. */
    java.util.function.Supplier<String> storeBucketProbe = () -> null;
    /** {@link #storeBucketProbe} as the last pump read it — what captures key by. */
    volatile String storeBucket;
    /** LOD acquisition is on (the client's {@code lod.receive}) — fills and captures are
     *  acquisition work. Test seam. */
    BooleanSupplier acquisitionActive = () -> true;
    /** An integrated (singleplayer) server is running: Xaero may write from the world save.
     *  Test seam. */
    BooleanSupplier singleplayer = () -> false;
    /** {@code integrations.map_ingestion.nether_store_mib} (MiB; 0 = no store and no fill on
     *  switch), re-read every pump so /lss reload applies it. Test seam. */
    java.util.function.IntSupplier storeMbSetting = () -> 0;
    /** Builds THE column store, once per bridge (production registers it as a column-cache
     *  tenant). Null = the store is not settings-managed: bare sessions and tests assign
     *  {@code fill.store} directly. */
    java.util.function.LongFunction<XaeroColumnStore> storeFactory;
    private XaeroColumnStore columnStore;
    private int appliedStoreMb = -1;
    /** Xaero's WRITING_DISTANCE as the last ladder read it (-1 or unbound = unlimited). Main thread. */
    int writingDistanceConfig = -1;
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
    /** The previous report's heap hold: its END re-bases the halt window — the belt's held
     *  time was pacing, never evidence of a stuck writer (main thread only). */
    boolean lastHeapHold;
    /** The open-region window stopped holding work back while it was still turning over (a
     *  genuine release, not a stall): the next report re-bases the halt window too. A hold
     *  that drops because the window stopped turning over, or because a gate held the pump,
     *  keeps its age — that writer is stuck again (main thread only). */
    boolean windowReleased;
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
    // The open-region window (main thread only). Off (0) for a bare constructor — the
    // test default; production init arms it from the heap size.
    int openRegionCap;
    /** Window regions → 0 once committed into, or the grant time of a reserved load. */
    final java.util.LinkedHashMap<RegionKey, Long> openRegions = new java.util.LinkedHashMap<>();
    /** The last time a region left the window or the window filled (the B1 turnover clock). */
    long windowProgressMillis;
    Object openRegionsDimension;
    volatile int openRegionsGauge;
    final AtomicLong windowWaits = new AtomicLong();
    /** The last drain parked a bucket on a full window (the report's pacing hold). */
    boolean windowBound;
    /** The heap belt (map-ingestion plan §4.3): {@link MapMemoryGuard#NEVER} for a bare
     *  constructor (Tier 1 must not read the test JVM's heap); production arms it. */
    MapMemoryGuard memory = MapMemoryGuard.NEVER;
    /** The last pump held all writes for the heap belt (the report's pacing hold). */
    volatile boolean heapHolding; // read by the fill worker too
    final AtomicLong heapHolds = new AtomicLong();

    /**
     * One column delivery as the bridge holds it: the ingest handle captured in the callback,
     * the acquisition generation it belongs to, and the once-per-column report latch its
     * tiles share (§B5: one re-serve per column, however many of its tiles dropped — the
     * handle dedupes too, the latch also covers the null-handle {@link DropReporter} path).
     */
    final class Receipt {
        final long generation = acquisition.acquisitionGeneration.get();
        final LSSApi.IngestFailureHandle handle = LSSApi.captureIngestFailureHandle();
        final java.util.concurrent.atomic.AtomicBoolean reported = new java.util.concurrent.atomic.AtomicBoolean();
        /** A band-fill job's token is revoked when the job is cancelled (a delivered column's
         *  receipt never is). */
        volatile boolean revoked;

        boolean active() {
            return !this.revoked && !acquisition.retiringAcquisition
                    && this.generation == acquisition.acquisitionGeneration.get()
                    && (this.handle == null || this.handle.isActive());
        }
    }

    /**
     * One tile's hold on its column's {@link Receipt}. A LIVE tile (a delivered column) takes
     * its own {@code deferAcceptance()} lease (§B5: a shared lease would close on the first
     * commit and orphan a sibling's later drop report; {@code ColumnDelivery} counts leases);
     * a band-fill tile (re-extracted from the column store) holds a lease-free generation
     * token: it never reports, owes or re-serves anything.
     */
    final class Origin {
        final Receipt receipt;
        final long generation;
        final LSSApi.IngestFailureHandle handle;
        final boolean live;
        /** The column's surface tile: what "the map has this column" means. Cave tiles and
         *  band-fill tiles say nothing about it. */
        final boolean surface;
        private volatile boolean written;
        final Runnable release;
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        Origin() {
            this(new Receipt(), true);
        }

        Origin(Receipt receipt, boolean live) {
            this(receipt, live, false);
        }

        Origin(Receipt receipt, boolean live, boolean surface) {
            this.receipt = receipt;
            this.generation = receipt.generation;
            this.handle = receipt.handle;
            this.live = live;
            this.surface = surface;
            this.release = !live || this.handle == null ? () -> {} : this.handle.deferAcceptance();
        }

        /** The tile is on the map, or Xaero's own writer covers these chunks. */
        void markWritten() {
            this.written = true;
        }

        boolean active() {
            return !this.closed.get() && this.receipt.active();
        }

        /** The column's one report: the first of its tiles to drop claims it. */
        boolean claimReport() {
            return this.live && this.receipt.reported.compareAndSet(false, true);
        }

        void close() {
            if (!this.closed.compareAndSet(false, true)) return;
            if (this.live && this.surface && this.handle != null) {
                // Issue #380: the delivery learns that the column is on the map. Said here,
                // before the lease below is released, and only for a tile that was written:
                // a delivery that ends without it leaves the map OWED the column, so every
                // path that lets a tile go (switched off, blocked by the server, Xaero's
                // own switches, an expired deferral, a cleared queue, an early return
                // before any tile existed) is covered without having to say anything.
                try {
                    if (this.written) this.handle.written();
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                }
            }
            this.release.run();
        }
    }

    /** Acquisition OFF retains the native world: committed texture rebuilds still run. */
    void onAcquisitionEnd() {
        // Receive OFF: no fill while acquisition is off (the fill gate's acquisition term keeps
        // a new job from starting; coverage stays — the session goes on).
        this.fill.cancel(this.fill.job);
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
        this(h, levelOps, enabled, sessionActive, registrar, deregistrar, backpressureEnabled,
                dropReporter, () -> true);
    }

    XaeroSession(XaeroBindings h, LevelOps levelOps, BooleanSupplier enabled,
                   BooleanSupplier sessionActive,
                   java.util.function.Consumer<VoxelColumnConsumer> registrar,
                   java.util.function.Consumer<VoxelColumnConsumer> deregistrar,
                   BooleanSupplier backpressureEnabled, DropReporter dropReporter,
                   BooleanSupplier serverAllowsWrites) {
        this.serverAllowsWrites = serverAllowsWrites;
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
     * Disabled with NO connection (the title screen): release the consumer now, so the next
     * handshake does not advertise LOD reception for a map the player just turned off
     * (review). The add-only rule exists for a live connection, where a mid-session
     * deregistration turns every arriving column into an ingest failure; without one nothing
     * can arrive. Both map backends share it.
     */
    void settleOfflineRegistration() {
        if (this.registered && !this.enabled.getAsBoolean() && !this.connected.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        }
    }

    /** The client has a play connection (null on the title screen and before JOIN). */
    static boolean productionConnected() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.getConnection() != null;
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
        this.layerPlan = null; // the next session re-reads what Xaero shows
        this.xaeroWorldId = null;
        this.fill.reset(); // coverage is session-scoped; the store closes (its files stay)
        lastProductionFillLevel = null; // the old connection's level facts and registries
        this.loadStats.clearPending();
        // §12 (review m5): the main-thread half re-clears the latches — an
        // in-flight ladder's finally can re-latch AFTER onSessionEnd's off-thread
        // clear; this half runs at the next pump top, after any such ladder.
        this.undrainablePumps = 0;
        this.haltSinceMillis = 0;
        this.lastHeapHold = false;
        this.windowReleased = false;
        this.pumpDrainable = true;
        this.haltWedged = false;
        this.wedgeSinceMillis = 0;
        this.settingsWritesOff = false; // a new session re-observes the switches
        this.openRegions.clear(); // the old world's regions are Xaero's to release now
        this.openRegionsDimension = null;
        this.openRegionsGauge = 0;
        this.windowBound = false;
        this.heapHolding = false;
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
                        level.getMinY(), level.getMaxY() + 1, columnData, level.dimensionType(), level);
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
     * what keeps a long structural block from stopping the fill for good. A HEAP
     * hold (map-ingestion plan §4.3) reports the full halt — the belt stops every
     * write, so an occupancy-scaled report would keep filling the queue with the
     * memory the belt is protecting — and the END of a heap hold or a genuine window
     * release opens a fresh window, so paced time never ages into a wedge on the first
     * report after it.
     * Main client thread (the LSSApi poll).
     */
    int reportBackpressure() {
        boolean released = (this.lastHeapHold && !this.heapHolding) || this.windowReleased;
        this.lastHeapHold = this.heapHolding;
        this.windowReleased = false;
        double occupancy = this.heapHolding
                ? Math.max(this.acquisition.occupancy, BP_HALT_OCCUPANCY) : this.acquisition.occupancy;
        if (!this.backpressureEnabled.getAsBoolean()) return noSignal();
        if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()
                || !this.serverAllowsWrites.getAsBoolean()) { // a blocked map never throttles Voxy
            return noSignal();
        }
        if (this.haltWedged) {
            // §12.9: two re-arm paths. Fast — the queue actually drained back down.
            // Clock — the duty cycle: under sustained arrival ≥ drain the occupancy
            // floor never comes (the one-way-exit MAJOR), so after
            // BP_WEDGE_REARM_MILLIS governance re-enters through the normal path
            // (the halt re-engages at once on a still-full queue, arrivals stop,
            // and the writer finally out-drains a silenced stream).
            if (occupancy < BP_WEDGE_REARM_OCCUPANCY
                    || nowMillis() - this.wedgeSinceMillis > BP_WEDGE_REARM_MILLIS) {
                this.haltWedged = false;
            } else {
                return noSignal();
            }
        }
        if (nowMillis() - this.lastPumpMillis > this.bpPumpStaleMillis) return noSignal();
        int halt = dev.vox.lss.networking.client.LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        int report = (int) Math.round(halt * Math.min(1.0, occupancy / BP_HALT_OCCUPANCY));
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
            double occ = occupancy;
            if (this.haltSinceMillis == 0 || released) {
                this.haltSinceMillis = now;
                this.haltPeakOccupancy = occ;
            } else if (occ < this.haltPeakOccupancy - BP_HALT_PROGRESS_EPS) {
                this.haltSinceMillis = now; // genuinely draining: a fresh window
                this.haltPeakOccupancy = occ;
            } else if (occ > this.haltPeakOccupancy) {
                this.haltPeakOccupancy = occ; // the landing tail: recession is from the peak
            } else if (now - this.haltSinceMillis > wedgeMillis()) {
                long window = wedgeMillis();
                this.haltWedged = true;
                this.wedgeSinceMillis = now;
                this.haltSinceMillis = 0;
                if (now - this.lastWedgeWarnMillis > 60_000) { // cyclical now: rate-limit
                    this.lastWedgeWarnMillis = now;
                    LSSLogger.warn("Xaero map bridge: the map writer made no progress through"
                            + " a " + (window / 1000) + " s backpressure halt —"
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

    /** The halt time-box: {@link #BP_PACE_HOLD_MILLIS} while the open-region window or
     *  the heap belt is holding the stream (Xaero is draining at its frame rate, or memory
     *  is tight — pacing, not a stuck writer), else the §12 stuck-writer window. */
    long wedgeMillis() {
        return this.windowBound || this.heapHolding ? Math.max(BP_PACE_HOLD_MILLIS, this.bpHaltWedgeMillis)
                : this.bpHaltWedgeMillis;
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

    /** Decode-thread entry for a sky-lit, ceiling-less level of these heights (tests). */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData) {
        offerColumn(dimension, chunkX, chunkZ, new XaeroTileExtractor.LevelFacts(worldBottomY, worldTopY,
                true, false, worldTopY - worldBottomY), columnData);
    }

    /** The consumer's entry: the level facts come from the column's own level. */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData,
                     net.minecraft.world.level.dimension.DimensionType type) {
        offerColumn(dimension, chunkX, chunkZ, worldBottomY, worldTopY, columnData, type, null);
    }

    /** {@code clientLevel}: the delivering level (production), the fill store's key source. */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData,
                     net.minecraft.world.level.dimension.DimensionType type, Object clientLevel) {
        offerColumn(clientLevel, dimension, chunkX, chunkZ, type == null
                ? new XaeroTileExtractor.LevelFacts(worldBottomY, worldTopY, true, false, worldTopY - worldBottomY)
                : new XaeroTileExtractor.LevelFacts(worldBottomY, worldTopY, type.hasSkyLight(), type.hasCeiling(),
                        type.logicalHeight()), columnData);
    }

    /**
     * The layers one column writes (§B2/§B3, phase B-I): always the surface, plus the layer
     * Xaero shows — the current band in Layered mode, the Full layer, nothing more on the
     * surface — when the published plan is for THIS column's dimension. On a mismatch (the
     * Nether-entry burst lands before the pump re-reads Xaero) only the surface (O3-2): a
     * guessed band ignored Xaero's roof probe, its cave-mode switches (allowed, Full, off) and
     * its height clamp, so it wrote layers Xaero would not show (a roof-highway entry, a server
     * that disallows cave maps). In a ceiling dimension the column is still kept, and
     * fill-on-switch writes the layer Xaero shows from the store once the plan names the
     * dimension. Cave writes are off without the cave bindings and in world-save mode. Decode
     * thread.
     */
    List<XaeroTileExtractor.LayerTarget> planTargets(Object dimension, XaeroTileExtractor.LevelFacts level) {
        var plan = this.layerPlan;
        int depth = plan != null ? plan.depth() : XaeroTileExtractor.DEFAULT_CAVE_DEPTH;
        var surface = XaeroTileExtractor.LayerTarget.surface(depth);
        if (this.h.caveGate == null || plan == null || plan.worldSave() || plan.dimension() != dimension) {
            return List.of(surface);
        }
        int layer = plan.currentLayer();
        if (layer == SURFACE_LAYER) return List.of(surface);
        if (layer == XaeroTileExtractor.FULL) return List.of(surface, XaeroTileExtractor.LayerTarget.full(depth));
        return List.of(surface, XaeroTileExtractor.LayerTarget.band(layer, level.topY() - 1, depth));
    }

    /**
     * A tile layer that is neither the surface nor the layer Xaero shows for this dimension: a
     * map-only leftover (cave-layers plan fold item 12). It still commits when its region takes
     * it (valid data), but it never reports, never owes and never wins a load grant (O3-1): a
     * re-serve writes only the surface and the shown layer, so its report or debt would only
     * re-download the column and cost the position an ingest strike, and its grants would load
     * regions Xaero saves every 600 ms. Any thread (one volatile read).
     */
    boolean leftoverLayer(Object dimension, int layer) {
        var plan = this.layerPlan;
        return plan != null && layer != SURFACE_LAYER && layer != plan.currentLayer() && plan.dimension() == dimension;
    }

    /**
     * {@link #planTargets(Object, XaeroTileExtractor.LevelFacts)} bounded by distance: a cave
     * layer (band or Full) is written only within min(the server's LOD distance, the fill
     * radius cap) chunks of the player — a Chebyshev square, the fill's bound — in EVERY
     * dimension; the surface stays unbounded. Xaero's auto cave mode switches on under any roof,
     * so an indoor Overworld player would otherwise double the map writes (and the region files)
     * across the whole LOD radius. A player of unknown position skips the bound.
     */
    List<XaeroTileExtractor.LayerTarget> planTargets(Object dimension, int chunkX, int chunkZ,
                                                     XaeroTileExtractor.LevelFacts level) {
        var targets = planTargets(dimension, level);
        if (targets.size() < 2) return targets;
        PlayerPos player = this.playerProbe.position();
        if (player == null || player.dimension() != dimension) return targets;
        int radius = this.fill.fillRadius();
        int px = ((int) Math.floor(player.x())) >> 4;
        int pz = ((int) Math.floor(player.z())) >> 4;
        if (Math.abs(chunkX - px) <= radius && Math.abs(chunkZ - pz) <= radius) return targets;
        this.caveOutOfRange.incrementAndGet();
        return List.of(targets.get(0));
    }

    /** Decode-thread entry: extract + enqueue (latest-wins, bounded, oldest drops). */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     XaeroTileExtractor.LevelFacts facts, VoxelColumnData column) {
        offerColumn(null, dimension, chunkX, chunkZ, facts, column);
    }

    /** {@code clientLevel}: the delivering level (null in tests: the fill-level seam answers). */
    void offerColumn(Object clientLevel, ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     XaeroTileExtractor.LevelFacts level, VoxelColumnData columnData) {
        Receipt receipt = new Receipt();
        List<Origin> origins = List.of();
        try {
            if (!receipt.active() || this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()
                    || !this.serverAllowsWrites.getAsBoolean()) { // map-policy plan A4: no write, no report
                return;
            }
            // Kept BEFORE the settings-off refusal below, on purpose (F1-NIT2): Xaero's own
            // writing switches stop map writes, not LSS's terrain copy — the copy is what writes
            // the shown band once writing is back on (nothing re-sends these columns).
            if (level.ceiling() && this.fill.enabled) captureForFill(dimension, chunkX, chunkZ, level, columnData, clientLevel);
            // §12.9: the one pre-extraction refusal that survives — Xaero's own
            // map-writing switches both off (the ladder clears the queue every pump;
            // extracting for it is pure decode-thread waste). Counted, silent: the
            // map is off by the user's choice, not dropped work.
            if (this.settingsWritesOff && this.backpressureEnabled.getAsBoolean()) {
                this.skippedSettings.incrementAndGet();
                return;
            }
            var targets = planTargets(dimension, chunkX, chunkZ, level);
            var held = new ArrayList<Origin>(targets.size());
            for (var target : targets) {
                held.add(new Origin(receipt, true, !target.cave()));
            }
            origins = held;
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
                // Don't pay the 256-pixel extraction for tiles the full queue would evict
                // on arrival (sustained-overflow CPU on the LOD decode thread): full, and
                // none of the column's (layer, chunk) keys queued for an in-place
                // replacement. Count cap only: the byte cap (which binds first on
                // overlay-heavy tiles — sweep C N3) needs the tile's size, unknown before
                // extraction, and past it the enqueue evicts the OLDEST entry, so that
                // extraction is not wasted.
                if (this.acquisition.queue.size() - this.acquisition.fillCount >= this.maxQueue) {
                    overflowed = true;
                    for (var target : targets) {
                        if (this.acquisition.queue.containsKey(new TileKey(target.layer(), key))) {
                            overflowed = false;
                            break;
                        }
                    }
                    if (overflowed) this.droppedOverflow.addAndGet(targets.size());
                }
            }
            if (overflowed) {
                // Governed overflow is structurally ~0 (the halt fires at 75%); a stray
                // one self-heals via the reporter. OUTSIDE the lock (§18.1 discipline).
                // WI-3: a tile whose region the last pump saw awaiting is OWED instead —
                // its re-serve lands once the region loads (an immediate report would
                // re-serve into the same shed). One report per column (the receipt latch).
                for (int i = 0; i < targets.size(); i++) {
                    var origin = origins.get(i);
                    if (!this.acquisition.shedToOwed(dimension, targets.get(i).layer(), key, origin)) {
                        this.acquisition.reportDroppedIfGoverned(dimension, chunkX, chunkZ, origin);
                    }
                }
                origins = List.of();
                return;
            }
            var plan = this.layerPlan; // one read: session end nulls it from the main thread
            boolean ignoreHeightmaps = plan != null && plan.ignoreHeightmaps();
            var tiles = XaeroTileExtractor.extract(chunkX, chunkZ, level, columnData, targets, ignoreHeightmaps);
            for (int i = 0; i < targets.size(); i++) offerPrepared(dimension, tiles.get(i), origins.get(i));
            origins = List.of(); // every lease is the queue's (or released) now
        } finally {
            // Early returns and throws release every lease (close is idempotent: a throw
            // mid-hand-off also releases the already-queued siblings, whose entries then
            // drop at the drain — the pre-cave single-origin behavior).
            for (var origin : origins) origin.close();
        }
    }

    // ---- fill-on-switch (ceiling dimensions) ----

    /** Keep a delivered ceiling-dimension column for later band fills (decode thread; the
     *  caller passed the session/ingestion/server-allows gates). Not without the cave
     *  bindings, not in world-save mode, and only for the dimension the player is in (a late
     *  column of the dimension just left would reopen that store). */
    void captureForFill(Object dimension, int chunkX, int chunkZ, XaeroTileExtractor.LevelFacts level,
                        VoxelColumnData columnData, Object clientLevel) {
        if (this.h.caveGate == null || this.fill.store == null || !this.acquisitionActive.getAsBoolean()) return;
        var plan = this.layerPlan;
        if (plan != null && plan.dimension() == dimension && plan.worldSave()) return;
        // Singleplayer can be world-save mode (no cave writes, nothing to keep): unknown until
        // the plan for this dimension is read, so nothing is kept before it.
        if ((plan == null || plan.dimension() != dimension) && this.singleplayer.getAsBoolean()) return;
        PlayerPos player = this.playerProbe.position();
        if (player == null || player.dimension() != dimension) return;
        var fillLevel = clientLevel != null ? productionFillLevel(clientLevel) : this.fillLevelProbe.get();
        if (fillLevel == null || fillLevel.dimension() != dimension) return;
        this.fill.capture(fillLevel, this.storeBucket, this.xaeroWorldId, dimension, chunkX, chunkZ, columnData);
    }

    /** The store setting, every pump (main thread): a new value re-caps the store; 0 detaches
     *  it and deletes its files; the {@code -Dlss.xaero.bandFill=false} kill switch detaches
     *  it and keeps them. */
    void applyStoreSetting() {
        if (this.storeFactory == null) return;
        int mb = Math.max(0, this.storeMbSetting.getAsInt());
        int effective = this.fill.enabled ? mb : 0;
        if (effective == this.appliedStoreMb) return;
        this.appliedStoreMb = effective;
        if (effective == 0) {
            this.fill.onWorldChange(); // the job, coverage and stale set named the store
            this.fill.store = null;
            if (this.fill.enabled) { // the user's 0, not the kill switch: give the disk back
                if (this.columnStore == null) {
                    this.columnStore = this.storeFactory.apply(XaeroColumnStore.DEFAULT_CAP_BYTES);
                }
                this.columnStore.purgeAll();
            } else if (this.columnStore != null) {
                this.columnStore.closeActive();
            }
            return;
        }
        long cap = (long) Math.max(16, effective) << 20;
        if (this.columnStore == null) {
            this.columnStore = this.storeFactory.apply(cap);
        } else {
            this.columnStore.setCapBytes(cap);
            this.columnStore.resetSession(); // a settings change retries a write-failed store
        }
        this.fill.store = this.columnStore;
    }

    /** The fill trigger, every pump (main thread). */
    void tickFill() {
        if (this.fill.store == null) return;
        this.storeBucket = this.storeBucketProbe.get();
        this.fill.tick(this.layerPlan, this.fillLevelProbe.get(),
                writesOpen() && this.h.caveGate != null && !this.settingsWritesOff
                        && this.acquisitionActive.getAsBoolean(), this.storeBucket, this.xaeroWorldId);
    }

    /** Writes are open: alive, enabled, in a session, and allowed by the server. */
    boolean writesOpen() {
        return !this.dead && this.enabled.getAsBoolean() && this.sessionActive.getAsBoolean()
                && this.serverAllowsWrites.getAsBoolean();
    }

    /** The fill worker may offer: writes are open, the heap belt is not holding, and the
     *  reliable queue is short (live arrivals keep priority). */
    boolean fillRoom() {
        return writesOpen() && !this.heapHolding && !this.settingsWritesOff
                && this.acquisition.queuedGauge < this.fill.queueHigh;
    }

    /** One band-fill tile into the reliable queue, lease-free (any thread). Its generation token
     *  is the job's receipt (O3 N2): the queue lock re-checks it, so a job cancelled between the
     *  worker's own check and the offer lands nothing. */
    boolean offerFill(Object dimension, XaeroTileExtractor.PreparedTile tile, Receipt jobReceipt) {
        return this.acquisition.offerFill(dimension, tile, new Origin(jobReceipt, false));
    }

    /** Test seam: a fill tile under a fresh token. */
    boolean offerFill(Object dimension, XaeroTileExtractor.PreparedTile tile) {
        return offerFill(dimension, tile, new Receipt());
    }

    /** The production fill-level cache entry: the level is held WEAKLY — it is only the cache's
     *  identity key, and a strong hold pinned a whole ClientLevel (chunks, light, entities) past
     *  leaving its dimension (O4-F1). */
    record CachedFillLevel(java.lang.ref.WeakReference<Object> level, XaeroBandFill.FillLevel fill) {}

    /** The last level's fill facts (any thread); cleared at session end so the old connection's
     *  registries do not outlive it either. */
    static volatile CachedFillLevel lastProductionFillLevel;

    /** {@code level}'s fill facts from the cache, else {@code compute}d and cached (keyed weakly). */
    static XaeroBandFill.FillLevel cachedFillLevel(Object level,
                                                   java.util.function.Function<Object, XaeroBandFill.FillLevel> compute) {
        var cached = lastProductionFillLevel;
        if (cached != null && cached.level().get() == level) return cached.fill();
        var fill = compute.apply(level);
        lastProductionFillLevel = new CachedFillLevel(new java.lang.ref.WeakReference<>(level), fill);
        return fill;
    }

    /** The current client level's fill facts (main thread). */
    static XaeroBandFill.FillLevel productionFillLevel() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc == null || mc.level == null ? null : productionFillLevel(mc.level);
    }

    /** A client level's fill facts, cached per level (any thread: registries are frozen). */
    static XaeroBandFill.FillLevel productionFillLevel(Object clientLevel) {
        return cachedFillLevel(clientLevel, XaeroSession::computeFillLevel);
    }

    private static XaeroBandFill.FillLevel computeFillLevel(Object clientLevel) {
        var level = (net.minecraft.client.multiplayer.ClientLevel) clientLevel;
        var type = level.dimensionType();
        var facts = new XaeroTileExtractor.LevelFacts(level.getMinY(), level.getMaxY() + 1, type.hasSkyLight(),
                type.hasCeiling(), type.logicalHeight());
        var registries = level.registryAccess();
        return new XaeroBandFill.FillLevel(level.dimension(),
                dev.vox.lss.networking.client.ColumnCacheStore.dimensionFileKey(level.dimension()), facts,
                net.minecraft.world.level.chunk.PalettedContainerFactory.create(registries),
                registryFingerprint(registries, facts));
    }

    /** What the stored ids mean: the block-state id space, the biome registry's id → key
     *  order, and the dimension's heights. A different value discards a store. */
    static long registryFingerprint(net.minecraft.core.RegistryAccess registries, XaeroTileExtractor.LevelFacts facts) {
        long h = 0xcbf29ce484222325L;
        // The stored format: the store's segment version and the column codec's.
        h = mix(h, XaeroColumnStore.VERSION);
        h = mix(h, XaeroColumnCodec.FORMAT);
        h = mix(h, net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        // Block-state ids: every block's key and state count, in id order (a mod set that keeps
        // the same total but reorders or swaps blocks changes this).
        var blocks = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        for (int id = 0; id < blocks.size(); id++) {
            var block = blocks.byId(id);
            if (block == null) {
                h = mix(h, 0);
                continue;
            }
            var key = blocks.getKey(block);
            h = mix(h, key == null ? 0 : key.toString().hashCode());
            h = mix(h, block.getStateDefinition().getPossibleStates().size());
        }
        h = mix(h, net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size());
        // Biome ids: this connection's synced registry, id → key.
        var biomes = registries.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        for (int id = 0; id < biomes.size(); id++) {
            var biome = biomes.byId(id);
            var key = biome == null ? null : biomes.getKey(biome);
            h = mix(h, key == null ? 0 : key.toString().hashCode());
        }
        h = mix(h, facts.minY());
        h = mix(h, facts.topY());
        return mix(h, facts.logicalHeight());
    }

    private static long mix(long h, int value) {
        return (h ^ value) * 0x100000001b3L;
    }

    static boolean productionSingleplayer() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.hasSingleplayerServer();
    }

    /** This connection's column-cache bucket (main thread). */
    static String productionStoreBucket() {
        var manager = dev.vox.lss.networking.client.ClientNetGlue.getRequestManager();
        return manager == null ? null : manager.cacheBucket();
    }

    /** {@code -Dlss.xaero.bandFill.storeMB}: a dev override of the client setting. */
    private static final Integer STORE_MB_OVERRIDE = Integer.getInteger("lss.xaero.bandFill.storeMB");

    /** The store cap in MiB: the dev override, else {@code integrations.map_ingestion.nether_store_mib}. */
    static int productionStoreMb() {
        return STORE_MB_OVERRIDE != null ? STORE_MB_OVERRIDE : LSSClientConfig.CONFIG.mapIngestionNetherStoreMb();
    }

    /** The production store: inside the column cache's buckets, closed around every clear. */
    static XaeroColumnStore productionColumnStore(long capBytes) {
        var codec = dev.vox.lss.networking.payloads.ZstdWireSupport.codecOrNull();
        var store = new XaeroColumnStore(dev.vox.lss.networking.client.ColumnCacheStore::bucketDir,
                dev.vox.lss.networking.client.ColumnCacheStore::cacheRootPath, codec, capBytes);
        dev.vox.lss.networking.client.ColumnCacheStore.addBucketTenant(
                new dev.vox.lss.networking.client.ColumnCacheStore.BucketTenant() {
                    @Override
                    public void beforeClear() {
                        store.suspendForClear();
                    }

                    @Override
                    public void afterClear() {
                        store.resumeAfterClear();
                    }
                });
        return store;
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

    java.util.Set<RegionKey> awaitingRegionsForTest() {
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
        return hasQueuedForTest(SURFACE_LAYER, chunkX, chunkZ);
    }

    boolean hasQueuedForTest(int layer, int chunkX, int chunkZ) {
        synchronized (this.acquisition.queueLock) {
            return this.acquisition.queue.containsKey(new TileKey(layer, ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL)));
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
            case "written_cave" -> this.writtenCave.get();
            case "xaero_crashed" -> this.xaeroCrashed ? 1 : 0;
            case "load_count" -> this.loadStats.loads.total;
            case "region_vanished" -> this.loadStats.vanished.get();
            case "defer_not_resting" -> this.defers.notResting.get();
            case "defer_capacity" -> this.defers.capacity.get();
            case "capped_pumps" -> this.cappedPumps.get();
            case "cave_out_of_range" -> this.caveOutOfRange.get();
            case "dropped_stale_layer" -> this.droppedStaleLayer.get();
            case "fill_rearms" -> this.fill.rearms.get();
            case "fill_moved_passes" -> this.fill.movedPasses.get();
            case "fill_bad_columns" -> this.fill.badColumns.get();
            case "store_corrupt" -> this.fill.store == null ? 0 : this.fill.store.corrupt.get();
            case "fill_queued" -> this.acquisition.fillQueuedGauge;
            case "fill_started" -> this.fill.started.get();
            case "fill_completed" -> this.fill.completed.get();
            case "fill_cancelled" -> this.fill.cancelled.get();
            case "fill_tiles" -> this.fill.tilesOffered.get();
            case "fill_committed" -> this.fill.tilesCommitted.get();
            case "fill_skipped_covered" -> this.fill.skippedCovered.get();
            case "fill_skipped_queued" -> this.fill.skippedQueued.get();
            case "store_columns" -> this.fill.store == null ? 0 : this.fill.store.columnsGauge;
            case "store_capture_drops" -> this.fill.store == null ? 0 : this.fill.store.captureDrops.get();
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
        // Map-policy plan A5: the server's request, not a local state — name it.
        if (!this.serverAllowsWrites.getAsBoolean()) return "-1(blocked_by_server)";
        if (this.haltWedged) return "-1(wedged)";
        if (nowMillis() - this.lastPumpMillis > this.bpPumpStaleMillis) return "-1(stale)";
        String f = String.format(java.util.Locale.ROOT, "%.2f", this.acquisition.occupancy);
        return this.pumpDrainable ? f : f + "(blocked)"; // §12.8: blocked still governs
    }

    /** The layer Xaero shows, as the last pump read it ({@code -} before the first read). */
    String layerToken() {
        var plan = this.layerPlan;
        return plan == null ? "-" : plan.layerToken();
    }

    /** surface | layered | full — or why cave writes are off (§B8: the diag says so). */
    String modeToken() {
        if (this.h.caveGate == null) return "surface(cave_unbound)";
        var plan = this.layerPlan;
        if (plan == null) return "-";
        if (plan.worldSave()) return plan.mode() + "(cave_off:world_save)";
        return plan.mode();
    }

    String describe() {
        String state = this.dead ? "dead" : !this.enabled.getAsBoolean() ? "disabled"
                : !this.serverAllowsWrites.getAsBoolean() ? "blocked_by_server" : "active";
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
                + ", written_cave=" + this.writtenCave.get()
                + ", layer=" + layerToken() + ", mode=" + modeToken()
                + ", " + this.fill.describe()
                + ", drops_reported=" + this.acquisition.dropsReported.get()
                + ", owed=" + this.acquisition.owedGauge
                + ", owed_regions=" + this.acquisition.owedRegionsGauge
                + ", owed_reported=" + this.acquisition.owedReported.get()
                + ", owed_evicted=" + this.acquisition.owedEvicted.get()
                + ", bp=" + bpToken()
                + ", open_regions=" + this.openRegionsGauge + "/" + this.openRegionCap
                + ", window_waits=" + this.windowWaits.get()
                + ", heap=" + this.memory.token()
                + (this.xaeroCrashed ? ", xaero_crashed=true" : "")
                + (this.settingsGateBroken ? ", settings_gate=broken" : "")
                + (this.h.optionalMissing != null ? ", optional_unbound=" + this.h.optionalMissing : "");
    }

    // ---- the pump (main client thread) ----

    void pump() {
        if (this.sessionEndPending) settleSessionEnd();
        maybeRegister();
        settleOfflineRegistration();
        if (this.dead) {
            // A dead bridge must not pin Xaero's regions/tile chunks (each leaf
            // texture holds a direct buffer) for the rest of the session (review B).
            if (!this.rebuilds.pendingUpdates.isEmpty()) {
                this.rebuilds.pendingUpdates.clear();
                this.rebuilds.pendingUpdatesGauge = 0;
            }
            this.undrainablePumps = 0; // no ladder ran: the consecutive chain breaks
            this.fill.cancel(this.fill.job);
            return;
        }
        this.rebuilds.pumpCount++;
        this.lastPumpMillis = nowMillis(); // §12 watchdog: the pump machinery is alive
        if (this.enabled.getAsBoolean()) publishLayerPlan();
        try {
            applyStoreSetting();
            tickFill();
        } catch (Throwable t) {
            // Contained like the ladder (F1-NIT3): the store setting and the fill trigger start
            // threads, register a cache tenant and hash registries — a failure there is a bridge
            // failure (the death latch), never a client-tick crash.
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            if (this.dead) return;
        }
        // §17.1 (review fold): the frame marker is consumed HERE, once per pump — and
        // the interval allowance / frame-scarcity meters re-arm.
        this.rebuilds.frameActiveThisPump = this.rebuilds.frameFlushRan;
        this.rebuilds.frameFlushRan = false;
        this.rebuilds.rebuildSpentSinceLastPumpNanos = 0;
        this.rebuilds.framesSinceLastPump = 0;
        if (!this.enabled.getAsBoolean() || !this.serverAllowsWrites.getAsBoolean()) {
            // (Map-policy plan A4: a server deny takes the local-off branch — queue and debt
            // dropped unreported, committed rebuilds still flush.)
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
     * Publishes what Xaero shows (§B3) — at the TOP of the pump, ahead of every gate, so a
     * blocked or idle pump still tracks layer switches and the Nether entry. Plain reads of
     * Xaero's own fields, tagged with Xaero's current dimension (the decode thread compares
     * it with each column's). A throw is a drifted Xaero: the bridge's failure latch.
     */
    void publishLayerPlan() {
        var g = this.h.caveGate;
        if (g == null) return;
        try {
            if (this.h.crashGate != null) {
                // The one-tick crash shield holds here too: never touch a crashed Xaero.
                Object handler = this.h.crashGate.crashHandler().invoke();
                if (handler != null && this.h.crashGate.getCrashedBy().invoke(handler) != null) return;
            }
            Object session = this.h.getCurrentSession.invoke();
            if (session == null || !(boolean) this.h.sessionIsUsable.invoke(session)) return;
            Object mp = this.h.getMapProcessor.invoke(session);
            if (mp == null) return;
            // Xaero's world: the fill store's key (O4-F5); read ahead of the dimension so a world
            // change is seen no later than the ladder's own world-id check.
            this.xaeroWorldId = (String) this.h.getCurrentWorldId.invoke(mp);
            Object mapWorld = this.h.getMapWorld.invoke(mp);
            if (mapWorld == null) return;
            Object dimensionId = this.h.getCurrentDimensionId.invoke(mapWorld);
            if (dimensionId == null) return;
            int layer = (int) g.getCurrentCaveLayer().invoke(mp);
            int depth = (int) this.h.getCaveModeDepthConfig.invoke(mp);
            boolean ignoreHeightmaps = (boolean) g.isIgnoreHeightmaps().invoke(mapWorld);
            Object mapDimension = g.getCurrentDimension().invoke(mapWorld);
            boolean worldSave = mapDimension != null && (boolean) g.isUsingWorldSave().invoke(mapDimension);
            var plan = new LayerPlan(dimensionId, layer, depth, ignoreHeightmaps, worldSave);
            if (!plan.equals(this.layerPlan)) this.layerPlan = plan;
            if (this.h.getToSave != null) {
                // Xaero reads this list unsynchronized too; a size is all the exporter needs.
                int toSave = ((java.util.List<?>) this.h.getToSave.invoke(this.h.getMapSaveLoad.invoke(mp))).size();
                this.toSaveGauge = toSave;
                if (toSave > this.toSaveMax) this.toSaveMax = toSave;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
        }
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
                // A gate block, the cave layer, a dead bridge: whatever holds the stream,
                // it is not the window or the heap belt — no 60 s pacing box for it.
                this.windowBound = false;
                this.heapHolding = false;
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
                // The fill follows Xaero's world itself (tick, at the top of this pump, from the
                // published world id — O4-F5): its job is already cancelled and its token
                // revoked (O3 N2), so no stored column of the old world lands after this clear.
                this.droppedStale.addAndGet(clearQueueCollectingReports());
                this.acquisition.clearOwedCollectingReports(); // WI-3: the old world's debts are reported like its tiles
                this.loadStats.clearPending(); // O3 N3: never follow the old world's region objects
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
                    // The native writer's distance input (getWriteDistance): unlimited in
                    // world-save mode or when unset/negative (§B5 natively-writable skip).
                    Object w = this.h.writingDistanceOption == null ? null
                            : g.getEffective().invoke(manager, this.h.writingDistanceOption.invoke());
                    this.writingDistanceConfig = worldSave || !(w instanceof Integer d) ? -1 : d;
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                    this.settingsGateBroken = true;
                    loadNew = true;
                    update = true;
                    this.writingDistanceConfig = -1;
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
            boolean heapHalt = this.backpressureEnabled.getAsBoolean() && this.memory.halted();
            // §4.3: owed rebuilds still flush under heap pressure, and their regions stay
            // visited until they do — letting them park mid-rebuild loses those tiles
            // silently (review); the window already bounds how many regions are open.
            this.rebuilds.tickFlush(mp, dimensionId);
            if (this.dead) return false;
            // No cave-layer wait (§B5): a tile's layer is fixed at extraction and commits to
            // that layer — a queued band tile whose band is no longer shown is still valid data.
            probeOpenRegions(mp, dimensionId);
            this.heapHolding = heapHalt;
            if (heapHalt) {
                // §4.3: no commits, no load grants, no owed probes — entries stay queued
                // and the report paces the stream until the heap recovers (duty-cycled).
                this.heapHolds.incrementAndGet();
                this.acquisition.regionsWaiting = 0;
                this.windowBound = false;
                return true;
            }
            drainEntries(mp, saveLoad, world, dimensionId, loadNew, update, nativeReach(dimensionId));
            return true;
        }
    }

    /**
     * The native writer's reach this pump (§B5): it writes only the layer it currently writes
     * (the layer Xaero shows — the writer follows it after a 300 ms debounce), only within its
     * write distance — min(WRITING_DISTANCE, 32, render distance), and min(16, that) for a cave
     * layer — and only chunks whose 8 neighbours are loaded ({@link XaeroTileWriter#nativelyWritable}).
     * Without the cave bindings or a plan for this dimension, the writer is assumed on the
     * surface (the pre-cave rule). Two native cases this does not model (writeMap): with the
     * map screen open a cave layer is NOT capped at 16 (the bridge then writes tiles 17..32 the
     * native writer also writes — harmless duplicates), and while a layer switch is in progress
     * (its caveStart differs from the one last written) the native reach drops to 4 chunks for
     * that sweep (tiles 5..16 the bridge skipped are written by the native writer's next sweep).
     */
    NativeReach nativeReach(Object dimensionId) {
        var plan = this.layerPlan;
        int layer = this.h.caveGate != null && plan != null && plan.dimension() == dimensionId
                ? plan.currentLayer() : SURFACE_LAYER;
        int distance = this.writingDistanceConfig < 0 ? Integer.MAX_VALUE : this.writingDistanceConfig;
        distance = Math.min(distance, Math.min(NATIVE_MAX_WRITE_DISTANCE, this.renderDistance.getAsInt()));
        if (layer != SURFACE_LAYER) distance = Math.min(NATIVE_CAVE_WRITE_DISTANCE, distance);
        PlayerPos player = this.playerProbe.position();
        if (player != null && player.dimension() != dimensionId) player = null;
        return new NativeReach(layer, distance, player);
    }

    /** Cave writes are on for this dimension: the cave bindings resolved and Xaero does not
     *  write it from the world save (§B5). */
    boolean caveWritesOn() {
        var plan = this.layerPlan;
        return this.h.caveGate != null && (plan == null || !plan.worldSave());
    }

    /**
     * Per-frame flush (plan §17/§17.1): the texture-rebuild phase runs at FRAME
     * cadence — Xaero's own sweep scheduling — instead of bunched on the client
     * tick, at most a pressure-capped few recolors per call (base
     * {@link #frameMaxRebuilds}; scarce-frame bumps; allowance-bounded). Mirrors the
     * pump ladder's gate envelope exactly down to the dimension equality (a rebuild
     * must never run under weaker gates than the pump's flush did); the settings
     * gate sits BELOW the flush in the pump ladder (rebuilds are owed debt to
     * already-committed tile chunks, not new writes) and is skipped here for the
     * same reason. Never drains commits, never grants loads, never visits
     * regions (tick-side — the 1 s park guard needs only pump cadence), and defers
     * the world-change queue drop to the pump (it returns instead). Shares the
     * pump's containment + death latch.
     */
    void frameFlush() {
        this.rebuilds.frameFlush();
    }

    /** One queue entry paired with its key for the bucketed drain. */
    record Pending(TileKey key, Entry entry, XaeroTileExtractor.PreparedTile tile, Origin origin) {}

    /** A region probed this pump whose bucket is awaiting its Xaero load — the
     *  verdict is Xaero's own state, read inside the probe's region monitor. */
    record WaitingRegion(RegionKey regionKey, int tiles, Outcome verdict) {}

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
        drainEntries(mp, saveLoad, world, dimensionId, loadNew, update, NativeReach.SURFACE_ANYWHERE);
    }

    void drainEntries(Object mp, Object saveLoad,
                              Object world, Object dimensionId,
                              boolean loadNew, boolean update, NativeReach reach) throws Throwable {
        long generation = this.acquisition.acquisitionGeneration.get();
        long start = System.nanoTime();

        List<Pending> snapshot;
        synchronized (this.acquisition.queueLock) {
            snapshot = new ArrayList<>(this.acquisition.queue.size());
            for (var e : this.acquisition.queue.entrySet()) {
                snapshot.add(new Pending(e.getKey(), e.getValue(), e.getValue().tile, e.getValue().origin));
            }
        }
        var buckets = new LinkedHashMap<RegionKey, List<Pending>>(); // keeps spiral locality
        for (var pending : snapshot) {
            var regionKey = new RegionKey(pending.tile().layer(),
                    regionKeyOf(pending.tile().chunkX(), pending.tile().chunkZ()));
            buckets.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(pending);
        }
        boolean caveWritesOn = caveWritesOn();

        var bucketKeys = new ArrayList<>(buckets.keySet());
        var waiting = new ArrayList<WaitingRegion>();
        int commits = 0;
        boolean progressed = false;
        boolean windowWaited = false;
        int size = bucketKeys.size();
        int startIndex = size == 0 ? 0 : Math.floorMod(this.acquisition.drainRotation++, size);
        boolean capped = false;
        bucketLoop:
        for (int n = 0; n < size; n++) {
            RegionKey regionKey = bucketKeys.get((startIndex + n) % size);
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
                    this.cappedPumps.incrementAndGet();
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
                if (pending.tile().cave() && !caveWritesOn) {
                    // World-save mode came on after this cave tile was extracted (§B5):
                    // cave writes are off — dropped, unreported (the native writer owns it).
                    if (this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        pending.origin().close();
                    }
                    progressed = true;
                    continue;
                }
                if (this.writer.nativelyWritable(world, pending.tile(), reach)) {
                    // The native writer owns these chunks and rewrites them on its
                    // clean-flag anyway — never fight it (plan §2.6) — but only in the
                    // layer it writes, within its distance (§B5).
                    if (this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        pending.origin().markWritten(); // the map has it, from the real chunks
                        pending.origin().close();
                        this.skippedNative.incrementAndGet();
                        // The native writer covers it in this layer: a fill need not.
                        if (pending.tile().cave()) {
                            this.fill.markCovered(pending.entry().dimension, regionKey.layer(), pending.key().chunk());
                        }
                    }
                    progressed = true;
                    continue;
                }
                if (!windowAdmits(regionKey)) {
                    // §4.1: the window is full of regions Xaero has not released yet —
                    // this bucket waits (entries retained) and the report paces the stream.
                    this.windowWaits.incrementAndGet();
                    windowWaited = true;
                    continue bucketLoop;
                }
                progressed = true;
                var outcome = this.writer.commitEntry(mp, dimensionId, pending.tile(), loadNew, update);
                switch (outcome) {
                    case COMMITTED -> {
                        this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        pending.origin().markWritten();
                        pending.origin().close();
                        if (this.openRegionCap > 0) admitToWindow(regionKey, 0L);
                        this.written.incrementAndGet();
                        if (pending.tile().cave()) {
                            this.writtenCave.incrementAndGet();
                            // Fill-on-switch coverage: this layer has this position this session.
                            this.fill.markCovered(pending.entry().dimension, regionKey.layer(), pending.key().chunk());
                        }
                        if (!pending.origin().live) this.fill.tilesCommitted.incrementAndGet();
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
                            // never a strike against the stalled resource (a leftover
                            // layer's stays silent: oweExpired refuses it, O3-1).
                            this.acquisition.oweExpired(pending.entry().dimension, regionKey.layer(),
                                    pending.key().chunk(), regionKey.region(), pending.origin());
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
                        expireStaleLayer(regionKey, bucket, dimensionId);
                        continue bucketLoop;
                    }
                    case AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT -> {
                        // The whole bucket waits on this region's load: one defer
                        // event per BUCKET per pump, entries stay queued (awaiting-
                        // load is exempt from the deferral cap), and the verdict
                        // feeds the grant phase's memoryless window — except a
                        // leftover layer's bucket, which never competes for a load
                        // (fold item 12, O3-1); one whose load is already in flight
                        // still counts against the in-flight budget.
                        this.deferEvents.incrementAndGet();
                        boolean leftover = expireStaleLayer(regionKey, bucket, dimensionId);
                        if (!leftover || outcome == Outcome.AWAITING_IN_FLIGHT) {
                            waiting.add(new WaitingRegion(regionKey, bucket.size(), outcome));
                        }
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
        // Pacing, not a stuck writer, while the window held work back AND it is still
        // turning over (a region left, or it filled, within the pace window). Commits into
        // already-open regions do not clear it: a trickle that cannot recede the occupancy
        // is exactly what the window makes, and the short time box would wedge it
        // (re-review; the turnover clause bounds a window that is wide enough).
        boolean turningOver = nowMillis() - this.windowProgressMillis <= BP_PACE_HOLD_MILLIS;
        if (this.windowBound && !windowWaited && turningOver) this.windowReleased = true;
        this.windowBound = windowWaited && turningOver;
        this.openRegionsGauge = this.openRegions.size();
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
                var awaiting = new java.util.HashSet<RegionKey>();
                for (var w : waiting) awaiting.add(w.regionKey());
                this.acquisition.awaitingRegions = awaiting;
            }
        }
        grantLoads(mp, saveLoad, waiting);
    }

    /**
     * A held bucket of a layer that is neither the one Xaero shows nor the surface (tiles queued
     * before a switch): exempt from the deferral cap like every region-scoped wait, it could pin
     * occupancy — and so pace Voxy — for a region that never rests or loads. Its entries expire
     * SILENTLY after {@link #staleLayerPumps} held pumps (no report, no debt: they are map-only
     * leftovers, and fill-on-switch refills that layer from the store when it is shown again).
     * An entry of another dimension in the bucket is left to the stale-dimension filter.
     * @return true when the bucket is such a leftover (the caller grants it no load).
     */
    private boolean expireStaleLayer(RegionKey regionKey, List<Pending> bucket, Object dimensionId) {
        if (!leftoverLayer(dimensionId, regionKey.layer())) return false;
        for (var pending : bucket) {
            if (pending.entry().dimension != dimensionId) continue;
            if (++pending.entry().staleLayerPumps > this.staleLayerPumps
                    && this.acquisition.removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                pending.origin().close();
                this.droppedStaleLayer.incrementAndGet();
            }
        }
        return true;
    }

    /** Whether a commit may land in {@code regionKey}: the window is off, already holds
     *  the region, or has a free slot (the region joins on its first COMMITTED). */
    boolean windowAdmits(RegionKey regionKey) {
        return this.openRegionCap <= 0 || this.openRegions.containsKey(regionKey)
                || this.openRegions.size() < this.openRegionCap;
    }

    /** A load granted into the window keeps its slot this long before the loader starts it. */
    static final long GRANT_RESERVE_MILLIS = 30_000;

    /** Enters (or refreshes) a window region; {@code reservedAt} 0 = committed into. A grant
     *  always (re)stamps its reservation — also onto a committed region, whose reload is then
     *  in flight and must not be released as parked (re-review); a commit turns a
     *  reservation into a committed slot. */
    void admitToWindow(RegionKey regionKey, long reservedAt) {
        Long previous = this.openRegions.get(regionKey);
        if (reservedAt == 0L && previous != null && previous == 0L) return;
        this.openRegions.put(regionKey, reservedAt);
        if (previous == null && this.openRegions.size() >= this.openRegionCap) this.windowProgressMillis = nowMillis();
    }

    /** Releases window regions Xaero has parked (loadState 3/4) or removed — loading (1) and
     *  loaded (2) regions stay, as does a reserved grant within {@link #GRANT_RESERVE_MILLIS}
     *  — at most {@value #OPEN_REGION_PROBES_PER_PUMP} per pump, oldest first, a still-open
     *  region moving to the back (round-robin without a cursor over a changing set). A
     *  dimension change empties the window (its keys name another dimension's regions). */
    void probeOpenRegions(Object mp, Object dimensionId) throws Throwable {
        if (this.openRegionsDimension != dimensionId) {
            this.openRegions.clear();
            this.openRegionsDimension = dimensionId;
            this.loadStats.clearPending(); // O3 N3: the grants name the old dimension's regions
        }
        if (this.openRegions.isEmpty()) {
            this.openRegionsGauge = 0;
            return;
        }
        long now = nowMillis();
        var batch = new ArrayList<java.util.Map.Entry<RegionKey, Long>>(OPEN_REGION_PROBES_PER_PUMP);
        for (var e : this.openRegions.entrySet()) {
            if (batch.size() >= OPEN_REGION_PROBES_PER_PUMP) break;
            batch.add(java.util.Map.entry(e.getKey(), e.getValue()));
        }
        for (var e : batch) {
            RegionKey key = e.getKey();
            Object region = this.h.getLeafMapRegion.invoke(mp, key.layer(), (int) (key.region() >> 32),
                    (int) key.region(), false);
            byte state = -1;
            if (region != null) {
                synchronized (region) {
                    state = (byte) this.h.getLoadState.invoke(region);
                }
                this.loadStats.onObserve(key, region, state, now);
            } else {
                this.loadStats.onMissing(key);
            }
            boolean reserved = e.getValue() != 0L && now - e.getValue() < GRANT_RESERVE_MILLIS;
            this.openRegions.remove(key);
            if (state == 1 || state == 2 || reserved) {
                this.openRegions.put(key, e.getValue()); // still open: to the back of the round
            } else if (e.getValue() == 0L || state == 3 || state == 4) {
                // Turnover: Xaero released a region. An expired reservation whose load never
                // started frees its slot without being progress (re-review).
                this.windowProgressMillis = now;
            }
        }
        this.openRegionsGauge = this.openRegions.size();
    }

    /** Owe a deferral-expired tile (pump side; governed only, like every owe) — a
     *  TILE-scoped debt: released once its own tile chunk is ready. */
    // Direct debt seam retained for the owed-set fixture; production transfers an existing origin.
    void oweExpired(Object dimension, long packedChunk, long regionKey) {
        this.acquisition.oweExpired(dimension, SURFACE_LAYER, packedChunk, regionKey);
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
        int inFlightOutsideWindow = 0;
        var candidates = new ArrayList<WaitingRegion>();
        for (var w : waiting) {
            if (w.verdict() == Outcome.AWAITING_IN_FLIGHT) {
                inFlight++;
                if (!this.openRegions.containsKey(w.regionKey())) inFlightOutsideWindow++;
            } else {
                candidates.add(w);
            }
        }
        int budget = MAX_OUTSTANDING_LOADS - inFlight;
        // §4.1 (approach D(iii)): a granted load becomes a resident region, so loads
        // count against the open-region window — far owed regions are not reloaded
        // while the window is full.
        // A grant reserves its window slot (B4): loads in flight hold theirs, and a load still
        // queued past its reservation counts too (re-review: no transient overshoot).
        if (this.openRegionCap > 0) {
            budget = Math.min(budget, this.openRegionCap - this.openRegions.size() - inFlightOutsideWindow);
        }
        if (budget <= 0 || candidates.isEmpty()) return;
        candidates.sort((a, b) -> Integer.compare(b.tiles(), a.tiles()));
        var chosen = candidates.subList(0, Math.min(budget, candidates.size()));
        for (int i = chosen.size() - 1; i >= 0; i--) {
            if (this.dead) return;
            if (requestRegionLoad(mp, saveLoad, chosen.get(i).regionKey())) {
                this.loadRequests.incrementAndGet();
                if (this.openRegionCap > 0) admitToWindow(chosen.get(i).regionKey(), Math.max(1L, nowMillis()));
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
    boolean requestRegionLoad(Object mp, Object saveLoad, RegionKey regionKey) {
        try {
            int regionX = (int) (regionKey.region() >> 32);
            int regionZ = (int) regionKey.region();
            Object region = this.h.getLeafMapRegion.invoke(mp, regionKey.layer(),
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
                this.loadStats.onGrant(regionKey, region, nowMillis());
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

    static PendingKey pendingKey(Object dimension, int layer, int tileChunkX, int tileChunkZ) {
        return new PendingKey(dimension, layer, ((long) tileChunkX << 32) | (tileChunkZ & 0xFFFFFFFFL));
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
