package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.voxel.ColumnTimestampCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Core processing loop running on a dedicated thread.
 * Receives per-tick snapshots and lossless events from the main thread, processes
 * pre-serialized column data, and enqueues results to per-player output queues.
 *
 * <p>The main→processing mailbox has two kinds of input with one sentence of semantics:
 * snapshot state is latest-wins (an unconsumed snapshot is replaced), events are lossless
 * (generation outcomes, player removals, timestamp invalidations, and dirty-clears
 * accumulate until consumed). All event producers run on the main thread.
 *
 * @param <PlayerState>  the platform-specific player state type
 */
public abstract class OffThreadProcessor<PlayerState extends AbstractPlayerRequestState<?>> {

    private static final int SNAPSHOT_POLL_MS = 50;
    private static final int SHUTDOWN_JOIN_MS = 5000;
    private static final int EVICTION_INTERVAL_CYCLES = 1200; // ~60s at 20 TPS
    private static final int SAVE_INTERVAL_CYCLES = 6000; // ~5 min at 20 TPS

    /** Request for the main thread to submit a generation ticket (requires MC world state). */
    public record GenerationTicketRequest(UUID playerUuid, int cx, int cz, String dimension,
                                           long submissionOrder) {}

    private record TimestampInvalidation(String dimension, long[] positions) {}

    /** Everything the processing thread takes from the mailbox in one cycle. */
    private record MailboxTake(TickSnapshot snapshot,
                               List<TickSnapshot.GenerationReadyData> generationReady,
                               List<UUID> removals,
                               List<TimestampInvalidation> invalidations,
                               Map<UUID, ArrayList<long[]>> dirtyClears) {}

    // Mailbox (guarded by mailboxLock). Snapshot: latest-wins. Event buffers: lossless,
    // swapped out whole by the processing thread. Producers are main-thread-only.
    private final Object mailboxLock = new Object();
    private TickSnapshot pendingSnapshot;
    private ArrayList<TickSnapshot.GenerationReadyData> pendingGenerationReady = new ArrayList<>();
    private ArrayList<UUID> pendingRemovals = new ArrayList<>();
    private ArrayList<TimestampInvalidation> pendingInvalidations = new ArrayList<>();
    private HashMap<UUID, ArrayList<long[]>> pendingDirtyClears = new HashMap<>();

    // Processing thread → main thread (thread-safe output)
    private final ConcurrentLinkedQueue<SendAction> sendActions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<GenerationTicketRequest> generationTicketRequests = new ConcurrentLinkedQueue<>();

    private final Thread processingThread;
    private final ColumnTimestampCache timestampCache;
    private final Map<UUID, PlayerState> players;
    // Nullable: disk reading is disabled when no reader is configured
    private final AbstractChunkDiskReader diskReader;
    private final SendActionBatcher sendActionBatcher = new SendActionBatcher();

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

    // this-escape is benign: the Thread is created here but only started later via start()
    // (post-construction), and the router's back-reference is only used from the processing
    // loop after start(), so no partially-initialized subclass state is touched before
    // construction completes.
    @SuppressWarnings("this-escape")
    protected OffThreadProcessor(Map<UUID, PlayerState> players,
                                  AbstractChunkDiskReader diskReader, boolean generationAvailable,
                                  Path dataDir, int perDimensionTimestampCacheSizeMB) {
        this.players = players;
        this.diskReader = diskReader;
        this.generationAvailable = generationAvailable;
        this.dataDir = dataDir;
        this.timestampCache = new ColumnTimestampCache(
                ColumnTimestampCache.mbToEntries(perDimensionTimestampCacheSizeMB));
        if (dataDir != null) {
            this.timestampCache.load(dataDir);
        }
        this.ctx = new ProcessingContext(this.sendActions, this.generationTicketRequests,
                new ProcessingDiagnostics(), new SequenceCounter());
        this.requestRouter = new IncomingRequestRouter<>(this, this.players, this.timestampCache,
                this.dedupTracker, diskReader != null, generationAvailable, this.ctx);
        this.processingThread = new Thread(this::processingLoop, "LSS Processing Thread");
        this.processingThread.setDaemon(true);
        this.processingThread.setPriority(Thread.NORM_PRIORITY - 1);
        this.processingThread.setUncaughtExceptionHandler((th, ex) ->
                LSSLogger.error("LSS processing thread died unexpectedly", ex));
    }

