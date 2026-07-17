package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

public class LodRequestManager {
    /** Backpressure threshold: halt sending when column processing queue exceeds this fraction. */
    private static final int BACKPRESSURE_NUMERATOR = 3;
    private static final int BACKPRESSURE_DENOMINATOR = 4;

    private SessionConfigS2CPayload sessionConfig;
    private String serverAddress;

    private int lastChunkX;
    private int lastChunkZ;
    private ResourceKey<Level> lastDimension;

    // Per-column state: timestamps + dirty/retry/validated marks (single owner)
    private final ColumnStateMap columns = new ColumnStateMap();

    // Positions declared in the last want-set that have not yet been answered
    private final InFlightTracker tracker = new InFlightTracker();

    private boolean cacheLoaded = false;
    private volatile CompletableFuture<Long2LongOpenHashMap> pendingCacheLoad = null;
    // Ingest failures reported while this dimension's cache load is still in flight: applied
    // against the not-yet-loaded map they are absorbed (onIngestFailed no-ops on an absent
    // entry) and loadFrom would then resurrect the stale ts>0 stamp from disk — a claim for
    // data no consumer holds, revalidated up_to_date every session. Buffer and re-apply
    // after the load lands. Main client thread only.
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet failuresDuringCacheLoad =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    // Metrics tracking (counters + rolling rates)
    private final RequestMetrics metrics = new RequestMetrics();

    // Edge-trigger for the decode-backpressure clear: exactly one empty batch per halt episode.
    private boolean backpressureClearSent = false;

