package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
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

    // Per-column state: timestamps + dirty/retry/validated marks (single owner)
    private final ColumnStateMap columns = new ColumnStateMap();

    // In-flight request tracking (position → sendTime)
    private final InFlightTracker tracker = new InFlightTracker();

    // Request queue — written by the scanner, consumed by drainQueue()
    private final RequestQueue queue = new RequestQueue();

    private boolean cacheLoaded = false;
    private volatile CompletableFuture<Long2LongOpenHashMap> pendingCacheLoad = null;

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
        var currentDim = level.dimension();

        // --- Dimension change: flush state, reload cache ---
        if (this.lastDimension != null && !currentDim.equals(this.lastDimension)) {
            this.onDimensionChange(currentDim);
        } else if (!this.cacheLoaded) {
            this.cacheLoaded = true;
            startAsyncCacheLoad(currentDim);
        }
        this.lastDimension = currentDim;

        // --- Movement: prune out-of-range data + drop stale in-flight tracking ---
        if (playerCx != this.lastChunkX || playerCz != this.lastChunkZ) {
            int pruneDistance = this.scanner.getPruneDistance();
            this.columns.pruneOutOfRange(playerCx, playerCz, pruneDistance);
            this.tracker.pruneOutOfRange(playerCx, playerCz, pruneDistance);
            this.lastChunkX = playerCx;
            this.lastChunkZ = playerCz;
            this.scanner.resetScanCounter();
        }

        // --- Metrics ---
        this.metrics.updateRollingRates();

        // --- Backpressure: halt when column processing queue is mostly full ---
        int columnQueueSize = LSSClientNetworking.getQueuedColumnCount();
        int haltThreshold = ClientColumnProcessor.MAX_QUEUED_COLUMNS * BACKPRESSURE_NUMERATOR / BACKPRESSURE_DENOMINATOR;
        if (columnQueueSize >= haltThreshold) return;

        // --- Cache gate: don't scan until timestamp cache has loaded ---
        // Poll inline rather than relying on thenAcceptAsync callback scheduling,
        // which can be delayed on starved render threads (e.g., CI with llvmpipe).
        if (this.pendingCacheLoad != null) {
            if (!this.pendingCacheLoad.isDone()) return;
            try {
                var loaded = this.pendingCacheLoad.getNow(null);
                if (loaded != null && this.lastDimension != null) {
                    this.columns.loadFrom(loaded);
                }
            } catch (Exception ignored) {}
            this.pendingCacheLoad = null;
        }

        // --- Periodic scan (every 20 ticks): discover positions needing requests ---
        int viewDistance = mc.options.getEffectiveRenderDistance();
        int scanned = this.scanner.maybeScan(playerCx, playerCz, viewDistance,
                columnQueueSize, haltThreshold,
                () -> countMissingVanillaChunks(level, playerCx, playerCz, viewDistance),
                this.columns, this.tracker::isInFlight, this.queue);
        if (scanned >= 0) {
            if (scanned > 0) {
                updateSendPerTick(scanned);
            }
            // Timeout sweep: evict stale requests on the scan cadence (even if scan skipped)
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
        this.maxSendPerTick = Math.min(LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                Math.max(MIN_SEND_PER_TICK, (lastScanQueued + LSSConstants.TICKS_PER_SECOND - 1) / LSSConstants.TICKS_PER_SECOND));
    }

    // --- Queue drain ---

    private int drainQueue(int maxToSend) {
        long now = System.nanoTime();
        boolean generationEnabled = this.sessionConfig.generationEnabled();
        int maxGenConcurrency = this.sessionConfig.generationConcurrencyLimitPerPlayer();
        int maxSyncConcurrency = this.sessionConfig.syncOnLoadConcurrencyLimitPerPlayer();
        int count = 0;

        while (count < maxToSend && this.queue.hasNext()) {
            long pos = this.queue.peekPosition();
            long ts = this.queue.peekTimestamp();
            if (this.tracker.isInFlight(pos)) { this.queue.skip(); continue; }
            if (this.columns.classify(pos, generationEnabled) == ColumnStateMap.SATISFIED) { this.queue.skip(); continue; }

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

    // --- Request sending and callbacks ---

    private void sendRequests(long[] positionBuffer, long[] timestampBuffer, int count) {
        try {
            ClientPlayNetworking.send(new BatchChunkRequestC2SPayload(positionBuffer, timestampBuffer, count));
        } catch (Exception e) {
            LSSLogger.error("Failed to send batch chunk request", e);
            for (int i = 0; i < count; i++) {
                this.tracker.removeByPosition(positionBuffer[i]);
            }
        }
        this.metrics.recordSendCycle(count);
    }

    public void onColumnReceived(long packed, long columnTimestamp, ResourceKey<Level> dimension) {
        // A column from another dimension is discarded by the dispatch drain
        // (ClientColumnProcessor filters on level.dimension()), so stamping it here would
        // mark the position SATISFIED in the current dimension's map with no data delivered.
        if (this.lastDimension != null && !this.lastDimension.equals(dimension)) return;
        // Apply even if the position is no longer tracked (e.g. it timed out client-side):
        // the data is authoritative for the position, and stamping it prevents the
        // timeout → silent-duplicate → second-timeout stall.
        this.tracker.removeByPosition(packed);
        this.columns.onReceived(packed, columnTimestamp);
        this.metrics.recordColumnReceived();
    }

    public void onDirtyColumns(long[] dirtyPositions) {
        boolean added = false;
        for (long packed : dirtyPositions) {
            added |= this.columns.markDirtyIfKnown(packed);
        }
        if (added) {
            this.scanner.resetScanCounter();
        }
    }

    public void onColumnNotGenerated(long packed) {
        this.tracker.removeByPosition(packed);
        this.columns.onNotGenerated(packed);
        this.metrics.recordNotGenerated();
    }

    public void onColumnUpToDate(long packed) {
        this.tracker.removeByPosition(packed);
        this.columns.onUpToDate(packed);
        this.metrics.recordUpToDate();
    }

    public void onRateLimited(long packed) {
        this.tracker.removeByPosition(packed);
        this.columns.markRetry(packed);
        this.metrics.recordRateLimited();
        this.scanner.noteRateLimited();
    }

    private void onDimensionChange(ResourceKey<Level> newDimension) {
        saveCache();
        resetRequestState();
        this.queue.clear();
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
        resetRequestState();
        this.queue.clear();
        this.scanner.reset();
    }

    // --- Public getters ---

    public int getReceivedColumnCount() { return this.columns.receivedCount(); }
    public int getEmptyColumnCount() { return this.columns.emptyCount(); }
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
