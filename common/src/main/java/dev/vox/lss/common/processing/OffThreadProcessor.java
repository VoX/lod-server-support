package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.voxel.ColumnTimestampCache;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core processing loop running on a dedicated thread.
 * Receives snapshots from the main thread, processes pre-serialized column data,
 * and enqueues results to per-player output queues.
 *
 * @param <PlayerState>  the platform-specific player state type (must implement {@link PlayerStateAccess})
 * @param <ReadResult>   the platform-specific disk reader result type (must implement {@link ReadResultAccess})
 */
public abstract class OffThreadProcessor<PlayerState extends PlayerStateAccess, ReadResult extends ReadResultAccess> {

    private static final int SNAPSHOT_POLL_MS = 50;
    private static final int SHUTDOWN_JOIN_MS = 5000;
    private static final int EVICTION_INTERVAL_CYCLES = 1200; // ~60s at 20 TPS
    private static final int SAVE_INTERVAL_CYCLES = 6000; // ~5 min at 20 TPS

    /** Request for the main thread to submit a generation ticket (requires MC world state). */
    public record GenerationTicketRequest(UUID playerUuid, int requestId, int cx, int cz,
                                           long submissionOrder) {}

    private record TimestampInvalidation(String dimension, long[] positions) {}

    private final Object snapshotLock = new Object();
    private TickSnapshot pendingSnapshot;
    private final ConcurrentLinkedQueue<SendAction> sendActions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<GenerationTicketRequest> generationTicketRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TimestampInvalidation> timestampInvalidations = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TickSnapshot.GenerationReadyData> droppedGenerationReady = new ConcurrentLinkedQueue<>();
    private final Thread processingThread;
    private final ColumnTimestampCache timestampCache;
    private final Map<UUID, PlayerState> players;

    // Disk-first routing flag (used in preparePlayers cancel-release logic)
    private final boolean diskReadingAvailable;
    private final boolean generationAvailable;

    // Collaborators
    private final ProcessingContext ctx;
    private final IncomingRequestRouter<PlayerState> requestRouter;

