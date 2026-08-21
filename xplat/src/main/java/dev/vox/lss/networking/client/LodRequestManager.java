package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

public class LodRequestManager {

    // Log-sweep hygiene (2026-08-13): per-column/per-frame failure sites aggregate to
    // one line/min — a persistent condition must not flood the client log.
    private static final dev.vox.lss.common.LogThrottle BATCH_SEND_FAIL_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    /** Backpressure threshold: halt sending when column processing queue exceeds this fraction. */
    private static final int BACKPRESSURE_NUMERATOR = 3;
    private static final int BACKPRESSURE_DENOMINATOR = 4;
    /**
     * Halt point for the CONSUMER-reported ingest backlog (issue #71,
     * docs/planning/ingest-backpressure-design.md): pending sections at which declarations
     * halt entirely. ~750–1200 real terrain columns ≈ 20–60 MB of retained section data at
     * steady state — bounded heap even on a small client. The budget taper (SpiralScanner)
     * scales against the same constant, so the halt is a backstop, not the operating point:
     * at equilibrium the budget settles where arrivals match the consumer's drain rate.
     * Deliberately derives from no server cap (the retired Global Constraint #28 class).
     */
    static final int INGEST_BACKLOG_HALT_SECTIONS = 6144;

    private SessionConfigS2CPayload sessionConfig;
    private String serverAddress;

    private int lastChunkX;
    private int lastChunkZ;
    /**
     * Movement-prune hysteresis (2026-08-05 review P2): the out-of-range prunes iterate the
     * ENTIRE per-column state (~263k timestamp entries plus sibling sets at the default
     * distance 256) and used to run on EVERY chunk crossing (~2.7 Hz in elytra flight) even
     * when nothing was out of range — a plausible dropped frame per crossing for work that
     * is memory-bounding, not latency-critical. Prunes now run only after this much
     * accumulated Chebyshev travel from the last-pruned center; a teleport (≥ the
     * threshold in one hop) prunes immediately by construction. Deferral slack is bounded:
     * entries linger at most this many chunks past getPruneDistance(). recenter() and the
     * scan cadence are untouched — those still fire per crossing (pinned).
     */
    static final int PRUNE_HYSTERESIS_CHUNKS = 8;
    private int lastPruneChunkX;
    private int lastPruneChunkZ;
    private ResourceKey<Level> lastDimension;

    // Per-column state: timestamps + dirty/retry/validated marks (single owner)
    private final ColumnStateMap columns = new ColumnStateMap();

    // Positions declared in the last want-set that have not yet been answered
    private final InFlightTracker tracker = new InFlightTracker();

    private boolean cacheLoaded = false;
    // The pending load carries PRE-BUILT section leaves (ColumnStateMap.LoadedState) —
    // built on the cache IO thread, adopted in O(leaves) at the gate. The old map-typed
    // future put the whole per-entry apply (up to 2M un-presized hash inserts, est.
    // 100-300 ms) on the render thread in one tick (quadtree-client-state-plan.md A1).
    private volatile CompletableFuture<ColumnStateMap.LoadedState> pendingCacheLoad = null;
    // Ingest failures reported while this dimension's cache load is still in flight: applied
    // against the not-yet-loaded map they are absorbed (onIngestFailed no-ops on an absent
    // entry) and loadFrom would then resurrect the stale ts>0 stamp from disk — a claim for
    // data no consumer holds, revalidated up_to_date every session. Buffer and re-apply
    // after the load lands. Main client thread only.
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet failuresDuringCacheLoad =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    // Dirty notices arriving DURING the load (final honesty review minor-3): the leaf map
    // is empty then, so markDirtyIfKnown drops them — and a summary frame assembled
    // BEFORE the edit's mark could otherwise validate the edited position right after
    // adoptLoaded, sealing it until the next dimension entry. Re-marked after the load
    // lands, strictly BEFORE the buffered frame applies. Main client thread only.
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet dirtyDuringCacheLoad =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    // Metrics tracking (counters + rolling rates)
    private final RequestMetrics metrics = new RequestMetrics();

    // Edge-trigger for the decode-backpressure clear: exactly one empty batch per halt episode.
    private boolean backpressureClearSent = false;

    // --- Region summaries (region-summary-sync-plan.md §6) ---
    /** Send seam for the fire-and-forget C2S request (the lss:client_info doctrine:
     *  legacy servers discard the unregistered channel; a send failure never matters). */
    interface SummarySender {
        void send(byte[] requestBody) throws Exception;
    }

    private SummarySender summarySender = body -> dev.vox.lss.platform.LoaderServices.get()
            .sendToServer(new dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload(body));
    /** The S2C frame buffered while this dimension's cache load is in flight (LATEST
     *  wins — a re-entered dimension's fresh frame replaces a stale one). Applied right
     *  after {@code adoptLoaded} + the buffered ingest-failure re-applies. */
    private byte[] pendingSummaryFrame;
    // Attributability counters (§6/§8): why re_resolved/up_to_date collapse on upgraded
    // pairs. Main client thread only; exported as the client summary.* group.
    private long summaryTilesClean;
    private long summaryTilesStale;
    private long summaryTilesUnknown;
    private long summaryTilesNoRegion;
    private long summaryColumnsValidated;
    private long summaryStampsApplied;
    private long summaryStampsIgnored;
    /** Harness gate (the far-player capability precedent): soak/benchmark clients never
     *  request, so no scenario baseline can shift; the summary scenarios opt back in
     *  via -Dlss.soak.summary. Seam for tests. */
    java.util.function.BooleanSupplier summaryHarnessGate = () ->
            (Boolean.getBoolean("lss.soak") || Boolean.getBoolean("lss.benchmark"))
                    && !Boolean.getBoolean("lss.soak.summary");
    /** CURRENT-dialect gate (the far-player/fast-cadence v16 doctrine): only a v20
     *  session requests. Seam for tests. */
    IntSupplier summarySessionVersion = ClientNetGlue::getSessionVersion;

    // Tier B v16 backward-compat (docs/planning/v16-client-compat-design.md §4). When true, the
    // egress rewrites a first-serve request stamp (<=0) to 0 — v16's generate-on-miss trigger —
    // so a legacy protocol-16 server generates cold columns instead of serving load-only. The
    // final decision (client opt-in AND a v16 session) is computed by the production factory and
    // set via setV16GenerationDrive; a v18 session always leaves it false, so the egress is
    // byte-identical there. The CORE keeps emitting -1 (the send buffer is untouched) — only the
    // outgoing wire copy is translated, so classify/tracker/cache state is unchanged.
    private boolean v16GenerationDrive = false;

