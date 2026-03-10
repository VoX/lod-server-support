package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;

import java.util.Map;
import java.util.UUID;

/**
 * Routes incoming chunk requests through the resolution pipeline for all players:
 * drain waiting queue → duplicate check → queue-full check → timestamp check →
 * concurrency acquire (or enqueue) → loaded-probe check → disk/generation submit.
 *
 * @param <PS> the platform-specific player state type
 */
class IncomingRequestRouter<PS extends PlayerStateAccess> {

    @FunctionalInterface
    interface DiskReadSubmitter {
        void submit(UUID playerUuid, int requestId, String dimension,
                    int cx, int cz, long submissionOrder);
    }

    @FunctionalInterface
    interface LoadedColumnSerializer<PS> {
        boolean serializeAndEnqueue(PS state, LoadedColumnData column, int requestId,
                                   long columnTimestamp, long submissionOrder, String dimension);
    }

    private final ColumnTimestampCache timestampCache;
    private final DedupTracker dedupTracker;
    private final boolean diskReadingAvailable;
    private final boolean generationAvailable;
    private final ProcessingContext ctx;

    IncomingRequestRouter(ColumnTimestampCache timestampCache,
                          DedupTracker dedupTracker,
                          boolean diskReadingAvailable, boolean generationAvailable,
                          ProcessingContext ctx) {
        this.timestampCache = timestampCache;
        this.dedupTracker = dedupTracker;
        this.diskReadingAvailable = diskReadingAvailable;
        this.generationAvailable = generationAvailable;
        this.ctx = ctx;
    }

    void routeAll(TickSnapshot snapshot, Map<UUID, PS> players,
                  DiskReadSubmitter diskReadSubmitter,
                  LoadedColumnSerializer<PS> loadedSerializer,
                  long cycleNow) {
        for (var entry : snapshot.players().entrySet()) {
            if (entry.getValue().dimensionChanged()) continue;
            var state = players.get(entry.getKey());
            if (state == null) continue;
            if (!state.supportsVoxelColumns()) continue;

            processIncomingRequests(state, entry.getKey(), entry.getValue(), snapshot,
                    diskReadSubmitter, loadedSerializer, cycleNow);
        }
    }

    private void processIncomingRequests(PS state, UUID playerUuid,
                                          TickSnapshot.PlayerTickData playerData,
                                          TickSnapshot snapshot,
                                          DiskReadSubmitter diskReadSubmitter,
                                          LoadedColumnSerializer<PS> loadedSerializer,
                                          long cycleNow) {
        String dimension = playerData.dimension();
        var loadedProbes = snapshot.loadedChunkProbes().getOrDefault(playerUuid, Long2ObjectMaps.emptyMap());

        // Phase 1: Drain previously queued requests that now have concurrency slots
        drainWaitingQueue(state, playerUuid, dimension, loadedProbes, snapshot,
                diskReadSubmitter, loadedSerializer, cycleNow);

        // Phase 2: Process new incoming requests
        IncomingRequest req;
        while ((req = state.pollIncomingRequest()) != null) {
            if (resolvedAsDuplicate(state, playerUuid, req)) continue;
            if (sendQueueFull(state, snapshot)) break;

            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (resolvedFromTimestamp(state, playerUuid, req, packed, dimension)) continue;

            // Check loaded probes before acquiring concurrency — in-memory hits don't need a disk/gen slot
            if (resolvedFromLoadedProbe(state, playerUuid, req, packed, loadedProbes, dimension, loadedSerializer, cycleNow)) continue;

            RequestType type = req.clientTimestamp() == 0 ? RequestType.GENERATION : RequestType.SYNC;
            var limiter = tryConcurrencyOrEnqueue(state, playerUuid, req, type, dimension);
            if (limiter == null) continue;

            submitToDiskOrGeneration(state, playerUuid, req, packed, dimension, type, limiter, diskReadSubmitter);
        }
    }

    /**
     * Drain the per-player waiting queue: peek front → re-check timestamp → try acquire concurrency →
     * if fail, stop (FIFO order preserved) → if success, pop and route through loaded-probe/disk/gen.
     */
    private void drainWaitingQueue(PS state, UUID playerUuid, String dimension,
                                    Long2ObjectMap<LoadedColumnData> loadedProbes,
                                    TickSnapshot snapshot,
                                    DiskReadSubmitter diskReadSubmitter,
                                    LoadedColumnSerializer<PS> loadedSerializer,
                                    long cycleNow) {
        var queue = state.getWaitingQueue();
        while (!queue.isEmpty()) {
            var queued = queue.peek();
            var req = queued.request();
            long packed = PositionUtil.packPosition(req.cx(), req.cz());

            // Re-check: might have been resolved by another player's read since enqueue
            if (state.hasDiskReadDone(req.cx(), req.cz())) {
                queue.poll();
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, req.requestId()));
                continue;
            }

            // Re-check timestamp (may have been served since enqueue)
            if (resolvedFromTimestamp(state, playerUuid, req, packed, dimension)) {
                queue.poll();
                continue;
            }

            // Check loaded probes before acquiring concurrency — in-memory hits don't need a slot
            if (resolvedFromLoadedProbe(state, playerUuid, req, packed, loadedProbes, dimension, loadedSerializer, cycleNow)) {
                queue.poll();
                continue;
            }

