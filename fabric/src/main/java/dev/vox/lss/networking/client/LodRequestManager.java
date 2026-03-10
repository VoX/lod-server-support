package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.CompletableFuture;

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

    // Key: packed position, Value: raw column timestamp
    // defaultReturnValue(-1L) -> -1 means "not in map / never requested"
    private final Long2LongOpenHashMap columnTimestamps = new Long2LongOpenHashMap();
    {
        columnTimestamps.defaultReturnValue(-1L);
    }

    // In-flight request tracking (position ↔ requestId ↔ sendTime)
    private final InFlightTracker tracker = new InFlightTracker();

    // Request queue — populated by scanner, consumed by drainQueue()
    private final RequestQueue queue = new RequestQueue();

    private boolean cacheLoaded = false;
    private volatile CompletableFuture<Long2LongOpenHashMap> pendingCacheLoad = null;

    // Positions pushed by the server's dirty column broadcast that need re-requesting.
    private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();

    // Positions that were rate-limited and need retry on next scan cycle.
    private final LongOpenHashSet rateLimitRetryPositions = new LongOpenHashSet();

    // Positions confirmed current (received data or up-to-date) in this session.
    // Cleared on reconnect/dimension change. Unvalidated cached positions are re-requested.
    private final LongOpenHashSet validatedThisSession = new LongOpenHashSet();

    // Metrics tracking (counters + rolling rates)
    private final RequestMetrics metrics = new RequestMetrics();

    // Fixed per-type concurrency caps (set from session config)
    private int maxGenConcurrency;
    private int maxSyncConcurrency;
    private boolean skipNextScan;

    // Derived per-tick send cap: 1/20th of last scan's queued count, floored at MIN_SEND_PER_TICK
    private int maxSendPerTick = MIN_SEND_PER_TICK;

    // Pre-allocated send buffers for drainQueue() to avoid per-tick allocation
    private int[] sendRequestIdBuffer = new int[MIN_SEND_PER_TICK];
    private long[] sendPositionBuffer = new long[MIN_SEND_PER_TICK];
    private long[] sendTimestampBuffer = new long[MIN_SEND_PER_TICK];

    // Expanding ring scanner
    private final SpiralScanner scanner = new SpiralScanner();

    private void putTimestamp(long packed, long timestamp) {
        long old = this.columnTimestamps.put(packed, timestamp);
        this.metrics.adjustCounters(old, timestamp);
    }

    private void clearTimestamps() {
        this.columnTimestamps.clear();
        this.metrics.reset();
    }

    private void loadTimestamps(Long2LongOpenHashMap loaded) {
        this.columnTimestamps.putAll(loaded);
        this.metrics.bulkRecount(this.columnTimestamps);
    }

    public void onSessionConfig(SessionConfigS2CPayload config, String serverAddress) {
        this.sessionConfig = config;
        this.serverAddress = serverAddress;
        resetRequestState();
        this.lastDimension = null;
        this.cacheLoaded = false;
        this.scanner.reset();
        this.maxGenConcurrency = config.generationConcurrencyLimitPerPlayer();
        this.maxSyncConcurrency = config.syncOnLoadConcurrencyLimitPerPlayer();
        this.skipNextScan = false;
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
        var currentDim = level.dimension();

        // --- Dimension change: flush state, reload cache ---
        if (this.lastDimension != null && !currentDim.equals(this.lastDimension)) {
            this.onDimensionChange(currentDim);
        } else if (!this.cacheLoaded) {
            this.cacheLoaded = true;
            startAsyncCacheLoad(currentDim);
        }
        this.lastDimension = currentDim;

        // --- Movement: prune out-of-range data + cancel stale requests ---
        if (playerCx != this.lastChunkX || playerCz != this.lastChunkZ) {
            int pruneDistance = this.scanner.getPruneDistance(this.sessionConfig);
            this.scanner.pruneOutOfRangeTimestamps(this.columnTimestamps, this.metrics, playerCx, playerCz, pruneDistance);
            this.scanner.pruneOutOfRangePositions(this.dirtyColumns, playerCx, playerCz, pruneDistance);
            this.scanner.pruneOutOfRangePositions(this.rateLimitRetryPositions, playerCx, playerCz, pruneDistance);
            this.scanner.pruneOutOfRangePositions(this.validatedThisSession, playerCx, playerCz, pruneDistance);
            this.pruneAndCancelOutOfRangePending(playerCx, playerCz, pruneDistance);
            this.lastChunkX = playerCx;
            this.lastChunkZ = playerCz;
            this.scanner.resetScanCounter();
        }

        // --- Metrics ---
        this.metrics.updateRollingRates();

        // --- Backpressure: halt when column processing queue is mostly full ---
        int columnQueueSize = LSSClientNetworking.getQueuedColumnCount();
        int columnQueueCapacity = ClientColumnProcessor.MAX_QUEUED_COLUMNS;
        if (columnQueueSize >= columnQueueCapacity * BACKPRESSURE_NUMERATOR / BACKPRESSURE_DENOMINATOR) return;

        // --- Cache gate: don't scan until timestamp cache has loaded ---
        // Poll inline rather than relying on thenAcceptAsync callback scheduling,
        // which can be delayed on starved render threads (e.g., CI with llvmpipe).
        if (this.pendingCacheLoad != null) {
            if (!this.pendingCacheLoad.isDone()) return;
            try {
                var loaded = this.pendingCacheLoad.getNow(null);
                if (loaded != null && this.lastDimension != null) {
                    this.loadTimestamps(loaded);
                }
            } catch (Exception ignored) {}
            this.pendingCacheLoad = null;
        }

        // --- Periodic scan (every 20 ticks): discover positions needing requests ---
        if (this.scanner.advanceScanTick()) {
            if (this.skipNextScan) {
                this.skipNextScan = false;
            } else {
                int viewDistance = mc.options.getEffectiveRenderDistance();

                // Measure vanilla chunk loading pressure
                int missingVanilla = countMissingVanillaChunks(level, playerCx, playerCz, viewDistance);
                this.scanner.updateMissingVanillaChunks(missingVanilla);

                // Compute scan budget: base × queue-pressure-scale × vanilla-load-scale
                int budget = SpiralScanner.baseBudget(this.sessionConfig);
                int haltThreshold = columnQueueCapacity * BACKPRESSURE_NUMERATOR / BACKPRESSURE_DENOMINATOR;
                if (columnQueueSize > 0) {
                    budget = Math.max(1, Math.round(budget * Math.max(0f, 1f - (float) columnQueueSize / haltThreshold)));
                }
                if (missingVanilla > 0) {
                    int exclusionArea = (2 * viewDistance + 1) * (2 * viewDistance + 1);
                    float vanillaScale = Math.max(0f, 1f - (float) missingVanilla / exclusionArea);
                    if (vanillaScale <= 0f) budget = 0;
                    else budget = Math.max(1, Math.round(budget * vanillaScale));
                }

                // Scan expanding rings for positions that need requesting
                if (budget > 0) {
                    var scanResult = this.scanner.scan(playerCx, playerCz, viewDistance,
                            this.columnTimestamps, this.dirtyColumns, this.rateLimitRetryPositions,
                            this.validatedThisSession, this.tracker::isInFlight, this.sessionConfig, budget);
                    if (scanResult.count() > 0) {
                        this.queue.populate(scanResult);
                        updateSendPerTick(scanResult.count());
                    }
                }
            }

            // Timeout sweep: evict stale requests (always, even if scan skipped)
            this.tracker.timeoutSweep(TIMEOUT_NANOS);
        }

        // --- Every tick: drain queue through concurrency limits ---
        if (this.queue.hasNext()) {
            int sent = drainQueue(this.maxSendPerTick);
            if (sent > 0) sendRequests(this.sendPositionBuffer, this.sendTimestampBuffer, sent);
        }
    }

    // --- Send rate adaptation ---

    private void updateSendPerTick(int lastScanQueued) {
        int perTick = Math.min(LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                Math.max(MIN_SEND_PER_TICK, (lastScanQueued + LSSConstants.TICKS_PER_SECOND - 1) / LSSConstants.TICKS_PER_SECOND));
        if (perTick != this.maxSendPerTick) {
            this.maxSendPerTick = perTick;
            if (perTick > this.sendPositionBuffer.length) {
                this.sendRequestIdBuffer = new int[perTick];
                this.sendPositionBuffer = new long[perTick];
                this.sendTimestampBuffer = new long[perTick];
            }
        }
    }

    // --- Queue drain ---

    private int drainQueue(int maxToSend) {
        long now = System.nanoTime();
        long[] positionBuffer = this.sendPositionBuffer;
        long[] timestampBuffer = this.sendTimestampBuffer;
        int count = 0;

        while (count < maxToSend && this.queue.hasNext()) {
            long pos = this.queue.peekPosition();
            long ts = this.queue.peekTimestamp();
            if (this.tracker.isInFlight(pos)) { this.queue.skip(); continue; }
            long stored = this.columnTimestamps.get(pos);
            if (stored > 0 && !this.dirtyColumns.contains(pos) && !this.rateLimitRetryPositions.contains(pos)
                    && this.validatedThisSession.contains(pos)) { this.queue.skip(); continue; }

            // Per-type concurrency check — skip if this type is full, try next
            boolean isGen = ts == 0;
            if (isGen && this.tracker.generationCount() >= this.maxGenConcurrency) { this.queue.skip(); continue; }
            if (!isGen && (this.tracker.size() - this.tracker.generationCount()) >= this.maxSyncConcurrency) { this.queue.skip(); continue; }

            this.queue.skip();
            positionBuffer[count] = pos;
            timestampBuffer[count] = ts;
            this.tracker.markPending(pos, now, isGen);
            this.rateLimitRetryPositions.remove(pos);
            this.dirtyColumns.remove(pos);
            count++;
        }

        return count;
    }

    // --- Request sending and callbacks ---

    private void sendRequests(long[] positionBuffer, long[] timestampBuffer, int count) {
        int[] requestIds = this.sendRequestIdBuffer;
        for (int i = 0; i < count; i++) {
            requestIds[i] = this.tracker.send(positionBuffer[i]);
        }
        try {
            ClientPlayNetworking.send(new BatchChunkRequestC2SPayload(requestIds, positionBuffer, timestampBuffer, count));
        } catch (Exception e) {
            LSSLogger.error("Failed to send batch chunk request", e);
            for (int i = 0; i < count; i++) {
                this.tracker.removeByRequestId(requestIds[i]);
            }
        }
        this.metrics.recordSendCycle(count);
    }

    public void onColumnReceived(int requestId, long columnTimestamp) {
        var completion = this.tracker.removeByRequestId(requestId);
        if (completion != null) {
            this.dirtyColumns.remove(completion.position());
            this.putTimestamp(completion.position(), columnTimestamp);
            this.validatedThisSession.add(completion.position());
        }
        this.metrics.recordColumnReceived();
    }

    public void onDirtyColumns(long[] dirtyPositions) {
        boolean added = false;
        for (long packed : dirtyPositions) {
            long stored = this.columnTimestamps.get(packed);
            if (stored > 0) {
                this.dirtyColumns.add(packed);
                added = true;
            }
        }
        if (added) {
            this.scanner.resetScanCounter();
        }
    }

    public void onColumnNotGenerated(int requestId) {
        var removal = this.tracker.removeByRequestId(requestId);
        if (removal != null) {
            this.putTimestamp(removal.position(), 0L);
        }
        this.metrics.recordNotGenerated();
    }

    public void onColumnUpToDate(int requestId) {
        var completion = this.tracker.removeByRequestId(requestId);
        if (completion != null) {
            this.validatedThisSession.add(completion.position());
            // Empty columns never get a VoxelColumn response, so columnTimestamps stays -1L.
            // Without a positive timestamp the scanner treats the position as "never requested"
            // and re-queues it every cycle. Stamp it now so the validatedThisSession gate works.
            if (this.columnTimestamps.get(completion.position()) == -1L) {
                this.putTimestamp(completion.position(), LSSConstants.epochSeconds());
            }
        }
        this.metrics.recordUpToDate();
    }

    public void onRateLimited(int requestId) {
        var removal = this.tracker.removeByRequestId(requestId);
        if (removal != null) {
            this.rateLimitRetryPositions.add(removal.position());
        }
        this.metrics.recordRateLimited();
        this.skipNextScan = true;
    }

    private void onDimensionChange(ResourceKey<Level> newDimension) {
        saveCache();
        this.cancelAllPending();
        resetRequestState();
        this.queue.clear();
        this.scanner.resetScanCounter();
        this.cacheLoaded = true;
        startAsyncCacheLoad(newDimension);
    }

    private void resetRequestState() {
        this.clearTimestamps();
        this.dirtyColumns.clear();
        this.rateLimitRetryPositions.clear();
        this.validatedThisSession.clear();
        this.tracker.clear();
        this.skipNextScan = false;
    }

    private void startAsyncCacheLoad(ResourceKey<Level> dimension) {
        this.pendingCacheLoad = ColumnCacheStore.loadAsync(this.serverAddress, dimension);
    }

    private void sendCancelPacket(int reqId) {
        try { ClientPlayNetworking.send(new CancelRequestC2SPayload(reqId)); }
        catch (Exception ignored) {}
    }

    private void pruneAndCancelOutOfRangePending(int playerCx, int playerCz, int pruneDistance) {
        this.tracker.pruneOutOfRange(playerCx, playerCz, pruneDistance, this::sendCancelPacket);
    }

    private void cancelAllPending() {
        this.tracker.forEachRequestId(this::sendCancelPacket);
    }

    public void disconnect() {
        this.tracker.clear();
    }

    public void saveCache() {
        if (this.serverAddress != null && this.lastDimension != null && !this.columnTimestamps.isEmpty()) {
            ColumnCacheStore.saveAsync(this.serverAddress, this.lastDimension, this.columnTimestamps);
        }
    }

    public void flushCache() {
        if (this.serverAddress != null) {
            ColumnCacheStore.clearForServer(this.serverAddress);
        }
        resetRequestState();
        this.queue.clear();
        this.scanner.resetScanCounter();
    }

    // --- Public getters ---

    public int getReceivedColumnCount() { return this.metrics.getReceivedCount(); }
    public int getEmptyColumnCount() { return this.metrics.getEmptyCount(); }
    public int getEffectiveLodDistanceChunks() { return this.sessionConfig != null ? this.scanner.getEffectiveLodDistance(this.sessionConfig) : 0; }
    public long getTotalSendCycles() { return this.metrics.getTotalSendCycles(); }
    public long getTotalPositionsRequested() { return this.metrics.getTotalPositionsRequested(); }
    public int getDirtyColumnCount() { return this.dirtyColumns.size(); }
    public int getConfirmedRing() { return this.scanner.getConfirmedRing(); }
    public int getScanRing() { return this.scanner.getScanRing(); }
    public int getMissingVanillaChunks() { return this.scanner.getMissingVanillaChunks(); }

    // Response counters
    public long getTotalColumnsReceived() { return this.metrics.getTotalColumnsReceived(); }
    public long getTotalUpToDate() { return this.metrics.getTotalUpToDate(); }
    public long getTotalNotGenerated() { return this.metrics.getTotalNotGenerated(); }
    public long getTotalRateLimited() { return this.metrics.getTotalRateLimited(); }

    // Rolling rates
    public double getReceiveRate() { return this.metrics.getReceiveRate(); }
    public double getRequestRate() { return this.metrics.getRequestRate(); }

    // Concurrency
    public int getPendingCount() { return this.tracker.size(); }
    public int getQueueRemaining() { return this.queue.remaining(); }

    // Last scan budget
    public int getLastBudget() { return this.scanner.getLastBudget(); }
    public int getLastSyncQueued() { return this.scanner.getLastSyncQueued(); }
    public int getLastGenQueued() { return this.scanner.getLastGenQueued(); }
}