    // Send buffers, sized once at the protocol's batch cap (~16 KB total)
    private final long[] sendPositionBuffer = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];
    private final long[] sendTimestampBuffer = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];

    // Expanding ring scanner (owns scan cadence + budget policy)
    private final SpiralScanner scanner = new SpiralScanner();

    /**
     * Ingest-backlog poll (issue #71): the largest consumer-reported pending-ingest depth
     * in sections, -1 when no consumer reports or the kill switch is off. Package-private
     * seam so tests pin both the production wiring (LSSApi aggregation) and the config
     * gate without a running Minecraft client; tick() polls it every tick.
     */
    IntSupplier ingestBacklogSupplier = () ->
            LSSClientConfig.CONFIG.enableIngestBackpressure
                    ? LSSApi.maxReportedIngestBacklog() : -1;

    // Last polled backlog — trace + /lss diag observability.
    private int lastIngestBacklog = -1;

    // The client transfer governor (adaptive-transfer-rate-plan.md Mechanism A): an
    // AIMD on RECEIVED wire bytes that, on a congested slow link, caps the want-set
    // through the manual rate-cap machinery. Suppliers are seams so tests drive
    // tickWithContext without the networking statics or a running client.
    final TransferRateGovernor governor = new TransferRateGovernor();
    /** Cumulative want-set columns actually OFFERED to the wire (post-send) — the
     *  governor's offer-backing input (impl review MAJOR-1). Main client thread. */
    private long declaredColumnsCumulative;
    java.util.function.LongSupplier wireBytesReceivedSupplier =
            LSSClientNetworking::getWireBytesReceived;
    java.util.function.LongSupplier columnsReceivedSupplier =
            LSSClientNetworking::getColumnsReceived;
    /** The client's own ping (<=0 = no sample) — Mechanism A's congestion conjunct.
     *  Reads the debug overlay's ping logger FIRST (fed by our own 1 Hz probe below —
     *  fresh within ~1 s) and falls back to the tab-list ping, which vanilla refreshes
     *  only every 600 ticks with (3l+s)/4 smoothing — live round 5's runaway rode that
     *  30 s blind spot for its whole duration. */
    IntSupplier ownPingSupplier = LodRequestManager::readOwnPing;
    /** 1 Hz ping-probe sender seam (vanilla answers {@code ServerboundPingRequestPacket}
     *  with a pong that the debug overlay's ping logger records unconditionally). */
    Runnable pingProbeSender = LodRequestManager::sendPingProbe;
    /** Seeded far-negative, not 0: the JLS permits a negative nanoTime epoch, where a
     *  0 seed would sit AHEAD of the clock and silence the probe forever (round-5
     *  review n1). MIN/2 keeps the first comparison overflow-free. */
    private long lastPingProbeMillis = Long.MIN_VALUE / 2;
    /** Config kill switch seam (the adaptiveCadenceEnabled pattern). */
    java.util.function.BooleanSupplier transferGovernorEnabled =
            () -> LSSClientConfig.CONFIG.enableAdaptiveTransferRate;
    /** Join slow start (join-slow-start-plan.md): sessions START in the governed
     *  RAMP and earn their rate. Manager tests that pin uncapped first walks
     *  override this to false (the frontier-damping test pattern);
     *  productionDefaultEnablesSlowStart pins the real wiring. */
    java.util.function.BooleanSupplier joinSlowStartEnabled =
            () -> LSSClientConfig.CONFIG.enableJoinSlowStart;

    private static int readOwnPing() {
        var mc = Minecraft.getInstance(); // null under fabric-loader-junit (headless)
        if (mc == null) return -1;
        try {
            var logger = mc.getDebugOverlay().getPingLogger();
            int size = logger.size();
            if (size > 0) return (int) Math.min(Integer.MAX_VALUE, logger.get(size - 1));
        } catch (Exception e) {
            // fall through to the tab ping
        }
        var conn = mc.getConnection();
        var player = mc.player;
        if (conn == null || player == null) return -1;
        var info = conn.getPlayerInfo(player.getUUID());
        return info != null ? info.getLatency() : -1;
    }

    private static void sendPingProbe() {
        var mc = Minecraft.getInstance();
        if (mc == null) return;
        try {
            var connection = mc.getConnection();
            if (connection != null) {
                connection.send(new net.minecraft.network.protocol.ping
                        .ServerboundPingRequestPacket(net.minecraft.util.Util.getMillis()));
            }
        } catch (Exception e) {
            // A congestion probe is never worth a client-side failure.
        }
    }

    /** min-composition with BOTH off-sentinels explicit (review m2): {@code <= 0}
     *  means OFF on each side and must never win a naive min. */
    static int composeRateCaps(int manual, int governed) {
        if (manual <= 0) return Math.max(governed, 0);
        if (governed <= 0) return manual;
        return Math.min(manual, governed);
    }

    /** The GOVERNED half of the budget-clamp min-compose — the ONE composition both
     *  the scanner's columnBurstCap supplier and the window-limited latch read (the
     *  implementation panel's MAJOR-1: with the adaptive cadence off the governed
     *  half is the FULL sustained rate, and a latch re-reading burst = sustained/4
     *  mis-attributed manually-capped walks in the band [ceil(sustained/4),
     *  sustained/2) to the governor). */
    private int governedBurstCap() {
        return this.scanner.adaptiveCadenceEnabled.getAsBoolean()
                ? this.governor.burstColumnsPerSecond()
                : this.governor.sustainedColumnsPerSecond();
    }

    public LodRequestManager() {
        // Adaptive cadence: the scanner's fast trigger reads the awaiting-set size each
        // tick. Same main-client-thread contract as every other tracker consumer — every
        // mutation arrives via client.execute tasks on the thread that ticks maybeScan.
        this.scanner.setOutstandingSupplier(this.tracker::size);
        // The transfer governor's seam split (plan review M2): the governed SUSTAINED
        // rate feeds the spacing gate, the governed BURST cap (ceil(R/4)) feeds the
        // budget clamp — 4 Hz quarter-batches while governed. Each site min-composes
        // with the manual knob; with the governor off both read the manual value,
        // bit-identical to the shipped shape. The burst site falls back to the FULL
        // sustained rate when the adaptive cadence is off (impl review MAJOR-1: with
        // the fast path structurally unavailable, quarter-batches at 1 Hz deliver a
        // quarter of the governed rate forever — full 1 Hz batches are the manual
        // knob's shipped shape and let the loop actually reach its target).
        this.scanner.columnRateCap = () -> composeRateCaps(
                LSSClientConfig.CONFIG.lodColumnsPerSecondLimit,
                this.governor.sustainedColumnsPerSecond());
        this.scanner.columnBurstCap = () -> composeRateCaps(
                LSSClientConfig.CONFIG.lodColumnsPerSecondLimit,
                governedBurstCap());
    }

    public void onSessionConfig(SessionConfigS2CPayload config, String serverAddress) {
        this.sessionConfig = config;
        this.serverAddress = serverAddress;
        // Safe default until the factory wires the Tier B decision for this session (a v18
        // session never re-enables it).
        this.v16GenerationDrive = false;
        resetRequestState();
        // The governor dies with the SESSION (plan review m3); the negative-delta
        // guard covers any interval already spanning the gate-counter zeroing.
        this.governor.reset();
        this.lastDimension = null;
        this.cacheLoaded = false;
        this.scanner.setConfig(config);
        this.scanner.reset();
    }

    /**
     * Tier B v16 backward-compat: enable driving on-demand generation on a legacy (protocol-16)
     * server by rewriting first-serve request stamps to v16's generate trigger. The caller
     * (production factory / tests) is responsible for only enabling this on a genuine v16
     * session with the client opt-in set — a v18 session must always pass false.
     */
    void setV16GenerationDrive(boolean enabled) {
        this.v16GenerationDrive = enabled;
    }

    /**
     * Tier B only: a legacy (protocol-16) gen-ENABLED server answers a TRANSIENT gen-slot-full
     * miss with NOT_GENERATED (v0.6.2 `handleDiskNotFound` sends it when the GENERATION slot
     * cap is hit), unlike a v18 server which drops that silently and reserves NOT_GENERATED for
     * permanent unservability. So on such a session NOT_GENERATED must be treated as a
     * re-declarable drop, not v18's session-permanent park — otherwise a cold backfill parks the
     * bulk of every wave after one gen-slot bounce. A gen-DISABLED v16 server (generationEnabled
     * false) is excluded: its NOT_GENERATED IS permanent, and re-declaring would starve the
     * want-set. Guarded by Tier B so Tier A and every v18 session keep the permanent park.
     */
    /** The governor's dialect exclusion (mirrors the fast cadence's v16 gate): a
     *  legacy-fallback session's pacing is the drip-feed's own. */
    private boolean isLegacySession() {
        return this.sessionConfig == null
                || this.sessionConfig.protocolVersion()
                        == LSSConstants.V16_COMPAT_PROTOCOL_VERSION;
    }

    private boolean v16TransientNotGenerated() {
        return this.v16GenerationDrive && this.sessionConfig != null
                && this.sessionConfig.generationEnabled();
    }

    private static int countMissingVanillaChunks(ClientLevel level, int playerCx, int playerCz, int radius) {
        var chunkSource = level.getChunkSource();
        int missing = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!chunkSource.hasChunk(playerCx + dx, playerCz + dz)) {
                    missing++;
                }
            }
        }
        return missing;
    }

    public void tick() {
        // --- Guards ---
        if (this.sessionConfig == null || !this.sessionConfig.enabled()) return;
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || player.isDeadOrDying()) return;
        var level = mc.level;
        if (level == null) return;

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int viewDistance = mc.options.getEffectiveRenderDistance();
        tickWithContext(playerCx, playerCz, level.dimension(), viewDistance,
                LSSClientNetworking.getQueuedColumnCount(),
                LSSClientNetworking.getQueuedColumnBytes(),
                this.ingestBacklogSupplier.getAsInt(),
                () -> countMissingVanillaChunks(level, playerCx, playerCz, viewDistance));
        // Transport instrument (1 Hz, self-throttled): vanilla's ping/chunk-rate view of the
        // shared connection next to LSS's byte rates — see ClientNetTrace and
        // docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.2. After the tick
        // body so the queue/backlog numbers it reports are this tick's.
        if (ClientTraceLog.enabled()) {
            ClientNetTrace.maybeEmit(mc, player, level, viewDistance,
                    LSSClientNetworking.getQueuedColumnCount(),
                    LSSClientNetworking.getQueuedColumnBytes(),
                    this.lastIngestBacklog, this.tracker.size(),
                    this.governor.getDesiredBytesPerSec(),
                    this.governor.sustainedColumnsPerSecond(),
                    this.metrics.getRttP50Ms(), this.metrics.getRttP95Ms());
        }
    }

    /**
     * Tick body behind the Minecraft-client guards, phases in fixed order: dimension/cache →
     * movement prune → metrics → backpressure halt → cache gate → scan+send. The backpressure
     * halt deliberately precedes the cache poll and the scan: while the decode queue is mostly
     * full nothing new is declared, and recovery resumes only once the queue drains.
     * Package-private for direct test coverage — tick() needs a running Minecraft client.
     */
    void tickWithContext(int playerCx, int playerCz, ResourceKey<Level> currentDim,
                         int viewDistance, int columnQueueSize, long columnQueueBytes,
                         int ingestBacklogSections, IntSupplier missingVanilla) {
        this.lastIngestBacklog = ingestBacklogSections;
        tickDimensionAndCachePhase(currentDim, playerCx, playerCz);
        tickMovementPhase(playerCx, playerCz);
        this.metrics.updateRollingRates();
        boolean halted = haltedByBackpressure(columnQueueSize, columnQueueBytes,
                ingestBacklogSections);
        // The transfer governor observes EVERY tick — including halted ones, which is
        // how a #71 halt disqualifies its interval (the client deliberately stopped
        // ingesting; the depressed tail rate is not a link measurement). The ping read
        // (tab-list lookup) is skipped while inactive.
        boolean governorActive =
                this.transferGovernorEnabled.getAsBoolean() && !isLegacySession();
        if (governorActive && !TransferRateGovernor.harnessJvm()) {
            // The 1 Hz congestion probe feeding readOwnPing's logger read (harness JVMs
            // stay probe-silent — the governor's own inertness gate is internal to
            // tick(), so the manager must share it here). ClientNetTrace probes too
            // while tracing (double at ~2 Hz is a few dozen bytes/s — harmless).
            long nowMs = this.governor.clock.getAsLong();
            if (nowMs - this.lastPingProbeMillis >= 1_000L) {
                this.lastPingProbeMillis = nowMs;
                this.pingProbeSender.run();
                // Vanilla-first input, sampled LIVE on the same 1 Hz cadence (round-5
                // review M3/M1-wiring): the scanner's cached copy updates only on
                // PERIODIC fires — unbounded staleness in the governed 4 Hz steady
                // state, and one tick after a dimension change it still holds the OLD
                // dimension's count, which would seed the freshly-cleared floor. The
                // live count is this tick's view of the CURRENT level; the scanner's
                // cache stays what it always was, a diagnostic. Cost: one O(RD²)
                // loaded-chunk sweep per second, only while the governor is active.
                this.governor.noteMissingVanilla(missingVanilla.getAsInt());
            }
        }
        // Slow-start arming is re-asserted every tick (wiring, not session state):
        // a mid-session OFF demotes RAMP->OPEN inside the setter; ON applies at the
        // next session entry (join-slow-start-plan.md toggle table).
        this.governor.setSlowStartEnabled(this.joinSlowStartEnabled.getAsBoolean());
        this.governor.tick(this.governor.clock.getAsLong(),
                this.wireBytesReceivedSupplier.getAsLong(),
                this.columnsReceivedSupplier.getAsLong(),
                this.declaredColumnsCumulative,
                // Answered positions of ANY response type (impl review MAJOR — the
                // ramp's byte-free growth evidence: a warm rejoin's answers are
                // up_to_date frames that move zero wire bytes).
                this.metrics.getTotalColumnsReceived() + this.metrics.getTotalUpToDate()
                        + this.metrics.getTotalNotGenerated(),
                this.tracker.size(), halted,
                governorActive ? this.ownPingSupplier.getAsInt() : -1,
                governorActive);
        if (halted) {
            // Entering the halt: silence would leave the server pumping the last want-set
            // (up to 1024 backlogged asks). An EMPTY batch is the explicit "want nothing"
            // declaration — it replaces the backlog with nothing; already-admitted work
            // still completes (bounded by the server's slot caps). Edge-triggered: exactly
            // one clear per halt episode. This is the ONLY producer of empty batches.
            if (!this.backpressureClearSent) {
                this.backpressureClearSent = true;
                sendClearBatch();
            }
            return;
        }
        this.backpressureClearSent = false;
        if (!tickCacheGatePhase()) return;
        tickScanPhase(playerCx, playerCz, viewDistance, columnQueueSize, columnQueueBytes,
                ingestBacklogSections, missingVanilla);
    }

    /** The explicit backpressure clear: an empty want-set replaces the server backlog with nothing. */
    private void sendClearBatch() {
        if (ClientTraceLog.enabled()) ClientTraceLog.event("clear_batch", "");
        try {
            this.batchSender.send(new BatchChunkRequestC2SPayload(new long[0], new long[0], 0));
            // A real (empty) declaration went out — disarm the fast cadence with it. The
            // tracker is deliberately NOT replaced (late-status gating still wants the
            // pre-halt awaiting set), so without this the one declaration path that
            // bypasses tickScanPhase would leave "lastSentCount == what was declared"
            // false and invite a fast re-declare storm right out of the halt.
            this.scanner.noteDeclared(0);
        } catch (Exception e) {
            long n = BATCH_SEND_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.error("Failed to send backpressure clear batch (" + n
                        + " send failure(s) since the last report; retried next halted"
                        + " tick)", e);
            }
            this.backpressureClearSent = false; // retry on the next halted tick
        }
    }

    /** Dimension change: flush state, reload cache, and fire the region-summary request
     *  in parallel with the load (§6 — the position is this tick's, so a portal's 8×
     *  coordinate scale centers the window correctly). First tick starts the initial load. */
    void tickDimensionAndCachePhase(ResourceKey<Level> currentDim, int playerCx, int playerCz) {
        if (this.lastDimension != null && !currentDim.equals(this.lastDimension)) {
            this.onDimensionChange(currentDim);
            sendRegionSummaryRequest(currentDim, playerCx, playerCz);
        } else if (!this.cacheLoaded) {
            this.cacheLoaded = true;
            startAsyncCacheLoad(currentDim);
            sendRegionSummaryRequest(currentDim, playerCx, playerCz);
        }
        this.lastDimension = currentDim;
    }

    /**
     * Movement: prune out-of-range data + drop stale in-flight tracking, and re-center the
     * ring walk at the new position. Deliberately does NOT touch the scan cadence: the
     * pre-v17 debounce here restarted it on every chunk crossing, starving scanning — and
     * with it re-declaration, the want-set's only self-heal — whenever crossings outpaced
     * the 20-tick window (sustained creative flight stopped LOD generation entirely). A
     * scan from a moving center is exactly what replace semantics absorb (superseded +
     * re-declared), and yielding to vanilla's own chunk loading during fast travel is
     * server-side read/generation priority's job (the client-side vanilla-load budget
     * scale is retired), not the cadence's. Side benefit: the
     * (0,0) lastChunk init no longer costs a player joining outside chunk (0,0) their
     * primed immediate first scan. Both pinned in LodRequestManagerTickTest.
     */
    void tickMovementPhase(int playerCx, int playerCz) {
        if (playerCx != this.lastChunkX || playerCz != this.lastChunkZ) {
            if (ClientTraceLog.enabled()) {
                ClientTraceLog.event("move", "\"from\":[" + this.lastChunkX + "," + this.lastChunkZ
                        + "],\"to\":[" + playerCx + "," + playerCz
                        + "],\"confirmed_before\":" + this.scanner.getConfirmedRing());
            }
            // Hysteresis: the prunes are full-state iterations and memory-bounding only —
            // run them once per PRUNE_HYSTERESIS_CHUNKS of accumulated travel, not per
            // crossing (see the constant). Everything else about the crossing (recenter,
            // trace, anchor update) keeps firing every time.
            if (Math.max(Math.abs(playerCx - this.lastPruneChunkX),
                    Math.abs(playerCz - this.lastPruneChunkZ)) >= PRUNE_HYSTERESIS_CHUNKS) {
                int pruneDistance = this.scanner.getPruneDistance();
                this.columns.pruneOutOfRange(playerCx, playerCz, pruneDistance);
                this.tracker.pruneOutOfRange(playerCx, playerCz, pruneDistance);
                // Pruned in-flight requests will never get a tracked answer — drop their RTT
                // stamps with them or they orphan toward the sampling cap.
                this.metrics.pruneRttStampsOutOfRange(playerCx, playerCz, pruneDistance);
                this.lastPruneChunkX = playerCx;
                this.lastPruneChunkZ = playerCz;
            }
            // Chebyshev crossing delta, computed BEFORE the anchor update: the scanner's
            // prefix retention shifts its state by exactly this much (a diagonal sprint
            // crossing is d=1; a teleport is large and recenter() full-resets past
            // RECENTER_FULL_RESET_DELTA).
            int crossingDelta = Math.max(Math.abs(playerCx - this.lastChunkX),
                    Math.abs(playerCz - this.lastChunkZ));
            this.lastChunkX = playerCx;
            this.lastChunkZ = playerCz;
            this.scanner.recenter(crossingDelta);
            // Live round 2: a crossing holds the governor's up-probe for the current
            // interval — vanilla's own chunk bursts are about to compete for the link.
            this.governor.noteMovement();
        }
    }

    /** Backpressure: halt when the column processing queue is mostly full — by COUNT or by
     *  BYTES — or when a consumer's reported ingest backlog crosses its own halt point.
     *  Admission ({@code ClientColumnProcessor.admits}) is bounded by both queue caps, and
     *  for columns above ~44 KiB the byte cap binds first; a count-only halt would let
     *  arrivals hit the byte-cap drop path instead (each drop burns an ingest-failure
     *  strike, and four park the position for the session). Halting at 3/4 of EITHER cap
     *  keeps the designed halt+clear ahead of the drop regime regardless of column size.
     *  The ingest term (issue #71) covers the pipe the queue caps cannot see: a consumer
     *  whose hand-off is non-blocking (Voxy's unbounded ingest queue) drains our decode
     *  queue at full speed while its own backlog eats the heap; <=0 = no signal. */
    boolean haltedByBackpressure(int columnQueueSize, long columnQueueBytes, int ingestBacklogSections) {
        return columnQueueSize >= haltThreshold() || columnQueueBytes >= byteHaltThreshold()
                || ingestBacklogSections >= INGEST_BACKLOG_HALT_SECTIONS;
    }

    /** Package-private so the backpressure-clear tests can drive the exact halt edge. */
    static int haltThreshold() {
        return ClientColumnProcessor.MAX_QUEUED_COLUMNS * BACKPRESSURE_NUMERATOR / BACKPRESSURE_DENOMINATOR;
    }

    /** Package-private so the backpressure-clear tests can drive the exact byte-halt edge. */
    static long byteHaltThreshold() {
        return ClientColumnProcessor.MAX_QUEUED_BYTES * BACKPRESSURE_NUMERATOR / BACKPRESSURE_DENOMINATOR;
    }

    /**
     * Cache gate: don't scan until the timestamp cache has loaded; returns false while the
     * load is still pending. Polls inline rather than relying on thenAcceptAsync callback
     * scheduling, which can be delayed on starved render threads (e.g., CI with llvmpipe).
     */
    boolean tickCacheGatePhase() {
        if (this.pendingCacheLoad != null) {
            if (!this.pendingCacheLoad.isDone()) return false;
            try {
                var loaded = this.pendingCacheLoad.getNow(null);
                if (loaded != null && this.lastDimension != null) {
                    this.columns.adoptLoaded(loaded);
                }
            } catch (Exception ignored) {}
            this.pendingCacheLoad = null;
            if (!this.failuresDuringCacheLoad.isEmpty()) {
                // Failures reported during the load: the load may have just resurrected their
                // stale stamps — re-apply so the positions unstamp and re-request honestly.
                for (long failed : this.failuresDuringCacheLoad) {
                    this.columns.onIngestFailed(failed);
                }
                this.failuresDuringCacheLoad.clear();
            }
            if (!this.dirtyDuringCacheLoad.isEmpty()) {
                // Dirty notices dropped while the leaf map was empty: re-mark against the
                // adopted stamps (and reopen their rings) BEFORE the buffered frame can
                // validate the edited positions off their pre-edit loaded stamps.
                int reopenLod = this.scanner.currentReopenLod();
                for (long packed : this.dirtyDuringCacheLoad) {
                    if (this.columns.markDirtyIfKnown(packed)) {
                        this.scanner.reopenRing(Math.max(
                                Math.abs(PositionUtil.unpackX(packed) - this.lastChunkX),
                                Math.abs(PositionUtil.unpackZ(packed) - this.lastChunkZ)),
                                reopenLod);
                    }
                }
                this.dirtyDuringCacheLoad.clear();
            }
            if (this.pendingSummaryFrame != null) {
                // The buffered summary applies strictly AFTER the failure re-applies.
                // Lost-CONTENT failures unstamp (no candidate bit at all); lost-CLEAR
                // failures stay stamped but carry a retry mark, which outranks
                // validated in classify — either way a rejected column re-declares
                // instead of being sealed validated by a frame that raced the load.
                byte[] frame = this.pendingSummaryFrame;
                this.pendingSummaryFrame = null;
                onRegionSummaryFrame(frame);
            }
        }
        return true;
    }

    /**
     * Scan phase (adaptive cadence: 20-tick fallback + completion-triggered fast re-scans):
     * build the complete want-set and send it as ONE batch in the same tick. A
     * walked-but-empty scan (0) sends NOTHING — the convergence invariant that
     * keeps the soak quiescence predicate alive (a heartbeat would keep requests_received moving
     * forever and silently disable every law) — but still replaces the awaiting set so phantom
     * entries cannot linger.
     * @return the {@link SpiralScanner#maybeScan} result (-1 when no walk happened)
     */
    int tickScanPhase(int playerCx, int playerCz, int viewDistance, int columnQueueSize,
                      long columnQueueBytes, int ingestBacklogSections, IntSupplier missingVanilla) {
        int scanned = this.scanner.maybeScan(playerCx, playerCz, viewDistance,
                columnQueueSize, haltThreshold(),
                columnQueueBytes, byteHaltThreshold(),
                ingestBacklogSections, INGEST_BACKLOG_HALT_SECTIONS, missingVanilla,
                this.columns, this.sendPositionBuffer, this.sendTimestampBuffer);
        if (scanned >= 0 && ClientTraceLog.enabled()) {
            int minRing = Integer.MAX_VALUE, maxRing = -1;
            for (int i = 0; i < scanned; i++) {
                long p = this.sendPositionBuffer[i];
                int ring = Math.max(Math.abs(PositionUtil.unpackX(p) - playerCx),
                        Math.abs(PositionUtil.unpackZ(p) - playerCz));
                if (ring < minRing) minRing = ring;
                if (ring > maxRing) maxRing = ring;
            }
            ClientTraceLog.event("scan", "\"center\":[" + playerCx + "," + playerCz
                    + "],\"confirmed\":" + this.scanner.getConfirmedRing()
                    + ",\"reopened\":" + this.scanner.getReopenedRingCount()
                    + ",\"fast\":" + this.scanner.wasLastScanFast()
                    + ",\"declared\":" + scanned
                    + ",\"budget\":" + this.scanner.getLastBudget()
                    + ",\"ingest_backlog\":" + ingestBacklogSections
                    + ",\"missing_vanilla\":" + this.scanner.getMissingVanillaChunks()
                    + ",\"ring_min\":" + (maxRing < 0 ? -1 : minRing)
                    + ",\"ring_max\":" + maxRing);
        }
        if (scanned >= 0) {
            this.tracker.replaceWith(this.sendPositionBuffer, scanned);
            // Arm/disarm the adaptive fast cadence beside the tracker replace, so
            // "lastSentCount == what the awaiting set holds" is true by construction
            // (a 0-count converged walk disarms; sendRequests' catch re-disarms a
            // failed send before the next tick's predicate can read it).
            this.scanner.noteDeclared(scanned);
            if (scanned > 0 && sendRequests(this.sendPositionBuffer,
                    this.sendTimestampBuffer, scanned)) {
                // The governor's window-limited latch (ramp-window-limited-credit-
                // plan.md §3.2): a SUCCESSFULLY-SENT, COMPLETION-CLOCKED (fast-
                // fired) walk that the GOVERNED burst cap truncated — the cap was
                // the binding, near-taper-free clamp (scanner provenance) AND the
                // governed rate is the binding half of the min-compose (a manually-
                // capped loop must not claim to be governor-window-limited). The
                // fast conjunct is the dynamics-review MAJOR-2 fold: a backlogged
                // slow link runs FALLBACK-clocked (the outstanding gate refuses
                // fast fires while >5% is unanswered) and its 1 Hz full-budget
                // re-declares would otherwise satisfy answeredAllAsked up to
                // ~5.3x the link rate — fallback fires never latch, so slow links
                // keep (approximately) main's Row-4 park. Tick order puts
                // governor.tick() before this phase, so the latch lands in the
                // interval this declaration counts toward.
                int governedBurst = governedBurstCap();
                int manual = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
                if (this.scanner.wasLastScanFast()
                        && this.scanner.wasLastWalkTruncated()
                        && this.scanner.wasLastBudgetCapClamped()
                        && governedBurst > 0
                        && (manual <= 0 || governedBurst <= manual)) {
                    this.governor.noteWindowLimited();
                }
            }
        }
        return scanned;
    }

    // --- Request sending and callbacks ---

    /** Batch send transport. Seam for tests; production default is Fabric client networking. */
    interface BatchSender {
        void send(BatchChunkRequestC2SPayload payload);
    }

    private BatchSender batchSender = payload -> dev.vox.lss.platform.LoaderServices.get().sendToServer(payload);

    /** @return true iff the batch reached the transport (the same condition that
     *          bumps {@code declaredColumnsCumulative}) — the window-limited latch's
     *          send-success conjunct. */
    private boolean sendRequests(long[] positionBuffer, long[] timestampBuffer, int count) {
        // Snapshot to exact-length arrays: BatchChunkRequestC2SPayload's StreamCodec reads
        // these lazily when the connection encodes on the netty event loop, which races the
        // next scan overwriting the reused sendPositionBuffer/sendTimestampBuffer. The
        // payload must own its data so a declaration's positions can't be corrupted mid-encode.
        long[] positions = java.util.Arrays.copyOf(positionBuffer, count);
        long[] timestamps = java.util.Arrays.copyOf(timestampBuffer, count);
        if (this.v16GenerationDrive) {
            // Tier B: ask a legacy protocol-16 server to GENERATE cold columns. A first-serve
            // stamp (<=0 — the client has no data) becomes 0, v16's generate-on-miss trigger; a
            // resync stamp (>0) is left untouched so existing terrain still up-to-date-checks.
            // Only the wire copy is rewritten — the send buffer (and thus classify/tracker/cache)
            // still holds the honest -1. The v0.6.2 server reads disk-FIRST for ts==0, so this
            // serves disk-resident columns and generates only true misses (no regeneration).
            for (int i = 0; i < count; i++) {
                if (timestamps[i] <= 0) timestamps[i] = 0;
            }
        }
        boolean sent = false;
        try {
            this.batchSender.send(new BatchChunkRequestC2SPayload(positions, timestamps, count));
            sent = true;
            this.declaredColumnsCumulative += count; // the governor's offer-backing input
            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                this.metrics.recordRequestSent(positions[i], nowMs); // RTT: last-declare stamp
            }
        } catch (Exception e) {
            long n = BATCH_SEND_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.error("Failed to send want-set batch (" + n
                        + " send failure(s) since the last report; retried at 1 Hz)", e);
            }
            // Nothing is consumed at send any more (marks are answer-consumed), and the next
            // scan re-declares the identical set, so no mark restoration is needed. Just drop
            // the awaiting entries so late-status gating doesn't credit a batch that never
            // reached the wire — and disarm the fast cadence, or the emptied awaiting set
            // would read as "all answered" and turn a dying connection into a 4 Hz retry
            // hammer (it must retry at the 1 Hz fallback, exactly as before).
            this.tracker.replaceWith(positions, 0);
            this.scanner.noteDeclared(0);
        }
        this.metrics.recordSendCycle(count); // counts attempts — a failed batch still counts
        return sent;
    }

    public boolean onColumnReceived(long packed, long columnTimestamp, ResourceKey<Level> dimension) {
        return onColumnReceived(packed, columnTimestamp, dimension, false);
    }

    /**
     * @param authoritativeClear the received column was a 0-section content-&gt;air CLEAR (see
     *        {@link ClientColumnProcessor#isClearColumn}). If the consumer later rejects it, the
     *        re-request must carry the pre-clear content stamp so the server re-sends the clear;
     *        recorded via {@link ColumnStateMap#markAuthoritativeClear} inside the dimension guard.
     */
    public boolean onColumnReceived(long packed, long columnTimestamp, ResourceKey<Level> dimension,
                                 boolean authoritativeClear) {
        return onColumnReceived(packed, columnTimestamp, dimension, authoritativeClear, (byte) -1);
    }

    /**
     * @param source the server's serve-source tag (LSSConstants.COLUMN_SOURCE_*), -1 when
     *        unknown (test rigs / legacy). Diagnostic attribution only — feeds the trace.
     * @return false ONLY for the out-of-range unsolicited drop — the caller must then skip
     *         consumer dispatch too, or a hostile/buggy server still grows the consumer's
     *         LOD store without bound (the state map alone being guarded is half a fix).
     *         Cross-dimension columns return true: the dispatch drain's dimension filter
     *         owns that discard (deliberate — see the guard below).
     */
    public boolean onColumnReceived(long packed, long columnTimestamp, ResourceKey<Level> dimension,
                                 boolean authoritativeClear, byte source) {
        // A column from another dimension is discarded by the dispatch drain
        // (ClientColumnProcessor filters on level.dimension()), so stamping it here would
        // mark the position SATISFIED in the current dimension's map with no data delivered.
        if (this.lastDimension != null && !this.lastDimension.equals(dimension)) return true;
        // Range guard: an unsolicited column far outside the prune radius must not stamp —
        // the movement pruner only runs on chunk crossings, so a stationary client fed
        // arbitrary far positions (buggy or hostile server) would otherwise grow the state
        // map without bound. In-range unsolicited columns still stamp (authoritative data).
        if (PositionUtil.isOutOfRange(packed, this.lastChunkX, this.lastChunkZ,
                this.scanner.getPruneDistance())) {
            LSSLogger.debug("Dropping out-of-range column " + PositionUtil.unpackX(packed)
                    + "," + PositionUtil.unpackZ(packed)
                    + " (player chunk " + this.lastChunkX + "," + this.lastChunkZ + ")");
            if (ClientTraceLog.enabled()) {
                ClientTraceLog.event("col_dropped_range", "\"pos\":[" + PositionUtil.unpackX(packed)
                        + "," + PositionUtil.unpackZ(packed) + "]");
            }
            return false;
        }
        // Capture the pre-clear content stamp BEFORE onReceived overwrites it with the clear's
        // timestamp — a rejected clear re-requests with this value, not ts=-1.
        long preClearStamp = authoritativeClear ? this.columns.timestampFor(packed) : -1L;
        // Apply even if the position is no longer tracked (e.g. it timed out client-side):
        // the data is authoritative for the position, and stamping it prevents the
        // timeout → silent-duplicate → second-timeout stall.
        if (ClientTraceLog.enabled()) {
            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            ClientTraceLog.event("col", "\"pos\":[" + cx + "," + cz
                    + "],\"ring\":" + Math.max(Math.abs(cx - this.lastChunkX), Math.abs(cz - this.lastChunkZ))
                    + ",\"ts\":" + columnTimestamp
                    + ",\"src\":" + source + (authoritativeClear ? ",\"clear\":true" : ""));
        }
        this.tracker.removeByPosition(packed);
        this.columns.onReceived(packed, columnTimestamp);
        if (authoritativeClear) this.columns.markAuthoritativeClear(packed, preClearStamp);
        consumeStaleCrossing(packed); // a dirty that crossed this in-flight first serve forces a re-request
        this.metrics.recordColumnReceived(packed, System.currentTimeMillis()); // RTT sample + counter
        return true;
    }

    /**
     * Region-summary S2C frame (region-summary-sync-plan.md §6). Kill switch checked at
     * APPLY too (never request, never apply — a mid-session flip stops both halves);
     * malformed frames drop contained, and the APPLY is inside the same containment
     * (P2 client review MAJOR-1 — the FarPlayerClientSupport bar: a decode that passes
     * the wire guards must still not let an apply-time surprise escape into the client
     * tick); a frame for another dimension drops (the anti-stale binding). Frame
     * ORDERING (final review, honesty lens): a STALE frame carries a LOWER stamp and
     * can only OVER-validate — the safe delivery order (fresh-then-stale) is
     * unreachable in-session (one MIN_PRIORITY sweeper assembling in admission order,
     * a FIFO ready queue, TCP, latest-wins buffering here), and the reachable order
     * (stale-then-fresh) is corrected by the fresher frame's provenance-scoped
     * revocation, with classify's dirty rung outranking validation meanwhile (see
     * {@code ColumnStateMap.applyTileValidation}). A frame racing this dimension's
     * cache load is BUFFERED (latest wins) and applied right after {@code adoptLoaded}
     * — validating before the stamps exist would be a silent no-op and lose the whole
     * exchange.
     */
    public void onRegionSummaryFrame(byte[] body) {
        if (!LSSClientConfig.CONFIG.enableRegionSummarySync) return;
        if (this.lastDimension == null) return;
        try {
            var summary = dev.vox.lss.common.region.RegionSummaryWire.decodeSummary(body);
            if (!this.lastDimension.identifier().toString().equals(summary.dimension())) return;
            if (this.pendingCacheLoad != null) {
                this.pendingSummaryFrame = body;
                return;
            }
            applySummary(summary);
        } catch (Exception e) {
            LSSLogger.debug("Region summary dropped: " + e.getMessage());
        }
    }

    /** Decoded-and-dimension-checked apply: per-tile, per-column validation. */
    private void applySummary(dev.vox.lss.common.region.RegionSummaryWire.Summary summary) {
        int r = summary.tileRadius();
        long validatedTotal = 0;
        int i = 0;
        // Hoisted once per frame (the onDirtyColumns batch discipline) for the
        // revocation reopens below.
        int reopenLod = this.scanner.currentReopenLod();
        java.util.function.LongConsumer revokedOut = packed ->
                // A revoked position may sit below the scanner's confirmed prefix —
                // reopen its OWN ring (the dirty-batch idiom, cadence-neutral) so it
                // re-declares at the NEXT scheduled scan instead of being orphaned
                // until a full scanner reset (final review, client lens MAJOR-1).
                this.scanner.reopenRing(Math.max(
                        Math.abs(PositionUtil.unpackX(packed) - this.lastChunkX),
                        Math.abs(PositionUtil.unpackZ(packed) - this.lastChunkZ)), reopenLod);
        for (int tz = summary.centerTileZ() - r; tz <= summary.centerTileZ() + r; tz++) {
            for (int tx = summary.centerTileX() - r; tx <= summary.centerTileX() + r; tx++) {
                long stamp = summary.stampSeconds()[i++];
                if (stamp == dev.vox.lss.common.region.RegionSummaryWire.STAMP_NO_REGION) {
                    // NO_REGION is NO EVIDENCE, not a claim (final honesty review
                    // MAJOR-1): the table's never-observed scope is server-lifetime
                    // only, so a region deleted while the server was OFF also reads
                    // "never observed" — validating cached stamps against it would
                    // seal the deleted terrain forever (the client would never re-ask,
                    // so it would never even trigger the regeneration). Both sentinels
                    // validate nothing; stamped residue re-declares and heals. Own
                    // counter (mirrors the server's tiles_no_region disposition) so
                    // the harness honesty legs on tiles_unknown stay sharp — a summary
                    // window legitimately covers many never-generated regions.
                    this.summaryTilesNoRegion++;
                    continue;
                }
                if (stamp == dev.vox.lss.common.region.RegionSummaryWire.STAMP_NEVER_CLEAN) {
                    this.summaryTilesUnknown++;
                    continue;
                }
                var outcome = this.columns.applyTileValidation(tx, tz, stamp, revokedOut);
                validatedTotal += outcome.newlyValidated();
                if (outcome.fullyValidated()) this.summaryTilesClean++;
                else this.summaryTilesStale++;
            }
        }
        this.summaryColumnsValidated += validatedTotal;
        if (ClientTraceLog.enabled()) {
            ClientTraceLog.event("summary", "\"tiles\":" + i
                    + ",\"validated\":" + validatedTotal);
        }
    }

    /**
     * Fire the summary request at dimension entry, in PARALLEL with the async cache
     * load (§6: the RTT hides in the merge-save → load dead window; the response, if it
     * beats the load, is buffered). Fire-and-forget: no gating, no timeout, no retry —
     * a lost frame = today's per-column behavior. Gated on the client kill switch, the
     * CURRENT dialect, and the harness property gate.
     */
    private void sendRegionSummaryRequest(ResourceKey<Level> dimension, int playerCx, int playerCz) {
        this.pendingSummaryFrame = null; // a new dimension invalidates any buffered frame
        if (!LSSClientConfig.CONFIG.enableRegionSummarySync) return;
        if (this.summaryHarnessGate.getAsBoolean()) return;
        if (this.summarySessionVersion.getAsInt() != LSSConstants.PROTOCOL_VERSION) return;
        int lod = this.scanner.getEffectiveLodDistance();
        int radius = Math.min(dev.vox.lss.common.region.RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS,
                (lod + 31) / 32 + 1);
        try {
            this.summarySender.send(dev.vox.lss.common.region.RegionSummaryWire.encodeRequest(
                    new dev.vox.lss.common.region.RegionSummaryWire.Request(
                            dimension.identifier().toString(),
                            playerCx >> 5, playerCz >> 5, radius)));
            if (ClientTraceLog.enabled()) {
                ClientTraceLog.event("summary_req", "\"r\":" + radius);
            }
        } catch (Exception e) {
            LSSLogger.debug("Region-summary request send failed: " + e.getMessage());
        }
    }

    public long getSummaryTilesClean() { return this.summaryTilesClean; }
    public long getSummaryTilesStale() { return this.summaryTilesStale; }
    public long getSummaryTilesUnknown() { return this.summaryTilesUnknown; }
    public long getSummaryTilesNoRegion() { return this.summaryTilesNoRegion; }
    public long getSummaryColumnsValidated() { return this.summaryColumnsValidated; }
    public long getSummaryStampsApplied() { return this.summaryStampsApplied; }
    public long getSummaryStampsIgnored() { return this.summaryStampsIgnored; }

    /**
     * Stamped up_to_date S2C frame (stamped-up-to-date-plan.md §4): ratchet cached
     * acquisition stamps forward to the server's verification second, so the next
     * summary frame validates the columns a header write once passed. Kill-switch
     * gated like the summary apply (the ratchet's only consumer is tile validation);
     * dimension mismatch drops (the anti-stale binding — overworld/End coords
     * overlap); decode is contained WITH the semantic stamp bounds (a hostile huge
     * second would seal a position against offline edits permanently — the ratchet
     * is monotonic and revocation only touches summary provenance). No cache-load
     * buffering: answers only flow after declarations, which only flow after the
     * cache gate, so a frame racing a load can only be a stale prior-session frame —
     * absent leaves make it a no-op.
     */
    public void onColumnStamps(byte[] body) {
        if (!LSSClientConfig.CONFIG.enableRegionSummarySync) return;
        if (this.lastDimension == null) return;
        try {
            var stamps = dev.vox.lss.common.region.ColumnStampsWire.decode(
                    body, System.currentTimeMillis() / 1000L);
            if (!this.lastDimension.identifier().toString().equals(stamps.dimension())) return;
            long[] positions = stamps.packedPositions();
            long[] seconds = stamps.stampSeconds();
            for (int i = 0; i < positions.length; i++) {
                if (this.columns.ratchetStamp(positions[i], seconds[i])) {
                    this.summaryStampsApplied++;
                } else {
                    this.summaryStampsIgnored++;
                }
            }
            if (ClientTraceLog.enabled()) {
                ClientTraceLog.event("stamps", "\"n\":" + positions.length);
            }
        } catch (Exception e) {
            LSSLogger.debug("Column-stamps frame dropped: " + e.getMessage());
        }
    }

    void setSummarySenderForTest(SummarySender sender) { this.summarySender = sender; }

    public void onDirtyColumns(long[] dirtyPositions) {
        if (ClientTraceLog.enabled() && dirtyPositions.length > 0) {
            ClientTraceLog.event("dirty", "\"n\":" + dirtyPositions.length
                    + ",\"first\":[" + PositionUtil.unpackX(dirtyPositions[0]) + ","
                    + PositionUtil.unpackZ(dirtyPositions[0]) + "]");
        }
        // Hoisted once per batch (since-release review M1): the per-call form re-reads
        // the effective lod, and a wire-cap dirty frame would bump the Voxy distance
        // cache ~51 times inside one network drain.
        int reopenLod = this.scanner.currentReopenLod();
        for (long packed : dirtyPositions) {
            if (this.pendingCacheLoad != null) {
                // The leaf map is empty mid-load, so the mark below would drop — queue
                // for the post-adopt re-mark (must land before any buffered summary
                // frame validates the edited position; see tickCacheGatePhase).
                this.dirtyDuringCacheLoad.add(packed);
            }
            if (this.columns.markDirtyIfKnown(packed)) {
                // Cadence-NEUTRAL: only re-open the position's OWN ring so it (which may
                // sit below the confirmed prefix) is reachable at the NEXT scheduled scan
                // — a full prefix collapse here cost a full-disc re-walk per broadcast
                // (the render-thread hitch; docs/planning/scanner-reopened-rings-plan.md).
                // resetScanCounter() here was the last survivor of the cadence-debounce
                // class: it DELAYED the next scan 20 ticks per broadcast, and at the floor
                // for a SENDING dirtyBroadcastIntervalSeconds (1 s; 0 disables sends
                // entirely — this handler simply never fires then) a sustained edit stream
                // could phase-lock scans off entirely — starving re-declaration, the
                // want-set's only self-heal (the same failure class as the removed
                // movement debounce).
                this.scanner.reopenRing(Math.max(
                        Math.abs(PositionUtil.unpackX(packed) - this.lastChunkX),
                        Math.abs(PositionUtil.unpackZ(packed) - this.lastChunkZ)), reopenLod);
            }
            // Record a crossing REGARDLESS of markDirtyIfKnown's result: a dirty crossing an
            // in-flight request survives onReceived's dirty-clear only via staleInFlight. This
            // must cover the resync case (stored>0, where markDirtyIfKnown returns true) as well
            // as the first-serve case (stored==-1, returns false) — otherwise a second edit that
            // arrives while the first serve is in flight has its dirty mark cleared by onReceived
            // and is never re-requested (a permanent stale column). No-op when not in flight.
            this.columns.noteStaleIfInFlight(packed, this.tracker.isInFlight(packed));
        }
    }

    /**
     * If a dirty broadcast crossed this position's in-flight first serve, un-settle it and
     * force a ring re-walk so the (pre-edit) answer that just arrived is superseded by a
     * re-request. Called from every terminal first-serve outcome.
     */
    private void consumeStaleCrossing(long packed) {
        if (this.columns.resolveStale(packed)) {
            this.columns.markDirtyIfKnown(packed); // now that it has a disposition, re-mark for re-request
            // Reach it below the confirmed prefix — its own ring only, like the dirty
            // batch handler (a full prefix collapse per stale crossing was the
            // render-thread-hitch shape).
            this.scanner.reopenRing(Math.max(
                    Math.abs(PositionUtil.unpackX(packed) - this.lastChunkX),
                    Math.abs(PositionUtil.unpackZ(packed) - this.lastChunkZ)));
        }
    }

    /**
     * A column stamped received was never ingested by a consumer (decode failure, consumer
     * rejection, undispatched at disconnect). Forget the stamp so the next scan re-requests
     * it with ts=-1 — the server's honest re-resolution then re-serves it instead of
     * answering up-to-date. Main client thread only.
     */
    public void onIngestFailure(ResourceKey<Level> dimension, long packed) {
        if (this.lastDimension == null || !this.lastDimension.equals(dimension)) {
            // A report for another dimension (a consumer rejected asynchronously after the
            // player changed dimension): its in-memory state map is gone, but the false stamp is
            // already saved in that dimension's cache. Remove it there directly so the next visit
            // re-resolves honestly instead of getting a false up_to_date (#36).
            if (this.serverAddress != null && dimension != null) {
                ColumnCacheStore.removeAsync(this.serverAddress, dimension, packed);
            }
            return;
        }
        this.tracker.removeByPosition(packed);
        if (this.pendingCacheLoad != null) {
            // The in-memory apply below is absorbed while the map is empty pre-load; remember
            // the position so tickCacheGatePhase re-applies the failure over the loaded stamps.
            this.failuresDuringCacheLoad.add(packed);
        }
        this.columns.onIngestFailed(packed);
        this.metrics.recordIngestFailure();
        // No resetScanCounter here: it is a debounce (it can only DELAY the next scan,
        // never advance it), and the retry mark already forces the confirmed-ring reset.
        // A steady failure trickle would otherwise push the scan cadence back indefinitely.
    }

    public void onColumnNotGenerated(long packed) {
        if (ClientTraceLog.enabled()) {
            ClientTraceLog.event("ng", "\"pos\":[" + PositionUtil.unpackX(packed) + ","
                    + PositionUtil.unpackZ(packed) + "],\"tracked\":" + this.tracker.isInFlight(packed));
        }
        // BatchResponse carries no dimension, so unlike onColumnReceived this cannot be
        // dimension-guarded. Gate on the awaiting set instead: it is cleared on dimension
        // change, pruned on movement, and replaced wholesale by each scan, so a late status
        // can never stamp the fresh map (matches the pre-v16 requestId gate).
        if (!this.tracker.isInFlight(packed)) {
            // Untracked terminal answer — its position left the last declared want-set
            // (dimension change / movement prune / scan replace). The RTT stamp is equally
            // dead: orphans otherwise accumulate to the 4096-stamp cap and permanently
            // silence RTT sampling for the session.
            this.metrics.discardRttStamp(packed);
            this.metrics.recordNotGenerated();
            return;
        }
        this.tracker.removeByPosition(packed);
        this.metrics.discardRttStamp(packed); // terminal non-column answer: the RTT stamp is dead
        if (v16TransientNotGenerated() && this.columns.timestampFor(packed) <= 0) {
            // Legacy-server transient miss on a FIRST-SERVE position (stamp <=0, which Tier B
            // rewrote to a generate trigger and the old server bounced on a full gen slot): leave
            // it first-serve so the next 1 Hz scan re-declares and re-drives generation — the
            // client-side mirror of the silent-drop-and-heal loop v18 runs server-side. Cold
            // terrain then fills at the old server's generation throughput instead of being
            // abandoned; a rare permanent gen failure persists as one re-declared position
            // (negligible budget), never a backfill stall. Deliberately NOT counted as
            // not_generated — that counter means "permanently unservable", which a transient
            // bounce is not (and on a gen-enabled server it would climb into the thousands).
            //
            // A RESYNC position (cached ts>0, which Tier B does NOT rewrite) is excluded by the
            // stamp check: a NOT_GENERATED for it means the server's copy is gone and it will not
            // regenerate a ts>0 disk miss, so healing would re-declare the identical ts>0 forever
            // without ever regenerating. That IS permanent — fall through and park it (a later
            // dirty broadcast revives it if the region is regenerated).
            return;
        }
        this.columns.onNotGenerated(packed);
        consumeStaleCrossing(packed); // a dirty that crossed this in-flight first serve forces a re-request
        // No resetConfirmedRing here (the old ts=0 gen-retry needed one — CL-014): a
        // NOT_GENERATED position is now PERMANENTLY session-satisfied, so there is no hole
        // below the ring to re-reach, and during a gen-disabled backfill the answer stream
        // would force a full re-walk per answer for nothing. The one path that CAN park a
        // re-requestable position below a confirmed ring — a dirty crossing the in-flight
        // answer — is consumeStaleCrossing above, which reopens the position's own ring.
        this.metrics.recordNotGenerated();
    }

    public void onColumnUpToDate(long packed) {
        if (ClientTraceLog.enabled()) {
            ClientTraceLog.event("utd", "\"pos\":[" + PositionUtil.unpackX(packed) + ","
                    + PositionUtil.unpackZ(packed) + "],\"tracked\":" + this.tracker.isInFlight(packed));
        }
        if (!this.tracker.isInFlight(packed)) {
            this.metrics.discardRttStamp(packed); // untracked terminal answer: stamp is dead (see onColumnNotGenerated)
            this.metrics.recordUpToDate();
            return;
        }
        this.tracker.removeByPosition(packed);
        this.metrics.discardRttStamp(packed); // terminal non-column answer: the RTT stamp is dead
        this.columns.onUpToDate(packed);
        consumeStaleCrossing(packed); // a dirty that crossed this in-flight first serve forces a re-request
        this.metrics.recordUpToDate();
    }

    private void onDimensionChange(ResourceKey<Level> newDimension) {
        if (ClientTraceLog.enabled()) {
            ClientTraceLog.event("dim_change", "\"to\":\"" + newDimension.identifier() + "\"");
        }
        // Unstamp columns still queued for decode BEFORE saveCache persists the old
        // dimension's map: their stamps describe data no consumer ever saw (the drain's
        // dimension filter will discard the payloads once the level switches). At this
        // point lastDimension is still the OLD dimension, so the per-report guard keeps
        // new-dimension stragglers (never stamped) from touching the old map.
        LSSClientNetworking.reportUndispatchedColumns(this);
        saveCache();
        resetRequestState();
        // Same connection, same link: the governor keeps its MEASUREMENTS across a
        // dimension change and drops only control state (impl review MINOR-2 — a full
        // reset would reseed the ping baseline from the congested current reading and
        // the engagement conjunct could never fire again).
        this.governor.onDimensionChange();
        // Re-anchor the prune hysteresis: the cleared state has nothing to prune, and a
        // stale anchor from the old dimension would fire (or defer) the first new-dimension
        // prune arbitrarily.
        this.lastPruneChunkX = this.lastChunkX;
        this.lastPruneChunkZ = this.lastChunkZ;
        this.scanner.resetScanCounter();
        this.cacheLoaded = true;
        startAsyncCacheLoad(newDimension);
    }

    private void resetRequestState() {
        this.columns.clear();
        this.tracker.clear();
        this.metrics.reset();
    }

    private void startAsyncCacheLoad(ResourceKey<Level> dimension) {
        this.failuresDuringCacheLoad.clear(); // per-dimension buffer; a new load starts clean
        this.dirtyDuringCacheLoad.clear();     // same scope — old-dimension notices are stale
        this.pendingCacheLoad = ColumnCacheStore.loadStateAsync(this.serverAddress, dimension);
    }

    public void disconnect() {
        this.tracker.clear();
        this.governor.reset(); // the reset-family convention (teardown is self-sufficient)
        // Defensive disarm: the manager is normally dropped right after, but the session
        // gate's teardown is deliberately self-sufficient — an armed scanner over a
        // cleared tracker would satisfy the fast trigger trivially.
        this.scanner.noteDeclared(0);
    }

    public void saveCache() {
        if (this.serverAddress == null || this.lastDimension == null) return;
        // Abandoned load window: failures buffered while this dimension's cache load was
        // still in flight were never re-applied (tickCacheGatePhase won't run for it again —
        // this save precedes a dimension change or disconnect). Their in-memory apply was
        // absorbed pre-load, so the FILE still holds the stale ts>0 stamps — and merge-on-
        // save would now preserve them forever (the old overwrite truncated them away by
        // accident). Unstamp them through the coalesced removal path; a legitimate re-serve
        // lost this way costs one honest re-request on the next visit.
        if (this.pendingCacheLoad != null && !this.failuresDuringCacheLoad.isEmpty()) {
            for (long failed : this.failuresDuringCacheLoad) {
                ColumnCacheStore.removeAsync(this.serverAddress, this.lastDimension, failed);
            }
            this.failuresDuringCacheLoad.clear();
        }
        // Merge-on-save: the movement prune bounds the in-memory map to a disc around the
        // player, so a plain overwrite truncated the persisted cache to that disc (ghost
        // terrain + full re-download on revisit — see ColumnCacheStore.mergeSaveAsync).
        // A session whose map ended EMPTY but which deliberately unstamped positions must
        // still save: skipping would resurrect the dishonest stamps from the file.
        //
        // OWNERSHIP TRANSFER (the A2 fix, quadtree-client-state-plan.md §3.4): the state
        // is DETACHED into the save — no render-thread copy — which resets the per-column
        // map. Both callers (onDimensionChange, the session-gate teardown) clear or drop
        // this manager immediately after, so the reset replaces a copy-then-discard.
        if (!this.columns.isEmptyMap() || this.columns.hasPersistentRemovals()) {
            ColumnCacheStore.mergeSaveDetachedAsync(this.serverAddress, this.lastDimension,
                    this.columns.detachForSave(), this.lastChunkX, this.lastChunkZ);
        }
    }

    public void flushCache() {
        if (this.serverAddress != null) {
            ColumnCacheStore.clearForServer(this.serverAddress);
        }
        this.pendingCacheLoad = null; // drop any in-flight load — its pre-clear result would resurrect flushed timestamps
        this.pendingSummaryFrame = null; // a frame buffered for the pre-flush state dies with it
        this.failuresDuringCacheLoad.clear();
        this.dirtyDuringCacheLoad.clear();
        resetRequestState();
        this.scanner.reset();
    }

    // --- Test seams ---
    // tick() needs a running Minecraft client, so LodRequestManagerTest seeds the
    // collaborators directly. Single-threaded use only (main-client-thread contract).

    ColumnStateMap columnsForTest() { return this.columns; }
    InFlightTracker trackerForTest() { return this.tracker; }
    SpiralScanner scannerForTest() { return this.scanner; }

    /** tick() derives this from the client level; tests set it directly. */
    void setLastDimensionForTest(ResourceKey<Level> dimension) { this.lastDimension = dimension; }

    /** Replace the batch send transport (production default sends via Fabric networking). */
    void setBatchSenderForTest(BatchSender sender) { this.batchSender = sender; }

    /** Inject a cache-load future to drive the cache gate without real cache IO. Keeps
     *  the historical raw-map signature; the leaf build happens on whatever thread
     *  completes the future (tests are single-threaded and small). */
    void setPendingCacheLoadForTest(CompletableFuture<Long2LongOpenHashMap> future) {
        this.pendingCacheLoad = future.thenApply(ColumnStateMap::buildLoaded);
    }

    /** Skip the first-tick async cache load — tick-phase tests drive the gate explicitly. */
    void markCacheLoadedForTest() { this.cacheLoaded = true; }

    // --- Public getters ---

    /** Dimension id of the level this manager is currently scanning, or "none" before the first tick. */
    public String getCurrentDimensionId() {
        var dim = this.lastDimension;
        return dim != null ? dim.identifier().toString() : "none";
    }

    /**
     * Stored timestamp for one column position (-1 absent, 0 a legacy pre-v17 cache
     * artifact — the client no longer writes 0, see ColumnStateMap#onUpToDate's park-time
     * purge — &gt;0 epoch seconds). Main client thread only — used by the soak harness
     * per-position probes.
     */
    public long getColumnTimestamp(int cx, int cz) {
        return this.columns.timestampFor(PositionUtil.packPosition(cx, cz));
    }

    /**
     * True if the client already holds server data for this position (a resync). Must be
     * queried BEFORE {@link #onColumnReceived} stamps the arriving column. Drives the decode's
     * authoritative air-fill: a resync clears ghost terrain for absent sections, a first serve
     * (nothing held) does not.
     */
    public boolean heldContentBefore(long packed) { return this.columns.timestampFor(packed) > 0; }

    public int getReceivedColumnCount() { return this.columns.receivedCount(); }
    public int getEmptyColumnCount() { return this.columns.emptyCount(); }
    /** Positions resolved this session with no server data (all-air up-to-date, ingest-parked). */
    public int getSatisfiedColumnCount() { return this.columns.sessionSatisfiedCount(); }
    public int getEffectiveLodDistanceChunks() { return this.sessionConfig != null ? this.scanner.getEffectiveLodDistance() : 0; }
    public long getTotalSendCycles() { return this.metrics.getTotalSendCycles(); }
    public long getTotalPositionsRequested() { return this.metrics.getTotalPositionsRequested(); }
    public int getDirtyColumnCount() { return this.columns.dirtyCount(); }
    public int getConfirmedRing() { return this.scanner.getConfirmedRing(); }
    /** Reopened-below-prefix ring count (scanner-reopened-rings-plan.md) — the diag/exporter field. */
    public int getReopenedRingCount() { return this.scanner.getReopenedRingCount(); }
    public int getScanRing() { return this.scanner.getScanRing(); }
    public int getMissingVanillaChunks() { return this.scanner.getMissingVanillaChunks(); }
    /** Fast (completion-triggered) scan fires this session — the adaptive-cadence liveness signal. */
    public long getFastScans() { return this.scanner.getFastScans(); }
    public long getRateGated() { return this.scanner.getRateGated(); }
    /** Rings the section-store fast path confirmed without a position walk (session). */
    public long getQuadRingSkips() { return this.scanner.getQuadRingSkips(); }
    /** Reopened-ring valve overflows this session (quadtree-client-state-plan.md phase 0
     *  — the B1 "does the valve trip in the wild?" measurement). */
    public long getValveTrips() { return this.scanner.getValveTrips(); }

    /** Governor receipt for /lss diag (review n14: rate_gated conflates manual and
     *  governed refusals — this label disambiguates). Four shapes since the slow
     *  start: ENGAGED keeps the pre-phase "<cols>/s (<KB>/s)" (the live-round
     *  receipt format), RAMP renders "ramp@<KB>/s (<cols>/s)", plus "open"/"off". */
    public String getGovernedRateLabel() {
        return switch (this.governor.getPhase()) {
            case ENGAGED -> this.governor.sustainedColumnsPerSecond() + "/s ("
                    + (this.governor.getDesiredBytesPerSec() / 1024) + " KB/s)";
            case RAMP -> "ramp@" + (this.governor.getDesiredBytesPerSec() / 1024)
                    + " KB/s (" + this.governor.sustainedColumnsPerSecond() + "/s, credits="
                    + this.governor.rampOpenStreakForDiag() + "/"
                    + TransferRateGovernor.DISENGAGE_RATE_INTERVALS
                    + (this.governor.windowLimitedLatched() ? ", wl" : "") + ")";
            case OPEN -> "open";
            case DISABLED -> "off";
        };
    }

    // Response counters
    public long getTotalColumnsReceived() { return this.metrics.getTotalColumnsReceived(); }
    public long getTotalUpToDate() { return this.metrics.getTotalUpToDate(); }
    public long getTotalNotGenerated() { return this.metrics.getTotalNotGenerated(); }
    public long getTotalIngestFailures() { return this.metrics.getTotalIngestFailures(); }

    // Rolling rates
    public double getReceiveRate() { return this.metrics.getReceiveRate(); }
    public double getRequestRate() { return this.metrics.getRequestRate(); }

    // RTT distribution (nearest-rank percentiles over the retained sample ring; -1 when empty)
    public double getRttP50Ms() { return this.metrics.getRttP50Ms(); }
    public double getRttP95Ms() { return this.metrics.getRttP95Ms(); }

    // Concurrency
    public int getPendingCount() { return this.tracker.size(); }
    // Last scan budget
    public int getLastBudget() { return this.scanner.getLastBudget(); }
    public int getLastQueued() { return this.scanner.getLastQueued(); }
    /** Last polled consumer ingest backlog in sections (-1 = no signal). Diag/trace only. */
    public int getLastIngestBacklog() { return this.lastIngestBacklog; }
}