    // Cross-player disk read deduplication (processing-thread-owned)
    private final DedupTracker dedupTracker = new DedupTracker();
    private final Path dataDir;
    private int evictionCounter;
    private int saveCounter;
    private int consecutiveErrors;
    private long cycleNow; // cached epochSeconds for current processing cycle

    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LSS-TimestampSave");
        t.setDaemon(true);
        return t;
    });

    protected OffThreadProcessor(Map<UUID, PlayerState> players,
                                  boolean diskReadingAvailable, boolean generationAvailable,
                                  Path dataDir) {
        this.players = players;
        this.diskReadingAvailable = diskReadingAvailable;
        this.generationAvailable = generationAvailable;
        this.dataDir = dataDir;
        this.timestampCache = new ColumnTimestampCache();
        if (dataDir != null) {
            this.timestampCache.load(dataDir);
        }
        this.ctx = new ProcessingContext(this.sendActions, this.generationTicketRequests,
                new ProcessingDiagnostics(), new SequenceCounter());
        this.requestRouter = new IncomingRequestRouter<>(this.timestampCache,
                this.dedupTracker, diskReadingAvailable, generationAvailable, this.ctx);
        this.processingThread = new Thread(this::processingLoop, "LSS Processing Thread");
        this.processingThread.setDaemon(true);
        this.processingThread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    public void start() {
        this.processingThread.start();
    }

    /** Post a snapshot for the processing thread to consume. */
    public void postSnapshot(TickSnapshot snapshot) {
        synchronized (this.snapshotLock) {
            if (this.pendingSnapshot != null) {
                // Snapshot overwrite: queue dropped generationReady for the processing thread
                // to release their concurrency slots (ConcurrencyLimiter is not thread-safe)
                for (var genReady : this.pendingSnapshot.generationReady()) {
                    this.droppedGenerationReady.add(genReady);
                }
            }
            this.pendingSnapshot = snapshot;
            this.snapshotLock.notifyAll();
        }
    }

    /** Drain completed send actions (called by main thread). */
    public SendAction pollSendAction() {
        return this.sendActions.poll();
    }

    /** Drain generation ticket requests (called by main thread). */
    public GenerationTicketRequest pollGenerationTicketRequest() {
        return this.generationTicketRequests.poll();
    }

    /** Queue timestamp invalidation for dirty positions (called by main thread). */
    public void invalidateTimestamps(String dimension, long[] positions) {
        this.timestampInvalidations.add(new TimestampInvalidation(dimension, positions));
    }

    /** Drain disk reader results for a player. Returns ReadResult or null. */
    protected abstract ReadResult pollDiskResult(PlayerState state);
    protected abstract ReadResult pollGenerationResult(PlayerState state);

    /** Enqueue payloads from a disk/gen result into the player's ready queue. */
    protected abstract void enqueueResultPayloads(PlayerState state, ReadResult result);

    /** Submit a disk read for unloaded chunk (called from processing thread). */
    protected abstract void submitDiskRead(UUID playerUuid, int requestId, String dimension,
                                            int cx, int cz,
                                            long submissionOrder);

    /**
     * Store timestamp and enqueue pre-serialized column data as a payload.
     */
    protected boolean compressAndEnqueueLoaded(PlayerState state, LoadedColumnData column,
                                               int requestId, long columnTimestamp,
                                               long submissionOrder,
                                               String dimension) {
        if (column.serializedSections() == null || column.serializedSections().length == 0) {
            return false;
        }

        int estimatedBytes = column.serializedSections().length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        // Store timestamp for up-to-date checks
        long packed = PositionUtil.packPosition(column.cx(), column.cz());
        this.timestampCache.put(dimension, packed, columnTimestamp, this.cycleNow);

        buildAndEnqueueColumnPayload(state, column.cx(), column.cz(), dimension,
                requestId, columnTimestamp, submissionOrder,
                column.serializedSections(), estimatedBytes);
        return true;
    }

    /** Build platform-specific payload from serialized section bytes and enqueue to the player's ready queue. */
    protected abstract void buildAndEnqueueColumnPayload(PlayerState state, int cx, int cz,
                                                          String dimension, int requestId,
                                                          long columnTimestamp, long submissionOrder,
                                                          byte[] sectionBytes, int estimatedBytes);

    private void processingLoop() {
        while (true) {
            TickSnapshot snapshot;
            synchronized (this.snapshotLock) {
                while (this.pendingSnapshot == null) {
                    try {
                        this.snapshotLock.wait(SNAPSHOT_POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                snapshot = this.pendingSnapshot;
                this.pendingSnapshot = null;
            }

            if (snapshot.shutdown()) return;

            try {
                this.processCycle(snapshot);
                this.consecutiveErrors = 0;
            } catch (Exception e) {
                LSSLogger.error("Error in processing cycle", e);
                if (++this.consecutiveErrors >= 10) {
                    LSSLogger.error("Processing thread hit " + this.consecutiveErrors + " consecutive errors, backing off");
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    private void processCycle(TickSnapshot snapshot) {
        this.cycleNow = LSSConstants.epochSeconds();
        this.ctx.diagnostics().resetTickCounters();

        releaseDroppedGenerationSlots();
        preparePlayers(snapshot);
        drainDiskResultsForAllPlayers(snapshot);
        drainGenerationResultsForAllPlayers(snapshot);
        processGenerationReady(snapshot);
        routeIncomingRequests(snapshot);

        if (++this.evictionCounter >= EVICTION_INTERVAL_CYCLES) {
            this.evictionCounter = 0;
            int evicted = this.timestampCache.evictIfOversized();
            if (evicted > 0 && LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Evicted " + evicted + " oversized timestamp cache entries (" + this.timestampCache.size() + " remaining)");
            }
        }

        if (this.dataDir != null && ++this.saveCounter >= SAVE_INTERVAL_CYCLES) {
            this.saveCounter = 0;
            var cacheSnapshot = this.timestampCache.snapshotForSave();
            SAVE_EXECUTOR.execute(() -> cacheSnapshot.save(this.dataDir));
        }
    }

    /** Release generation concurrency slots from snapshots that were overwritten before processing. */
    private void releaseDroppedGenerationSlots() {
        TickSnapshot.GenerationReadyData genReady;
        while ((genReady = this.droppedGenerationReady.poll()) != null) {
            var state = this.players.get(genReady.playerUuid());
            if (state != null) {
                state.removePendingByPosition(genReady.columnData().cx(), genReady.columnData().cz());
                state.getRateLimiters().generation().release();
            }
        }
    }

    // ---- Phase 1: Handle dimension changes, drain cancels ----

    private void preparePlayers(TickSnapshot snapshot) {
        // Drain timestamp invalidations queued from main thread
        TimestampInvalidation inv;
        while ((inv = this.timestampInvalidations.poll()) != null) {
            this.timestampCache.invalidate(inv.dimension(), inv.positions());
        }

        // Clean up dedup groups for removed players
        for (UUID removedUuid : snapshot.removedPlayers()) {
            cleanupDedupGroups(this.dedupTracker.removePlayer(removedUuid));
        }

        for (var entry : snapshot.players().entrySet()) {
            var state = this.players.get(entry.getKey());
            if (state == null) continue;
            if (entry.getValue().dimensionChanged()) {
                state.clearProcessingState();
                cleanupDedupGroups(this.dedupTracker.removePlayer(entry.getKey()));
            }
            state.drainDirtyClearRequests();

            // Drain cancel requests
            Integer cancelId;
            while ((cancelId = state.pollCancel()) != null) {
                var pending = state.removePendingByRequestId(cancelId);
                if (pending != null) {
                    state.getRateLimiters().forRequest(pending.type(), this.diskReadingAvailable).release();
                }
            }
        }
    }

    private void cleanupDedupGroups(java.util.List<DedupTracker.RemovedGroup> removedGroups) {
        for (var rg : removedGroups) {
            int cx = PositionUtil.unpackX(rg.packed());
            int cz = PositionUtil.unpackZ(rg.packed());
            for (var attachment : rg.group().attached()) {
                var attachedState = this.players.get(attachment.playerUuid());
                if (attachedState != null) {
                    attachedState.removePendingByPosition(cx, cz);
                    attachedState.getRateLimiters().syncOnLoad().release();
                }
            }
        }
    }

    // ---- Phase 2: Drain disk reader results (with cross-player dedup dispatch) ----

    private void drainDiskResultsForAllPlayers(TickSnapshot snapshot) {
        for (var entry : snapshot.players().entrySet()) {
            var state = this.players.get(entry.getKey());
            if (state == null) continue;
            UUID playerUuid = entry.getKey();

            ReadResult result;
            while ((result = this.pollDiskResult(state)) != null) {
                int cx = result.chunkX();
                int cz = result.chunkZ();
                var pending = state.removePendingByPosition(cx, cz);
                int requestId = pending != null ? pending.requestId() : result.requestId();

                state.getRateLimiters().syncOnLoad().release();

                long packed = PositionUtil.packPosition(cx, cz);

                if (result.saturated()) {
                    this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, requestId));
                    if (LSSLogger.isDebugEnabled()) {
                        LSSLogger.debug("Rate-limited " + playerUuid + " (disk saturated): chunk [" + cx + ", " + cz + "]");
                    }
                } else if (result.notFound()) {
                    handleDiskNotFound(playerUuid, state, requestId, cx, cz, pending);
                } else {
                    state.markDiskReadDone(cx, cz);
                    if (result.sectionBytes() != null) {
                        this.enqueueResultPayloads(state, result);
                    } else {
                        // All-air chunk (exists on disk but no visible sections) — notify client
                        this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, requestId));
                    }
                    // Store timestamp so reconnecting clients get up-to-date responses
                    this.timestampCache.put(entry.getValue().dimension(), packed, result.columnTimestamp(), this.cycleNow);
                }
                this.ctx.diagnostics().incrementDiskDrained();

                // Dispatch to attached players in the dedup group
                var group = this.dedupTracker.removeGroup(packed);
                if (group != null) {
                    dispatchDedupGroup(group, result, cx, cz);
                }
            }
        }
    }

    private void dispatchDedupGroup(DedupTracker.Group group, ReadResult result,
                                     int cx, int cz) {
        byte[] sectionBytes = result.sectionBytes();
        for (var attachment : group.attached()) {
            var attachedState = this.players.get(attachment.playerUuid());
            if (attachedState == null) continue;

            var attachedPending = attachedState.removePendingByPosition(cx, cz);
            attachedState.getRateLimiters().syncOnLoad().release();
            int attachedRequestId = attachedPending != null ? attachedPending.requestId() : attachment.requestId();

            if (result.saturated()) {
                this.ctx.sendActions().add(new SendAction.RateLimited(attachment.playerUuid(), attachedRequestId));
            } else if (result.notFound()) {
                handleDiskNotFound(attachment.playerUuid(), attachedState, attachedRequestId, cx, cz, attachedPending);
            } else if (sectionBytes != null) {
                attachedState.markDiskReadDone(cx, cz);
                buildAndEnqueueColumnPayload(attachedState, cx, cz, group.dimension(),
                        attachedRequestId, result.columnTimestamp(), attachment.submissionOrder(),
                        sectionBytes, result.estimatedBytes());
            } else {
                // Empty column (all air) — mark done and notify client
                attachedState.markDiskReadDone(cx, cz);
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(attachment.playerUuid(), attachedRequestId));
            }
            this.ctx.diagnostics().incrementDiskDrained();
        }
    }

    /** Disk-first fallback: if the original request was GENERATION and generation is available,
     *  queue a generation ticket; otherwise send ColumnNotGenerated. */
    private void handleDiskNotFound(UUID playerUuid, PlayerState state, int requestId,
                                     int cx, int cz, PendingRequest pending) {
        if (pending != null && pending.type() == RequestType.GENERATION && this.generationAvailable) {
            if (state.getRateLimiters().generation().tryAcquire()) {
                this.ctx.generationTicketRequests().add(new GenerationTicketRequest(
                        playerUuid, requestId, cx, cz, this.ctx.sequence().next()));
                state.addPendingRequest(new PendingRequest(requestId, cx, cz, RequestType.GENERATION));
            } else {
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, requestId));
            }
        } else {
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, requestId));
        }
    }

    // ---- Phase 3: Drain generation results ----

    private void drainGenerationResultsForAllPlayers(TickSnapshot snapshot) {
        for (var entry : snapshot.players().entrySet()) {
            var state = this.players.get(entry.getKey());
            if (state == null) continue;
            UUID playerUuid = entry.getKey();

            ReadResult result;
            while ((result = this.pollGenerationResult(state)) != null) {
                int cx = result.chunkX();
                int cz = result.chunkZ();
                var pending = state.removePendingByPosition(cx, cz);
                int requestId = pending != null ? pending.requestId() : result.requestId();

                state.getRateLimiters().generation().release();

                if (result.notFound()) {
                    this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, requestId));
                } else {
                    state.markDiskReadDone(cx, cz);
                    this.enqueueResultPayloads(state, result);
                    long packed = PositionUtil.packPosition(cx, cz);
                    this.timestampCache.put(entry.getValue().dimension(), packed, result.columnTimestamp(), this.cycleNow);
                }
                this.ctx.diagnostics().incrementGenDrained();
            }
        }
    }

    // ---- Phase 3b: Process generation-ready data from snapshot ----

    private void processGenerationReady(TickSnapshot snapshot) {
        for (var genReady : snapshot.generationReady()) {
            var state = this.players.get(genReady.playerUuid());
            if (state == null) continue;
            int cx = genReady.columnData().cx();
            int cz = genReady.columnData().cz();
            state.removePendingByPosition(cx, cz);
            state.markDiskReadDone(cx, cz);
            state.getRateLimiters().generation().release();

            var playerData = snapshot.players().get(genReady.playerUuid());
            if (playerData == null) continue;
            String dimension = playerData.dimension();
            boolean sent = this.compressAndEnqueueLoaded(state, genReady.columnData(), genReady.requestId(),
                    genReady.columnTimestamp(), genReady.submissionOrder(),
                    dimension);
            if (!sent) {
                this.sendActions.add(new SendAction.ColumnUpToDate(genReady.playerUuid(), genReady.requestId()));
            }
            this.ctx.diagnostics().incrementGenDrained();
        }
    }

    // ---- Phase 4: Route incoming requests per player ----

    private void routeIncomingRequests(TickSnapshot snapshot) {
        this.requestRouter.routeAll(snapshot, this.players,
                this::submitDiskRead, this::compressAndEnqueueLoaded, this.cycleNow);
    }

    public ProcessingDiagnostics getDiagnostics() {
        return this.ctx.diagnostics();
    }

    public void shutdown() {
        this.postSnapshot(TickSnapshot.shutdownSentinel());
        try {
            this.processingThread.interrupt();
            this.processingThread.join(SHUTDOWN_JOIN_MS);
            if (this.processingThread.isAlive()) {
                LSSLogger.warn("Processing thread did not terminate within " + SHUTDOWN_JOIN_MS + "ms");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Save timestamp cache to disk (synchronous — server is shutting down)
        if (this.dataDir != null) {
            this.timestampCache.save(this.dataDir);
        }
    }
}