    public void start() {
        this.processingThread.start();
    }

    // ---- Mailbox producers (main thread) ----

    /**
     * Post this tick's snapshot (latest-wins) and generation outcomes (lossless).
     * Ownership of the snapshot's maps and the generationReady list transfers to the processor.
     */
    public void postSnapshot(TickSnapshot snapshot, List<TickSnapshot.GenerationReadyData> generationReady) {
        synchronized (this.mailboxLock) {
            if (!generationReady.isEmpty()) {
                this.pendingGenerationReady.addAll(generationReady);
            }
            this.pendingSnapshot = snapshot;
            this.mailboxLock.notifyAll();
        }
    }

    /** Notify the processing thread that a player was removed (disconnect or dimension change). */
    public void notifyPlayerRemoved(UUID uuid) {
        synchronized (this.mailboxLock) {
            this.pendingRemovals.add(uuid);
        }
    }

    /** Queue timestamp invalidation for dirty positions. */
    public void invalidateTimestamps(String dimension, long[] positions) {
        synchronized (this.mailboxLock) {
            this.pendingInvalidations.add(new TimestampInvalidation(dimension, positions));
        }
    }

    /** Queue positions for clearing from a player's diskReadDone set (dirty re-send support). */
    public void clearDiskReadDone(UUID playerUuid, long[] positions) {
        synchronized (this.mailboxLock) {
            this.pendingDirtyClears.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(positions);
        }
    }

    /**
     * Feed a generation failure for a request whose ticket could not be submitted
     * (e.g. generation capacity rejection). Delivered like any other generation outcome.
     */
    public void feedGenerationFailure(UUID playerUuid, int cx, int cz, String dimension, long submissionOrder) {
        synchronized (this.mailboxLock) {
            this.pendingGenerationReady.add(new TickSnapshot.GenerationReadyData(
                    playerUuid, cx, cz, dimension, null, 0L, submissionOrder));
        }
    }

    // ---- Main-thread output drains ----

    /** Platform hook that sends one batched-response frame to a player. */
    @FunctionalInterface
    public interface BatchSender<PlayerState> {
        void send(PlayerState state, byte[] responseTypes, long[] packedPositions, int count) throws Exception;
    }

    /**
     * Drain completed send actions and flush them as one batched response per player
     * (called by the main thread each tick).
     */
    public void drainSendActions(BatchSender<PlayerState> sender) {
        this.sendActionBatcher.clear();

        SendAction action;
        while ((action = this.sendActions.poll()) != null) {
            var state = this.players.get(action.playerUuid());
            // Identity check: a dimension change re-registers the same UUID with a fresh
            // state — actions produced for the old session must not reach the new one.
            if (state == null || state != action.producerState()
                    || !state.hasCompletedHandshake()) continue;
            this.sendActionBatcher.add(action.playerUuid(), action.responseType(), action.packedPosition());
        }

        if (this.sendActionBatcher.isEmpty()) return;

        this.sendActionBatcher.forEach((uuid, types, positions, count) -> {
            var state = this.players.get(uuid);
            if (state == null || !state.hasCompletedHandshake()) return;
            try {
                sender.send(state, types, positions, count);
            } catch (Exception e) {
                LSSLogger.error("Failed to send batch response to " + state.getPlayerName(), e);
            }
        });
    }

    /** Drain generation ticket requests (called by main thread). */
    public GenerationTicketRequest pollGenerationTicketRequest() {
        return this.generationTicketRequests.poll();
    }

