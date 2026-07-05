package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    // WS4 durable invalidation: a dirty-broadcast invalidation removes cache entries in memory;
    // without a prompt save, a crash within the ~5-min periodic window resurrects them (false
    // up_to_date). Debounce on the processing thread: on the FIRST removal since the last save,
    // arm a countdown; save when it elapses (coalesce, do NOT reset) so continuous churn cannot
    // starve durability, and the countdown floor (~2s) bounds how often snapshotForSave's deep
    // copy runs under a high edit rate. Atomic-move gives atomicity, not power-loss durability
    // (no fsync) — the shrunk window covers graceful/process crashes; power-loss is deferred to
    // the tombstone fallback.
    private static final int INVALIDATE_SAVE_MAX_CYCLES = 40; // ~2s at 20 cycles/s
    private boolean invalidationDirty = false;
    private int invalidationCountdown = 0;

    // Per-instance (not static): a static executor's non-expiring daemon thread pins the
    // OffThreadProcessor class — and, on Paper, the whole plugin classloader — for the JVM's
    // life, leaking one thread + classloader on every /reload. Bounding it to the processor
    // and stopping it in shutdown() ties its lifetime to the server run. The factory captures
    // no instance state, so initializing it in the field initializer is this-escape-safe.
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
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
        return enqueueLoadedColumn(state, column, columnTimestamp, submissionOrder, dimension, false);
    }

    /**
     * As {@link #enqueueLoadedColumn(PlayerState, LoadedColumnData, long, long, String)} but
     * skips the timestamp-cache stamp when the column is stale against an in-flight edit, so a
     * dirty re-request re-resolves instead of drawing a false up_to_date. The column is still
     * enqueued (better than nothing) — only the stamp is withheld.
     */
    protected boolean enqueueLoadedColumn(PlayerState state, LoadedColumnData column,
                                          long columnTimestamp,
                                          long submissionOrder,
                                          String dimension,
                                          boolean staleAgainstEdit) {
        if (column.serializedSections() == null || column.serializedSections().length == 0) {
            return false;
        }

        int estimatedBytes = column.serializedSections().length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        // Store timestamp for up-to-date checks (skipped when stale so the edit re-resolves)
        long packed = PositionUtil.packPosition(column.cx(), column.cz());
        if (!staleAgainstEdit) {
            this.timestampCache.put(dimension, packed, columnTimestamp, this.cycleNow);
        }

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

            if (take.snapshot().shutdown()) {
                // The sentinel take destructively drained the lossless buffers with it. The
                // per-session events (removals, dirty-clears, generation outcomes) die with
                // the session, but queued INVALIDATIONS must still hit the cache — shutdown's
                // final save otherwise persists pre-edit stamps that answer false up_to_date
                // across the restart.
                for (var inv : take.invalidations()) {
                    this.timestampCache.invalidate(inv.dimension(), inv.positions());
                }
                return;
            }

            try {
                this.processCycle(take);
                this.consecutiveErrors = 0;
            } catch (Throwable t) {
                // Catch Throwable (not just Exception) so a transient Error doesn't silently
                // kill the processing thread and stop the LOD pipeline for every player.
                LSSLogger.error("Error in processing cycle", t);
                // The take destructively drained the lossless event buffers; discarding them
                // on a failed cycle permanently loses player removals (leaked slots/dedup
                // groups), generation outcomes (stranded pending generations), and
                // dirty-clears/invalidations (up_to_date answered for stale content). Re-queue
                // them so the next cycle retries — every one of these events is idempotent, so
                // re-applying any a partial cycle already applied is harmless (a re-sent column
                // or up_to_date is position-idempotent on the client).
                requeueLosslessEvents(take);
                if (++this.consecutiveErrors >= 10) {
                    LSSLogger.error("Processing thread hit " + this.consecutiveErrors + " consecutive errors, backing off");
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    /**
     * Re-inject a failed cycle's lossless events into the mailbox so the next cycle retries
     * them. Ordering does not matter: removals, invalidations, and dirty-clears are set/map
     * removals (idempotent), and generation outcomes are keyed by position (a duplicate
     * response is idempotent on the client). The snapshot is intentionally NOT restored — it
     * is latest-wins and the next tick posts a fresh one within ~50 ms.
     */
    private void requeueLosslessEvents(MailboxTake take) {
        synchronized (this.mailboxLock) {
            this.pendingGenerationReady.addAll(take.generationReady());
            this.pendingRemovals.addAll(take.removals());
            this.pendingInvalidations.addAll(take.invalidations());
            for (var entry : take.dirtyClears().entrySet()) {
                this.pendingDirtyClears.merge(entry.getKey(), entry.getValue(),
                        (existing, taken) -> { existing.addAll(taken); return existing; });
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

        boolean periodicDue = ++this.saveCounter >= SAVE_INTERVAL_CYCLES;
        boolean invalidationDue = this.invalidationDirty && --this.invalidationCountdown <= 0;
        if (this.dataDir != null && (periodicDue || invalidationDue)) {
            this.saveCounter = 0;
            this.invalidationDirty = false; // a save flushes any pending invalidation too
            var cacheSnapshot = this.timestampCache.snapshotForSave();
            try {
                this.saveExecutor.execute(() -> cacheSnapshot.save(this.dataDir));
            } catch (RejectedExecutionException e) {
                // Shutdown already called saveExecutor.shutdown() while this processing thread
                // was still finishing a cycle (it outlived the join timeout). The final save is
                // handled there; swallow rather than let this surface as a spurious ERROR from
                // the processingLoop catch — the state is already being persisted on the way out.
                LSSLogger.debug("Skipped periodic timestamp cache save — save executor is shutting down");
            }
        }
    }

    // ---- Phase 1: Apply lossless events ----

    private void applyEvents(MailboxTake take) {
        for (var inv : take.invalidations()) {
            int removed = this.timestampCache.invalidate(inv.dimension(), inv.positions());
            if (removed > 0 && !this.invalidationDirty) {
                this.invalidationDirty = true;
                this.invalidationCountdown = INVALIDATE_SAVE_MAX_CYCLES;
            }
            // A disk read OR a generation outcome in flight for an invalidated position may
            // carry PRE-edit bytes; delivering it later in this same cycle (or a following one)
            // must not re-stamp the cache or re-mark diskReadDone, or the client's dirty
            // re-request draws a false up_to_date and pre-edit terrain seals against both
            // healing ladders. Disk reads are tracked via their dedup group; generation does
            // not dedup, so it has its own per-player in-flight oracle (markGenerationStale).
            for (long packed : inv.positions()) {
                if (this.dedupTracker.hasGroup(packed, inv.dimension())) {
                    this.invalidatedInFlight
                            .computeIfAbsent(inv.dimension(), k -> new LongOpenHashSet())
                            .add(packed);
                }
            }
            markGenerationStale(inv.dimension(), inv.positions());
        }

        // Player removals (disconnect or dimension change): release the player's dedup groups
        // and the attached players' pending entries, and drop its generation in-flight tracking
        // (an escalated generation abandoned before its outcome drains would otherwise leak).
        // The removed state itself is simply dropped — its slot counts die with it.
        for (UUID removed : take.removals()) {
            cleanupDedupGroups(this.dedupTracker.removePlayer(removed));
            removeGenerationTracking(removed);
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
            // The read backing this group will never deliver — drop its stale-guard entry.
            consumeInvalidatedInFlight(rg.group().dimension(), rg.packed());
        }
    }

    /** Marks positions whose in-flight disk read was overtaken by a dirty invalidation
     *  (processing-thread only; entries consumed at delivery or with their dedup group). */
    private final Map<String, LongOpenHashSet> invalidatedInFlight = new HashMap<>();

    private boolean consumeInvalidatedInFlight(String dimension, long packed) {
        var set = this.invalidatedInFlight.get(dimension);
        if (set == null || !set.remove(packed)) return false;
        if (set.isEmpty()) this.invalidatedInFlight.remove(dimension);
        return true;
    }

    /** Positions with a generation ticket admitted (via disk-not-found escalation) but whose
     *  outcome has not yet drained, keyed by player then dimension. Generation does NOT dedup,
     *  so — unlike the disk path, which tracks in-flight reads through their dedup group — this
     *  is generation's own in-flight oracle. Keyed by PLAYER so {@link #removeGenerationTracking}
     *  can sweep it on removal (disconnect / dimension change) exactly as {@link #cleanupDedupGroups}
     *  sweeps the disk path's {@link #invalidatedInFlight}: a generation abandoned before its
     *  outcome drains therefore cannot leak. Processing-thread only. */
    private final Map<UUID, Map<String, LongOpenHashSet>> generationInFlight = new HashMap<>();

    /** Subset of {@link #generationInFlight} positions overtaken by a dirty invalidation before
     *  their outcome drained, same keying. The generation twin of {@link #invalidatedInFlight}:
     *  a tainted outcome carries PRE-edit terrain, so it must not mark diskReadDone or re-stamp
     *  the timestamp cache. Per-player, so two players generating the same column taint
     *  independently and neither pins the other's flag. Processing-thread only. */
    private final Map<UUID, Map<String, LongOpenHashSet>> generationStale = new HashMap<>();

    /** Record an admitted generation as in-flight. Package-private so the router's direct
     *  generation path (no disk reader) registers it too — both gen-ticket producers must, or
     *  an overtaking edit on the unregistered path re-stamps pre-edit terrain as up_to_date. */
    void addGenerationInFlight(UUID player, String dimension, long packed) {
        this.generationInFlight
                .computeIfAbsent(player, k -> new HashMap<>())
                .computeIfAbsent(dimension, k -> new LongOpenHashSet())
                .add(packed);
    }

    /** Taint every in-flight generation among {@code positions}: its buffered outcome predates
     *  the edit, so at delivery it must skip the stamp + diskReadDone and re-resolve. Iterates
     *  only players that currently have in-flight generations (usually none), so a
     *  no-generation invalidation batch costs a single map-empty check. */
    private void markGenerationStale(String dimension, long[] positions) {
        if (this.generationInFlight.isEmpty()) return;
        LongOpenHashSet invalidated = null; // built lazily, only if some player generates here
        for (var entry : this.generationInFlight.entrySet()) {
            var inFlight = entry.getValue().get(dimension);
            if (inFlight == null || inFlight.isEmpty()) continue;
            if (invalidated == null) invalidated = new LongOpenHashSet(positions);
            for (long packed : inFlight) {
                if (invalidated.contains(packed)) {
                    this.generationStale
                            .computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                            .computeIfAbsent(dimension, k -> new LongOpenHashSet())
                            .add(packed);
                }
            }
        }
    }

    /** Retire one player's drained generation outcome and report whether an edit tainted it
     *  while it was buffered. Called for every drained outcome; a generation abandoned before
     *  its outcome drains (the player left) is retired instead by
     *  {@link #removeGenerationTracking} on the removal event, so nothing leaks. */
    private boolean consumeGenerationInFlight(UUID player, String dimension, long packed) {
        boolean stale = false;
        var staleDims = this.generationStale.get(player);
        if (staleDims != null) {
            var staleSet = staleDims.get(dimension);
            if (staleSet != null && staleSet.remove(packed)) {
                stale = true;
                if (staleSet.isEmpty()) staleDims.remove(dimension);
                if (staleDims.isEmpty()) this.generationStale.remove(player);
            }
        }
        var inFlightDims = this.generationInFlight.get(player);
        if (inFlightDims != null) {
            var inFlight = inFlightDims.get(dimension);
            if (inFlight != null) {
                inFlight.remove(packed);
                if (inFlight.isEmpty()) inFlightDims.remove(dimension);
                if (inFlightDims.isEmpty()) this.generationInFlight.remove(player);
            }
        }
        return stale;
    }

    /** Drop a departed player's generation tracking (disconnect / dimension change) so a
     *  generation abandoned before its outcome drained cannot leak — the generation analogue of
     *  {@link #cleanupDedupGroups} reclaiming the disk path's {@link #invalidatedInFlight}. */
    private void removeGenerationTracking(UUID player) {
        this.generationInFlight.remove(player);
        this.generationStale.remove(player);
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

                // A dirty invalidation overtook this read while it was in flight: the bytes
                // may predate the edit. Deliver the column (better than nothing), but leave
                // diskReadDone and the timestamp cache unset so the client's dirty
                // re-request re-resolves from disk instead of drawing a false up_to_date.
                boolean staleAgainstEdit = consumeInvalidatedInFlight(dimension, packed);

                deliverDiskResult(playerUuid, state, result, result.submissionOrder(), dimension,
                        staleAgainstEdit);

                if (!staleAgainstEdit && !result.saturated() && !result.notFound()) {
                    // Store timestamp so reconnecting clients get up-to-date responses
                    this.timestampCache.put(result.dimension(), packed, result.columnTimestamp(), this.cycleNow);
                } else if (result.notFound()) {
                    // Disk says the chunk no longer exists (region trimmed/deleted outside
                    // MC). A stale cached stamp would answer up_to_date to data-claiming
                    // clients forever — ghost terrain for a chunk the server cannot serve.
                    int removed = this.timestampCache.invalidate(result.dimension(), new long[]{packed});
                    if (removed > 0 && !this.invalidationDirty) {
                        this.invalidationDirty = true;
                        this.invalidationCountdown = INVALIDATE_SAVE_MAX_CYCLES;
                    }
                }

                // Dispatch the same result to attached players in the dedup group
                var group = this.dedupTracker.removeGroup(packed, dimension);
                if (group != null) {
                    for (var attachment : group.attached()) {
                        var attachedState = this.players.get(attachment.playerUuid());
                        if (attachedState == null) continue;
                        deliverDiskResult(attachment.playerUuid(), attachedState, result,
                                attachment.submissionOrder(), dimension, staleAgainstEdit);
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
                                    long submissionOrder, String dimension,
                                    boolean staleAgainstEdit) {
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
            if (!staleAgainstEdit) {
                state.markDiskReadDone(cx, cz);
            }
            boolean allAir = result.sectionBytes() == null;
            boolean sent = !allAir
                    && buildAndEnqueueColumnPayload(state, cx, cz, result.dimension(),
                            result.columnTimestamp(), submissionOrder,
                            result.sectionBytes(), result.estimatedBytes());
            if (!sent) {
                // All-air chunk (no visible sections): a resync client (claimsData) may hold
                // stale content here, so send an authoritative clearing 0-section column; a
                // client with nothing (first serve) gets a cheap up_to_date. An enqueue
                // REJECTION (oversized column / oversized dimension id) is NOT all-air: the
                // server knows real content exists, so a clear would erase the client's
                // stale-but-real terrain and seal the fabricated air — the terminal answer is
                // up_to_date so the client keeps what it has.
                boolean claimsData = pending != null && pending.claimsData();
                if (!(allAir && claimsData && sendEmptiedColumn(state, cx, cz, result.dimension(),
                        result.columnTimestamp(), submissionOrder))) {
                    this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
                }
            }
        }
        this.ctx.diagnostics().incrementDiskDrained();
    }

    // A VoxelColumn body carrying zero sections (one varint 0). Sent to a data-claiming client
    // for an all-air resolution so it clears ghost terrain by ingesting air for every section.
    private static final byte[] ZERO_SECTION_COLUMN = new byte[]{0x00};

    /**
     * Authoritative all-air serve: enqueue a 0-section {@code VoxelColumn} (carrying the server
     * columnTimestamp) so a data-claiming client clears the column. Returns false if the payload
     * could not be enqueued (caller falls back to up_to_date).
     */
    boolean sendEmptiedColumn(PlayerState state, int cx, int cz, String dimension,
                              long columnTimestamp, long submissionOrder) {
        int est = ZERO_SECTION_COLUMN.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        return buildAndEnqueueColumnPayload(state, cx, cz, dimension, columnTimestamp,
                submissionOrder, ZERO_SECTION_COLUMN, est);
    }

    /**
     * Stamp an all-air in-memory resolution (the data path stamps inside
     * {@link #enqueueLoadedColumn}; all-air skips it) so a client that received the clear or
     * up_to_date converges to a cheap warm up_to_date on future resyncs instead of
     * re-resolving — and re-clearing — the same void column every session.
     */
    void recordAllAirResolution(String dimension, long packed, long columnTimestamp) {
        this.timestampCache.put(dimension, packed, columnTimestamp, this.cycleNow);
    }

    /** Disk-first fallback: if the original request was GENERATION and generation is available,
     *  queue a generation ticket; otherwise send ColumnNotGenerated. */
    private void handleDiskNotFound(UUID playerUuid, PlayerState state, long packed,
                                     int cx, int cz, PendingRequest pending, String dimension) {
        if (pending != null && pending.type() == RequestType.GENERATION && this.generationAvailable) {
            if (state.tryAdmit(new PendingRequest(cx, cz, RequestType.GENERATION, SlotType.GENERATION, pending.claimsData()))) {
                addGenerationInFlight(playerUuid, dimension, packed);
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
            int cx = entry.cx();
            int cz = entry.cz();
            long packed = PositionUtil.packPosition(cx, cz);
            // Retire the in-flight generation record for EVERY drained outcome (delivered,
            // all-air, not-generated, player gone, dimension changed); an outcome that never
            // drains because the player left is retired by removeGenerationTracking on the
            // removal event instead. Capture whether a dirty edit overtook this buffered outcome.
            boolean genStale = consumeGenerationInFlight(entry.playerUuid(), entry.dimension(), packed);

            var state = this.players.get(entry.playerUuid());
            if (state == null) continue;
            // A dimension change replaces the player's state (removePlayer + registerPlayer,
            // same UUID). Outcomes harvested for the old session carry the old dimension —
            // skip them instead of poisoning the fresh state (their pending died with it).
            String current = snapshot.playerDimensions().get(entry.playerUuid());
            if (current == null || !current.equals(entry.dimension())) continue;
            var pending = state.removePendingByPosition(cx, cz);

            if (entry.columnData() == null) {
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(entry.playerUuid(), packed, state));
            } else {
                // A dirty invalidation overtook this generation while its outcome was buffered:
                // the serialized bytes predate the edit. Deliver the column (better than
                // nothing), but leave diskReadDone and the timestamp cache unset so the client's
                // dirty re-request re-resolves instead of drawing a false up_to_date — the
                // generation twin of the stale guard in drainDiskResultsForAllPlayers.
                if (!genStale) {
                    state.markDiskReadDone(cx, cz);
                }
                byte[] genSections = entry.columnData().serializedSections();
                boolean allAir = genSections == null || genSections.length == 0;
                boolean sent = !allAir && this.enqueueLoadedColumn(state, entry.columnData(),
                        entry.columnTimestamp(), entry.submissionOrder(), entry.dimension(), genStale);
                if (!sent) {
                    if (allAir && !genStale) {
                        // Stamp so future resyncs at this timestamp converge to a cheap
                        // up_to_date instead of re-resolving every session (the data path
                        // stamps inside enqueueLoadedColumn; all-air skips it). A stale outcome
                        // skips the stamp so the edited column re-resolves.
                        this.timestampCache.put(entry.dimension(), packed,
                                entry.columnTimestamp(), this.cycleNow);
                    }
                    // Generated all-air: a sync resync escalated to generation (claimsData) may
                    // hold stale content — send a clearing column; a pure gen request (ts=0)
                    // gets up_to_date. An enqueue REJECTION (oversized) is NOT all-air — see
                    // deliverDiskResult: clearing would erase real terrain; answer up_to_date.
                    boolean claimsData = pending != null && pending.claimsData();
                    if (!(allAir && claimsData && sendEmptiedColumn(state, cx, cz, entry.dimension(),
                            entry.columnTimestamp(), entry.submissionOrder()))) {
                        this.sendActions.add(new SendAction.ColumnUpToDate(entry.playerUuid(), packed, state));
                    }
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

    /**
     * Read-only internals view for the dev-only soak/benchmark exporters (dedup group
     * count, mailbox depth, timestamp-cache occupancy/evictions). Harness seam only —
     * production code never calls this. The dedup tracker is processing-thread-owned, so
     * its size is read best-effort: a racing structural change reports {@code -1} instead
     * of throwing into the caller's tick.
     */
    public HarnessInternals getHarnessInternals() {
        int dedupGroups;
        try {
            dedupGroups = this.dedupTracker.size();
        } catch (java.util.ConcurrentModificationException e) {
            dedupGroups = -1;
        }
        int mailboxDepth;
        synchronized (this.mailboxLock) {
            mailboxDepth = (this.pendingSnapshot != null ? 1 : 0)
                    + this.pendingGenerationReady.size()
                    + this.pendingRemovals.size()
                    + this.pendingInvalidations.size()
                    + this.pendingDirtyClears.size();
        }
        return new HarnessInternals(dedupGroups, mailboxDepth,
                this.timestampCache.getEvictionCount(), this.timestampCache.sizesPerDimension());
    }

    /** @see #getHarnessInternals() */
    public record HarnessInternals(int dedupGroups, int mailboxDepth, long tscacheEvictions,
                                   Map<String, Integer> tscacheSizePerDimension) {}

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
                this.saveExecutor.submit(() -> snapshot.save(this.dataDir))
                        .get(SHUTDOWN_JOIN_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LSSLogger.warn("Timestamp cache final save did not complete cleanly", e);
            }
        }

        // Stop the save thread (graceful: any queued periodic save still runs) so it cannot
        // outlive the server run and pin the Paper plugin classloader across a /reload.
        this.saveExecutor.shutdown();
    }
}
