package dev.vox.lss.common.processing;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;

import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for per-player request state, generic on the queued payload type.
 * Contains all shared fields and logic; platform subclasses provide the
 * {@code QueuedPayload} type and any MC-dependent behavior.
 *
 * @param <Q> the queued payload type (must be {@link Comparable} for priority queue ordering)
 */
public abstract class AbstractPlayerRequestState<Q extends Comparable<Q>> implements PlayerStateAccess {

    /** A request waiting in the per-player queue for a concurrency slot to open. */
    public record QueuedRequest(IncomingRequest request, RequestType type, String dimension) {}

    private final UUID playerUuid;
    private volatile boolean hasHandshake = false;
    private volatile int capabilities = 0;

    // Bound on the per-player incoming queues: a flooding client must not grow them without
    // limit (heap OOM / main-thread DoS). Dropped entries are harmless — the client re-requests
    // un-acked positions on its next scan. Counts are approximate (best-effort under races).
    private static final int MAX_INCOMING_QUEUE = 16384;

    // Network handler → processing thread (thread-safe intermediaries)
    private final ConcurrentLinkedQueue<IncomingRequest> incomingRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger incomingRequestCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<Integer> incomingCancels = new ConcurrentLinkedQueue<>();
    private final AtomicInteger incomingCancelCount = new AtomicInteger();
    // Main thread → processing thread (dirty column clear requests)
    private final ConcurrentLinkedQueue<long[]> pendingDirtyClear = new ConcurrentLinkedQueue<>();
    // Processing thread → main thread (thread-safe output)
    private final ConcurrentLinkedQueue<Q> readyPayloads = new ConcurrentLinkedQueue<>();

    // Owned by processing thread (single-threaded access)
    private final Long2ObjectOpenHashMap<PendingRequest> pendingByPosition = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<PendingRequest> pendingByRequestId = new Int2ObjectOpenHashMap<>();
    private final PriorityQueue<Q> sendQueue = new PriorityQueue<>();
    private final LongOpenHashSet diskReadDone = new LongOpenHashSet();
    private final PlayerBandwidthTracker bandwidth = new PlayerBandwidthTracker();
    private final RateLimiterSet rateLimiters;
    private final ArrayDeque<QueuedRequest> waitingQueue = new ArrayDeque<>();
    private final AtomicLong totalRequestsReceived = new AtomicLong();
    private volatile long desiredBandwidth = Long.MAX_VALUE;
    // Single-writer (main thread) — volatile for cross-thread visibility to processing thread
    private volatile int sendQueueSizeSnapshot = 0;
    // Single-writer (processing thread only) — volatile sufficient for cross-thread visibility
    private volatile int pendingSyncCount = 0;
    private volatile int pendingGenerationCount = 0;

    protected AbstractPlayerRequestState(UUID playerUuid, int syncRate, int syncConcurrency,
                                          int genRate, int genConcurrency) {
        this.playerUuid = playerUuid;
        this.rateLimiters = new RateLimiterSet(syncRate, syncConcurrency, genRate, genConcurrency);
    }

    // ---- Handshake / Capability ----

    public void setCapabilities(int capabilities) {
        this.capabilities = capabilities;
    }

    public int getCapabilities() {
        return this.capabilities;
    }

    public boolean supportsVoxelColumns() {
        return (this.capabilities & LSSConstants.CAPABILITY_VOXEL_COLUMNS) != 0;
    }

    public void markHandshakeComplete() {
        this.hasHandshake = true;
    }

    public boolean hasCompletedHandshake() {
        return this.hasHandshake;
    }

    // ---- Incoming request helpers (subclasses call these from addRequest) ----

    protected void enqueueIncomingRequest(IncomingRequest request) {
        if (this.incomingRequestCount.get() >= MAX_INCOMING_QUEUE) {
            return;
        }
        this.incomingRequests.add(request);
        this.incomingRequestCount.incrementAndGet();
        this.totalRequestsReceived.incrementAndGet();
    }

    public void addCancel(int requestId) {
        if (this.incomingCancelCount.get() >= MAX_INCOMING_QUEUE) {
            return;
        }
        this.incomingCancels.add(requestId);
        this.incomingCancelCount.incrementAndGet();
    }

    // ---- Queue management ----

    /**
     * Drain ready payloads from the processing thread into the send queue.
     * Called by the main thread before flushing.
     */
    public void drainReadyPayloads() {
        Q qp;
        while ((qp = this.readyPayloads.poll()) != null) {
            this.sendQueue.add(qp);
        }
        this.sendQueueSizeSnapshot = this.sendQueue.size();
    }

    public boolean canSend(long allocationBytes) {
        return this.bandwidth.canSend(allocationBytes);
    }

    public void recordSend(int bytes) {
        this.bandwidth.recordSend(bytes);
    }

    /**
     * Drain dirty column clear requests from main thread.
     * Called by the processing thread at the start of each cycle.
     */
    @Override
    public void drainDirtyClearRequests() {
        long[] dirtyPositions;
        while ((dirtyPositions = this.pendingDirtyClear.poll()) != null) {
            for (long pos : dirtyPositions) {
                getDiskReadDonePositions().remove(pos);
            }
        }
    }

