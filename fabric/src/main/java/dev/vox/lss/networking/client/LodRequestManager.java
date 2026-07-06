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
    private static final int MIN_SEND_PER_TICK = 16;
    private static final long TIMEOUT_NANOS = 10_000L * LSSConstants.NANOS_PER_MS;

    private SessionConfigS2CPayload sessionConfig;
    private String serverAddress;

    private int lastChunkX;
    private int lastChunkZ;
    private ResourceKey<Level> lastDimension;

    // Per-column state: timestamps + dirty/retry/validated marks (single owner)
    private final ColumnStateMap columns = new ColumnStateMap();

    // In-flight request tracking (position → sendTime)
    private final InFlightTracker tracker = new InFlightTracker();

    // Request queue — written by the scanner, consumed by drainQueue()
    private final RequestQueue queue = new RequestQueue();

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

    // Derived per-tick send cap: 1/20th of last scan's queued count, floored at MIN_SEND_PER_TICK
    private int maxSendPerTick = MIN_SEND_PER_TICK;

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
     * movement prune → metrics → backpressure halt → cache gate → scan+sweep → drain+send.
     * The backpressure halt deliberately precedes the cache poll, the timeout sweep, and the
     * drain: while the decode queue is mostly full nothing new goes out, and recovery paths
     * resume only once the queue drains. Package-private for direct test coverage — tick()
     * needs a running Minecraft client.
     */
    void tickWithContext(int playerCx, int playerCz, ResourceKey<Level> currentDim,
                         int viewDistance, int columnQueueSize, IntSupplier missingVanilla) {
        tickDimensionAndCachePhase(currentDim);
        tickMovementPhase(playerCx, playerCz);
        this.metrics.updateRollingRates();
        if (haltedByBackpressure(columnQueueSize)) return;
        if (!tickCacheGatePhase()) return;
        tickScanPhase(playerCx, playerCz, viewDistance, columnQueueSize, missingVanilla);
        tickDrainPhase();
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
     * Movement: prune out-of-range data + drop stale in-flight tracking, and restart the
     * scan cadence at the new center. Two pinned consequences (LodRequestManagerTickTest):
     * the (0,0) lastChunk init makes a player joining outside chunk (0,0) take this branch
     * on tick 1, losing the primed immediate first scan (~1 s join delay); and crossing a
     * chunk boundary more often than every 20 ticks restarts the cadence each time,
     * starving both scans AND the timeout sweep that rides them until the player rests.
     */
    void tickMovementPhase(int playerCx, int playerCz) {
        if (playerCx != this.lastChunkX || playerCz != this.lastChunkZ) {
            int pruneDistance = this.scanner.getPruneDistance();
            this.columns.pruneOutOfRange(playerCx, playerCz, pruneDistance);
            this.tracker.pruneOutOfRange(playerCx, playerCz, pruneDistance);
            // Pruned in-flight requests will never get a tracked answer — drop their RTT
            // stamps with them or they orphan toward the sampling cap.
            this.metrics.pruneRttStampsOutOfRange(playerCx, playerCz, pruneDistance);
            this.lastChunkX = playerCx;
            this.lastChunkZ = playerCz;
            this.scanner.resetScanCounter();
        }
    }

    /** Backpressure: halt when the column processing queue is mostly full. */
    boolean haltedByBackpressure(int columnQueueSize) {
        return columnQueueSize >= haltThreshold();
    }

    private static int haltThreshold() {
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
     * Periodic scan (every 20 ticks): discover positions needing requests.
     * @return the {@link SpiralScanner#maybeScan} result (-1 when the cadence did not fire)
     */
    int tickScanPhase(int playerCx, int playerCz, int viewDistance, int columnQueueSize,
                      IntSupplier missingVanilla) {
        int scanned = this.scanner.maybeScan(playerCx, playerCz, viewDistance,
                columnQueueSize, haltThreshold(), missingVanilla,
                this.columns, this.tracker::isInFlight, this.queue);
        if (scanned >= 0) {
            if (scanned > 0) {
                updateSendPerTick(scanned);
            }
            // Timeout sweep: evict stale requests on the scan cadence (even if scan skipped).
            sweepTimeouts();
        }
        return scanned;
    }

    /**
     * Every tick: drain queue through concurrency limits and send.
     * @return the number of positions sent
     */
    int tickDrainPhase() {
        if (this.queue.hasNext()) {
            int sent = drainQueue(this.maxSendPerTick);
            if (sent > 0) sendRequests(this.sendPositionBuffer, this.sendTimestampBuffer, sent);
            return sent;
        }
        return 0;
    }

    // --- Send rate adaptation ---

    private void updateSendPerTick(int lastScanQueued) {
        this.maxSendPerTick = Math.min(LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                Math.max(MIN_SEND_PER_TICK, (lastScanQueued + LSSConstants.TICKS_PER_SECOND - 1) / LSSConstants.TICKS_PER_SECOND));
    }

    // --- Queue drain ---

    /** Package-private for direct test coverage — tick() needs a running Minecraft client. */
    int drainQueue(int maxToSend) {
        long now = System.nanoTime();
        boolean generationEnabled = this.sessionConfig.generationEnabled();
        int maxGenConcurrency = this.sessionConfig.generationConcurrencyLimitPerPlayer();
        int maxSyncConcurrency = this.sessionConfig.syncOnLoadConcurrencyLimitPerPlayer();
        int count = 0;

        while (count < maxToSend && this.queue.hasNext()) {
            long pos = this.queue.peekPosition();
            if (this.tracker.isInFlight(pos)) { this.queue.skip(); continue; }
            // Re-derive the timestamp from classify at SEND time, not the scan-time value the
            // queue buffered: a stamp forgotten between scan and drain (e.g. an ingest failure
            // reset it to -1) would otherwise go out as a stale ts>0 claiming data the client
            // no longer holds, defeating the server's ts<=0 honest re-resolution.
            long ts = this.columns.classify(pos, generationEnabled);
            if (ts == ColumnStateMap.SATISFIED) { this.queue.skip(); continue; }

            // Per-type concurrency check — skip if this type is full, try next
            boolean isGen = ts == 0;
            if (isGen && this.tracker.generationCount() >= maxGenConcurrency) { this.queue.skip(); continue; }
            if (!isGen && (this.tracker.size() - this.tracker.generationCount()) >= maxSyncConcurrency) { this.queue.skip(); continue; }

            this.queue.skip();
            this.sendPositionBuffer[count] = pos;
            this.sendTimestampBuffer[count] = ts;
            this.tracker.markPending(pos, now, isGen);
            this.columns.markSent(pos);
            count++;
        }

        return count;
    }

    /**
     * Evict timed-out in-flight requests and mark each eviction for retry — in-flight
     * counts as satisfied for ring confirmation, so an unmarked eviction inside a
     * confirmed ring would never be rescanned (permanent hole; the bandwidth-throttle
     * soak found 161 such orphans; see InFlightTracker.timeoutSweep).
     * Package-private for direct test coverage — tick() needs a running Minecraft client.
     */
    void sweepTimeouts() {
        this.tracker.timeoutSweep(TIMEOUT_NANOS, this.columns::markRetry);
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
        // next tick's drainQueue overwriting the reused sendPositionBuffer/sendTimestampBuffer.
        // The payload must own its data so a request's positions can't be corrupted mid-encode.
        long[] positions = java.util.Arrays.copyOf(positionBuffer, count);
        long[] timestamps = java.util.Arrays.copyOf(timestampBuffer, count);
        try {
            this.batchSender.send(new BatchChunkRequestC2SPayload(positions, timestamps, count));
            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                this.metrics.recordRequestSent(positions[i], nowMs); // RTT send stamp per position
            }
        } catch (Exception e) {
            LSSLogger.error("Failed to send batch chunk request", e);
            for (int i = 0; i < count; i++) {
                this.tracker.removeByPosition(positions[i]);
                // drainQueue consumed the dirty/retry marks at markSent, so without
                // restoration a validated position whose batch never reached the wire
                // classifies SATISFIED forever — the same orphan class as a timeout
                // eviction (see sweepTimeouts), without the 10 s grace. Re-mark retry:
                // classify re-asks with the stored timestamp, no invented stamps.
                this.columns.markRetry(positions[i]);
            }
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
        // Capture the pre-clear content stamp BEFORE onReceived overwrites it with the clear's
        // timestamp — a rejected clear re-requests with this value, not ts=-1.
        long preClearStamp = authoritativeClear ? this.columns.timestampFor(packed) : -1L;
        // Apply even if the position is no longer tracked (e.g. it timed out client-side):
        // the data is authoritative for the position, and stamping it prevents the
        // timeout → silent-duplicate → second-timeout stall.
        this.tracker.removeByPosition(packed);
        this.columns.onReceived(packed, columnTimestamp);
        if (authoritativeClear) this.columns.markAuthoritativeClear(packed, preClearStamp);
        consumeStaleCrossing(packed); // a dirty that crossed this in-flight first serve forces a re-request
        this.metrics.recordColumnReceived(packed, System.currentTimeMillis()); // RTT sample + counter
    }

    public void onDirtyColumns(long[] dirtyPositions) {
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
        // BatchResponse carries no dimension, so unlike onColumnReceived this cannot be
        // dimension-guarded. Gate on tracking instead: the tracker is cleared on dimension
        // change / timeout / prune, so a late status can never stamp the fresh map
        // (matches the pre-v16 requestId gate).
        if (!this.tracker.isInFlight(packed)) {
            // Untracked terminal answer (the tracker entry died to a timeout/prune): the RTT
            // stamp is equally dead — orphans otherwise accumulate to the 4096-stamp cap and
            // permanently silence RTT sampling for the session.
            this.metrics.discardRttStamp(packed);
            this.metrics.recordNotGenerated();
            return;
        }
        this.tracker.removeByPosition(packed);
        this.metrics.discardRttStamp(packed); // terminal non-column answer: the RTT stamp is dead
        this.columns.onNotGenerated(packed);
        consumeStaleCrossing(packed); // a dirty that crossed this in-flight first serve forces a re-request
        // The scanner skips in-flight positions without breaking ring confirmation, so the ring
        // can confirm PAST this position while it was in-flight. With generation enabled the ts=0
        // stamp is gen-retry-able (classify -> 0), but a below-ring position is never rescanned
        // until the ring re-walks — force that re-walk so a stationary player does not keep an
        // ungenerated hole (CL-014). Cadence-neutral and gen-disabled-safe (classify parks ts=0
        // when generation is off, so the re-walk just skips it).
        this.scanner.resetConfirmedRing();
        this.metrics.recordNotGenerated();
    }

    public void onColumnUpToDate(long packed) {
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

    public void onRateLimited(long packed) {
        this.scanner.noteRateLimited(); // backoff regardless of tracking (pre-v16 set skipNextScan outside the gate)
        if (!this.tracker.isInFlight(packed)) {
            this.metrics.discardRttStamp(packed); // untracked terminal answer: stamp is dead (see onColumnNotGenerated)
            this.metrics.recordRateLimited();
            return;
        }
        this.tracker.removeByPosition(packed);
        this.metrics.discardRttStamp(packed); // a bounce is terminal for THIS request; the retry re-stamps
        this.columns.markRetry(packed);
        this.metrics.recordRateLimited();
    }

    private void onDimensionChange(ResourceKey<Level> newDimension) {
        // Unstamp columns still queued for decode BEFORE saveCache persists the old
        // dimension's map: their stamps describe data no consumer ever saw (the drain's
        // dimension filter will discard the payloads once the level switches). At this
        // point lastDimension is still the OLD dimension, so the per-report guard keeps
        // new-dimension stragglers (never stamped) from touching the old map.
        LSSClientNetworking.reportUndispatchedColumns(this);
        saveCache();
        resetRequestState();
        this.queue.clear();
        this.scanner.resetScanCounter();
        this.scanner.clearSkipNextScan();
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
        this.queue.clear();
        this.scanner.reset();
    }

    // --- Test seams ---
    // tick() needs a running Minecraft client, so LodRequestManagerTest seeds the
    // collaborators directly. Single-threaded use only (main-client-thread contract).

    ColumnStateMap columnsForTest() { return this.columns; }
    InFlightTracker trackerForTest() { return this.tracker; }
    RequestQueue queueForTest() { return this.queue; }
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
        return dim != null ? dim.location().toString() : "none";
    }

    /**
     * Stored timestamp for one column position (-1 absent, 0 not-generated, &gt;0 epoch
     * seconds). Main client thread only — used by the soak harness per-position probes.
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
    public long getTotalRateLimited() { return this.metrics.getTotalRateLimited(); }
    public long getTotalIngestFailures() { return this.metrics.getTotalIngestFailures(); }

    // Rolling rates
    public double getReceiveRate() { return this.metrics.getReceiveRate(); }
    public double getRequestRate() { return this.metrics.getRequestRate(); }

    // RTT distribution (nearest-rank percentiles over the retained sample ring; -1 when empty)
    public double getRttP50Ms() { return this.metrics.getRttP50Ms(); }
    public double getRttP95Ms() { return this.metrics.getRttP95Ms(); }

    // Concurrency
    public int getPendingCount() { return this.tracker.size(); }
    public int getQueueRemaining() { return this.queue.remaining(); }

    // Last scan budget
    public int getLastBudget() { return this.scanner.getLastBudget(); }
    public int getLastSyncQueued() { return this.scanner.getLastSyncQueued(); }
    public int getLastGenQueued() { return this.scanner.getLastGenQueued(); }
}