    // ---- Platform hooks ----

    /**
     * Submit a disk read for an unloaded chunk (called from processing thread). Returns
     * {@code false} if the read could not be submitted (e.g. no reader, or the dimension's level
     * is not registered yet) so the caller can unwind the pending entry + dedup group instead
     * of leaking them.
     */
    protected abstract boolean submitDiskRead(UUID playerUuid, String dimension,
                                            int cx, int cz,
                                            long submissionOrder);

    /**
     * Store timestamp and enqueue pre-serialized column data as a payload.
     */
    protected boolean enqueueLoadedColumn(PlayerState state, LoadedColumnData column,
                                          long columnTimestamp,
                                          long submissionOrder,
                                          String dimension) {
        if (column.serializedSections() == null || column.serializedSections().length == 0) {
            return false;
        }

        int estimatedBytes = column.serializedSections().length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        // Store timestamp for up-to-date checks
        long packed = PositionUtil.packPosition(column.cx(), column.cz());
        this.timestampCache.put(dimension, packed, columnTimestamp, this.cycleNow);

        return buildAndEnqueueColumnPayload(state, column.cx(), column.cz(), dimension,
                columnTimestamp, submissionOrder,
                column.serializedSections(), estimatedBytes);
    }

    /**
     * Build platform-specific payload from serialized section bytes and enqueue to the
     * player's ready queue. Returns false when the column cannot go on the wire (oversized
     * payload) — every caller then answers up-to-date so the position resolves terminally
     * instead of dropping silently (the old silent drop left diskReadDone set with no
     * response, and the client retried an unserveable position forever).
     */
    protected abstract boolean buildAndEnqueueColumnPayload(PlayerState state, int cx, int cz,
                                                             String dimension,
                                                             long columnTimestamp, long submissionOrder,
                                                             byte[] sectionBytes, int estimatedBytes);

    // ---- Processing loop ----

