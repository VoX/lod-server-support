package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.PriorityQueue;

/**
 * Per-player state tracking for the Paper plugin. Adapted from Fabric's PlayerRequestState
 * with QueuedPayload holding encoded byte[] + channel name instead of CustomPacketPayload.
 */
public class PaperPlayerRequestState {
    private ServerPlayer player;
    private volatile boolean hasHandshake = false;
    private ResourceKey<Level> lastDimension;

    private final LinkedHashMap<Integer, RequestBatch> pendingBatches = new LinkedHashMap<>();
    private final PriorityQueue<QueuedPayload> sendQueue = new PriorityQueue<>();
    private final LongOpenHashSet pendingDiskReads = new LongOpenHashSet();
    private final LongOpenHashSet diskReadDone = new LongOpenHashSet();
    private final LongOpenHashSet pendingGeneration = new LongOpenHashSet();
    private final IntOpenHashSet cancelledBatchIds = new IntOpenHashSet();

    private long availableTokens = 0;
    private long lastRefillNanos = 0;
    private int sectionsSentThisTick = 0;

    private long lastTruncationWarnNanos = 0;

    private long totalSectionsSent = 0;
    private long totalBytesSent = 0;
    private long totalRequestsReceived = 0;
    private long totalRequestsRejected = 0;

    public static class RequestBatch {
        public final int batchId;
        public final long[] positions;
        public final long[] timestamps;
        public int nextPositionIndex;
        public boolean cancelled;

        public RequestBatch(int batchId, long[] positions, long[] timestamps) {
            this.batchId = batchId;
            this.positions = positions;
            this.timestamps = timestamps;
        }

        public boolean isFullyProcessed() {
            return this.nextPositionIndex >= this.positions.length;
        }
    }

    public static class QueuedPayload implements Comparable<QueuedPayload> {
        public final byte[] data;
        public final String channel;
        public final int batchId;
        public final int estimatedBytes;
        public final long submissionOrder;

        public QueuedPayload(byte[] data, String channel, int batchId, int estimatedBytes, long submissionOrder) {
            this.data = data;
            this.channel = channel;
            this.batchId = batchId;
            this.estimatedBytes = estimatedBytes;
            this.submissionOrder = submissionOrder;
        }

        @Override
        public int compareTo(QueuedPayload other) {
            return Long.compare(this.submissionOrder, other.submissionOrder);
        }
    }

    public PaperPlayerRequestState(ServerPlayer player) {
        this.player = player;
        this.lastDimension = player.level().dimension();
    }

    public void markHandshakeComplete() {
        this.hasHandshake = true;
    }

    public boolean hasCompletedHandshake() {
        return this.hasHandshake;
    }

