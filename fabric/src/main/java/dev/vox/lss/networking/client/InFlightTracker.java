package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.function.IntConsumer;

/**
 * Tracks in-flight chunk requests: the 3-way mapping between packed positions,
 * request IDs, and send timestamps. Also tracks which positions are generation
 * requests (clientTimestamp == 0) so callers don't need a parallel data structure.
 *
 * <p><b>Thread safety:</b> Not thread-safe. All methods must be called from
 * the main client thread (render/tick loop).
 */
class InFlightTracker {

    record RemovedRequest(long position) {}

    // Key: packed position, Value: send time (System.nanoTime())
    // defaultReturnValue(0L) -> 0 means "not in map"
    private final Long2LongOpenHashMap pendingRequests = new Long2LongOpenHashMap();
    {
        pendingRequests.defaultReturnValue(0L);
    }

    private int nextRequestId = 0;
    private final Int2LongOpenHashMap requestIdToPosition = new Int2LongOpenHashMap();
    {
        requestIdToPosition.defaultReturnValue(Long.MIN_VALUE);
    }
    private final Long2IntOpenHashMap positionToRequestId = new Long2IntOpenHashMap();
    {
        positionToRequestId.defaultReturnValue(-1);
    }

    // Positions that are generation requests (clientTimestamp == 0)
    private final LongOpenHashSet generationPositions = new LongOpenHashSet();

    /**
     * Register a new in-flight request. Allocates a request ID and records position
     * in the secondary maps. The position must already be in {@link #pendingRequests}
     * via {@link #markPending}.
     *
     * @return the allocated request ID
     */
    int send(long position) {
        int requestId = this.nextRequestId++;
        this.requestIdToPosition.put(requestId, position);
        this.positionToRequestId.put(position, requestId);
        return requestId;
    }

    /**
     * Record that a position is now pending and whether it is a generation request.
     * Called before {@link #send} to mark positions in-flight before request IDs
     * are allocated.
     */
    void markPending(long position, long sendTimeNanos, boolean isGeneration) {
        this.pendingRequests.put(position, sendTimeNanos);
        if (isGeneration) {
            this.generationPositions.add(position);
        }
    }

    /**
     * Complete an in-flight request by request ID. Removes from all maps.
     *
     * @return RemovedRequest with position, or null if the request ID was unknown
     */
    RemovedRequest removeByRequestId(int requestId) {
        long pos = removeAllByRequestId(requestId);
        return pos != Long.MIN_VALUE ? new RemovedRequest(pos) : null;
    }

    /** Remove a request by ID from all maps. Returns position or Long.MIN_VALUE if absent. */
    private long removeAllByRequestId(int requestId) {
        long pos = this.requestIdToPosition.remove(requestId);
        if (pos == Long.MIN_VALUE) return Long.MIN_VALUE;
        this.positionToRequestId.remove(pos);
        this.pendingRequests.remove(pos);
        this.generationPositions.remove(pos);
        return pos;
    }

    boolean isInFlight(long position) {
        return this.pendingRequests.containsKey(position);
    }

    int size() {
        return this.pendingRequests.size();
    }

    int generationCount() {
        return this.generationPositions.size();
    }

    /** Remove position from the secondary maps and return its request ID (-1 if absent). */
    private int removeFromSecondaryMaps(long pos) {
        int requestId = this.positionToRequestId.remove(pos);
        if (requestId != -1) this.requestIdToPosition.remove(requestId);
        return requestId;
    }

    /**
     * Sweep all timed-out requests, removing them from all maps.
     */
    void timeoutSweep(long thresholdNanos) {
        long now = System.nanoTime();
        var iter = this.pendingRequests.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (now - entry.getLongValue() > thresholdNanos) {
                long pos = entry.getLongKey();
                removeFromSecondaryMaps(pos);
                this.generationPositions.remove(pos);
                iter.remove();
            }
        }
    }

    /**
     * Remove all pending requests outside the given Chebyshev distance from
     * the player. Calls back with each request ID for cancellation packets.
     */
    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance, IntConsumer cancelCallback) {
        var iter = this.pendingRequests.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long pos = entry.getLongKey();
            if (PositionUtil.isOutOfRange(pos, playerCx, playerCz, pruneDistance)) {
                int requestId = removeFromSecondaryMaps(pos);
                this.generationPositions.remove(pos);
                if (requestId != -1) cancelCallback.accept(requestId);
                iter.remove();
            }
        }
    }

    /**
     * Invoke callback for each in-flight request ID (for sending cancel packets).
     */
    void forEachRequestId(IntConsumer callback) {
        for (var entry : this.positionToRequestId.long2IntEntrySet()) {
            callback.accept(entry.getIntValue());
        }
    }

    void clear() {
        this.nextRequestId = 0;
        this.pendingRequests.clear();
        this.requestIdToPosition.clear();
        this.positionToRequestId.clear();
        this.generationPositions.clear();
    }
}
