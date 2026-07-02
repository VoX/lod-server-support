package dev.vox.lss.common.processing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;

import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for per-player request state, generic on the queued payload type.
 * Contains all shared fields and logic; platform subclasses provide the
 * {@code QueuedPayload} type and any MC-dependent behavior.
 *
 * <p>Admission is derived: a request occupies its {@link SlotType} slot exactly while its
 * {@link PendingRequest} entry exists in the pending map, so the slot counters cannot drift
 * from the map (the structural fix for the permit-leak bugs of earlier review rounds).
 *
 * @param <T> the platform payload type (Fabric: CustomPacketPayload, Paper: byte[])
 */
public abstract class AbstractPlayerRequestState<T> {

    private final UUID playerUuid;
    private volatile boolean hasHandshake = false;
    private volatile int capabilities = 0;
    // The dimension this state was REGISTERED under — invariant for the state's lifetime
    // (a dimension change replaces the state via removePlayer+registerPlayer). The router
    // uses it to reject a stale snapshot's dimension: a multi-tick processing cycle can
    // otherwise route the NEW session's requests under the OLD snapshot's dimension,
    // leaking pending slots against disk reads whose results are dimension-skipped.
    // Null in bare test rigs (guard is skipped) — both platform services always set it.
    private volatile String registeredDimension;

    // Bound on the per-player incoming queue: a flooding client must not grow it without
    // limit (heap OOM / main-thread DoS). Dropped entries are harmless — the client re-requests
    // un-acked positions on its next scan. Counts are approximate (best-effort under races).
    private static final int MAX_INCOMING_QUEUE = 16384;

    // Network handler → processing thread (thread-safe intermediaries)
    private final ConcurrentLinkedQueue<IncomingRequest> incomingRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger incomingRequestCount = new AtomicInteger();
    // Processing thread → main thread (thread-safe output)
    private final ConcurrentLinkedQueue<QueuedPayload<T>> readyPayloads = new ConcurrentLinkedQueue<>();

    // Owned by processing thread (single-threaded access)
    private final Long2ObjectOpenHashMap<PendingRequest> pendingByPosition = new Long2ObjectOpenHashMap<>();
    // Owned by main thread (drained from readyPayloads, flushed to the wire)
    private final PriorityQueue<QueuedPayload<T>> sendQueue = new PriorityQueue<>();
    private final LongOpenHashSet diskReadDone = new LongOpenHashSet();
    // Column positions with a payload somewhere in the send pipeline (readyPayloads or
    // sendQueue). Incremented at enqueue (processing thread), decremented on wire send or
    // send-failure drop (main thread), read by the router (processing thread) to answer
    // "is this position's data still on its way?" — counted, not a set, because a dirty
    // re-serve can put a second payload for the same position in flight.
    private final ConcurrentHashMap<Long, Integer> enqueuedColumns = new ConcurrentHashMap<>();
    private final PlayerBandwidthTracker bandwidth = new PlayerBandwidthTracker();
    private final AtomicLong totalRequestsReceived = new AtomicLong();
    private final AtomicLong totalIncomingDropped = new AtomicLong();
    // Single-writer (main thread) — volatile for cross-thread visibility to processing thread
    private volatile int sendQueueSizeSnapshot = 0;

    // Admission slots: caps are immutable; held counts are derived from the pending map
    // (single-writer: processing thread; volatile for /lsslod command reads).
    private final int syncSlotCap;
    private final int genSlotCap;
    private volatile int heldSyncSlots = 0;
    private volatile int heldGenSlots = 0;

    protected AbstractPlayerRequestState(UUID playerUuid, int syncConcurrency, int genConcurrency) {
        this.playerUuid = playerUuid;
        this.syncSlotCap = syncConcurrency;
        this.genSlotCap = genConcurrency;
    }

    // ---- Handshake / Capability ----

    public void setCapabilities(int capabilities) {
        this.capabilities = capabilities;
    }

    public int getCapabilities() {
        return this.capabilities;
    }

    public String registeredDimension() {
        return this.registeredDimension;
    }