    // Send buffers, sized once at the protocol's batch cap (~16 KB total)
    private final long[] sendPositionBuffer = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];
    private final long[] sendTimestampBuffer = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];

    // Expanding ring scanner (owns scan cadence + budget policy)
    private final SpiralScanner scanner = new SpiralScanner();

    public void onSessionConfig(SessionConfigS2CPayload config, String serverAddress) {
        this.sessionConfig = config;
        this.serverAddress = serverAddress;
        resetRequestState();
        this.lastDimension = null;
        this.cacheLoaded = false;
        this.scanner.setConfig(config);
        this.scanner.reset();
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
                () -> countMissingVanillaChunks(level, playerCx, playerCz, viewDistance));
    }

    /**
     * Tick body behind the Minecraft-client guards, phases in fixed order: dimension/cache →
     * movement prune → metrics → backpressure halt → cache gate → scan+send. The backpressure
     * halt deliberately precedes the cache poll and the scan: while the decode queue is mostly
     * full nothing new is declared, and recovery resumes only once the queue drains.
     * Package-private for direct test coverage — tick() needs a running Minecraft client.
     */
    void tickWithContext(int playerCx, int playerCz, ResourceKey<Level> currentDim,
                         int viewDistance, int columnQueueSize, IntSupplier missingVanilla) {
        tickDimensionAndCachePhase(currentDim);
        tickMovementPhase(playerCx, playerCz);
        this.metrics.updateRollingRates();
        if (haltedByBackpressure(columnQueueSize)) {
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
        tickScanPhase(playerCx, playerCz, viewDistance, columnQueueSize, missingVanilla);
    }

    /** The explicit backpressure clear: an empty want-set replaces the server backlog with nothing. */
    private void sendClearBatch() {
        if (ClientTraceLog.enabled()) ClientTraceLog.event("clear_batch", "");
        try {
            this.batchSender.send(new BatchChunkRequestC2SPayload(new long[0], new long[0], 0));
        } catch (Exception e) {
            LSSLogger.error("Failed to send backpressure clear batch", e);
            this.backpressureClearSent = false; // retry on the next halted tick
        }
    }

    /** Dimension change: flush state, reload cache. First tick starts the initial load. */
    void tickDimensionAndCachePhase(ResourceKey<Level> currentDim) {
        if (this.lastDimension != null && !currentDim.equals(this.lastDimension)) {
            this.onDimensionChange(currentDim);
        } else if (!this.cacheLoaded) {
            this.cacheLoaded = true;
            startAsyncCacheLoad(currentDim);
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
     * re-declared), and yielding to vanilla's own chunk loading during fast travel is the
     * scanner's vanilla-load budget scale's job, not the cadence's. Side benefit: the
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
            int pruneDistance = this.scanner.getPruneDistance();
            this.columns.pruneOutOfRange(playerCx, playerCz, pruneDistance);
            this.tracker.pruneOutOfRange(playerCx, playerCz, pruneDistance);
            // Pruned in-flight requests will never get a tracked answer — drop their RTT
            // stamps with them or they orphan toward the sampling cap.
            this.metrics.pruneRttStampsOutOfRange(playerCx, playerCz, pruneDistance);
            this.lastChunkX = playerCx;
            this.lastChunkZ = playerCz;
            this.scanner.recenter();
        }
    }

    /** Backpressure: halt when the column processing queue is mostly full. */
    boolean haltedByBackpressure(int columnQueueSize) {
        return columnQueueSize >= haltThreshold();
    }

    /** Package-private so the backpressure-clear tests can drive the exact halt edge. */
    static int haltThreshold() {
        return ClientColumnProcessor.MAX_QUEUED_COLUMNS * BACKPRESSURE_NUMERATOR / BACKPRESSURE_DENOMINATOR;
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
                    this.columns.loadFrom(loaded);
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
        }
        return true;
    }

    /**
     * Periodic scan (every 20 ticks): build the complete want-set and send it as ONE batch in
     * the same tick. A walked-but-empty scan (0) sends NOTHING — the convergence invariant that
     * keeps the soak quiescence predicate alive (a heartbeat would keep requests_received moving
     * forever and silently disable every law) — but still replaces the awaiting set so phantom
     * entries cannot linger.
     * @return the {@link SpiralScanner#maybeScan} result (-1 when no walk happened)
     */
    int tickScanPhase(int playerCx, int playerCz, int viewDistance, int columnQueueSize,
                      IntSupplier missingVanilla) {
        int scanned = this.scanner.maybeScan(playerCx, playerCz, viewDistance,
                columnQueueSize, haltThreshold(), missingVanilla,
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
                    + ",\"declared\":" + scanned
                    + ",\"budget\":" + this.scanner.getLastBudget()
                    + ",\"missing_vanilla\":" + this.scanner.getMissingVanillaChunks()
                    + ",\"ring_min\":" + (maxRing < 0 ? -1 : minRing)
                    + ",\"ring_max\":" + maxRing);
        }
        if (scanned >= 0) {
            this.tracker.replaceWith(this.sendPositionBuffer, scanned);
            if (scanned > 0) {
                sendRequests(this.sendPositionBuffer, this.sendTimestampBuffer, scanned);
            }
        }
        return scanned;
    }

    // --- Request sending and callbacks ---

    /** Batch send transport. Seam for tests; production default is Fabric client networking. */
    interface BatchSender {
        void send(BatchChunkRequestC2SPayload payload);
    }

    private BatchSender batchSender = payload -> ClientPlayNetworking.send(payload);

    private void sendRequests(long[] positionBuffer, long[] timestampBuffer, int count) {
        // Snapshot to exact-length arrays: BatchChunkRequestC2SPayload's StreamCodec reads
        // these lazily when the connection encodes on the netty event loop, which races the
        // next scan overwriting the reused sendPositionBuffer/sendTimestampBuffer. The
        // payload must own its data so a declaration's positions can't be corrupted mid-encode.
        long[] positions = java.util.Arrays.copyOf(positionBuffer, count);
        long[] timestamps = java.util.Arrays.copyOf(timestampBuffer, count);
        try {
            this.batchSender.send(new BatchChunkRequestC2SPayload(positions, timestamps, count));
            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                this.metrics.recordRequestSent(positions[i], nowMs); // RTT: last-declare stamp
            }
        } catch (Exception e) {
            LSSLogger.error("Failed to send want-set batch", e);
            // Nothing is consumed at send any more (marks are answer-consumed), and the next
            // scan re-declares the identical set, so no mark restoration is needed. Just drop
            // the awaiting entries so late-status gating doesn't credit a batch that never
            // reached the wire.
            this.tracker.replaceWith(positions, 0);
        }
        this.metrics.recordSendCycle(count); // counts attempts — a failed batch still counts
    }

    public void onColumnReceived(long packed, long columnTimestamp, ResourceKey<Level> dimension) {
        onColumnReceived(packed, columnTimestamp, dimension, false);
    }

    /**
     * @param authoritativeClear the received column was a 0-section content-&gt;air CLEAR (see
     *        {@link ClientColumnProcessor#isClearColumn}). If the consumer later rejects it, the
     *        re-request must carry the pre-clear content stamp so the server re-sends the clear;
     *        recorded via {@link ColumnStateMap#markAuthoritativeClear} inside the dimension guard.
     */
    public void onColumnReceived(long packed, long columnTimestamp, ResourceKey<Level> dimension,
                                 boolean authoritativeClear) {
        // A column from another dimension is discarded by the dispatch drain
        // (ClientColumnProcessor filters on level.dimension()), so stamping it here would
        // mark the position SATISFIED in the current dimension's map with no data delivered.
        if (this.lastDimension != null && !this.lastDimension.equals(dimension)) return;
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
            return;
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
                    + ",\"ts\":" + columnTimestamp + (authoritativeClear ? ",\"clear\":true" : ""));
        }
        this.tracker.removeByPosition(packed);
        this.columns.onReceived(packed, columnTimestamp);
        if (authoritativeClear) this.columns.markAuthoritativeClear(packed, preClearStamp);
        consumeStaleCrossing(packed); // a dirty that crossed this in-flight first serve forces a re-request
        this.metrics.recordColumnReceived(packed, System.currentTimeMillis()); // RTT sample + counter
    }

    public void onDirtyColumns(long[] dirtyPositions) {
        if (ClientTraceLog.enabled() && dirtyPositions.length > 0) {
            ClientTraceLog.event("dirty", "\"n\":" + dirtyPositions.length
                    + ",\"first\":[" + PositionUtil.unpackX(dirtyPositions[0]) + ","
                    + PositionUtil.unpackZ(dirtyPositions[0]) + "]");
        }
        boolean added = false;
        for (long packed : dirtyPositions) {
            added |= this.columns.markDirtyIfKnown(packed);
            // Record a crossing REGARDLESS of markDirtyIfKnown's result: a dirty crossing an
            // in-flight request survives onReceived's dirty-clear only via staleInFlight. This
            // must cover the resync case (stored>0, where markDirtyIfKnown returns true) as well
            // as the first-serve case (stored==-1, returns false) — otherwise a second edit that
            // arrives while the first serve is in flight has its dirty mark cleared by onReceived
            // and is never re-requested (a permanent stale column). No-op when not in flight.
            this.columns.noteStaleIfInFlight(packed, this.tracker.isInFlight(packed));
        }
        if (added) {
            this.scanner.resetScanCounter();
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
            this.scanner.resetConfirmedRing();     // reach it below the confirmed ring
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
        this.columns.onNotGenerated(packed);
        consumeStaleCrossing(packed); // a dirty that crossed this in-flight first serve forces a re-request
        // No resetConfirmedRing here (the old ts=0 gen-retry needed one — CL-014): a
        // NOT_GENERATED position is now PERMANENTLY session-satisfied, so there is no hole
        // below the ring to re-reach, and during a gen-disabled backfill the answer stream
        // would force a full re-walk per answer for nothing. The one path that CAN park a
        // re-requestable position below a confirmed ring — a dirty crossing the in-flight
        // answer — is consumeStaleCrossing above, which does its own confirmed-ring reset.
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
        this.pendingCacheLoad = ColumnCacheStore.loadAsync(this.serverAddress, dimension);
    }

    public void disconnect() {
        this.tracker.clear();
    }

    public void saveCache() {
        if (this.serverAddress != null && this.lastDimension != null && !this.columns.isEmptyMap()) {
            ColumnCacheStore.saveAsync(this.serverAddress, this.lastDimension, this.columns.mapForSave());
        }
    }

    public void flushCache() {
        if (this.serverAddress != null) {
            ColumnCacheStore.clearForServer(this.serverAddress);
        }
        this.pendingCacheLoad = null; // drop any in-flight load — its pre-clear result would resurrect flushed timestamps
        this.failuresDuringCacheLoad.clear();
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

    /** Inject a cache-load future to drive the cache gate without real cache IO. */
    void setPendingCacheLoadForTest(CompletableFuture<Long2LongOpenHashMap> future) {
        this.pendingCacheLoad = future;
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
    public int getScanRing() { return this.scanner.getScanRing(); }
    public int getMissingVanillaChunks() { return this.scanner.getMissingVanillaChunks(); }

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
}
