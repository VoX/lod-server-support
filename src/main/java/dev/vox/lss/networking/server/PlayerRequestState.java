package dev.vox.lss.networking.server;

import dev.vox.lss.LSSLogger;
import dev.vox.lss.networking.payloads.ChunkRequestC2SPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;

public class PlayerRequestState {
    private ServerPlayer player;
    private volatile boolean hasHandshake = false;
    private int clientProtocolVersion;
    private ResourceKey<Level> lastDimension;

    private final LinkedHashMap<Integer, RequestBatch> pendingBatches = new LinkedHashMap<>();
    private final Deque<QueuedPayload> sendQueue = new ArrayDeque<>();
    private final LongOpenHashSet pendingDiskReads = new LongOpenHashSet();
    private final LongOpenHashSet diskReadDone = new LongOpenHashSet();
    private final LongOpenHashSet pendingGeneration = new LongOpenHashSet();

    private long bytesSentThisSecond = 0;
    private long lastSecondNanos = 0;
    private int sectionsSentThisTick = 0;

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

    public static class QueuedPayload {
        public final CustomPacketPayload payload;
        public final int batchId;
        public final int estimatedBytes;

        public QueuedPayload(CustomPacketPayload payload, int batchId, int estimatedBytes) {
            this.payload = payload;
            this.batchId = batchId;
            this.estimatedBytes = estimatedBytes;
        }
    }

    public PlayerRequestState(ServerPlayer player) {
        this.player = player;
        this.lastDimension = player.level().dimension();
    }

    public void markHandshakeComplete(int clientProtocolVersion) {
        this.hasHandshake = true;
        this.clientProtocolVersion = clientProtocolVersion;
    }

    public boolean hasCompletedHandshake() {
        return this.hasHandshake;
    }

    public void addBatch(ChunkRequestC2SPayload payload, int lodDistance, int playerCx, int playerCz, int maxRequestsPerBatch) {
        this.totalRequestsReceived++;

        long[] raw = payload.positions();
        long[] rawTs = payload.timestamps();
        if (raw.length > maxRequestsPerBatch) {
            long[] truncated = new long[maxRequestsPerBatch];
            long[] truncatedTs = new long[maxRequestsPerBatch];
            System.arraycopy(raw, 0, truncated, 0, maxRequestsPerBatch);
            System.arraycopy(rawTs, 0, truncatedTs, 0, maxRequestsPerBatch);
            this.totalRequestsRejected += raw.length - maxRequestsPerBatch;
            LSSLogger.warn("Truncated batch from " + raw.length + " to " + maxRequestsPerBatch
                    + " positions from " + this.player.getName().getString());
            raw = truncated;
            rawTs = truncatedTs;
        }
        int validCount = 0;
        for (long pos : raw) {
            int cx = ChunkRequestC2SPayload.unpackX(pos);
            int cz = ChunkRequestC2SPayload.unpackZ(pos);
            if (Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)) <= lodDistance) {
                validCount++;
            }
        }

        if (validCount == 0) {
            this.totalRequestsRejected += raw.length;
            return;
        }

        long[] validated;
        long[] validatedTs;
        if (validCount == raw.length) {
            validated = raw;
            validatedTs = rawTs;
        } else {
            validated = new long[validCount];
            validatedTs = new long[validCount];
            int idx = 0;
            for (int i = 0; i < raw.length; i++) {
                int cx = ChunkRequestC2SPayload.unpackX(raw[i]);
                int cz = ChunkRequestC2SPayload.unpackZ(raw[i]);
                if (Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)) <= lodDistance) {
                    validated[idx] = raw[i];
                    validatedTs[idx] = rawTs[i];
                    idx++;
                }
            }
            int rejected = raw.length - validCount;
            this.totalRequestsRejected += rejected;
            if (rejected > 0) {
                LSSLogger.warn("Rejected " + rejected + " out-of-range positions from " + this.player.getName().getString());
            }
        }

        this.pendingBatches.put(payload.batchId(), new RequestBatch(payload.batchId(), validated, validatedTs));
    }

    public void cancelBatches(int[] batchIds) {
        for (int id : batchIds) {
            var batch = this.pendingBatches.get(id);
            if (batch != null) {
                batch.cancelled = true;
            }
        }
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
                next = advance();
                return result;
            }
        };
    }

    public boolean canSend(long allocationBytes) {
        long now = System.nanoTime();
        if (now - this.lastSecondNanos > 1_000_000_000L) {
            this.bytesSentThisSecond = 0;
            this.lastSecondNanos = now;
        }
        return this.bytesSentThisSecond < allocationBytes;
    }

    public void recordSend(int bytes) {
        this.sectionsSentThisTick++;
        this.bytesSentThisSecond += bytes;
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
    }

    public void updatePlayer(ServerPlayer newPlayer) {
        this.player = newPlayer;
    }

    private static long encodeColumnPos(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public boolean isPendingDiskRead(int cx, int cz) {
        return this.pendingDiskReads.contains(encodeColumnPos(cx, cz));
    }

    public void markPendingDiskRead(int cx, int cz) {
        this.pendingDiskReads.add(encodeColumnPos(cx, cz));
    }

    public void clearPendingDiskRead(int cx, int cz) {
        this.pendingDiskReads.remove(encodeColumnPos(cx, cz));
    }

    public boolean isPendingGeneration(int cx, int cz) {
        return this.pendingGeneration.contains(encodeColumnPos(cx, cz));
    }

    public void markPendingGeneration(int cx, int cz) {
        this.pendingGeneration.add(encodeColumnPos(cx, cz));
    }

    public void clearPendingGeneration(int cx, int cz) {
        this.pendingGeneration.remove(encodeColumnPos(cx, cz));
    }

    public int getPendingGenerationCount() {
        return this.pendingGeneration.size();
    }

    public boolean hasDiskReadDone(int cx, int cz) {
        return this.diskReadDone.contains(encodeColumnPos(cx, cz));
    }

    public void markDiskReadDone(int cx, int cz) {
        this.diskReadDone.add(encodeColumnPos(cx, cz));
    }

    public void clearDiskReadDoneForBatch(long[] positions) {
        for (long pos : positions) {
            int cx = ChunkRequestC2SPayload.unpackX(pos);
            int cz = ChunkRequestC2SPayload.unpackZ(pos);
            this.diskReadDone.remove(encodeColumnPos(cx, cz));
        }
    }

    public ServerPlayer getPlayer() { return this.player; }
    public Deque<QueuedPayload> getSendQueue() { return this.sendQueue; }
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