    private void processingLoop() {
        while (true) {
            MailboxTake take;
            synchronized (this.mailboxLock) {
                while (this.pendingSnapshot == null) {
                    try {
                        this.mailboxLock.wait(SNAPSHOT_POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                take = new MailboxTake(this.pendingSnapshot, this.pendingGenerationReady,
                        this.pendingRemovals, this.pendingInvalidations, this.pendingDirtyClears);
                this.pendingSnapshot = null;
                this.pendingGenerationReady = new ArrayList<>();
                this.pendingRemovals = new ArrayList<>();
                this.pendingInvalidations = new ArrayList<>();
                this.pendingDirtyClears = new HashMap<>();
            }

            if (take.snapshot().shutdown()) return;

            try {
                this.processCycle(take);
                this.consecutiveErrors = 0;
            } catch (Throwable t) {
                // Catch Throwable (not just Exception) so a transient Error doesn't silently
                // kill the processing thread and stop the LOD pipeline for every player.
                LSSLogger.error("Error in processing cycle", t);
                if (++this.consecutiveErrors >= 10) {
                    LSSLogger.error("Processing thread hit " + this.consecutiveErrors + " consecutive errors, backing off");
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    private void processCycle(MailboxTake take) {
        this.cycleNow = LSSConstants.epochSeconds();
        this.ctx.diagnostics().resetTickCounters();

        applyEvents(take);
        drainDiskResultsForAllPlayers(take.snapshot());
        processGenerationReady(take.generationReady(), take.snapshot());
        routeIncomingRequests(take.snapshot());

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

    // ---- Phase 1: Apply lossless events ----

    private void applyEvents(MailboxTake take) {
        for (var inv : take.invalidations()) {
            this.timestampCache.invalidate(inv.dimension(), inv.positions());
        }

        // Player removals (disconnect or dimension change): release the player's dedup groups
        // and the attached players' pending entries. The removed state itself is simply dropped
        // — its slot counts die with it, so nothing can leak.
        for (UUID removed : take.removals()) {
            cleanupDedupGroups(this.dedupTracker.removePlayer(removed));
        }

        for (var entry : take.dirtyClears().entrySet()) {
            var state = this.players.get(entry.getKey());
            if (state == null) continue;
            for (long[] positions : entry.getValue()) {
                state.clearDiskReadDone(positions);
            }
        }
    }

    private void cleanupDedupGroups(List<DedupTracker.RemovedGroup> removedGroups) {
        for (var rg : removedGroups) {
            int cx = PositionUtil.unpackX(rg.packed());
            int cz = PositionUtil.unpackZ(rg.packed());
            for (var attachment : rg.group().attached()) {
                var attachedState = this.players.get(attachment.playerUuid());
                if (attachedState != null) {
                    attachedState.removePendingByPosition(cx, cz);
                }
            }
        }
    }

    // ---- Phase 2: Drain disk reader results (with cross-player dedup dispatch) ----

    private void drainDiskResultsForAllPlayers(TickSnapshot snapshot) {
        for (var entry : snapshot.playerDimensions().entrySet()) {
            var state = this.players.get(entry.getKey());
            if (state == null) continue;
            UUID playerUuid = entry.getKey();
            String dimension = entry.getValue();

            if (this.diskReader == null) continue;
            var queue = this.diskReader.getPlayerQueue(state.getPlayerUUID());
            if (queue == null) continue;

            ChunkReadResult result;
            while ((result = queue.poll()) != null) {
                if (!dimension.equals(result.dimension())) {
                    // Read submitted before a dimension change: the pending entry, slot, and
                    // dedup group that backed it died with the removed state. Delivering it here
                    // would stamp diskReadDone/timestampCache under the NEW dimension and could
                    // tear down a live new-dimension dedup group at the same packed position.
                    continue;
                }
                int cx = result.chunkX();
                int cz = result.chunkZ();
                long packed = PositionUtil.packPosition(cx, cz);

                deliverDiskResult(playerUuid, state, result, result.submissionOrder(), dimension);

                if (!result.saturated() && !result.notFound()) {
                    // Store timestamp so reconnecting clients get up-to-date responses
                    this.timestampCache.put(result.dimension(), packed, result.columnTimestamp(), this.cycleNow);
                }

                // Dispatch the same result to attached players in the dedup group
                var group = this.dedupTracker.removeGroup(packed, dimension);
                if (group != null) {
                    for (var attachment : group.attached()) {
                        var attachedState = this.players.get(attachment.playerUuid());
                        if (attachedState == null) continue;
                        deliverDiskResult(attachment.playerUuid(), attachedState, result,
                                attachment.submissionOrder(), dimension);
                    }
                }
            }
        }
    }

    /**
     * Deliver one disk read result to one recipient: resolve the pending entry (freeing its
     * slot), then answer with RateLimited / not-found fallback / column data / up-to-date.
     * Shared by the primary requester and every dedup-attached player.
     */
    private void deliverDiskResult(UUID playerUuid, PlayerState state, ChunkReadResult result,
                                    long submissionOrder, String dimension) {
        int cx = result.chunkX();
        int cz = result.chunkZ();
        long packed = PositionUtil.packPosition(cx, cz);
        var pending = state.removePendingByPosition(cx, cz);

        if (result.saturated()) {
            this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, packed, state));
            if (LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Rate-limited " + playerUuid + " (disk saturated): chunk [" + cx + ", " + cz + "]");
            }
        } else if (result.notFound()) {
            handleDiskNotFound(playerUuid, state, packed, cx, cz, pending, dimension);
        } else {
            state.markDiskReadDone(cx, cz);
            boolean sent = result.sectionBytes() != null
                    && buildAndEnqueueColumnPayload(state, cx, cz, result.dimension(),
                            result.columnTimestamp(), submissionOrder,
                            result.sectionBytes(), result.estimatedBytes());
            if (!sent) {
                // All-air chunk (no visible sections) or oversized column — notify client
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
            }
        }
        this.ctx.diagnostics().incrementDiskDrained();
    }

    /** Disk-first fallback: if the original request was GENERATION and generation is available,
     *  queue a generation ticket; otherwise send ColumnNotGenerated. */
    private void handleDiskNotFound(UUID playerUuid, PlayerState state, long packed,
                                     int cx, int cz, PendingRequest pending, String dimension) {
        if (pending != null && pending.type() == RequestType.GENERATION && this.generationAvailable) {
            if (state.tryAdmit(new PendingRequest(cx, cz, RequestType.GENERATION, SlotType.GENERATION))) {
                this.ctx.generationTicketRequests().add(new GenerationTicketRequest(
                        playerUuid, cx, cz, dimension, this.ctx.sequence().next()));
            } else {
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
            }
        } else {
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
        }
    }

    // ---- Phase 3: Process generation outcomes ----

    private void processGenerationReady(List<TickSnapshot.GenerationReadyData> generationReady,
                                         TickSnapshot snapshot) {
        for (var entry : generationReady) {
            var state = this.players.get(entry.playerUuid());
            if (state == null) continue;
            // A dimension change replaces the player's state (removePlayer + registerPlayer,
            // same UUID). Outcomes harvested for the old session carry the old dimension —
            // skip them instead of poisoning the fresh state (their pending died with it).
            String current = snapshot.playerDimensions().get(entry.playerUuid());
            if (current == null || !current.equals(entry.dimension())) continue;
            int cx = entry.cx();
            int cz = entry.cz();
            state.removePendingByPosition(cx, cz);
            long packed = PositionUtil.packPosition(cx, cz);

            if (entry.columnData() == null) {
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(entry.playerUuid(), packed, state));
            } else {
                state.markDiskReadDone(cx, cz);
                boolean sent = this.enqueueLoadedColumn(state, entry.columnData(),
                        entry.columnTimestamp(), entry.submissionOrder(), entry.dimension());
                if (!sent) {
                    this.sendActions.add(new SendAction.ColumnUpToDate(entry.playerUuid(), packed, state));
                }
            }
            this.ctx.diagnostics().incrementGenDrained();
        }
    }

    // ---- Phase 4: Route incoming requests per player ----

    private void routeIncomingRequests(TickSnapshot snapshot) {
        this.requestRouter.routeAll(snapshot, this.cycleNow);
    }

    public ProcessingDiagnostics getDiagnostics() {
        return this.ctx.diagnostics();
    }

    public void shutdown() {
        this.postSnapshot(TickSnapshot.shutdownSentinel(), List.of());
        try {
            this.processingThread.interrupt();
            this.processingThread.join(SHUTDOWN_JOIN_MS);
            if (this.processingThread.isAlive()) {
                LSSLogger.warn("Processing thread did not terminate within " + SHUTDOWN_JOIN_MS + "ms");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Save timestamp cache to disk. Route the final save through SAVE_EXECUTOR (single-threaded,
        // FIFO) so it runs after any in-flight periodic save, then wait for it — otherwise a late
        // async save could overwrite the final state with an older snapshot.
        // Skip if the processing thread is still alive: ColumnTimestampCache is single-owner
        // (processing thread), so snapshotting it here would race its put/invalidate/evict.
        if (this.processingThread.isAlive()) {
            LSSLogger.warn("Skipping final timestamp cache save — processing thread still running");
        } else if (this.dataDir != null) {
            var snapshot = this.timestampCache.snapshotForSave();
            try {
                SAVE_EXECUTOR.submit(() -> snapshot.save(this.dataDir))
                        .get(SHUTDOWN_JOIN_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LSSLogger.warn("Timestamp cache final save did not complete cleanly", e);
            }
        }
    }
}