    /** Queue positions for clearing from diskReadDone (called from main thread). */
    public void clearDiskReadDoneForPositions(long[] positions) {
        this.pendingDirtyClear.add(positions);
    }

    /**
     * Clear shared concurrent queues on dimension change.
     * Subclasses should call this from their own onDimensionChange() and may add
     * additional platform-specific clearing.
     */
    protected void onDimensionChangeBase() {
        this.incomingRequests.clear();
        this.incomingCancels.clear();
        this.incomingRequestCount.set(0);
        this.incomingCancelCount.set(0);
        this.readyPayloads.clear();
        this.sendQueue.clear();
        this.pendingDirtyClear.clear();
    }

    /**
     * Clear processing-thread-owned state on dimension change.
     */
    public void clearProcessingState() {
        this.pendingByPosition.clear();
        this.pendingByRequestId.clear();
        this.diskReadDone.clear();
        this.waitingQueue.clear();
        this.pendingSyncCount = 0;
        this.pendingGenerationCount = 0;
        // A dimension change abandons all in-flight requests (pending cleared above, and the disk/
        // generation result queues are discarded by cleanupPlayerServices), so reset the
        // concurrency limiters. Otherwise the permits held by those discarded requests leak and
        // the player's effective concurrency erodes across repeated dimension changes.
        this.rateLimiters.syncOnLoad().reset();
        this.rateLimiters.generation().reset();
    }

    // ---- PlayerStateAccess per-request methods ----

    @Override
    public IncomingRequest pollIncomingRequest() {
        var r = this.incomingRequests.poll();
        if (r != null) this.incomingRequestCount.decrementAndGet();
        return r;
    }

    @Override
    public void addPendingRequest(PendingRequest pending) {
        long packed = PositionUtil.packPosition(pending.cx(), pending.cz());
        var replaced = this.pendingByPosition.put(packed, pending);
        if (replaced != null) {
            this.pendingByRequestId.remove(replaced.requestId());
            decrementPendingCounter(replaced.type());
        }
        this.pendingByRequestId.put(pending.requestId(), pending);
        incrementPendingCounter(pending.type());
    }

    @Override
    public PendingRequest removePendingByPosition(int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        var pending = this.pendingByPosition.remove(packed);
        if (pending != null) {
            this.pendingByRequestId.remove(pending.requestId());
            decrementPendingCounter(pending.type());
        }
        return pending;
    }

    @Override
    public PendingRequest removePendingByRequestId(int requestId) {
        var pending = this.pendingByRequestId.remove(requestId);
        if (pending != null) {
            long packed = PositionUtil.packPosition(pending.cx(), pending.cz());
            this.pendingByPosition.remove(packed);
            decrementPendingCounter(pending.type());
        }
        return pending;
    }

    @Override
    public boolean hasPendingRequest(int cx, int cz) {
        return this.pendingByPosition.containsKey(PositionUtil.packPosition(cx, cz));
    }

    private void incrementPendingCounter(RequestType type) {
        if (type == RequestType.SYNC) this.pendingSyncCount++;
        else this.pendingGenerationCount++;
    }

    private void decrementPendingCounter(RequestType type) {
        if (type == RequestType.SYNC) this.pendingSyncCount--;
        else this.pendingGenerationCount--;
    }

    @Override
    public Integer pollCancel() {
        var c = this.incomingCancels.poll();
        if (c != null) this.incomingCancelCount.decrementAndGet();
        return c;
    }

    public boolean hasDiskReadDone(int cx, int cz) {
        return this.diskReadDone.contains(PositionUtil.packPosition(cx, cz));
    }

    public void markDiskReadDone(int cx, int cz) {
        this.diskReadDone.add(PositionUtil.packPosition(cx, cz));
    }

    // ---- Accessors for concurrent queues (used by sibling classes) ----

    public Iterable<IncomingRequest> getIncomingRequests() {
        return this.incomingRequests;
    }

    public void addReadyPayload(Q payload) {
        this.readyPayloads.add(payload);
    }

    protected LongOpenHashSet getDiskReadDonePositions() {
        return this.diskReadDone;
    }

    // ---- Getters ----

    @Override
    public RateLimiterSet getRateLimiters() { return this.rateLimiters; }
    @Override
    public ArrayDeque<QueuedRequest> getWaitingQueue() { return this.waitingQueue; }
    @Override
    public int getWaitingQueueSize() { return this.waitingQueue.size(); }
    @Override
    public UUID getPlayerUUID() { return this.playerUuid; }
    public PriorityQueue<Q> getSendQueue() { return this.sendQueue; }
    /** Returns a volatile snapshot of the send queue size, safe for cross-thread reads. */
    public int getSendQueueSize() { return this.sendQueueSizeSnapshot; }
    public int getPendingSyncCount() { return this.pendingSyncCount; }
    public int getPendingGenerationCount() { return this.pendingGenerationCount; }
    public long getTotalSectionsSent() { return this.bandwidth.getTotalSectionsSent(); }
    public long getTotalBytesSent() { return this.bandwidth.getTotalBytesSent(); }
    public long getTotalRequestsReceived() { return this.totalRequestsReceived.get(); }
    public void setDesiredBandwidth(long desiredRate) { this.desiredBandwidth = desiredRate; }
    public long getDesiredBandwidth() { return this.desiredBandwidth; }
}