            // Try acquire concurrency slot
            var limiter = state.getRateLimiters().forRequest(queued.type(), this.diskReadingAvailable);
            if (!limiter.tryAcquire()) break; // stop draining — FIFO preserved

            queue.poll();

            submitToDiskOrGeneration(state, playerUuid, req, packed, dimension, queued.type(), limiter, diskReadSubmitter);
        }
    }

    /** Returns true if the request is a known duplicate (already served or in-flight). */
    private boolean resolvedAsDuplicate(PS state, UUID playerUuid, IncomingRequest req) {
        if (state.hasDiskReadDone(req.cx(), req.cz())) {
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, req.requestId()));
            return true;
        }
        if (state.hasPendingRequest(req.cx(), req.cz())) {
            this.ctx.diagnostics().incrementSkippedDuplicate();
            return true;
        }
        return false;
    }

    /** Returns true if the send queue is full — caller should break the loop. */
    private boolean sendQueueFull(PS state, TickSnapshot snapshot) {
        if (snapshot.maxSendQueueSize() > 0
                && state.getSendQueueSize() >= snapshot.maxSendQueueSize()) {
            this.ctx.diagnostics().incrementQueueFull();
            return true;
        }
        return false;
    }

    /**
     * Try to acquire a concurrency slot. If the slot is available, returns the limiter.
     * If not, enqueues the request in the waiting queue (or sends RateLimited if queue is full).
     * Returns null when the request was enqueued or rejected.
     */
    private ConcurrencyLimiter tryConcurrencyOrEnqueue(PS state, UUID playerUuid, IncomingRequest req,
                                                        RequestType type, String dimension) {
        var limiters = state.getRateLimiters();
        var limiter = limiters.forRequest(type, this.diskReadingAvailable);

        if (limiter.tryAcquire()) {
            return limiter;
        }

        // Concurrency full — try to enqueue
        int queueCap = limiters.syncRateLimit() + limiters.genRateLimit();
        if (state.getWaitingQueueSize() < queueCap) {
            state.getWaitingQueue().add(new AbstractPlayerRequestState.QueuedRequest(req, type, dimension));
            this.ctx.diagnostics().incrementQueued();
        } else {
            // Queue overflow — reject
            this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, req.requestId()));
            this.ctx.diagnostics().incrementRateLimited(type);
            if (LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Rate-limited " + playerUuid + " (" + type + "): queue full at " + queueCap
                        + " for chunk [" + req.cx() + ", " + req.cz() + "] in " + dimension);
            }
        }
        return null;
    }

    /** Returns true if resolved from an in-memory loaded chunk probe. */
    private boolean resolvedFromLoadedProbe(PS state, UUID playerUuid, IncomingRequest req, long packed,
                                             Long2ObjectMap<LoadedColumnData> probes, String dimension,
                                             LoadedColumnSerializer<PS> loadedSerializer,
                                             long cycleNow) {
        var probe = probes.get(packed);
        if (probe == null) return false;

        long ts = cycleNow;
        boolean sent = loadedSerializer.serializeAndEnqueue(state, probe, req.requestId(), ts,
                this.ctx.sequence().next(), dimension);
        if (!sent) {
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, req.requestId()));
        }
        state.markDiskReadDone(req.cx(), req.cz());
        this.ctx.diagnostics().incrementInMemory();
        return true;
    }

    /**
     * Returns true if the column is up-to-date based on timestamp cache.
     * Only sends ColumnUpToDate — no data is served from this cache.
     */
    private boolean resolvedFromTimestamp(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension) {
        if (req.clientTimestamp() <= 0) return false;

        long cachedTs = this.timestampCache.get(dimension, packed);
        if (cachedTs > 0 && cachedTs <= req.clientTimestamp()) {
            state.markDiskReadDone(req.cx(), req.cz());
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, req.requestId()));
            this.ctx.diagnostics().incrementUpToDate();
            return true;
        }

        return false;
    }

    /** Submit to disk reader (disk-first for both SYNC and GENERATION when disk available) or generation service. */
    private void submitToDiskOrGeneration(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension, RequestType type,
                                           ConcurrencyLimiter limiter,
                                           DiskReadSubmitter diskReadSubmitter) {
        long order = this.ctx.sequence().next();

        if (type == RequestType.SYNC || this.diskReadingAvailable) {
            // Route through disk reader (with cross-player dedup)
            state.addPendingRequest(new PendingRequest(req.requestId(), req.cx(), req.cz(), type));
            boolean attached = this.dedupTracker.tryAttachOrCreate(packed, dimension, playerUuid, req.requestId(), order);
            if (!attached) {
                diskReadSubmitter.submit(playerUuid, req.requestId(), dimension, req.cx(), req.cz(), order);
            }
            this.ctx.diagnostics().incrementDiskQueued();
        } else if (type == RequestType.GENERATION && this.generationAvailable) {
            // No disk reader — direct generation
            state.addPendingRequest(new PendingRequest(req.requestId(), req.cx(), req.cz(), type));
            this.ctx.generationTicketRequests().add(
                    new OffThreadProcessor.GenerationTicketRequest(playerUuid, req.requestId(), req.cx(), req.cz(), order));
        } else {
            // No disk reader AND no generation — can't serve
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, req.requestId()));
            limiter.release();
        }
    }
}
