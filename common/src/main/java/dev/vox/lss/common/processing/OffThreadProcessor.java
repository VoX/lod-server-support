package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.Brand;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
    public record GenerationTicketRequest(UUID playerUuid, RequestRegistration registration, int cx, int cz, String dimension,
                                           long submissionOrder) {
        public GenerationTicketRequest {
            java.util.Objects.requireNonNull(registration, "registration");
        }
    }

    private record PlayerRemoval(UUID uuid, RequestRegistration registration) {}

    private record TimestampInvalidation(String dimension, long[] positions,
                                         Runnable onApplied) {}

    /** Everything the processing thread takes from the mailbox in one cycle. */
    private record MailboxTake(TickSnapshot snapshot,
                               List<TickSnapshot.GenerationReadyData> generationReady,
                               List<PlayerRemoval> removals,
                               List<TimestampInvalidation> invalidations,
                               Map<UUID, ArrayList<long[]>> dirtyClears) {}

    // Mailbox (guarded by mailboxLock). Snapshot: latest-wins. Event buffers: lossless,
    // swapped out whole by the processing thread. Producers are main-thread-only.
    private final Object mailboxLock = new Object();
    private final LoadedProbeGuard loadedProbeGuard = new LoadedProbeGuard();
    private TickSnapshot pendingSnapshot;
    private ArrayList<TickSnapshot.GenerationReadyData> pendingGenerationReady = new ArrayList<>();
    private ArrayList<PlayerRemoval> pendingRemovals = new ArrayList<>();
    private ArrayList<TimestampInvalidation> pendingInvalidations = new ArrayList<>();
    private HashMap<UUID, ArrayList<long[]>> pendingDirtyClears = new HashMap<>();

    // Processing thread → main thread (thread-safe output)
    private final ConcurrentLinkedQueue<SendAction> sendActions = new ConcurrentLinkedQueue<>();
    /** Stamped-up_to_date predicate (stamped-up-to-date-plan.md §9.1/§9.2); volatile —
     *  wired by the platform after construction, read on the processing thread. */
    private volatile UpToDateStampSource stampSource = UpToDateStampSource.NEVER;
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
    // LOD-store counters (docs/planning/lod-store-implementation-plan.md). The zero
    // instance exists unconditionally — all-zero while lodStore=off — so the
    // diagnostics/exporter schema is identical across the kill-switch A/B arms; with a
    // store attached, the STORE's own instance (shared with the reader rung) is served.
    private final dev.vox.lss.common.store.LodStoreDiagnostics storeDiagnostics =
            new dev.vox.lss.common.store.LodStoreDiagnostics();
    // The LOD store (null while lodStore=off). Attached once at service init, before
    // start(). The processor owns the DELIVERY-PATH side of the store contract: deposits
    // AFTER the stale guard, the invalidation fan-out, and the ghost-guard delete.
    private volatile dev.vox.lss.common.store.LodStoreService store;
    // The wire codec for compressed column shipping (protocol 19). Null = raw for
    // everyone (useCompressedColumns off, or the server-side zstd native probe failed).
    // Attached once at service init before start(); read by the processing thread.
    private volatile dev.vox.lss.common.store.StoreCodec wireCodec;
    // NOTE (Phase 3 review + 4-agent round R2-M2): the dirty->store invalidation
    // fan-out stays ON on BOTH platforms, deliberately. A Fabric gating experiment
    // (save-hook deposits looked like they made the drain invalidation redundant)
    // opened two real holes — a stale in-flight disk read completing after the edit
    // could overwrite the fresh row with a src_stamp the boot sweep can never catch,
    // and a shed hook deposit stranded the stale row for the session — so the fan-out
    // was restored. The final review round then proved the CONVERSE: with the fan-out
    // unconditional, no hook deposit could ever survive it (the same save that
    // deposits also marks; the drain tombstone postdates the deposit), so the hook is
    // now DELETE-only (LSSServerNetworking.onChunkSaveData) and edited columns re-warm
    // through THIS delivery path on the dirty re-serve. Cost: one NBT re-read per
    // edited column per session.
    private final Path dataDir;
    private int evictionCounter;
    private int saveCounter;
    private int consecutiveErrors;
    // Log-sweep hygiene (2026-08-13): persistent-condition error sites aggregate to one
    // line/min — the park-overflow-WARN deletion set the bar for self-healing paths.
    private final dev.vox.lss.common.LogThrottle cycleErrorWarn =
            new dev.vox.lss.common.LogThrottle(60_000);
    private final dev.vox.lss.common.LogThrottle deliveryErrorWarn =
            new dev.vox.lss.common.LogThrottle(60_000);
    private long cycleNow; // cached epochSeconds for current processing cycle
    // Per-cycle phase-completion flags (processing-thread only): a failed cycle re-queues only
    // the lossless events whose phase did NOT complete, so an already-applied phase is never
    // re-run. Re-running a completed phase is NOT idempotent — re-delivering a consumed
    // generation outcome would find its stale mark already consumed and re-stamp pre-edit
    // terrain (false up_to_date), and re-applying a removal could sweep a re-registered
    // same-UUID player's fresh dedup/generation tracking.
    private boolean phase1EventsApplied;
    private boolean generationReadyApplied;
    // Late-drained dirty-clears/invalidations (see applyLateDirtyEvents): stashed so a
    // failed cycle can re-queue them independently of phase1EventsApplied — both flags
    // are true when routing throws, but only the late events may still need re-adding.
    private ArrayList<TimestampInvalidation> lateInvalidations;
    private HashMap<UUID, ArrayList<long[]>> lateDirtyClears;
    private boolean lateEventsApplied = true;

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
    // A bare ThreadPoolExecutor (identical semantics to Executors.newSingleThreadExecutor
    // minus the delegation wrapper) so the coalescing wiring pin can observe the real queue.
    private final ThreadPoolExecutor saveExecutor = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, Brand.shortName() + "-TimestampSave");
        t.setDaemon(true);
        return t;
    });

    // Latest-wins save coalescing (issue #62): due saves publish their snapshot to a
    // single slot instead of enqueueing one closure each — the executor queue stays
    // bounded no matter how slow a save is. Null iff dataDir is null (both call sites
    // sit behind the same dataDir guard).
    private final TimestampSaveScheduler saveScheduler;

    /** Margin the served-set sweep radius adds over the ingress range filter, so a
     *  position can never be swept while still declarable (M3). */
    public static final int SWEEP_RADIUS_MARGIN_CHUNKS = 16;
    /** Sweep radius for the old-signature constructor (test rigs and any future call site
     *  that forgets the 7-arg form): the LARGEST radius any config can produce
     *  (MAX_LOD_DISTANCE + LOD_DISTANCE_BUFFER) + the margin — deliberately fail-SAFE: a
     *  mistaken fallback under-sweeps (keeps more memory than needed) and can never
     *  destroy a still-declarable done-bit under a raised lodDistanceChunks. Production
     *  passes the config-derived radius. */
    private static final int DEFAULT_SWEEP_RADIUS_CHUNKS =
            LSSConstants.MAX_LOD_DISTANCE + LSSConstants.LOD_DISTANCE_BUFFER + SWEEP_RADIUS_MARGIN_CHUNKS;

    // diskReadDone sweep (M3): volatile since v0.11.0 stage C — /lsslod set
    // lodDistanceChunks re-derives it each lifecycle tick as a MONOTONIC MAX
    // (updateSweepRadius). Monotonic-max rationale (SET review, corrected): an
    // under-radius sweep of a still-declarable done-bit is semantically FREE (worst
    // case one redundant re-serve — in-pipeline/grace entries are kept regardless), so
    // the max avoids redundant serves, not corruption; the cost of a too-large radius
    // is only memory already bounded by M3's trim. Do not treat this as a
    // data-integrity constraint.
    private volatile int diskReadDoneSweepRadiusChunks;

    /** Runtime radius re-derivation (v0.11.0 stage C): monotonic max — see the field
     *  comment. Called from the owning service's tick pass with the config-derived
     *  radius; never shrinks within a run. */
    /** Wire the platform's stamped-up_to_date predicate (null resets to NEVER). */
    public void setUpToDateStampSource(UpToDateStampSource source) {
        this.stampSource = source == null ? UpToDateStampSource.NEVER : source;
    }

    public void updateSweepRadius(int derivedRadiusChunks) {
        if (derivedRadiusChunks > this.diskReadDoneSweepRadiusChunks) {
            this.diskReadDoneSweepRadiusChunks = derivedRadiusChunks;
        }
    }

    int sweepRadiusForTest() {
        return this.diskReadDoneSweepRadiusChunks;
    }

    /** Old-signature overload (test rigs) — production passes the config-derived radius. */
    protected OffThreadProcessor(Map<UUID, PlayerState> players,
                                  AbstractChunkDiskReader diskReader, boolean generationAvailable,
                                  Path dataDir, int perDimensionTimestampCacheSizeMB,
                                  int missMemoTtlSeconds) {
        this(players, diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds, DEFAULT_SWEEP_RADIUS_CHUNKS);
    }

    // this-escape is benign: the Thread is created here but only started later via start()
    // (post-construction), and the router's back-reference is only used from the processing
    // loop after start(), so no partially-initialized subclass state is touched before
    // construction completes.
    @SuppressWarnings("this-escape")
    protected OffThreadProcessor(Map<UUID, PlayerState> players,
                                  AbstractChunkDiskReader diskReader, boolean generationAvailable,
                                  Path dataDir, int perDimensionTimestampCacheSizeMB,
                                  int missMemoTtlSeconds, int diskReadDoneSweepRadiusChunks) {
        this.diskReadDoneSweepRadiusChunks = diskReadDoneSweepRadiusChunks;
        this.players = players;
        this.diskReader = diskReader;
        this.generationAvailable = generationAvailable;
        // normalize(): both platforms derive this from getWorldPath(LevelResource.ROOT), which
        // ends in "/." and otherwise prints as "./world/./data/..." in every cache log line (#32)
        this.dataDir = dataDir == null ? null : dataDir.normalize();
        this.saveScheduler = this.dataDir == null ? null
                : new TimestampSaveScheduler(this.saveExecutor, this.dataDir);
        this.timestampCache = new ColumnTimestampCache(
                perDimensionTimestampCacheSizeMB * 1024L * 1024L,
                java.util.concurrent.TimeUnit.SECONDS.toNanos(
                        Math.max(0, missMemoTtlSeconds)));
        if (this.dataDir != null) {
            this.timestampCache.load(this.dataDir);
        }
        // The stamp source is a delegating lambda so the platform can wire the real
        // predicate AFTER construction (setUpToDateStampSource); until then NEVER
        // keeps every up_to_date bit-identical to the unstamped behavior.
        this.ctx = new ProcessingContext(this.sendActions, this.generationTicketRequests,
                new ProcessingDiagnostics(), new SequenceCounter(),
                (player, dim, packed) -> this.stampSource.stampSecond(player, dim, packed));
        this.requestRouter = new IncomingRequestRouter<>(this, this.players, this.timestampCache,
                this.dedupTracker, diskReader != null, generationAvailable, this.ctx);
        this.processingThread = new Thread(this::processingLoop, Brand.shortName() + " Processing Thread");
        this.processingThread.setDaemon(true);
        this.processingThread.setPriority(Thread.NORM_PRIORITY - 1);
        this.processingThread.setUncaughtExceptionHandler((th, ex) ->
                LSSLogger.error(Brand.shortName() + " processing thread died unexpectedly", ex));
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
    public void notifyPlayerRemoved(UUID uuid, RequestRegistration registration) {
        registration.retire();
        synchronized (this.mailboxLock) {
            this.pendingRemovals.add(new PlayerRemoval(uuid, registration));
        }
    }

    /** Capture an invalidation epoch BEFORE native loaded-column serialization. */
    public LoadedProbeGuard.Capture captureLoadedProbe(String dimension,long position,RequestRegistration registration) {
        return this.loadedProbeGuard.capture(dimension,position,registration);
    }

    boolean currentLoadedProbe(LoadedColumnData data,String dimension,long position,PlayerState state) {
        return this.players.get(state.getPlayerUUID())==state
                && this.loadedProbeGuard.current(data,dimension,position,state.registration());
    }

    /** Queue timestamp invalidation for dirty positions. */
    public void invalidateTimestamps(String dimension, long[] positions) {
        invalidateTimestamps(dimension, positions, null);
    }

    /** Callback form: {@code onApplied} runs on the processing thread RIGHT AFTER the
     *  tscache/store invalidation lands — the dirty broadcasters pass the tracker's
     *  {@code confirmInvalidated} so the stamping guard's second phase releases
     *  exactly when the stale evidence is gone (plan §9.2, the 3-Opus fold). */
    public void invalidateTimestamps(String dimension, long[] positions, Runnable onApplied) {
        // ONCE-guard (final panel): a mid-list throw re-queues the whole batch list
        // (requeueLosslessEvents — re-invalidating IS idempotent), so an already-
        // applied batch can replay. Its confirm must not fire twice: the tracker's
        // invalidating guard now COUNTS outstanding batches per position, and a
        // double confirm would release a later batch's guard early.
        Runnable once;
        if (onApplied == null) {
            once = null;
        } else {
            var ran = new java.util.concurrent.atomic.AtomicBoolean();
            once = () -> {
                if (ran.compareAndSet(false, true)) {
                    onApplied.run();
                }
            };
        }
        synchronized (this.mailboxLock) {
            this.loadedProbeGuard.invalidate(dimension, positions);
            this.pendingInvalidations.add(new TimestampInvalidation(dimension, positions, once));
        }
    }

    /** Queue positions for clearing from a player's diskReadDone set (dirty re-send support). */
    public void clearDiskReadDone(UUID playerUuid, long[] positions) {
        synchronized (this.mailboxLock) {
            this.pendingDirtyClears.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(positions);
        }
    }

    /**
     * Feed a generation failure for a request whose ticket could not be submitted.
     * Delivered like any other generation outcome. {@code transientFailure = true} for
     * transient pressure (capacity rejection, removed player) — dropped silently and
     * counted superseded, so the client's re-declaration retries it; {@code false} only
     * for permanent unservability, which answers ColumnNotGenerated (a session-permanent
     * "stop asking" on the client).
     */
    public void feedGenerationFailure(UUID playerUuid, RequestRegistration registration, int cx, int cz, String dimension,
                                      long submissionOrder, boolean transientFailure) {
        synchronized (this.mailboxLock) {
            // A transient feed is always a capacity/removed-player reject: the generation was
            // never submitted, so the disk miss behind it counts into law A5's miss_dropped
            // term (stamped on the outcome; the counter increments on the processing thread,
            // which is the diagnostics class's single writer).
            this.pendingGenerationReady.add(new TickSnapshot.GenerationReadyData(
                    playerUuid, registration, cx, cz, dimension, null, 0L, submissionOrder,
                    transientFailure, transientFailure));
        }
    }

    // ---- Main-thread output drains ----

    /** Platform hook that sends one batched-response frame to a player. */
    @FunctionalInterface
    public interface BatchSender<PlayerState> {
        void send(PlayerState state, byte[] responseTypes, long[] packedPositions, int count) throws Exception;
    }

    /** Platform hook for stamped-up_to_date frames (stamped-up-to-date-plan.md §3):
     *  {@code eligible} gates by the summary-request mark + CURRENT dialect (the
     *  request is the capability declaration — released clients never receive);
     *  {@code send} ships one encoded frame, fire-and-forget (loss = today's
     *  behavior for those positions; they re-stamp on the next rejoin — deliberately
     *  NO retention, unlike the once-per-session summary frame). The platform counts
     *  frames/entries/bytes on send success. */
    public interface StampsSink<PlayerState> {
        boolean eligible(UUID playerUuid);
        void send(PlayerState state, byte[] frame, int entryCount) throws Exception;
    }

    /** Per-(player, dimension) stamp accumulator for one drain pass. */
    private static final class StampBatch {
        long[] positions = new long[64];
        long[] seconds = new long[64];
        int count;
        void add(long pos, long second) {
            if (this.count == this.positions.length) {
                this.positions = java.util.Arrays.copyOf(this.positions, this.count * 2);
                this.seconds = java.util.Arrays.copyOf(this.seconds, this.count * 2);
            }
            this.positions[this.count] = pos;
            this.seconds[this.count] = second;
            this.count++;
        }
    }

    /** Back-compat overload (tests, callers without the stamps lane). */
    public void drainSendActions(BatchSender<PlayerState> sender) {
        drainSendActions(sender, null);
    }

    /**
     * Drain completed send actions and flush them as one batched response per player
     * (called by the main thread each tick), plus the stamped-up_to_date frames for
     * eligible sessions when a {@code stampsSink} is wired.
     */
    public void drainSendActions(BatchSender<PlayerState> sender, StampsSink<PlayerState> stampsSink) {
        this.sendActionBatcher.clear();
        // (state, dimension) -> stamps; allocated lazily — empty on every tick that
        // produced no stamped verification (all tests, harness runs, cold joins).
        // Keyed by the identity-checked STATE (3-Opus fold), so the flush cannot
        // deliver into a session that re-registered mid-drain.
        java.util.Map<PlayerState, java.util.Map<String, StampBatch>> stampBatches = null;

        SendAction action;
        while ((action = this.sendActions.poll()) != null) {
            var state = this.players.get(action.playerUuid());
            // Identity check: a dimension change re-registers the same UUID with a fresh
            // state — actions produced for the old session must not reach the new one.
            if (state == null || state != action.producerState()
                    || !state.hasCompletedHandshake()) continue;
            // Probe-suppress choke point (review P1): an up_to_date answer means every
            // re-declaration of the position resolves at the router's earlier rungs, so a
            // probe for it is guaranteed-unused — up_to_date-resolved resync positions
            // (never in enqueuedColumns) used to re-serialize every tick until the next
            // declaration. ONE site covers all five producer paths (router timestamp/
            // probe/duplicate rungs + the disk and generation all-air terminals).
            if (action instanceof SendAction.ColumnUpToDate utd) {
                state.stampProbeSuppress(action.packedPosition());
                // Stamped verification (plan §9.1): the claim's dimension travels WITH
                // the action (never resolved at flush — overworld/End coords overlap),
                // and the identity check above already bound it to the live session.
                if (stampsSink != null && utd.stampSecond() > 0 && utd.stampDimension() != null
                        && stampsSink.eligible(action.playerUuid())) {
                    if (stampBatches == null) stampBatches = new java.util.HashMap<>();
                    stampBatches.computeIfAbsent(state, u -> new java.util.HashMap<>())
                            .computeIfAbsent(utd.stampDimension(), d -> new StampBatch())
                            .add(action.packedPosition(), utd.stampSecond());
                }
            }
            this.sendActionBatcher.add(action.playerUuid(), action.responseType(), action.packedPosition());
        }

        if (stampBatches != null) {
            // Stamps flush BEFORE the batch responses, deliberately (3-Opus fold, both
            // lenses): FIFO delivery then applies the ratchet BEFORE the same tick's
            // onUpToDate consumes retry/dirty marks, so the client-side mark-skip armor
            // is live for the same-tick pair — and a contained batch-send failure then
            // leaves only a strictly-weaker-claim shape. Contained as a whole: an Error
            // out of the stamps lane must never eat the tick's batch responses.
            try {
                flushStampBatches(stampBatches, stampsSink);
            } catch (Throwable t) {
                long n = this.deliveryErrorWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.error("Column-stamps flush failed — contained (" + n
                            + " failure(s) since the last report)", t);
                }
            }
        }

        if (this.sendActionBatcher.isEmpty()) return;

        this.sendActionBatcher.forEach((uuid, types, positions, count) -> {
            var state = this.players.get(uuid);
            if (state == null || !state.hasCompletedHandshake()) return;
            try {
                sender.send(state, types, positions, count);
            } catch (Exception e) {
                long n = this.deliveryErrorWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.error("Failed to send batch response to " + state.getPlayerName()
                            + " (" + n + " delivery failure(s) since the last report)", e);
                }
            }
        });
    }

    /** Encode + ship the tick's stamped verifications, SPLIT at the wire cap (plan
     *  §9.5 — never truncated: silent tail truncation would systematically drop the
     *  warm-rejoin bulk this exists for). Send failures are contained and dropped —
     *  the designed loss-tolerant case. */
    private void flushStampBatches(java.util.Map<PlayerState, java.util.Map<String, StampBatch>> batches,
                                   StampsSink<PlayerState> sink) {
        batches.forEach((state, byDimension) -> {
            // Identity re-check (belt — the accumulation already identity-checked):
            // the captured state must still be the live session.
            if (this.players.get(state.getPlayerUUID()) != state
                    || !state.hasCompletedHandshake()) {
                return;
            }
            byDimension.forEach((dimension, batch) -> {
                int offset = 0;
                while (offset < batch.count) {
                    int n = Math.min(
                            dev.vox.lss.common.region.ColumnStampsWire.MAX_STAMP_ENTRIES,
                            batch.count - offset);
                    try {
                        byte[] frame = dev.vox.lss.common.region.ColumnStampsWire.encode(
                                dimension,
                                java.util.Arrays.copyOfRange(batch.positions, offset, offset + n),
                                java.util.Arrays.copyOfRange(batch.seconds, offset, offset + n),
                                n);
                        sink.send(state, frame, n);
                    } catch (Throwable e) {
                        long c = this.deliveryErrorWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                        if (c > 0) {
                            LSSLogger.error("Failed to send column-stamps frame ("
                                    + c + " delivery failure(s) since the last report)", e);
                        }
                    }
                    offset += n;
                }
            });
        });
    }

    /** TEST-ONLY (same package): enqueue a send action directly — the drain's
     *  identity/probe-suppress choke-point pins need actions of both types without
     *  driving a full disk/generation pipeline. */
    void enqueueSendActionForTest(SendAction action) {
        this.sendActions.add(action);
    }

    /** Whether the persisted timestamp cache loaded EMPTY at construction (the review-P3
     *  skip gate's third conjunct — read once by the service constructor, before the
     *  processing thread starts, so no confinement issue). */
    public boolean isTimestampCacheEmpty() {
        return this.timestampCache.size() == 0;
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
     * of leaking them. {@code clientTimestamp} is the requesting client's declared stamp
     * (region-summary-sync-plan.md P1): the reader's header freshness rung consults it
     * before any region IO; {@code <= 0} (acquisition) leaves the rung inert.
     */
    protected abstract boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension,
                                            int cx, int cz,
                                            long submissionOrder, long clientTimestamp);

    /** True when the reader pool can accept a submit (see AbstractChunkDiskReader#hasHeadroom).
     *  False when no reader is configured. */
    boolean hasDiskHeadroom() {
        return this.diskReader != null && this.diskReader.hasHeadroom();
    }

    /** True when the disk-read gate is saturated (permits exhausted AND the park plus
     *  permit-less in-flight work would fill it — see AbstractChunkDiskReader#gateSaturated,
     *  Amendment 2). The router retains the entry and stops the player's pass. Null reader
     *  (no disk serving) is never saturated — mirroring {@link #hasDiskHeadroom}'s guard. */
    boolean gateSaturated() {
        return this.diskReader != null && this.diskReader.gateSaturated();
    }

    /** Books one gate-stopped router pass on the reader's diagnostics (no-op with no
     *  reader — unreachable in practice, since {@link #gateSaturated} gates the call). */
    void recordGateStop() {
        if (this.diskReader != null) this.diskReader.recordGateStop();
    }

    /**
     * Store timestamp and enqueue pre-serialized column data as a payload.
     */
    protected boolean enqueueLoadedColumn(PlayerState state, LoadedColumnData column,
                                          long columnTimestamp,
                                          long submissionOrder,
                                          String dimension,
                                          byte source) {
        return enqueueLoadedColumn(state, column, columnTimestamp, submissionOrder, dimension, false, source);
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
                                          boolean staleAgainstEdit,
                                          byte source) {
        // One holder per call: the probe path has no dedup group, so two players wanting
        // the same loaded chunk compress the same bytes twice — accepted (plan §0.4).
        return enqueueLoadedColumn(state, column, columnTimestamp, submissionOrder,
                dimension, staleAgainstEdit, source, null);
    }

    /** As above with a caller-supplied holder (the generation path shares one holder
     *  between its build and its store deposit — plan §3 frame reuse); null builds one. */
    protected boolean enqueueLoadedColumn(PlayerState state, LoadedColumnData column,
                                          long columnTimestamp,
                                          long submissionOrder,
                                          String dimension,
                                          boolean staleAgainstEdit,
                                          byte source,
                                          ColumnBytes sharedBytes) {
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
                sharedBytes != null ? sharedBytes
                        : ColumnBytes.ofRaw(this.wireCodec, column.serializedSections()),
                estimatedBytes, source);
    }

    /**
     * Build platform-specific payload from the column-bytes holder and enqueue to the
     * player's ready queue. The holder decides raw-vs-frame per recipient (the platform
     * build consults {@code state.wantsCompressedColumns()}); {@code estimatedBytes}
     * stays RAW-denominated (bandwidth-limiter semantics — design §5). Returns false
     * when the column cannot go on the wire (oversized payload) — every caller then
     * answers up-to-date so the position resolves terminally instead of dropping
     * silently (the old silent drop left diskReadDone set with no response, and the
     * client retried an unserveable position forever).
     */
    protected abstract boolean buildAndEnqueueColumnPayload(PlayerState state, int cx, int cz,
                                                             String dimension,
                                                             long columnTimestamp, long submissionOrder,
                                                             ColumnBytes bytes, int estimatedBytes,
                                                             byte source);

    // ---- Processing loop ----

    private void processingLoop() {
        while (true) {
            MailboxTake take;
            synchronized (this.mailboxLock) {
                while (this.pendingSnapshot == null) {
                    try {
                        this.mailboxLock.wait(SNAPSHOT_POLL_MS);
                    } catch (InterruptedException e) {
                        // shutdown() posts the sentinel BEFORE interrupting, and JLS 17.2.4
                        // lets a notified-and-interrupted wait() throw anyway — bailing out
                        // here would skip the sentinel branch's invalidation flush and let
                        // the final save persist pre-edit stamps (false up_to_date across
                        // the restart). With a snapshot present, fall through and process
                        // it normally; with none, flush queued invalidations and exit.
                        if (this.pendingSnapshot == null) {
                            flushPendingInvalidationsOnExit();
                            return;
                        }
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
                // across the restart. The store fan-out rides along: moot for the memory
                // tier, load-bearing once the Phase 2 disk store persists rows.
                for (var inv : take.invalidations()) {
                    if (this.store != null) {
                        this.store.invalidate(inv.dimension(), inv.positions());
                    }
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
                // Throttled (log-sweep top finding): a persistent throw here repeats at
                // cycle rate (~20 Hz, ~1 Hz once the backoff engages) FOREVER — one
                // stack per minute carries the same diagnostic value without the flood.
                long cycleErrs = this.cycleErrorWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (cycleErrs > 0) {
                    LSSLogger.error("Error in processing cycle (" + cycleErrs
                            + " failed cycle(s) since the last report)", t);
                }
                // The take destructively drained the lossless event buffers; discarding them
                // on a failed cycle permanently loses player removals (leaked slots/dedup
                // groups), generation outcomes (stranded pending generations), and
                // dirty-clears/invalidations (up_to_date answered for stale content). Re-queue
                // only the events whose phase did NOT complete (via the phase-completion flags),
                // so a fully-applied phase is never re-run — re-applying is NOT idempotent (a
                // re-delivered generation outcome finds its stale mark consumed and re-stamps
                // pre-edit terrain; a re-applied removal can sweep a re-registered player's
                // fresh tracking).
                requeueLosslessEvents(take);
                if (++this.consecutiveErrors >= 10) {
                    // The backoff NOTE rides the throttled line above (the old separate
                    // per-cycle line repeated ~1/s forever once engaged); the sleep is
                    // the mechanism, the log is not.
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        // Shutdown landed during the backoff: requeueLosslessEvents above put
                        // the failed cycle's unapplied invalidations back in the pending
                        // buffer — flush them so the final save can't persist pre-edit stamps.
                        flushPendingInvalidationsOnExit();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Re-inject a failed cycle's not-yet-applied lossless events into the mailbox so the next
     * cycle retries them, gated by the phase-completion flags so a completed phase is never
     * re-run. A completed phase's events would NOT be idempotent on re-application: a
     * re-delivered generation outcome finds its stale mark already consumed and re-stamps
     * pre-edit terrain, and a re-applied removal can sweep a re-registered same-UUID player's
     * fresh dedup/generation tracking. The snapshot is intentionally NOT restored — it is
     * latest-wins and the next tick posts a fresh one within ~50 ms.
     */
    private void requeueLosslessEvents(MailboxTake take) {
        synchronized (this.mailboxLock) {
            if (!this.generationReadyApplied) {
                this.pendingGenerationReady.addAll(take.generationReady());
            }
            if (!this.phase1EventsApplied) {
                this.pendingRemovals.addAll(take.removals());
                this.pendingInvalidations.addAll(take.invalidations());
                for (var entry : take.dirtyClears().entrySet()) {
                    this.pendingDirtyClears.merge(entry.getKey(), entry.getValue(),
                            (existing, taken) -> { existing.addAll(taken); return existing; });
                }
            }
            // The late take (applyLateDirtyEvents) is gated by its own flag: unlike the
            // phase-1 events, re-APPLYING these is idempotent (re-clearing a cleared
            // done-bit and re-invalidating an absent stamp are no-ops), so a half-applied
            // late take is safely re-queued whole.
            if (!this.lateEventsApplied) {
                if (this.lateInvalidations != null) {
                    this.pendingInvalidations.addAll(this.lateInvalidations);
                }
                if (this.lateDirtyClears != null) {
                    for (var entry : this.lateDirtyClears.entrySet()) {
                        this.pendingDirtyClears.merge(entry.getKey(), entry.getValue(),
                                (existing, taken) -> { existing.addAll(taken); return existing; });
                    }
                }
            }
        }
    }

    private void processCycle(MailboxTake take) {
        this.cycleNow = LSSConstants.epochSeconds();
        this.ctx.diagnostics().resetTickCounters();
        this.phase1EventsApplied = false;
        this.generationReadyApplied = false;
        this.lateInvalidations = null;
        this.lateDirtyClears = null;
        this.lateEventsApplied = true; // vacuously, until applyLateDirtyEvents takes something

        applyEvents(take);
        this.phase1EventsApplied = true; // invalidations, removals, dirty-clears fully applied
        drainDiskResultsForAllPlayers(take.snapshot());
        processGenerationReady(take.generationReady(), take.snapshot());
        this.generationReadyApplied = true; // every generation outcome consumed (per-outcome
        // containment inside means the phase itself no longer throws mid-list; the flag
        // stays as a backstop for a throw outside the contained region)
        beforeRouteHook();
        applyLateDirtyEvents();
        routeIncomingRequests(take.snapshot());

        if (++this.evictionCounter >= EVICTION_INTERVAL_CYCLES) {
            this.evictionCounter = 0;
            int evicted = this.timestampCache.evictIfOversized();
            if (evicted > 0 && LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Evicted " + evicted + " oversized timestamp cache entries (" + this.timestampCache.size() + " remaining)");
            }
            sweepRegisteredPlayerServedSets();
        }

        boolean periodicDue = ++this.saveCounter >= SAVE_INTERVAL_CYCLES;
        boolean invalidationDue = this.invalidationDirty && --this.invalidationCountdown <= 0;
        if (this.dataDir != null && (periodicDue || invalidationDue)) {
            this.saveCounter = 0;
            this.invalidationDirty = false; // a save flushes any pending invalidation too
            // Latest-wins slot, never a per-save closure on the executor queue: a save
            // slower than the due cadence must coalesce, not backlog (issue #62 retained
            // ~42 queued snapshots / 1.76 GB). Shutdown rejection is handled inside.
            this.saveScheduler.schedule(this.timestampCache.snapshotForSave());
        }
    }

    // ---- Phase 1: Apply lossless events ----

    private void applyEvents(MailboxTake take) {
        applyInvalidations(take.invalidations());

        // Player removals (disconnect or dimension change): release the player's dedup groups
        // and the attached players' pending entries, and drop its generation in-flight tracking
        // (a generation abandoned before its outcome drains would otherwise leak).
        // The removed state itself is simply dropped — its slot counts die with it.
        for (var removed : take.removals()) {
            cleanupDedupGroups(this.dedupTracker.removePlayer(removed.uuid(), removed.registration()));
            removeGenerationTracking(removed.registration());
        }

        applyDirtyClears(take.dirtyClears());
    }

    /** Shared by the cycle-start apply and the late drain so the two paths cannot drift. */
    private void applyInvalidations(List<TimestampInvalidation> invalidations) {
        for (var inv : invalidations) {
            // Store fan-out FIRST (plan §1 invalidation fan-out): the store's synchronous
            // removal + tombstone must land before anything can re-read the position.
            if (this.store != null) {
                this.store.invalidate(inv.dimension(), inv.positions());
            }
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
            // not dedup, so it has its own per-player in-flight oracle (generationInFlight),
            // tainted here by markGenerationStale.
            for (long packed : inv.positions()) {
                if (this.dedupTracker.hasGroup(packed, inv.dimension())) {
                    this.invalidatedInFlight
                            .computeIfAbsent(inv.dimension(), k -> new LongOpenHashSet())
                            .add(packed);
                }
            }
            markGenerationStale(inv.dimension(), inv.positions());
            if (inv.onApplied() != null) {
                try {
                    inv.onApplied().run();
                } catch (Throwable ignored) {
                    // The guard's release is advisory armor — a throw must never take
                    // down the invalidation apply (positions just stay suppressed).
                }
            }
        }
    }

    /** Shared by the cycle-start apply and the late drain so the two paths cannot drift. */
    private void applyDirtyClears(Map<UUID, ArrayList<long[]>> dirtyClears) {
        for (var entry : dirtyClears.entrySet()) {
            var state = this.players.get(entry.getKey());
            if (state == null) continue;
            for (long[] positions : entry.getValue()) {
                state.clearDiskReadDone(positions);
            }
        }
    }

    /**
     * Late drain of dirty-clears and timestamp invalidations, run after phases 1-3 and
     * immediately before routing. The router takes each player's want-set batch at ROUTE
     * time ({@code IncomingRequestRouter.processIncomingRequests}) — a strictly fresher
     * read than the cycle-start event take — so without this drain a batch that arrived
     * mid-cycle was routed against pre-clear done-bits / pre-invalidation stamps and could
     * be answered a stale up_to_date, which the client's answer-time dirty-mark consumption
     * turns into a sealed pre-edit column (the production shape of the TwoPlayerGameTests
     * fan-out flake, PRs #19/#30). The broadcaster enqueues the clear BEFORE sending the
     * dirty notice, so with this drain a re-ask CAUSED by a notice can no longer be routed
     * ahead of its clear; the race window shrinks from a full cycle + scan alignment to the
     * intra-phase-4 interval (an event landing after this drain but before that player's
     * route — milliseconds, and the causally-ordered notice path is closed).
     *
     * <p>Only these two event kinds are late-drained: both are idempotent (re-clearing a
     * cleared bit / re-invalidating an absent stamp are no-ops), so double-apply across a
     * failed cycle's requeue is harmless. Removals and generation outcomes are phase-
     * sensitive and non-idempotent (a re-applied removal can sweep a re-registered
     * player's fresh state; a re-consumed generation outcome un-taints) and stay on the
     * cycle-start-only cadence.
     *
     * <p>Alternative considered and rejected: taking all players' batches at cycle start
     * would close the race absolutely, but adds up to one cycle of want-set latency and
     * disturbs the router's dimension-mismatch leave-batch-untaken guard.
     */
    private void applyLateDirtyEvents() {
        ArrayList<TimestampInvalidation> inv = null;
        HashMap<UUID, ArrayList<long[]>> clears = null;
        synchronized (this.mailboxLock) {
            if (!this.pendingInvalidations.isEmpty()) {
                inv = this.pendingInvalidations;
                this.pendingInvalidations = new ArrayList<>();
            }
            if (!this.pendingDirtyClears.isEmpty()) {
                clears = this.pendingDirtyClears;
                this.pendingDirtyClears = new HashMap<>();
            }
        }
        if (inv == null && clears == null) return;
        this.lateInvalidations = inv;
        this.lateDirtyClears = clears;
        this.lateEventsApplied = false;
        if (inv != null) applyInvalidations(inv);
        if (clears != null) applyDirtyClears(clears);
        this.lateEventsApplied = true;
    }

    /** Test seam: runs on the processing thread immediately before the late dirty-event
     *  drain — the deterministic injection point for mid-cycle events and batches. */
    protected void beforeRouteHook() {}

    /**
     * Exit-path flush: applies queued timestamp invalidations directly to the cache before
     * the processing thread dies without taking the shutdown sentinel (interrupted wait
     * with no snapshot posted, or interrupted error-backoff sleep). Skipping this would
     * let shutdown's final save persist pre-edit stamps that answer false up_to_date
     * across a restart. Runs on the processing thread — the cache stays single-owner
     * (shutdown only snapshots after join confirms the thread is dead). The in-flight
     * taint bookkeeping is deliberately skipped: the session dies with the thread.
     */
    void flushPendingInvalidationsOnExit() {
        synchronized (this.mailboxLock) {
            for (var inv : this.pendingInvalidations) {
                if (this.store != null) {
                    this.store.invalidate(inv.dimension(), inv.positions());
                }
                this.timestampCache.invalidate(inv.dimension(), inv.positions());
            }
            this.pendingInvalidations = new ArrayList<>();
        }
    }

    private void cleanupDedupGroups(List<DedupTracker.RemovedGroup> removedGroups) {
        for (var rg : removedGroups) {
            int cx = PositionUtil.unpackX(rg.packed());
            int cz = PositionUtil.unpackZ(rg.packed());
            for (var attachment : rg.group().attached()) {
                var attachedState = this.players.get(attachment.playerUuid());
                if (attachedState != null && attachedState.registration() == attachment.registration()) {
                    if (attachedState.removePendingByPosition(cx, cz) != null) {
                        // The read backing this pending will never deliver and no message is
                        // sent (pre-existing silent-drop path) — book it as superseded so the
                        // request-conservation ledger closes.
                        this.ctx.diagnostics().addSuperseded(1);
                    }
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

    /** Positions with a generation ticket admitted (via disk-not-found escalation in
     *  {@link #handleDiskNotFound}, or the router's direct-generation path) but whose outcome
     *  has not yet drained, keyed by registration then dimension. Generation does NOT dedup, so —
     *  unlike the disk path, which tracks in-flight reads through their dedup group — this map
     *  is generation's own in-flight oracle. Keyed by registration so {@link #removeGenerationTracking}
     *  can sweep it on removal (disconnect / dimension change) exactly as {@link #cleanupDedupGroups}
     *  sweeps the disk path's {@link #invalidatedInFlight}: a generation abandoned before its
     *  outcome drains therefore cannot leak. Processing-thread only. */
    private final Map<RequestRegistration, Map<String, LongOpenHashSet>> generationInFlight = new HashMap<>();

    /** Subset of {@link #generationInFlight} positions overtaken by a dirty invalidation before
     *  their outcome drained, same keying. The generation twin of {@link #invalidatedInFlight}:
     *  a tainted outcome carries PRE-edit terrain, so it must not mark diskReadDone or re-stamp
     *  the timestamp cache. Per-player, so two players generating the same column taint
     *  independently and neither pins the other's flag. Processing-thread only. */
    private final Map<RequestRegistration, Map<String, LongOpenHashSet>> generationStale = new HashMap<>();

    /** Record an admitted generation as in-flight. Package-private so the router's direct
     *  generation path (no disk reader) registers it too — both gen-ticket producers must, or
     *  an overtaking edit on the unregistered path re-stamps pre-edit terrain as up_to_date. */
    void addGenerationInFlight(RequestRegistration player, String dimension, long packed) {
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
     *  its outcome drains (the player disconnected or changed dimension) is retired instead by
     *  {@link #removeGenerationTracking} on the removal event, so nothing leaks. */
    private boolean consumeGenerationInFlight(RequestRegistration player, String dimension, long packed) {
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
    private void removeGenerationTracking(RequestRegistration player) {
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
            var queue = this.diskReader.getPlayerQueue(state.getPlayerUUID(), state.registration());
            if (queue == null) continue;

            ChunkReadResult result;
            while ((result = queue.poll()) != null) {
                if (state.registration().isRetired() || this.players.get(playerUuid) != state) break;
                if (!dimension.equals(result.dimension())) {
                    // Read submitted before a dimension change: the pending entry, slot, and
                    // dedup group that backed it died with the removed state. Delivering it here
                    // would stamp diskReadDone/timestampCache under the NEW dimension and could
                    // tear down a live new-dimension dedup group at the same packed position.
                    continue;
                }
                // Stale-snapshot session guard (the phase-2 twin of the router's): a dimension
                // change can replace the state MID-CYCLE, after this snapshot was built. The
                // snapshot-dimension check above then passes (both are the OLD dimension) while
                // `state` is already the NEW session — delivering would mark an unearned
                // done-bit on it (a stale up_to_date seal), worst on an A->B->A trip where the
                // dimension strings match trivially. (Null = bare test rig, guard skipped.)
                String registered = state.registeredDimension();
                if (registered != null && !registered.equals(result.dimension())) continue;
                int cx = result.chunkX();
                int cz = result.chunkZ();
                long packed = PositionUtil.packPosition(cx, cz);

                // A dirty invalidation overtook this read while it was in flight: the bytes
                // may predate the edit. Deliver the column (better than nothing), but leave
                // diskReadDone and the timestamp cache unset so the client's dirty
                // re-request re-resolves from disk instead of drawing a false up_to_date.
                boolean staleAgainstEdit = consumeInvalidatedInFlight(dimension, packed);

                // ONE holder per drained result (plan §0.4): every dedup recipient's build
                // and the store deposit share it, so a mixed-capability group costs one
                // compress, never one per recipient. Null for non-data results (all-air /
                // not-found / saturated). A store-FRAME hit (plan §3) wraps the validated
                // frame — capable recipients ship it verbatim; raw-needing recipients
                // (v16/no-capability) cost one lazy ~24 µs decompress here on the
                // processing thread, bounded and rare.
                ColumnBytes columnBytes;
                if (result.frameBytes() != null) {
                    columnBytes = ColumnBytes.ofFrame(this.wireCodec, result.frameBytes(),
                            result.frameRawSize());
                } else if (result.sectionBytes() != null) {
                    columnBytes = ColumnBytes.ofRaw(this.wireCodec, result.sectionBytes());
                } else {
                    columnBytes = null;
                }

                deliverDiskResult(playerUuid, state, result, columnBytes,
                        result.submissionOrder(), dimension, staleAgainstEdit);

                if (result.headerFresh()) {
                    // Header freshness rung (P1): no bytes exist — never a deposit (the
                    // raw==null shape below would normalize to a fabricated ALL-AIR row).
                    // Refresh the timestamp cache at stamp + 1 so the next re-ask hits
                    // the router's cheap rung: the non-strict tscache compare
                    // (cached <= clientTs) then fires exactly iff clientTs > stamp — the
                    // header rung's own margined bound, preserved (the stamp already
                    // carries the serve-latency margin from the reader). MONOTONIC MAX
                    // (P1 review MAJOR): put() overwrites unconditionally and a LOWER
                    // cached value is strictly MORE permissive, so a header-derived
                    // bound must never replace a higher acquisition stamp the server
                    // actually observed — the downgrade would persist a memo-derived
                    // claim globally (lss-timestamps.bin) and bypass the rung's own
                    // 5 s re-validation forever. Stale-guarded like every stamp write.
                    if (!staleAgainstEdit) {
                        long refreshed = result.columnTimestamp() + 1;
                        long existing = this.timestampCache.get(result.dimension(), packed);
                        if (refreshed > existing) {
                            this.timestampCache.put(result.dimension(), packed,
                                    refreshed, this.cycleNow);
                        }
                    }
                } else if (!staleAgainstEdit && !result.saturated() && !result.notFound()) {
                    // Store timestamp so reconnecting clients get up-to-date responses
                    this.timestampCache.put(result.dimension(), packed, result.columnTimestamp(), this.cycleNow);
                    // LOD-store deposit — the delivery-path choke point, ONCE per drained
                    // result (not per dedup recipient), strictly AFTER the stale guard: an
                    // edit-overtaken read must never populate the store (§1). Store hits
                    // are not re-deposited. All-air (null section bytes here) normalizes
                    // to byte[0] at the store boundary. Deliberately independent of the
                    // per-recipient payload-build outcome (the gen flavor matches): a
                    // send rejection loses the delivery, not the bytes.
                    if (this.store != null && !result.fromStore()) {
                        depositColumn(result.dimension(), packed, result.sectionBytes(),
                                columnBytes, result.columnTimestamp(),
                                result.srcStampSeconds());
                    }
                } else if (result.notFound()) {
                    // Disk says the chunk no longer exists (region trimmed/deleted outside
                    // MC). A stale cached stamp would answer up_to_date to data-claiming
                    // clients forever — ghost terrain for a chunk the server cannot serve.
                    // Stamps ONLY: the full invalidate() also clears miss-memo entries, and
                    // this not-found is the very observation the memo write in
                    // handleDiskNotFound just recorded — full invalidation here would erase
                    // every memo the instant it was written.
                    int removed = this.timestampCache.invalidateStamps(result.dimension(), new long[]{packed});
                    if (removed > 0 && !this.invalidationDirty) {
                        this.invalidationDirty = true;
                        this.invalidationCountdown = INVALIDATE_SAVE_MAX_CYCLES;
                    }
                    // Ghost guard, store half (§1 fan-out): storage POSITIVELY answered
                    // "no such chunk" — the store row must die or the store re-serves
                    // deleted terrain forever. Gated on authoritativeMiss: an
                    // error/timeout triage says nothing about existence, and deleting a
                    // good row on a transient timeout would just cost a re-deposit.
                    if (this.store != null && result.authoritativeMiss()) {
                        this.store.delete(result.dimension(), packed);
                    }
                }

                // Dispatch the same result to attached players in the dedup group
                var group = this.dedupTracker.removeGroup(packed, dimension);
                if (group != null) {
                    for (var attachment : group.attached()) {
                        var attachedState = this.players.get(attachment.playerUuid());
                        if (attachedState == null || attachedState.registration() != attachment.registration()
                                || attachment.registration().isRetired()) continue;
                        // Same stale-snapshot session guard as the primary: an attached
                        // player's state can also be swapped mid-cycle by a dimension change.
                        String attachedRegistered = attachedState.registeredDimension();
                        if (attachedRegistered != null
                                && !attachedRegistered.equals(result.dimension())) continue;
                        deliverDiskResult(attachment.playerUuid(), attachedState, result,
                                columnBytes, attachment.submissionOrder(), dimension,
                                staleAgainstEdit);
                    }
                }
            }
        }
    }

    /**
     * Deliver one disk read result to one recipient: resolve the pending entry (freeing its
     * slot), then answer with not-found fallback / column data / up-to-date — or, for a
     * residual saturated result, drop it silently (counted superseded; the client re-declares).
     * Shared by the primary requester and every dedup-attached player.
     */
    private void deliverDiskResult(UUID playerUuid, PlayerState state, ChunkReadResult result,
                                    ColumnBytes columnBytes,
                                    long submissionOrder, String dimension,
                                    boolean staleAgainstEdit) {
        int cx = result.chunkX();
        int cz = result.chunkZ();
        long packed = PositionUtil.packPosition(cx, cz);
        var pending = state.removePendingByPosition(cx, cz);

        if (result.saturated()) {
            // Two bounce sources share this flavor, BOTH races-only since Amendment 2:
            // the residual pool rejection (the router's headroom gate prevents submits
            // into a full pool, so races/shutdown only) and the DiskReadGate's park
            // OVERFLOW (race armor — the router's gateSaturated retention conjunct holds
            // sustained pressure upstream; counted disk.gated at the bounce site, never
            // here). Silent drop either way — the pending slot was already freed above,
            // no dedup/stale-guard teardown is needed for a result that already drained,
            // and the client's next want-set re-declares the position.
            this.ctx.diagnostics().addSuperseded(1);
            if (LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Disk saturated/gated result dropped silently (superseded) for "
                        + playerUuid + ": chunk [" + cx + ", " + cz + "]");
            }
        } else if (result.headerFresh()) {
            // Header freshness rung (region-summary-sync-plan.md P1): the region header
            // proved content unchanged since result.columnTimestamp(). Per-RECIPIENT
            // verification — the rung fired on the SUBMITTER's stamp, but a dedup-
            // attached player's stamp may be older (or ts<=0), and for them the proof
            // says nothing: standard transient drop, re-declared within <=1 s (the next
            // submit re-runs the ladder against that player's own stamp). A ghost
            // delivery (pending == null) and an edit-overtaken result (the proof
            // predates the invalidation) drop the same way — never a stale up_to_date.
            if (!staleAgainstEdit && pending != null
                    && pending.clientTimestamp() > result.columnTimestamp()) {
                state.markDiskReadDone(cx, cz);
                // Compare-backed rung — stamped-up_to_date eligible (plan §9.1/§9.2).
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state,
                        this.ctx.stampSource().stampSecond(playerUuid, dimension, packed),
                        dimension));
                this.ctx.diagnostics().incrementUpToDate();
            } else {
                this.ctx.diagnostics().addSuperseded(1);
            }
        } else if (result.notFound()) {
            handleDiskNotFound(playerUuid, state, packed, cx, cz, pending, dimension,
                    result.authoritativeMiss() && !staleAgainstEdit);
        } else {
            // Store freshness rung (P1's delivery half): a store hit whose STORED
            // acquisition stamp is <= this recipient's declared stamp makes the bytes
            // redundant — the client's copy carries the same content claim ("current as
            // of the stored stamp"), so answer up_to_date instead of re-sending. The
            // NON-STRICT compare mirrors the router's tscache rung exactly (same
            // acquisition-stamp currency, same doctrine). Per recipient: an attached
            // player with an older stamp still gets the bytes below. Stale-guarded: an
            // overtaking edit voids the stored stamp's claim.
            if (result.fromStore() && !staleAgainstEdit && pending != null
                    && pending.clientTimestamp() > 0
                    && result.columnTimestamp() > 0
                    && result.columnTimestamp() <= pending.clientTimestamp()
                    && columnBytes != null) {
                state.markDiskReadDone(cx, cz);
                // Deliberately NEVER-STAMP (plan §9.1 as narrowed by the 3-Opus fold):
                // this rung's own doctrine is "delivery honesty — never a fabricated
                // fresh stamp" (a re-serve hands the STORED acquisition second), and
                // the store's staleness bound (boot sweep + the invalidation channel;
                // Fabric has NO periodic resweep at 0) is not provably inside the
                // freshness margin — a stamp here would claim strictly more than the
                // bytes the rung declined to send. Only the tscache and header rungs
                // stamp; these columns heal via those on a later ask.
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
                this.ctx.diagnostics().incrementUpToDate();
                this.ctx.diagnostics().incrementDiskDrained();
                return;
            }
            try {
                if (!staleAgainstEdit) {
                    state.markDiskReadDone(cx, cz);
                }
                // Serve-source attribution: a store hit reports COLUMN_SOURCE_STORE
                // (client trace src:3), the NBT path stays COLUMN_SOURCE_DISK.
                byte source = result.fromStore()
                        ? LSSConstants.COLUMN_SOURCE_STORE : LSSConstants.COLUMN_SOURCE_DISK;
                boolean allAir = columnBytes == null;
                boolean sent = !allAir
                        && buildAndEnqueueColumnPayload(state, cx, cz, result.dimension(),
                                result.columnTimestamp(), submissionOrder,
                                columnBytes, result.estimatedBytes(),
                                source);
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
                            result.columnTimestamp(), submissionOrder, source))) {
                        this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
                    }
                }
            } catch (Throwable t) {
                // Per-delivery containment — the phase-2 twin of processGenerationReady's
                // catch: the done-bit was marked BEFORE the payload build, so a build throw
                // (encode, OOM) would otherwise fail the cycle and leave an orphaned bit
                // that answers a ts>0 re-ask up_to_date with nothing in the pipeline. Clear
                // it (idempotent; the only bit reachable here is the one this try set — a
                // live pending blocked every other setter) and drop as a standard transient
                // (superseded, re-declared next scan). Other recipients of this dedup group
                // still get their deliveries.
                long n = this.deliveryErrorWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.error("Failed to deliver disk result for chunk " + cx + ", " + cz
                            + " (" + n + " delivery failure(s) since the last report)", t);
                }
                state.clearDiskReadDone(packed);
                this.ctx.diagnostics().addSuperseded(1);
            }
        }
        this.ctx.diagnostics().incrementDiskDrained();
    }

    // A VoxelColumn body carrying zero sections. Sent to a data-claiming client for an
    // all-air resolution so it clears ghost terrain by ingesting air for every section.
    // v20 shape since C1: dictCount 0 + sectionCount 0 — the clear-column invariant
    // (dictCount==0 ⇔ sectionCount==0); the client's isClearColumn still reads only the
    // leading VarInt, and the C2 legacy translators collapse it back to the 1-byte form.
    private static final byte[] ZERO_SECTION_COLUMN = new byte[]{0x00, 0x00};

    /**
     * Store deposit with opportunistic frame reuse (plan §3 "compress once, use twice"):
     * when a capable recipient's build already materialized the wire frame on this very
     * holder, hand the store the FRAME + processing-thread-computed hashes and skip the
     * batcher's compress; otherwise (raw sessions, all-air, or the frame simply wasn't
     * built yet — the deposit runs after the primary's build but before the dedup
     * fan-out, so a later recipient's frame is missed, accepted) deposit raw as before.
     * {@code raw} is always in hand at both call sites (never a store hit), so
     * {@code holder.raw()} costs nothing here.
     */
    private void depositColumn(String dimension, long packed, byte[] raw,
                               ColumnBytes holder, long columnTimestamp,
                               long srcStampSeconds) {
        if (holder != null && holder.frameMaterialized()) {
            byte[] frame = holder.frame();
            if (this.store.depositFrame(dimension, packed, frame, holder.rawSize(),
                    dev.vox.lss.common.store.LodStoreService.contentHash(holder.raw()),
                    dev.vox.lss.common.store.LodStoreService.contentHash(frame),
                    columnTimestamp, srcStampSeconds)) {
                return;
            }
            // false = unsupported/store-down, never "enqueued-with-shed" — the raw
            // fallback cannot double-deposit (see the interface contract).
        }
        this.store.deposit(dimension, packed, raw, columnTimestamp, srcStampSeconds);
    }

    /**
     * Authoritative all-air serve: enqueue a 0-section {@code VoxelColumn} (carrying the server
     * columnTimestamp) so a data-claiming client clears the column. Returns false if the payload
     * could not be enqueued (caller falls back to up_to_date).
     */
    boolean sendEmptiedColumn(PlayerState state, int cx, int cz, String dimension,
                              long columnTimestamp, long submissionOrder, byte source) {
        int est = ZERO_SECTION_COLUMN.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        // Clears are ALWAYS codec 0 (the ONE common pin, plan §0.8): the 1-byte body is
        // far below COLUMN_COMPRESS_MIN_BYTES, so the holder refuses a frame
        // structurally and the client's clear detection keeps its decompress-free read.
        return buildAndEnqueueColumnPayload(state, cx, cz, dimension, columnTimestamp,
                submissionOrder, ColumnBytes.ofRaw(this.wireCodec, ZERO_SECTION_COLUMN),
                est, source);
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

    /** Package-private test seam: the internal timestamp cache, so in-package tests can pin
     *  cache-coupled invariants that have no behavioral observable through the gated router
     *  rung (e.g. that a gen-disabled server writes no memo entries at all). */
    ColumnTimestampCache timestampCacheForTest() {
        return this.timestampCache;
    }

    /** Sweep EVERY player's served-set each eviction cycle (M3). All-players, not
     *  round-robin: a per-player turn every N minutes let the set's backing array grow to
     *  the between-turns peak — and open-hash sets never shrink, so that peak stayed
     *  resident (the trim() in the sweep needs the sweep to actually run to help). The
     *  cost is ~1 ms per 333k entries on the processing thread, once a minute. */
    private void sweepRegisteredPlayerServedSets() {
        for (var state : this.players.values()) {
            int swept = state.sweepDiskReadDoneOutsideRange(this.diskReadDoneSweepRadiusChunks);
            if (swept > 0 && LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Swept " + swept + " out-of-range served-set entries for "
                        + state.getPlayerUUID());
            }
        }
    }

    /** Sets the eviction counter to threshold−1 so the NEXT cycle runs the real periodic
     *  maintenance block (eviction + served-set sweep) — the wiring pin's seam. */
    void primeEvictionCounterForTest() {
        this.evictionCounter = EVICTION_INTERVAL_CYCLES - 1;
    }

    /** Completed router passes — deterministic cycle gating for test rigs. */
    int routeCyclesForTest() {
        return this.requestRouter.routeCyclesForTest();
    }

    /** The real save executor — the coalescing wiring pin blocks its worker and reads its queue. */
    ThreadPoolExecutor saveExecutorForTest() {
        return this.saveExecutor;
    }

    /** The coalescing slot (null iff dataDir is null) — wiring-pin observability. */
    TimestampSaveScheduler saveSchedulerForTest() {
        return this.saveScheduler;
    }

    /** Server-owned generation: a disk miss IS the generation trigger — no client
     *  classification is consulted. Gen available: take a GENERATION slot and queue a
     *  ticket; a full slot is TRANSIENT (silent drop, counted superseded — the next
     *  want-set declaration retries the miss; a NOT_GENERATED here would blank the
     *  position for the whole session). Gen unavailable: ColumnNotGenerated — the one
     *  permanent disposition, the client stops asking until a dirty broadcast revives it. */
    private void handleDiskNotFound(UUID playerUuid, PlayerState state, long packed,
                                     int cx, int cz, PendingRequest pending, String dimension,
                                     boolean authoritativeMiss) {
        if (pending == null) {
            // No live pending backs this delivery (duplicate/raced result). Under permanent
            // NOT_GENERATED semantics a wire answer here could session-satisfy a position off
            // a ghost delivery — drop silently; a still-wanted position is re-declared. Also
            // no memo write: a raced observation is stale by construction.
            this.ctx.diagnostics().addSuperseded(1);
            this.ctx.diagnostics().addMissDropped(1); // law A5: a miss with no submit, no answer
            return;
        }
        if (authoritativeMiss && this.generationAvailable) {
            // Memoize the miss BEFORE the gen ladder runs — a gated or slot-full drop still
            // learned the chunk is absent, and the memo is what stops the next 1 Hz
            // re-declaration from re-reading disk to re-learn it. NEVER written for an
            // error/timeout-triaged not-found (says nothing about existence), a ghost, or an
            // edit-overtaken delivery (the caller strips the flag: an invalidation consumed
            // this cycle means a save happened while the read flew — re-asserting absence
            // would undo the falsifying clear with its one falsification event already
            // spent). Gen-disabled servers skip the write too: the rung that reads the memo
            // is gated on generationAvailable, and entries only expire lazily on read — a
            // write nothing reads would linger until wholesale eviction.
            this.timestampCache.putMiss(dimension, packed, System.nanoTime());
        }
        if (!this.generationAvailable) {
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
            return;
        }
        escalateMissToGeneration(playerUuid, state, packed, cx, cz, pending.clientTimestamp(),
                dimension, "miss");
    }

    /** Admission-order diagnosis (-Dlss.admissionTrace=true, dev servers only — see
     *  test-server.sh): one line per generation-admission decision with the candidate
     *  ring, the live frontier stamp, and the nearest in-flight rings, so a live far-arc
     *  report can be pinned to either a gate leak (candidate outside its window at
     *  admission) or completion displacement (legal admission, moved player). */
    private static final boolean ADMISSION_TRACE = Boolean.getBoolean("lss.admissionTrace");

    private void traceAdmission(PlayerState state, int cx, int cz, String via, String verdict) {
        int[] near = state.nearestPendingRingsForTrace();
        LSSLogger.info("[lss-adm] c=" + cx + "," + cz
                + " r=" + state.ringFromPlayerForTrace(cx, cz)
                + " f=" + state.liveFrontierRingForTrace()
                + " fsrc=" + state.frontierStampSourceForTrace()
                + " sync=" + near[0] + " gen=" + near[1]
                + " via=" + via + " v=" + verdict);
    }

    /**
     * The gen-available half of the miss ladder, shared by the real disk-miss path
     * ({@link #handleDiskNotFound}) and the router's miss-memo rung so the ladder cannot
     * drift. Order-spread gate, the pacing rules ("generation never overtakes nearer
     * in-flight work": nearer-pending-SYNC hold + generation cohort span), then GENERATION
     * slot admission + stale-guard registration + ticket enqueue, or the standard silent
     * transient drop. Never a wire answer.
     *
     * <p>The pacing rules apply to BOTH paths deliberately (they were briefly memo-only,
     * justified by the delivery path's "emergent pacing" — escalations arrive in
     * read-completion ≈ proximity order. That justification only holds for a STATIONARY
     * player with fast reads: under movement, read completions are proximity-ordered
     * relative to the SUBMISSION-time position, and with memo-accelerated generation
     * starving reads by seconds, a stale far delivery escalated unpaced was the
     * "isolated chunks generating out in space ahead of a moving player" bug. A held
     * delivery is memoized + dropped and re-enters through the closest-first drain, so
     * pacing everywhere converts delivery disorder into drain order — the drain becomes
     * the sole effective orderer of generation admission.)
     */
    void escalateMissToGeneration(UUID playerUuid, PlayerState state, long packed,
                                   int cx, int cz, long clientTimestamp, String dimension,
                                   String via) {
        if (state.generationOvertakesNearerInFlight(cx, cz,
                LSSConstants.MAX_GENERATION_COHORT_SPAN)) {
            // Ordering refusal, same disposition as the spread gate: the standard
            // transient drop, re-declared at 1 Hz, admitted once the nearer work resolves.
            if (ADMISSION_TRACE) traceAdmission(state, cx, cz, via, "hold_pacing");
            this.ctx.diagnostics().addSuperseded(1);
            this.ctx.diagnostics().addMissDropped(1); // law A5: a miss with no submit, no answer
            this.ctx.diagnostics().addGenOrderGated(1);
            return;
        }
        if (state.generationOrderSpreadExceeded(cx, cz, LSSConstants.MAX_GENERATION_RING_SPREAD)) {
            // Order-spread gate: the platform scheduler owns completion order (chunk-system
            // rewrites like C2ME are not FIFO), so an unbounded per-slot refill lets far
            // tickets pile in above a starving near one and the world fills far-before-near.
            // Refusal is the standard transient drop — re-declared at 1 Hz, admitted once
            // the head resolves (or its generation timeout clears it).
            if (ADMISSION_TRACE) traceAdmission(state, cx, cz, via, "hold_spread");
            this.ctx.diagnostics().addSuperseded(1);
            this.ctx.diagnostics().addMissDropped(1); // law A5: a miss with no submit, no answer
            this.ctx.diagnostics().addGenOrderGated(1);
            return;
        }
        if (state.tryAdmit(new PendingRequest(cx, cz, SlotType.GENERATION, clientTimestamp))) {
            if (ADMISSION_TRACE) traceAdmission(state, cx, cz, via, "admit");
            addGenerationInFlight(state.registration(), dimension, packed);
            this.ctx.generationTicketRequests().add(new GenerationTicketRequest(
                    playerUuid, state.registration(), cx, cz, dimension, this.ctx.sequence().next()));
        } else {
            // Transient: the gen slot cap is momentarily full — never a wire answer.
            if (ADMISSION_TRACE) traceAdmission(state, cx, cz, via, "slot_full");
            this.ctx.diagnostics().addSuperseded(1);
            this.ctx.diagnostics().addMissDropped(1); // law A5: a miss with no submit, no answer
        }
    }

    // ---- Phase 3: Process generation outcomes ----

    private void processGenerationReady(List<TickSnapshot.GenerationReadyData> generationReady,
                                         TickSnapshot snapshot) {
        for (var entry : generationReady) {
            int cx = entry.cx();
            int cz = entry.cz();
            long packed = PositionUtil.packPosition(cx, cz);
            // Miss memo: generation FINISHING — in every outcome flavor (delivered, all-air,
            // permanent failure, transient timeout, player gone, dimension changed) — clears
            // the memoized absence BEFORE any per-player continue below: the miss is a fact
            // about the world, not about a player, and the world just changed (or the claim
            // must be re-derived). Successful deliveries also clear via the put() choke
            // point; this explicit clear covers the non-serving outcomes.
            this.timestampCache.clearMiss(entry.dimension(), packed);
            // Retire the in-flight generation record for EVERY drained outcome (delivered,
            // all-air, not-generated, player gone, dimension changed); an outcome that never
            // drains because the player left is retired by removeGenerationTracking on the
            // removal event instead. This consumes ONLY the originating registration, even
            // when its UUID now names another player state. Preserve the world miss clear
            // above for obsolete completions; it is not a claim about that replacement.
            boolean genStale = consumeGenerationInFlight(entry.registration(), entry.dimension(), packed);

            var state = this.players.get(entry.playerUuid());
            if (state == null || state.registration() != entry.registration()
                    || entry.registration().isRetired()) continue;
            // A dimension change replaces the player's state (removePlayer + registerPlayer,
            // same UUID). Outcomes harvested for the old session carry the old dimension —
            // skip them instead of poisoning the fresh state (their pending died with it).
            String current = snapshot.playerDimensions().get(entry.playerUuid());
            if (current == null || !current.equals(entry.dimension())) continue;
            // Stale-snapshot session guard (the phase-3 twin of the router's): when a
            // dimension change swaps the state MID-CYCLE, the snapshot check above compares
            // OLD-vs-OLD and passes while `state` is already the fresh session. Gate on the
            // STATE's own registered dimension so an old-session outcome cannot mark a
            // done-bit — or, worse, answer a session-permanent NOT_GENERATED — on the fresh
            // session. (Null = bare test rig, guard skipped. A double swap within one ~50ms
            // cycle could still string-match, but registration takes multiple ticks.)
            String registered = state.registeredDimension();
            if (registered != null && !registered.equals(entry.dimension())) continue;
            // Completion-order evidence (diagnostics only): a successful completion while a
            // NEARER ticket is still outstanding means the platform scheduler finished
            // far-before-near. A high rate here is the C2ME-style inversion signature that
            // the order-spread gate in handleDiskNotFound exists to bound.
            if (entry.columnData() != null && state.hasNearerOutstandingGeneration(cx, cz)) {
                this.ctx.diagnostics().addGenCompletionInversion(1);
            }
            var pending = state.removePendingByPosition(cx, cz);

            try {
                processGenerationOutcome(entry, state, pending, genStale, cx, cz, packed);
            } catch (Throwable t) {
                // Per-outcome containment: a throw here (payload encode, OOM) must not fail
                // the whole cycle — requeueLosslessEvents would replay the ALREADY-CONSUMED
                // earlier outcomes of this list, and a replayed stale-tainted outcome finds
                // its taint consumed and re-stamps pre-edit terrain. The failed outcome
                // becomes a standard transient drop (counted superseded; the client
                // re-declares and the miss re-escalates). Clear the done-bit: a delivered-
                // branch throw after markDiskReadDone would otherwise leave an orphaned bit
                // that answers a ts>0 re-ask up_to_date with nothing in the pipeline
                // (idempotent, and worst case costs one honest re-read). Pending was already
                // removed above — the catch must NOT touch pending again (a fresh session's
                // entry at the same position could be stripped).
                long n = this.deliveryErrorWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.error("Failed to process generation outcome for chunk " + cx + ", "
                            + cz + " (" + n + " delivery failure(s) since the last report)", t);
                }
                state.clearDiskReadDone(packed);
                this.ctx.diagnostics().addSuperseded(1);
            }
            this.ctx.diagnostics().incrementGenDrained();
        }
    }

    /** The per-outcome disposition body of {@link #processGenerationReady}, extracted so a
     *  single outcome's failure is containable (see the catch at the call site). */
    private void processGenerationOutcome(TickSnapshot.GenerationReadyData entry, PlayerState state,
                                          PendingRequest pending, boolean genStale, int cx, int cz,
                                          long packed) {
        if (entry.columnData() == null) {
            if (entry.transientFailure()) {
                // Transient outcome (timeout / capacity reject): never a wire answer —
                // NOT_GENERATED is session-permanent on the client, so transient pressure
                // must drop silently (counted superseded) and heal by re-declaration.
                this.ctx.diagnostics().addSuperseded(1);
                if (entry.missDropped()) {
                    // Capacity/removed-player reject: the miss produced neither a
                    // generation submission nor a wire answer (law A5's miss_dropped term;
                    // a timeout outcome skips this — its submission balanced the miss).
                    this.ctx.diagnostics().addMissDropped(1);
                }
            } else {
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(entry.playerUuid(), packed, state));
            }
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
            // One holder shared by the build AND the deposit (plan §3): a compressed
            // session's wire frame is reused by the store, compress-once-use-twice.
            ColumnBytes genBytes = allAir ? null
                    : ColumnBytes.ofRaw(this.wireCodec, genSections);
            boolean sent = !allAir && this.enqueueLoadedColumn(state, entry.columnData(),
                    entry.columnTimestamp(), entry.submissionOrder(), entry.dimension(), genStale,
                    LSSConstants.COLUMN_SOURCE_GENERATION, genBytes);
            // LOD-store deposit, generation flavor — same delivery-path choke point,
            // same stale-guard condition (a genStale outcome carries pre-edit bytes and
            // must not populate the store). All-air deposits byte[0] so a warm all-air
            // column never re-reads NBT (§1 all-air normalization). Deliberately NOT
            // gated on `sent`: a send-queue rejection loses the delivery, not the bytes
            // — depositing anyway makes the re-ask a warm hit instead of a re-generate.
            if (this.store != null && !genStale) {
                // The gen columnTimestamp IS the acquisition stamp: both platforms set
                // it via epochSeconds() at completion-serialization, the moment the
                // bytes exist (R1-M2 — closes the completion→delivery mailbox gap).
                if (allAir) {
                    this.store.deposit(entry.dimension(), packed, null,
                            entry.columnTimestamp(), entry.columnTimestamp());
                } else {
                    depositColumn(entry.dimension(), packed, genSections, genBytes,
                            entry.columnTimestamp(), entry.columnTimestamp());
                }
            }
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
                        entry.columnTimestamp(), entry.submissionOrder(),
                        LSSConstants.COLUMN_SOURCE_GENERATION))) {
                    this.ctx.sendActions().add(new SendAction.ColumnUpToDate(entry.playerUuid(), packed, state));
                }
            }
        }
    }

    // ---- Phase 4: Route incoming requests per player ----

    private void routeIncomingRequests(TickSnapshot snapshot) {
        this.requestRouter.routeAll(snapshot, this.cycleNow);
    }

    public ProcessingDiagnostics getDiagnostics() {
        return this.ctx.diagnostics();
    }

    /** The LOD-store counter family (all-zero while {@code lodStore=off}). */
    public dev.vox.lss.common.store.LodStoreDiagnostics getStoreDiagnostics() {
        var s = this.store;
        return s != null ? s.diagnostics() : this.storeDiagnostics;
    }

    /**
     * Attach the wire codec for compressed column shipping (protocol 19,
     * useCompressedColumns). Null (never attached) means every session ships raw —
     * the config-off AND natives-unavailable degrade path (plan §0.11: the service
     * probes {@code StoreCodec.zstdOrNull()} once at start and warns when config
     * wants compression but the native cannot serve it). Volatile: attached once at
     * service init, read by the processing thread.
     */
    public final void attachWireCodec(dev.vox.lss.common.store.StoreCodec codec) {
        this.wireCodec = codec;
    }

    /** The attached wire codec, or null while compression is off/unavailable. */
    protected final dev.vox.lss.common.store.StoreCodec wireCodec() {
        return this.wireCodec;
    }

    /** Attach the LOD store (lodStore != off). Must happen before {@link #start()}. */
    public void attachStore(dev.vox.lss.common.store.LodStoreService store) {
        this.store = store;
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

    /** Test seam: the processing thread, for interrupt-driven exit-path wiring pins. */
    Thread processingThreadForTest() {
        return this.processingThread;
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
            // The thread may have died OUTSIDE the flush-covered exits (uncaught throwable in
            // the take/requeue machinery, or long before this shutdown), leaving queued
            // invalidations stranded in the pending buffer — persisted un-applied they answer
            // false up_to_date across the restart. Single ownership of the cache has passed
            // to this thread (join confirmed dead), so apply them here before snapshotting.
            flushPendingInvalidationsOnExit();
            // This snapshot supersedes anything still in the coalescing slot; discarding
            // lets a queued-but-not-started drain no-op instead of spending part of the
            // SHUTDOWN_JOIN_MS window writing a stale full file first. (A drain already
            // RUNNING finishes before the submit below — same FIFO executor.)
            this.saveScheduler.discardPending();
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