    public void setRegisteredDimension(String dimension) {
        this.registeredDimension = dimension;
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
            this.totalIncomingDropped.incrementAndGet();
            return;
        }
        this.incomingRequests.add(request);
        this.incomingRequestCount.incrementAndGet();
        this.totalRequestsReceived.incrementAndGet();
    }

    // ---- Queue management ----

    /** Platform hook used by the flush error path and shared diagnostics. */
    public abstract String getPlayerName();

    /** Platform hook that puts one payload on the wire (may throw on a broken connection). */
    @FunctionalInterface
    public interface PayloadSender<T> {
        void send(T payload) throws Exception;
    }

    private static final long[] NO_DROPPED_POSITIONS = new long[0];

    /**
     * Drain ready payloads from the processing thread into the send queue, then flush as
     * many as the bandwidth allocation allows through the platform sender. A send failure
     * drops the remaining queue and returns the dropped packed positions — the caller MUST
     * route them to {@code OffThreadProcessor.clearDiskReadDone} so the client's
     * re-requests re-resolve instead of being answered up-to-date for data that was never
     * delivered. Called by the main thread each tick.
     *
     * @return packed positions of column payloads dropped by a send failure (empty when
     *         everything sent or remains queued)
     */
    public long[] flushSendQueue(long allocationBytes, SharedBandwidthLimiter globalLimiter,
                                  TickDiagnostics diag, PayloadSender<T> sender) {
        QueuedPayload<T> ready;
        while ((ready = this.readyPayloads.poll()) != null) {
            this.sendQueue.add(ready);
        }
        this.sendQueueSizeSnapshot = this.sendQueue.size();

        long[] dropped = NO_DROPPED_POSITIONS;
        while (!this.sendQueue.isEmpty()) {
            if (!this.bandwidth.canSend(allocationBytes)) break;

            var queued = this.sendQueue.peek();
            try {
                sender.send(queued.payload());
                this.sendQueue.poll();
                decrementEnqueued(queued.packedPos());
                this.bandwidth.recordSend(queued.estimatedBytes());
                globalLimiter.recordSend(queued.estimatedBytes());
                diag.recordSectionSent(queued.estimatedBytes());
            } catch (Exception e) {
                LSSLogger.error("Failed to send queued payload to " + getPlayerName()
                        + ", dropping remaining queue (" + this.sendQueue.size() + " entries)", e);
                var droppedList = new LongArrayList(this.sendQueue.size());
                for (var entry : this.sendQueue) {
                    droppedList.add(entry.packedPos());
                    decrementEnqueued(entry.packedPos());
                }
                this.sendQueue.clear();
                dropped = droppedList.toLongArray();
                break;
            }
        }
        this.sendQueueSizeSnapshot = this.sendQueue.size();
        return dropped;
    }

    // ---- Processing-thread-facing per-request API ----

    public IncomingRequest pollIncomingRequest() {
        var r = this.incomingRequests.poll();
        if (r != null) this.incomingRequestCount.decrementAndGet();
        return r;
    }

    public boolean tryAdmit(PendingRequest pending) {
        int cap = pending.heldSlot() == SlotType.SYNC_ON_LOAD ? this.syncSlotCap : this.genSlotCap;
        int held = pending.heldSlot() == SlotType.SYNC_ON_LOAD ? this.heldSyncSlots : this.heldGenSlots;
        if (held >= cap) return false;
        addPendingRequest(pending);
        return true;
    }

    private void addPendingRequest(PendingRequest pending) {
        long packed = PositionUtil.packPosition(pending.cx(), pending.cz());
        var replaced = this.pendingByPosition.put(packed, pending);
        if (replaced != null) {
            adjustSlot(replaced.heldSlot(), -1);
        }
        adjustSlot(pending.heldSlot(), +1);
    }

    public PendingRequest removePendingByPosition(int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        var pending = this.pendingByPosition.remove(packed);
        if (pending != null) {
            adjustSlot(pending.heldSlot(), -1);
        }
        return pending;
    }

    public boolean hasPendingRequest(int cx, int cz) {
        return this.pendingByPosition.containsKey(PositionUtil.packPosition(cx, cz));
    }

    private void adjustSlot(SlotType slot, int delta) {
        if (slot == SlotType.SYNC_ON_LOAD) this.heldSyncSlots += delta;
        else this.heldGenSlots += delta;
    }

    public boolean hasDiskReadDone(int cx, int cz) {
        return this.diskReadDone.contains(PositionUtil.packPosition(cx, cz));
    }

    public void markDiskReadDone(int cx, int cz) {
        this.diskReadDone.add(PositionUtil.packPosition(cx, cz));
    }

    /** Clear dirty positions from diskReadDone (processing thread, from dirty-clear events). */
    public void clearDiskReadDone(long[] positions) {
        for (long pos : positions) {
            this.diskReadDone.remove(pos);
        }
    }

    /** Clear one position from diskReadDone (processing thread, honest re-resolution of a ts&le;0 re-request). */
    public void clearDiskReadDone(long packed) {
        this.diskReadDone.remove(packed);
    }

    /** True while a column payload for this position sits in the send pipeline (any thread). */
    public boolean hasEnqueuedColumn(long packed) {
        return this.enqueuedColumns.containsKey(packed);
    }

    private void decrementEnqueued(long packed) {
        this.enqueuedColumns.computeIfPresent(packed, (k, v) -> v <= 1 ? null : v - 1);
    }

    // ---- Accessors for concurrent queues (used by sibling classes) ----

    public Iterable<IncomingRequest> getIncomingRequests() {
        return this.incomingRequests;
    }

    public void addReadyPayload(QueuedPayload<T> payload) {
        this.enqueuedColumns.merge(payload.packedPos(), 1, Integer::sum);
        this.readyPayloads.add(payload);
    }

    // ---- Getters ----

    public UUID getPlayerUUID() { return this.playerUuid; }
    /** Returns a volatile snapshot of the send queue size, safe for cross-thread reads. */
    public int getSendQueueSize() { return this.sendQueueSizeSnapshot; }
    public int getHeldSyncSlots() { return this.heldSyncSlots; }
    public int getHeldGenSlots() { return this.heldGenSlots; }
    public int getSyncSlotCap() { return this.syncSlotCap; }
    public int getGenSlotCap() { return this.genSlotCap; }
    public long getTotalSectionsSent() { return this.bandwidth.getTotalSectionsSent(); }
    public long getTotalBytesSent() { return this.bandwidth.getTotalBytesSent(); }
    public long getTotalRequestsReceived() { return this.totalRequestsReceived.get(); }
    public long getTotalIncomingDropped() { return this.totalIncomingDropped.get(); }
}
