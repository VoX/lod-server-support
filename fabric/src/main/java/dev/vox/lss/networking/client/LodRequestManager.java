package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.RequestCompleteS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class LodRequestManager {
    private SessionConfigS2CPayload sessionConfig;
    private String serverAddress;

    private int lastChunkX;
    private int lastChunkZ;
    private ResourceKey<Level> lastDimension;

    // Key: packed position, Value: server timestamp (>0 = received with data, 0 = empty/no data)
    // defaultReturnValue(-1L) → -1 means "not in map / never requested"
    private final Long2LongOpenHashMap columnTimestamps = new Long2LongOpenHashMap();
    {
        columnTimestamps.defaultReturnValue(-1L);
    }

    private final LongOpenHashSet pendingColumns = new LongOpenHashSet();
    private final Int2ObjectOpenHashMap<TrackedBatch> activeBatches = new Int2ObjectOpenHashMap<>();
    private int nextBatchId = 0;

    private static final long BATCH_TIMEOUT_MS = 60_000;

    private record TrackedBatch(long[] positions, long createdAt) {}
    private boolean cacheLoaded = false;

    // Positions pushed by the server's dirty column broadcast that need re-requesting.
    // Tracked separately so they're prioritized like fresh requests in the scan.
    private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();

    // Single scan state — walks the spiral once, classifying each position into
    // fresh (never requested), dirty (server-pushed change), generation (empty, retryable),
    // or resync (has cached data from previous session).
    private int scanRadius = 0;
    private int generationBudget = 0;
    private int generationDistance = 0;
    private boolean needsResync = false;

    private int receivedCount = 0;
    private int emptyCount = 0;
    private long totalBatchesSent = 0;
    private long totalPositionsRequested = 0;

    private void putTimestamp(long packed, long timestamp) {
        long old = this.columnTimestamps.put(packed, timestamp);
        // Update counters: remove old bucket, add new bucket
        if (old > 0) this.receivedCount--;
        else if (old == 0) this.emptyCount--;
        if (timestamp > 0) this.receivedCount++;
        else if (timestamp == 0) this.emptyCount++;
    }

    private void clearTimestamps() {
        this.columnTimestamps.clear();
        this.receivedCount = 0;
        this.emptyCount = 0;
    }

    private void loadTimestamps(Long2LongOpenHashMap loaded) {
        this.columnTimestamps.putAll(loaded);
        // Recount after bulk load
        this.receivedCount = 0;
        this.emptyCount = 0;
        for (long ts : this.columnTimestamps.values()) {
            if (ts > 0) this.receivedCount++;
            else if (ts == 0) this.emptyCount++;
        }
    }

    public void onSessionConfig(SessionConfigS2CPayload config, String serverAddress) {
        this.sessionConfig = config;
        this.serverAddress = serverAddress;
        this.generationBudget = config.maxGenerationRequestsPerBatch();
        this.generationDistance = config.generationDistanceChunks();
        this.pendingColumns.clear();
        this.activeBatches.clear();
        this.clearTimestamps();
        this.dirtyColumns.clear();
        this.scanRadius = 0;
        this.lastDimension = null;
        this.cacheLoaded = false;
        this.needsResync = true;
    }

    public void tick() {
        if (this.sessionConfig == null || !this.sessionConfig.enabled()) return;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;

        var level = mc.level;
        if (level == null) return;

        int playerCx = player.blockPosition().getX() >> 4;
        int playerCz = player.blockPosition().getZ() >> 4;
        var currentDim = level.dimension();

        if (this.lastDimension != null && !currentDim.equals(this.lastDimension)) {
            this.onDimensionChange(currentDim);
        } else if (!this.cacheLoaded) {
            this.loadTimestamps(ColumnCacheStore.load(this.serverAddress, currentDim));
            this.cacheLoaded = true;
        }
        this.lastDimension = currentDim;

        if (playerCx != this.lastChunkX || playerCz != this.lastChunkZ) {
            this.pruneOutOfRangePositions(playerCx, playerCz);
            this.pruneOutOfRangeTimestamps(playerCx, playerCz);
            this.lastChunkX = playerCx;
            this.lastChunkZ = playerCz;
            this.scanRadius = 0;
        }

        // Purge stale batches that the server never completed
        long now = System.currentTimeMillis();
        var staleIter = this.activeBatches.int2ObjectEntrySet().iterator();
        while (staleIter.hasNext()) {
            var entry = staleIter.next();
            if (now - entry.getValue().createdAt > BATCH_TIMEOUT_MS) {
                for (long pos : entry.getValue().positions) {
                    this.pendingColumns.remove(pos);
                }
                staleIter.remove();
            }
        }

        if (this.pendingColumns.size() >= this.sessionConfig.maxPendingRequests()) return;

        this.tickScan(playerCx, playerCz, mc);
    }

    /**
     * Single spiral scan that classifies each position and builds one unified batch.
     * <ul>
     *   <li><b>Fresh</b> (ts == -1) — never requested. Always collected with timestamp 0.</li>
     *   <li><b>Dirty</b> (in dirtyColumns set) — server pushed a change notification.
     *       Collected with stored timestamp, counted against main budget.</li>
     *   <li><b>Generation</b> (ts == 0) — empty, within generation distance.
     *       Collected with timestamp 0, subject to generation sub-budget.
     *       If generation is disabled (budget == 0), treated as settled.</li>
     *   <li><b>Resync</b> (ts &gt; 0, needsResync) — has cached data from a previous session.
     *       Collected with stored timestamp during the initial join sweep only.</li>
     * </ul>
     * Pending positions naturally pace generation retries — a position stays pending
     * until its batch completes, preventing redundant re-requests without a timer.
     */
    private void tickScan(int playerCx, int playerCz, Minecraft mc) {
        var clientConfig = LSSClientConfig.CONFIG;

        int exclusionRadius = mc.options.getEffectiveRenderDistance();
        int exclusionDistSq = exclusionRadius * exclusionRadius;
        int lodDistance = getEffectiveLodDistance();
        int maxPending = this.sessionConfig.maxPendingRequests();

        int[] budgets = computeBudgets(this.sessionConfig.maxRequestsPerBatch(),
                clientConfig.resyncBatchSize, this.needsResync);
        int budgetRemaining = budgets[0];
        int resyncRemaining = budgets[1];
        int genRemaining = this.generationBudget; // 0 if generation disabled

        // Single unified buffer — capped to the server's per-batch limit
        int maxBatchSize = Math.min(budgetRemaining, this.sessionConfig.maxRequestsPerBatch());
        long[] posBuf = new long[maxBatchSize];
        long[] tsBuf = new long[maxBatchSize];
        int count = 0;

        for (int radius = this.scanRadius; radius <= lodDistance; radius++) {
            if (this.pendingColumns.size() + count >= maxPending) break;
            if (budgetRemaining <= 0) break;

            boolean canAdvance = true;

            for (int side = 0; side < 4; side++) {
                for (int i = -radius; i <= radius; i++) {
                    int dx, dz;
                    switch (side) {
                        case 0 -> { dx = i; dz = -radius; }
                        case 1 -> { dx = i; dz = radius; }
                        case 2 -> { dx = -radius; dz = i; }
                        default -> { dx = radius; dz = i; }
                    }
                    if (side >= 2 && (dz == -radius || dz == radius)) continue;
                    if (dx * dx + dz * dz <= exclusionDistSq) continue;

                    int cx = playerCx + dx;
                    int cz = playerCz + dz;
                    long packed = PositionUtil.packPosition(cx, cz);

                    if (this.pendingColumns.contains(packed)) continue;

                    long storedTs = this.columnTimestamps.get(packed);

                    if (storedTs == -1L) {
                        // Fresh — never requested
                        if (budgetRemaining > 0
                                && this.pendingColumns.size() + count < maxPending) {
                            posBuf[count] = packed;
                            tsBuf[count] = 0L;
                            count++;
                            budgetRemaining--;
                        }
                        canAdvance = false;
                    } else if (this.dirtyColumns.contains(packed)) {
                        // Dirty — server notified us this column changed
                        if (budgetRemaining > 0
                                && this.pendingColumns.size() + count < maxPending) {
                            posBuf[count] = packed;
                            tsBuf[count] = storedTs;
                            count++;
                            budgetRemaining--;
                            this.dirtyColumns.remove(packed);
                        }
                        canAdvance = false;
                    } else if (storedTs == 0L) {
                        // Empty — generation retry
                        if (genRemaining > 0 && budgetRemaining > 0
                                && radius <= this.generationDistance
                                && this.pendingColumns.size() + count < maxPending) {
                            posBuf[count] = packed;
                            tsBuf[count] = 0L;
                            count++;
                            budgetRemaining--;
                            genRemaining--;
                            canAdvance = false;
                        } else if (this.generationBudget > 0 && radius <= this.generationDistance) {
                            // Generation available but budget exhausted — block advancement
                            canAdvance = false;
                        }
                        // else: generation disabled (budget == 0) — treat as settled
                    } else {
                        // Has data — resync only on initial join sweep
                        if (resyncRemaining > 0 && budgetRemaining > 0
                                && this.pendingColumns.size() + count < maxPending) {
                            posBuf[count] = packed;
                            tsBuf[count] = storedTs;
                            count++;
                            resyncRemaining--;
                            budgetRemaining--;
                        }
                        // Settled — don't prevent advancement
                    }
                }
            }

            if (canAdvance) {
                this.scanRadius = radius + 1;
            }
        }

        // Sweep completed — reset scan and mark initial resync done
        if (this.scanRadius > lodDistance) {
            this.scanRadius = 0;
            if (this.needsResync) this.needsResync = false;
        }

        // Send unified batch
        if (count > 0) {
            long[] positions = new long[count];
            long[] timestamps = new long[count];
            System.arraycopy(posBuf, 0, positions, 0, count);
            System.arraycopy(tsBuf, 0, timestamps, 0, count);

            int batchId = this.nextBatchId++;
            try {
                ClientPlayNetworking.send(new ChunkRequestC2SPayload(batchId, positions, timestamps));
                this.activeBatches.put(batchId, new TrackedBatch(positions, System.currentTimeMillis()));
                for (long pos : positions) {
                    this.pendingColumns.add(pos);
                }
                this.totalBatchesSent++;
                this.totalPositionsRequested += count;
            } catch (Exception e) {
                LSSLogger.error("Failed to send chunk request batch", e);
            }
        }
    }

    public void onSectionReceived(int cx, int cz, long columnTimestamp) {
        long packed = PositionUtil.packPosition(cx, cz);
        this.pendingColumns.remove(packed);
        this.dirtyColumns.remove(packed);
        this.putTimestamp(packed, columnTimestamp);
    }

    public void onDirtyColumns(long[] dirtyPositions) {
        for (long packed : dirtyPositions) {
            long ts = this.columnTimestamps.get(packed);
            if (ts > 0) {
                this.dirtyColumns.add(packed);
            }
        }
    }

    public void onColumnUpToDate(int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        this.pendingColumns.remove(packed);
        // Keep existing timestamp — data is current
    }

    public void onBatchComplete(int batchId, int status) {
        var batch = this.activeBatches.remove(batchId);
        if (batch != null) {
            long[] positions = batch.positions;
            for (long pos : positions) {
                this.pendingColumns.remove(pos);
                if (status != RequestCompleteS2CPayload.STATUS_REJECTED
                        && !this.columnTimestamps.containsKey(pos)) {
                    // Mark as empty (timestamp 0). When the server has a generation budget,
                    // the scan will re-request these positions on subsequent sweeps.
                    this.putTimestamp(pos, 0L);
                }
            }
        }
    }

    private void onDimensionChange(ResourceKey<Level> newDimension) {
        saveCache();
        this.pendingColumns.clear();
        this.activeBatches.clear();
        this.clearTimestamps();
        this.dirtyColumns.clear();
        this.scanRadius = 0;
        this.needsResync = true;
        this.loadTimestamps(ColumnCacheStore.load(this.serverAddress, newDimension));
        this.cacheLoaded = true;
    }

    public void saveCache() {
        if (this.serverAddress != null && this.lastDimension != null && !this.columnTimestamps.isEmpty()) {
            ColumnCacheStore.save(this.serverAddress, this.lastDimension, this.columnTimestamps);
        }
    }

    public void flushCache() {
        if (this.serverAddress != null) {
            ColumnCacheStore.clearForServer(this.serverAddress);
        }
        this.clearTimestamps();
        this.pendingColumns.clear();
        this.activeBatches.clear();
        this.dirtyColumns.clear();
        this.scanRadius = 0;
        this.needsResync = false;
    }

    private void pruneOutOfRangePositions(int playerCx, int playerCz) {
        if (this.sessionConfig == null) return;
        int lodDistance = getEffectiveLodDistance();

        var toCancel = new IntArrayList();

        for (var entry : this.activeBatches.int2ObjectEntrySet()) {
            long[] positions = entry.getValue().positions;
            long createdAt = entry.getValue().createdAt;
            int kept = 0;
            for (int i = 0; i < positions.length; i++) {
                int cx = PositionUtil.unpackX(positions[i]);
                int cz = PositionUtil.unpackZ(positions[i]);
                if (Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)) <= lodDistance) {
                    positions[kept++] = positions[i];
                } else {
                    this.pendingColumns.remove(positions[i]);
                }
            }

            if (kept == 0) {
                toCancel.add(entry.getIntKey());
            } else if (kept < positions.length) {
                long[] shrunk = new long[kept];
                System.arraycopy(positions, 0, shrunk, 0, kept);
                entry.setValue(new TrackedBatch(shrunk, createdAt));
            }
        }

        if (toCancel.isEmpty()) return;

        int[] cancelIds = toCancel.toIntArray();
        try {
            ClientPlayNetworking.send(new CancelRequestC2SPayload(cancelIds));
        } catch (Exception e) {
            LSSLogger.error("Failed to send cancel request", e);
        }

        for (int id : cancelIds) {
            this.activeBatches.remove(id);
        }
    }

    private void pruneOutOfRangeTimestamps(int playerCx, int playerCz) {
        int lodDistance = getEffectiveLodDistance();
        int pruneDistance = lodDistance + 32; // buffer to avoid thrashing
        var iter = this.columnTimestamps.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            int cx = PositionUtil.unpackX(entry.getLongKey());
            int cz = PositionUtil.unpackZ(entry.getLongKey());
            if (Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)) > pruneDistance) {
                long ts = entry.getLongValue();
                if (ts > 0) this.receivedCount--;
                else if (ts == 0) this.emptyCount--;
                iter.remove();
            }
        }
    }

    private int getEffectiveLodDistance() {
        int serverDistance = this.sessionConfig.lodDistanceChunks();
        int clientDistance = LSSClientConfig.CONFIG.lodDistanceChunks;
        if (clientDistance > 0) {
            return Math.min(clientDistance, serverDistance);
        }
        return serverDistance;
    }

    public int getPendingColumnCount() { return this.pendingColumns.size(); }
    public int getReceivedColumnCount() { return this.receivedCount; }
    public int getEmptyColumnCount() { return this.emptyCount; }
    public int getActiveBatchCount() { return this.activeBatches.size(); }
    public int getScanRadius() { return this.scanRadius; }
    public int getEffectiveLodDistanceChunks() { return this.sessionConfig != null ? getEffectiveLodDistance() : 0; }
    public int getGenerationBudget() { return this.generationBudget; }
    public long getTotalBatchesSent() { return this.totalBatchesSent; }
    public long getTotalPositionsRequested() { return this.totalPositionsRequested; }

    /**
     * Computes the resync and main budgets for a single tick's scan.
     * Returns [budgetRemaining, resyncRemaining].
     */
    static int[] computeBudgets(int maxRequestsPerBatch, int resyncBatchSize, boolean needsResync) {
        int budgetRemaining = maxRequestsPerBatch;
        int resyncRemaining = 0;
        if (needsResync) {
            resyncRemaining = Math.min(resyncBatchSize, budgetRemaining / 4);
        }
        return new int[]{budgetRemaining, resyncRemaining};
    }
}