    public void addBatch(int batchId, long[] rawPositions, long[] rawTimestamps,
                          int lodDistance, int playerCx, int playerCz, int maxRequestsPerBatch) {
        this.totalRequestsReceived++;

        long[] raw = rawPositions;
        long[] rawTs = rawTimestamps;
        if (raw.length > maxRequestsPerBatch) {
            long[] truncated = new long[maxRequestsPerBatch];
            long[] truncatedTs = new long[maxRequestsPerBatch];
            System.arraycopy(raw, 0, truncated, 0, maxRequestsPerBatch);
            System.arraycopy(rawTs, 0, truncatedTs, 0, maxRequestsPerBatch);
            this.totalRequestsRejected += raw.length - maxRequestsPerBatch;
            long now = System.nanoTime();
            if (now - this.lastTruncationWarnNanos > 1_000_000_000L) {
                this.lastTruncationWarnNanos = now;
                LSSLogger.warn("Truncated batch from " + raw.length + " to " + maxRequestsPerBatch
                        + " positions from " + this.player.getName().getString());
            }
            raw = truncated;
            rawTs = truncatedTs;
        }
        int writeIdx = 0;
        for (int i = 0; i < raw.length; i++) {
            int cx = PositionUtil.unpackX(raw[i]);
            int cz = PositionUtil.unpackZ(raw[i]);
            if (Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)) <= lodDistance) {
                raw[writeIdx] = raw[i];
                rawTs[writeIdx] = rawTs[i];
                writeIdx++;
            }
        }

        if (writeIdx == 0) {
            this.totalRequestsRejected += raw.length;
            return;
        }

        int rejected = raw.length - writeIdx;
        if (rejected > 0) {
            this.totalRequestsRejected += rejected;
            long now = System.nanoTime();
            if (now - this.lastTruncationWarnNanos > 1_000_000_000L) {
                this.lastTruncationWarnNanos = now;
                LSSLogger.warn("Rejected " + rejected + " out-of-range positions from " + this.player.getName().getString());
            }
        }

        long[] validated = writeIdx == raw.length ? raw : Arrays.copyOf(raw, writeIdx);
        long[] validatedTs = writeIdx == rawTs.length ? rawTs : Arrays.copyOf(rawTs, writeIdx);

        this.pendingBatches.put(batchId, new RequestBatch(batchId, validated, validatedTs));
    }

    public void cancelBatches(int[] batchIds) {
        for (int id : batchIds) {
            var batch = this.pendingBatches.get(id);
            if (batch != null) {
                batch.cancelled = true;
                this.cancelledBatchIds.add(id);
            }
        }
    }

    public boolean isBatchCancelled(int batchId) {
        return this.cancelledBatchIds.contains(batchId);
    }

    public RequestBatch nextBatchToProcess() {
        for (var batch : this.pendingBatches.values()) {
            if (!batch.cancelled && !batch.isFullyProcessed()) {
                return batch;
            }
        }
        return null;
    }

    public Iterator<RequestBatch> drainCompletedBatches() {
        var iter = this.pendingBatches.values().iterator();
        return new Iterator<>() {
            private RequestBatch next = advance();

            private RequestBatch advance() {
                while (iter.hasNext()) {
                    var batch = iter.next();
                    if (batch.cancelled || batch.isFullyProcessed()) {
                        return batch;
                    }
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public RequestBatch next() {
                var result = next;
                iter.remove();
                cancelledBatchIds.remove(result.batchId);
                next = advance();
                return result;
            }
        };
    }

    public boolean canSend(long allocationBytes) {
        if (allocationBytes <= 0) return false;
        long now = System.nanoTime();
        long elapsedNanos = now - this.lastRefillNanos;
        if (elapsedNanos >= 1_000_000L) { // skip sub-millisecond refills
            this.lastRefillNanos = now;
            // Cap to 1 second to prevent overflow: nanos * bytesPerSec can exceed Long.MAX_VALUE
            // when lastRefillNanos was 0 (first call) and System.nanoTime() is large (>73 min uptime)
            elapsedNanos = Math.min(elapsedNanos, 1_000_000_000L);
            long refill = elapsedNanos * allocationBytes / 1_000_000_000L;
            long burstCap = allocationBytes / 10; // 100ms burst (~2 ticks)
            this.availableTokens = Math.min(this.availableTokens + refill, burstCap);
        }
        return this.availableTokens > 0;
    }

    public void recordSend(int bytes) {
        this.sectionsSentThisTick++;
        this.availableTokens -= bytes;
        this.totalSectionsSent++;
        this.totalBytesSent += bytes;
    }

    public void resetTickCounter() {
        this.sectionsSentThisTick = 0;
    }

    public void onDimensionChange() {
        this.pendingBatches.clear();
        this.sendQueue.clear();
        this.pendingDiskReads.clear();
        this.diskReadDone.clear();
        this.pendingGeneration.clear();
        this.cancelledBatchIds.clear();
    }

    public void updatePlayer(ServerPlayer newPlayer) {
        this.player = newPlayer;
    }

    public boolean isPendingDiskRead(int cx, int cz) {
        return this.pendingDiskReads.contains(PositionUtil.packPosition(cx, cz));
    }

    public void markPendingDiskRead(int cx, int cz) {
        this.pendingDiskReads.add(PositionUtil.packPosition(cx, cz));
    }

    public void clearPendingDiskRead(int cx, int cz) {
        this.pendingDiskReads.remove(PositionUtil.packPosition(cx, cz));
    }

    public boolean isPendingGeneration(int cx, int cz) {
        return this.pendingGeneration.contains(PositionUtil.packPosition(cx, cz));
    }

    public void markPendingGeneration(int cx, int cz) {
        this.pendingGeneration.add(PositionUtil.packPosition(cx, cz));
    }

    public void clearPendingGeneration(int cx, int cz) {
        this.pendingGeneration.remove(PositionUtil.packPosition(cx, cz));
    }

    public int getPendingGenerationCount() {
        return this.pendingGeneration.size();
    }

    public boolean hasDiskReadDone(int cx, int cz) {
        return this.diskReadDone.contains(PositionUtil.packPosition(cx, cz));
    }

    public void markDiskReadDone(int cx, int cz) {
        this.diskReadDone.add(PositionUtil.packPosition(cx, cz));
    }

    public void clearDiskReadDoneForBatch(long[] positions) {
        for (long pos : positions) {
            this.diskReadDone.remove(pos);
        }
    }

    public ServerPlayer getPlayer() { return this.player; }
    public PriorityQueue<QueuedPayload> getSendQueue() { return this.sendQueue; }
    public int getSendQueueSize() { return this.sendQueue.size(); }
    public int getPendingBatchCount() { return this.pendingBatches.size(); }
    public int getPendingDiskReadCount() { return this.pendingDiskReads.size(); }
    public long getTotalSectionsSent() { return this.totalSectionsSent; }
    public long getTotalBytesSent() { return this.totalBytesSent; }
    public long getTotalRequestsReceived() { return this.totalRequestsReceived; }
    public long getTotalRequestsRejected() { return this.totalRequestsRejected; }
    public int getSectionsSentThisTick() { return this.sectionsSentThisTick; }
    public ResourceKey<Level> getLastDimension() { return this.lastDimension; }

    public boolean checkDimensionChange() {
        var currentDim = this.player.level().dimension();
        if (!currentDim.equals(this.lastDimension)) {
            this.lastDimension = currentDim;
            return true;
        }
        return false;
    }
}
