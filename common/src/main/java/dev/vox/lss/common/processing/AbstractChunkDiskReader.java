package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for async chunk disk readers. Provides executor setup, per-player result
 * queues, diagnostics, the full submit/triage envelope (saturation, errors, all-air,
 * not-found), and shutdown logic. Subclasses supply only the platform-specific
 * {@link ReadOperation} that produces serialized section bytes.
 */
public abstract class AbstractChunkDiskReader {

    /** Platform hook: read the chunk's NBT and serialize visible sections to wire bytes.
     *  Returns null for "not found"; an empty array for an all-air chunk. */
    @FunctionalInterface
    public interface ReadOperation {
        byte[] read() throws Exception;
    }

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final int QUEUE_CAPACITY_PER_THREAD = 32;

    /** The header rung's serve-latency margin — the shared freshness-claim doctrine
     *  (see {@code RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS}'s javadoc). */
    static final long HEADER_FRESH_MARGIN_SECONDS =
            dev.vox.lss.common.region.RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS;

    // Saturation is a normal, self-healing path: the result is dropped silently and the
    // client's next want-set re-declares the position (v17 — nothing is bounced back). Since
    // the router's headroom gate stops submits into a full pool, a rejection here is now a
    // residual (race/shutdown) rather than the steady state, but a burst can still reject many
    // reads per second, and one WARN per position floods the console (#32). Aggregate to at
    // most one warning per minute carrying the rejected count; per-delivery detail stays on
    // the debug path in OffThreadProcessor.
    private static final long SATURATION_WARN_INTERVAL_MS = 60_000;
    private final LogThrottle saturationWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);
    // Read timeouts are documented transients (miss-memo A/B finding) — same aggregation.
    private final LogThrottle timeoutWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);
    // (Gate PARK-OVERFLOW refusals are deliberately LOG-FREE — see the drop site.)
    // Non-timeout read failures aggregate too (log-sweep finding: the bare else beside
    // the throttled timeout branch repeated per failed read, and clients re-declare).
    // SEPARATE instances (final-review A-M3): the flake catalog's decisive A7 check is
    // `grep -c "Failed to read chunk" == 0`, and a shared throttle would let a delivery
    // failure win the release window and silence a real read error's line for 60 s.
    private final LogThrottle readErrorWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);
    private final LogThrottle deliveryFailWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);

    // Disk-read concurrency gate (disk-read-concurrency-gate-plan.md): bounds the
    // EXPENSIVE phase only — store hits are served before it. Constructed at pool size
    // (a structural no-op) and configured once at service init via configureReadGate,
    // AFTER store attachment (the store-conditional AUTO needs the post-degrade store
    // state). Capacity is a volatile read per acquire, so stage C's runtime mutation
    // needs no further plumbing here.
    private final DiskReadGate readGate;
    // The gate's PARK LIST (v0.11.0 stage B deviation, progress-doc decisions log
    // 2026-08-13 — measured, not speculative): the plan's pure fail-fast bounce let a
    // permit-LESS worker empty the shared pool queue at bounce speed (µs/task), so the
    // permit HOLDER ran one read per queue refill and starved — the first live scenario
    // run measured 1.6% permit utilization (~8 reads/s at 2 ms reads, decaying to
    // ~1.5/s) and could not converge. A store MISS that fails tryAcquire now PARKS here
    // (bounded) instead of bouncing; every permit release drains parked work first, so
    // permit holders run back-to-back expensive reads while the other workers keep
    // serving store hits — which is the plan's own stated intent ("reserve the other
    // half for store lookups") and what its sizing model already assumed. Park overflow
    // still bounces via the unchanged saturated-flavor drop (counted gated), keeping
    // the drop-heal pressure valve. Entries here are exactly as long-lived as pool-queue
    // entries (same staleness/dedup/shutdown story: pendings hold, late delivery is
    // idempotent, isShutdown() short-circuits).
    private record ParkedRead(UUID playerUuid, RequestRegistration registration, int chunkX, int chunkZ, String dimension,
                              long submissionOrder, ReadOperation operation) {}
    private final ConcurrentLinkedQueue<ParkedRead> gateParked = new ConcurrentLinkedQueue<>();
    private final AtomicInteger gateParkedCount = new AtomicInteger();
    private final int gateParkCapacity;

    private final ExecutorService executor;
    private final ArrayBlockingQueue<Runnable> workQueue;
    private final int threadCount;
    private final ConcurrentHashMap<UUID, RequestRegistration> playerResults = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    // Pool tasks accepted but not yet finished — the adaptive throttle's in-flight input.
    // A dedicated counter (not submitted-completed): store hits occupy pool capacity like
    // any task but are EXCLUDED from disk.submitted/completed by the rung contract, so
    // the counter pair no longer measures pool occupancy.
    private final AtomicInteger tasksInFlight = new AtomicInteger();

    // The LOD store (docs/planning/lod-store-implementation-plan.md §1): consulted by the
    // rung in readAndDeliver before any region IO. Null while lodStore=off. Volatile:
    // attached once at service init (before the first submit) from the server thread.
    private volatile dev.vox.lss.common.store.LodStoreService store;
    // Region freshness stamps (region-summary-sync-plan.md P1): the header rung's oracle.
    // Null on bare test rigs — the rung is then inert. Volatile: attached once at service
    // init (before the first submit) from the server thread.
    private volatile dev.vox.lss.common.region.RegionStampTable regionStamps;
    // Frame-form store serving (protocol 19): see setServeStoreFrames.
    private volatile boolean serveStoreFrames;

    // Adaptive read throttle (Approach B): null until a platform reader detects that its
    // background-priority path is incompatible (a chunk-IO-overhaul mod replaced vanilla IO) and
    // calls enableAdaptiveThrottleFallback(). Never set on a working-A server — A gives true
    // priority, so throttling would only cost LSS throughput for no gameplay benefit. Volatile:
    // enabled on the processing/submit thread, read by hasHeadroom() (submit thread) and fed by
    // recordRealCompletion() (pool threads).
    private volatile AdaptiveReadThrottle throttle;

    protected final DiskReaderDiagnostics diag = new DiskReaderDiagnostics();

    protected AbstractChunkDiskReader(int threadCount) {
        this.threadCount = threadCount;
        this.readGate = new DiskReadGate(threadCount);
        // Mirror the pool queue's bound: parked work is the same kind of buffered
        // demand, so the two buffers stay the same order of magnitude. LOAD-BEARING
        // RELATION (Amendment 2): gateParkCapacity >= queueCapacity is what makes
        // gateSaturated() structurally false at K = pool — permit-less in-flight work
        // (bounded by the queue) can never reach the park bound while the park itself
        // stays pigeonhole-empty. Shrinking the park below the queue reopens the
        // overflow-drop window the router retention exists to close.
        int queueCapacity = threadCount * QUEUE_CAPACITY_PER_THREAD;
        this.gateParkCapacity = queueCapacity; // ONE computation — the >= relation by construction
        var workQueue = new ArrayBlockingQueue<Runnable>(queueCapacity);
        this.workQueue = workQueue;
        this.executor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                workQueue, r -> {
            var thread = new Thread(r, Brand.shortName() + " Disk Reader #" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /**
     * True when a submit will not be rejected. Single-submitter contract: only the
     * processing thread submits, and pool workers only DRAIN the queue, so a true result
     * cannot turn into a rejection before the same thread's next submit (the race is
     * pessimistic-only). This is what keeps disk saturation out of the client-visible
     * protocol: the router leaves the entry in the backlog instead of submitting into a
     * full pool.
     */
    public boolean hasHeadroom() {
        if (this.workQueue.remainingCapacity() <= 0) return false;   // pool queue full (unchanged)
        var t = this.throttle;                                       // null on the working-A path
        if (t != null) {
            // Approach B expressed as a headroom modifier: when the adaptive limit is reached the
            // router leaves the read in the want-set backlog (NO_DISK_HEADROOM) and the client
            // re-declares it — no bounce, no rate_limited (retired in v17). in-flight is the
            // dedicated pool-task counter (store hits occupy the pool too, but are excluded
            // from the submitted/completed pair by the store rung contract).
            if (!t.canSubmit(this.tasksInFlight.get())) return false;
        }
        return true;
    }

    /**
     * Gate-saturation predicate for the router's retention conjunct (Amendment 2,
     * disk-read-concurrency-gate-plan.md): true when every permit is held AND the park
     * plus the permit-LESS in-flight work (queued tasks + workers still classifying —
     * the population that will try to park) would fill the park. The router then
     * RETAINS the entry and stops the player's pass instead of submitting into a
     * certain overflow drop; the next want-set declaration re-prioritizes it.
     *
     * <p>Counting only permit-less work ({@code tasksInFlight - inUse}) is what keeps
     * this structurally FALSE at K = pool (the no-op configuration every baseline
     * scenario pins): there the park is pigeonhole-empty — a classifying thread always
     * finds a permit — and with all permits held every running worker holds one, so the
     * term reduces to the queued count, which sits below {@code queueCapacity ==
     * gateParkCapacity} whenever {@link #hasHeadroom} passed (the router checks
     * headroom FIRST). A bare {@code tasksInFlight} term would read saturated at
     * queue-nearly-full on K = pool and shift the disk-saturation baseline. Both
     * comparisons are {@code >=}: in-use may transiently exceed a lowered capacity,
     * and the park count transiently overshoots via claim-then-back-out.
     *
     * <p>Evaluated PER ENTRY on the processing thread (stale reads self-heal within
     * one classification latency — a park refill mid-drain is seen by the next
     * entry). Hit-heavy over-conservatism near queue-full is accepted: the stop is
     * re-evaluated every pass at ~20 Hz. One transient exception to the K = pool
     * argument: a K LOWERED at runtime parks entries, and raising it back to pool
     * can read saturated until that residue drains — self-clearing (nothing refills
     * a held park; every release drains), unreachable in any pinned scenario.
     */
    public boolean gateSaturated() {
        int inUse = this.readGate.inUse();
        if (inUse < this.readGate.capacity()) return false;
        return this.gateParkedCount.get() + (this.tasksInFlight.get() - inUse)
                >= this.gateParkCapacity;
    }

    /** A saturation EPISODE = gate stops arriving with no quiet gap longer than this.
     *  Any pass that admits (or any second without a stop) ends the episode. */
    private static final long GATE_STOP_EPISODE_GAP_NANOS = 1_000_000_000L;
    /** Episode length that latches the once-per-session capacity WARN: ~3 s of
     *  repeated stops is sustained load. A cumulative count was the first shape here
     *  and was wrong both ways (3-Opus round): 20 isolated stops spread over hours
     *  latched a WARN claiming "sustained load", and one 2-tick blip on a 10-player
     *  server latched it in ~100 ms (the counter grows ~N players x 20 Hz). */
    private static final long GATE_STOP_WARN_SUSTAIN_NANOS = 3_000_000_000L;
    private volatile boolean gateStopWarnLatched = false;
    // Episode tracking — processing-thread only (recordGateStop's caller contract).
    private boolean gateStopSeen = false;
    private long gateStopEpisodeStartNanos;
    private long gateStopLastNanos;

    /**
     * Books one gate-stopped router pass (Amendment 2). The capacity WARN is LATCHED
     * once per session (the store-eviction precedent — a per-minute re-key fired 3-5
     * times during one legitimate cold join, the exact noise retention removes) and
     * only on a SUSTAINED episode: running totals live in {@code /lsslod diag}'s
     * {@code gate_stops=} token either way.
     */
    public void recordGateStop() {
        recordGateStop(System.nanoTime());
    }

    /** Clock-injectable seam (the latch is time-based). Processing thread only. */
    void recordGateStop(long nowNanos) {
        this.diag.recordGateStop();
        if (this.gateStopWarnLatched) return;
        if (!this.gateStopSeen
                || nowNanos - this.gateStopLastNanos > GATE_STOP_EPISODE_GAP_NANOS) {
            this.gateStopEpisodeStartNanos = nowNanos; // a new episode begins
            this.gateStopSeen = true;
        }
        this.gateStopLastNanos = nowNanos;
        if (nowNanos - this.gateStopEpisodeStartNanos >= GATE_STOP_WARN_SUSTAIN_NANOS) {
            this.gateStopWarnLatched = true;
            LSSLogger.warn("Disk reads are concurrency-gated under sustained load (read_gate="
                    + this.readGate.capacity() + "/" + this.readGate.capacity()
                    + ", park full): the request router is deferring cold-region reads to the"
                    + " next client declaration (running total: gate_stops= in /"
                    + Brand.serverCommand() + " diag). This is the gate working; raise"
                    + " maxConcurrentDiskReads in " + dev.vox.lss.common.Brand.lowerShortName()
                    + "-server-config.json if server CPU"
                    + " headroom allows and you want faster cold backfill. (Logged once per"
                    + " session.)");
        }
    }

    /** Latch observability for the Tier 1 episode-detector pins. */
    boolean gateStopWarnLatchedForTest() {
        return this.gateStopWarnLatched;
    }

    /** The native→v20 body translator for pre-migration {@code wirefmt=19} store rows
     *  (C4, XVER §5.3): platform-wired ({@code NbtSectionSerializer.toV20} against the
     *  server's own registries). REQUIRED for 19-row serves — {@code raw() == v20} is a
     *  C2 pipeline invariant (the client decodes v20; the legacy egress translates FROM
     *  v20), so an unwired translator reads a 19-hit as an errored miss rather than
     *  leaking native bytes downstream. Runs on the reader pool (the emit tables are
     *  memoized and thread-safe). */
    private volatile java.util.function.UnaryOperator<byte[]> storeLegacyTranslator;
    /** Resolved once on the first 19-row frame serve (review #5 — {@code zstdOrNull()}
     *  runs a full compress/decompress self-test per call; per-serve probing burned
     *  that on EVERY legacy hit). A store frame existing at all proves the natives
     *  loaded, so one probe suffices for the process lifetime. */
    private volatile dev.vox.lss.common.store.StoreCodec legacyRowCodec;

    public final void setStoreLegacyTranslator(java.util.function.UnaryOperator<byte[]> t) {
        this.storeLegacyTranslator = t;
    }

    /** Attach the LOD store (lodStore != off). Must happen before the first submit. */
    public final void attachStore(dev.vox.lss.common.store.LodStoreService store) {
        this.store = store;
    }

    /** Attach the region stamp table (the P1 header freshness rung's oracle). Must
     *  happen before the first submit; null (bare test rigs) leaves the rung inert. */
    public final void attachRegionStamps(dev.vox.lss.common.region.RegionStampTable table) {
        this.regionStamps = table;
    }

    /**
     * Enable frame-form store serving (protocol 19, plan §3): the rung consults
     * {@code getFrame} instead of {@code get}, delivering the stored zstd frame
     * verbatim (zero decompress; raw-needing recipients materialize lazily on the
     * processing thread). Set by the services ONLY while wire compression is live —
     * with compression off, frame hits would cost every recipient a processing-thread
     * decompress that {@code get} pays on the reader pool instead. Volatile: set once
     * at service init.
     */
    public final void setServeStoreFrames(boolean serveFrames) {
        this.serveStoreFrames = serveFrames;
    }

    /**
     * Idempotently enable the adaptive-throttle fallback (a platform reader's background-priority
     * path reported itself incompatible — a chunk-IO-overhaul mod replaced vanilla IO). Safe from
     * any thread; the first caller wins. The throttle starts at the pool's full depth (optimistic),
     * so enabling it does not restrict until measured read latency actually rises.
     */
    protected final void enableAdaptiveThrottleFallback() {
        if (this.throttle == null) {
            synchronized (this) {
                if (this.throttle == null) {
                    this.throttle = AdaptiveReadThrottle.forPool(this.threadCount,
                            QUEUE_CAPACITY_PER_THREAD, LSSConstants.ADAPTIVE_READ_TARGET_LATENCY_MS);
                }
            }
        }
    }

    /** The adaptive throttle's current effective concurrency limit, or -1 when it is not engaged
     *  (the normal working-A path). For {@code /lsslod diag}. */
    public int adaptiveThrottleLimitOrDisabled() {
        var t = this.throttle;
        return t == null ? -1 : t.currentLimit();
    }

    /** Package-private test seam: the live throttle instance (null until engaged), so the
     *  in-package wiring test can drive its AIMD limit with synthetic latency samples without
     *  occupying a pool thread. Production reads throttle state via
     *  {@link #adaptiveThrottleLimitOrDisabled()}. */
    AdaptiveReadThrottle throttleForTest() {
        return this.throttle;
    }

    protected boolean isShutdown() {
        return this.isShutdown.get();
    }

    /**
     * Submit a read: the operation runs on the reader pool and its outcome is triaged into
     * the player's result queue (store hit / data / all-air / not-found / saturated).
     * The store rung inside {@link #readAndDeliver} runs first; only a MISS proceeds to
     * region IO and the {@code disk.*} counters — {@code disk.submitted} therefore counts
     * at the start of the NBT path, not here (the rung contract: hits are excluded from
     * the disk pair and the throttle EWMA). Error containment lives inside
     * {@code readAndDeliver} (broadened to {@link Throwable}: an {@link Error} — SOE on
     * corrupt NBT — still produces a result first, or the request would strand its
     * admission slot + dedup group; the re-throw after bookkeeping is best-effort only,
     * FutureTask captures it into a Future nobody inspects).
     */
    protected final void submitRead(UUID playerUuid, int chunkX, int chunkZ, String dimension,
                                    long submissionOrder, ReadOperation operation) {
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder, 0L, operation);
    }

    /** Standalone reader admission: capture the registered sink before scheduling. */
    protected final void submitRead(UUID playerUuid, int chunkX, int chunkZ, String dimension,
                                    long submissionOrder, long clientTimestamp, ReadOperation operation) {
        var registration = this.playerResults.get(playerUuid);
        if (registration != null) submitRead(playerUuid, registration, chunkX, chunkZ,
                dimension, submissionOrder, clientTimestamp, operation);
    }

    /** Platform admission carries the originating player state, never a completion-time UUID lookup. */
    protected final void submitRead(UUID playerUuid, RequestRegistration registration,
                                    int chunkX, int chunkZ, String dimension, long submissionOrder,
                                    long clientTimestamp, ReadOperation operation) {
        if (isShutdown() || registration.isRetired()) return;

        try {
            this.tasksInFlight.incrementAndGet();
            this.executor.submit(() -> {
                try {
                    if (!isShutdown()) {
                        readAndDeliver(playerUuid, registration, chunkX, chunkZ, dimension, submissionOrder,
                                clientTimestamp, operation);
                    }
                } catch (Throwable t) {
                    // Last-resort containment (Phase 1 review MAJOR-1): every expected
                    // failure is handled INSIDE readAndDeliver (the store-rung belt, the
                    // op-region catch); reaching here means an unexpected throw outside
                    // those islands (result construction, queue append, an op-path Error
                    // re-thrown after its own bookkeeping). A result MUST still be
                    // delivered or the pending entry + dedup group wedge the position
                    // behind Duplicate.IN_FLIGHT for the whole session. Disk counters
                    // are deliberately untouched (state unknown — identity drift only on
                    // OOM-class events); a duplicate result for the Error path resolves
                    // as the documented ghost (pending already gone, silent drop).
                    // Since the gate's park drain, an OOM-class throw ESCAPING a DRAINED
                    // parked entry lands here with the ORIGINAL task's coords: the
                    // original gets the duplicate-ghost above while the drained entry's
                    // pending wedges uncontained — the same accepted OOM-class residual,
                    // now two positions wide (review B-4).
                    long fails = this.deliveryFailWarn.recordAndTryAcquire(
                            System.nanoTime() / 1_000_000);
                    if (fails > 0) {
                        LSSLogger.error("Unexpected failure delivering disk read at "
                                + chunkX + ", " + chunkZ + (fails > 1 ? " (+" + (fails - 1)
                                + " more since last report)" : ""), t);
                    }
                    addResult(registration, ChunkReadResult.notFoundFromError(
                            playerUuid, chunkX, chunkZ, dimension, submissionOrder));
                } finally {
                    this.tasksInFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            this.tasksInFlight.decrementAndGet();
            // nanoTime, not currentTimeMillis: the wall clock can step backwards (NTP), which
            // would silence the aggregate warning exactly while the pool is behind demand.
            long rejected = this.saturationWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (rejected > 0) {
                LSSLogger.warn("Disk reader saturated: " + rejected + " chunk read(s) dropped"
                        + " since the last warning — clients re-request automatically; raise"
                        + " diskReaderThreads in " + dev.vox.lss.common.Brand.lowerShortName()
                        + "-server-config.json if this persists");
            }
            // The bounce never consulted the store or storage: submitted+saturated+completed
            // recorded together so the at-rest identity (completed == outcomes) holds.
            this.diag.recordSubmitted();
            this.diag.recordSaturation();
            this.diag.recordCompleted(0);
            addResult(registration, ChunkReadResult.saturated(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
        }
    }

    /** Record a REAL read completion: the diagnostics count plus, when the adaptive throttle is
     *  engaged, the measured submit->result latency (the 0-latency bounce/error-before-IO paths
     *  are NOT fed — they measured no IO and would poison the EWMA). */
    private void recordRealCompletion(long elapsedNanos) {
        this.diag.recordCompleted(elapsedNanos);
        var t = this.throttle;
        if (t != null) t.recordLatency(elapsedNanos);
    }

    /**
     * The store rung (§1 rung contract): consult the LOD store before any region IO. A
     * hit delivers the STORED bytes + STORED timestamp (delivery honesty — never a
     * fabricated fresh stamp) tagged {@code fromStore}, touching NEITHER the
     * {@code disk.*} counters NOR {@link #recordRealCompletion} — a sub-100 µs hit fed
     * to the AIMD EWMA would collapse the adaptive limit on exactly the C2ME-latched
     * servers where the throttle is the only gameplay protection. {@code byte[0]} from
     * the store means all-air (delivered as the all-air result shape, never as
     * not-found — null section bytes would read as an authoritative miss and seed the
     * miss memo). {@link dev.vox.lss.common.store.LodStoreService#get} is contained by
     * contract (a store failure reads as a miss and counts {@code store.errors}).
     */
    private boolean storeServedHit(UUID playerUuid, RequestRegistration registration, int chunkX, int chunkZ, String dimension,
                                    long submissionOrder) {
        var s = this.store;
        if (s == null) return false;
        long packed = dev.vox.lss.common.PositionUtil.packPosition(chunkX, chunkZ);
        long t0 = System.nanoTime();
        if (this.serveStoreFrames) {
            // Frame-form rung (protocol 19, plan §3): the stored zstd frame ships
            // VERBATIM — zero decompress here, zero compress downstream. Exactly ONE of
            // getFrame/get is consulted per submit (a getFrame miss falls to region IO,
            // never to a second get() of the same row).
            dev.vox.lss.common.store.LodStoreService.FrameHit hit;
            try {
                hit = s.getFrame(dimension, packed);
            } catch (Throwable t) {
                s.diagnostics().recordError();
                hit = null;
            }
            if (hit == null) {
                s.diagnostics().recordMiss();
                return false;
            }
            if (hit.usize() > 0 && (hit.frame() == null || hit.frame().length == 0)) {
                // Contract-violation belt, twin of the raw rung's null-sectionBytes
                // guard (4-agent round, store F1): a data-claiming FrameHit with no
                // frame would flow downstream as a null-bytes holder and read as
                // ALL-AIR — an authoritative clearing column fabricated over real
                // terrain. Contain as an errored miss; the NBT ladder serves truth.
                s.diagnostics().recordError();
                s.diagnostics().recordMiss();
                return false;
            }
            if (hit.usize() == 0) {
                s.diagnostics().recordHit(System.nanoTime() - t0);
                // All-air: same result shape as the raw rung (null section bytes,
                // never not-found — a null read as an authoritative miss would seed
                // the miss memo falsely).
                addResult(registration, new ChunkReadResult(playerUuid, chunkX, chunkZ, null,
                        dimension, 0, hit.columnTimestamp(),
                        false, false, false, true, submissionOrder, 0L));
                return true;
            }
            if (hit.wirefmt() == dev.vox.lss.common.store.LodStoreService.WIREFMT_NATIVE_19) {
                // Pre-migration native-layout row (C4, XVER §5.3): decompress (fhash
                // already validated in the store) and translate to the canonical v20
                // form HERE — raw()==v20 is a C2 pipeline invariant, and a verbatim
                // native frame would be mis-decoded by every consumer. Delivered as
                // RAW bytes; the delivery re-compresses per recipient capability
                // (ColumnBytes.frame()) as with any raw source. Cost: tens of µs,
                // doubly decaying (the walk migrates rows; clients update).
                byte[] v20 = translateLegacyStoreRow(s, hit.frame(), hit.usize());
                if (v20 == null) {
                    return false; // errored miss, counted; the NBT ladder serves truth
                }
                // Hit recorded only on translation SUCCESS (review m17): a failed
                // translation is an errored miss and must not also book a hit.
                s.diagnostics().recordHit(System.nanoTime() - t0);
                addResult(registration, new ChunkReadResult(playerUuid, chunkX, chunkZ, v20,
                        dimension, v20.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                        hit.columnTimestamp(),
                        false, false, false, true, submissionOrder, 0L));
                return true;
            }
            s.diagnostics().recordHit(System.nanoTime() - t0);
            int estimatedBytes = hit.usize() + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
            addResult(registration, new ChunkReadResult(playerUuid, chunkX, chunkZ, null,
                    dimension, estimatedBytes, hit.columnTimestamp(),
                    false, false, false, true, submissionOrder, 0L,
                    hit.frame(), hit.usize()));
            return true;
        }
        dev.vox.lss.common.store.LodStoreService.StoreHit hit;
        try {
            hit = s.get(dimension, packed);
        } catch (Throwable t) {
            // get() is contained by contract; this belt exists because an escaped throw
            // here would strand the request in flight (leaked slot + orphaned dedup
            // group) — a miss is always the safe reading.
            s.diagnostics().recordError();
            hit = null;
        }
        if (hit == null) {
            s.diagnostics().recordMiss();
            return false;
        }
        if (hit.sectionBytes() == null) {
            // Contract violation (all-air must be byte[0], never null — a null here
            // would deliver as an authoritative miss and seed the miss memo falsely).
            // Contain as an errored miss; the NBT ladder serves the truth.
            s.diagnostics().recordError();
            s.diagnostics().recordMiss();
            return false;
        }
        boolean allAir = hit.sectionBytes().length == 0;
        byte[] bytes = allAir ? null : hit.sectionBytes();
        if (!allAir
                && hit.wirefmt() == dev.vox.lss.common.store.LodStoreService.WIREFMT_NATIVE_19) {
            // Pre-migration native-layout row on the raw rung — same translation
            // contract as the frame rung above.
            bytes = translateLegacyRaw(s, bytes);
            if (bytes == null) {
                return false;
            }
        }
        s.diagnostics().recordHit(System.nanoTime() - t0);
        int estimatedBytes = allAir ? 0
                : bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        addResult(registration, new ChunkReadResult(playerUuid, chunkX, chunkZ, bytes,
                dimension, estimatedBytes, hit.columnTimestamp(),
                false, false, false, true, submissionOrder, 0L));
        return true;
    }

    /** Decompress + translate a 19-row frame to v20; null = contained errored miss. */
    private byte[] translateLegacyStoreRow(dev.vox.lss.common.store.LodStoreService s,
                                           byte[] frame, int usize) {
        var codec = this.legacyRowCodec;
        if (codec == null) {
            codec = dev.vox.lss.common.store.StoreCodec.zstdOrNull();
            if (codec == null) {
                // No re-probe latch needed: a FrameHit cannot exist unless the store
                // opened, which required the natives — this arm is belt only.
                s.diagnostics().recordError();
                s.diagnostics().recordMiss();
                return null;
            }
            this.legacyRowCodec = codec;
        }
        try {
            return translateLegacyRawOrThrow(codec.decompress(frame, usize));
        } catch (Throwable t) {
            s.diagnostics().recordError();
            s.diagnostics().recordMiss();
            return null;
        }
    }

    /** Translate native raw bytes to v20; null = contained errored miss. */
    private byte[] translateLegacyRaw(dev.vox.lss.common.store.LodStoreService s,
                                      byte[] nativeRaw) {
        try {
            return translateLegacyRawOrThrow(nativeRaw);
        } catch (Throwable t) {
            s.diagnostics().recordError();
            s.diagnostics().recordMiss();
            return null;
        }
    }

    private byte[] translateLegacyRawOrThrow(byte[] nativeRaw) {
        var translator = this.storeLegacyTranslator;
        if (translator == null) {
            // Unwired translator (test rigs) + a 19-row: never leak native bytes into
            // the v20 pipeline — the errored miss is the safe reading.
            throw new IllegalStateException("no store legacy translator wired for a"
                    + " wirefmt=19 row");
        }
        return translator.apply(nativeRaw);
    }

    private void readAndDeliver(UUID playerUuid, RequestRegistration registration, int chunkX, int chunkZ, String dimension,
                                 long submissionOrder, long clientTimestamp,
                                 ReadOperation operation) {
        if (isShutdown()) return;
        // Header freshness rung (region-summary-sync-plan.md P1) — FIRST: cheaper than
        // the store rung once memoized (pure memory vs a b-tree row fetch) and it makes
        // the store lookup moot when it fires (the client's copy is current; no bytes of
        // any provenance are needed). Runs BEFORE the disk-read gate deliberately: the
        // memoized cost is one stat + one 8 KiB read per region per horizon, and every
        // doubt state is a cached sentinel, so this can never become the ungated bulk
        // IO the gate exists to bound. ts>0 only — an acquisition ask (ts<=0) has
        // nothing to validate. The claim must clear the stamp PLUS the serve-latency
        // margin (P1 review MAJOR): client stamps are issued at read COMPLETION, so a
        // read that raced a pending write can carry pre-change bytes stamped up to a
        // full read duration (bounded by the read timeout) after the change's header
        // second — a bare strict compare would validate exactly those. The margin also
        // absorbs the latch's unrelated-save-clears-early corner (RegionStampTable
        // javadoc). UNKNOWN/NEVER_CLEAN fall through — never answer from doubt.
        if (clientTimestamp > 0) {
            var rs = this.regionStamps;
            if (rs != null) {
                long stamp;
                try {
                    stamp = rs.chunkStampSecondsOrUnknown(dimension, chunkX, chunkZ);
                } catch (Throwable t) {
                    // Belt: an escaped throw here would strand the pending entry + dedup
                    // group behind Duplicate.IN_FLIGHT for the session. Doubt = read.
                    stamp = dev.vox.lss.common.region.RegionStampTable.UNKNOWN;
                }
                if (stamp >= 0 && stamp != dev.vox.lss.common.region.RegionStampTable.NEVER_CLEAN
                        && stamp + HEADER_FRESH_MARGIN_SECONDS < clientTimestamp) {
                    this.diag.recordHeaderHit();
                    // The result carries the MARGINED bound, so the delivery-side
                    // per-recipient compare and the tscache refresh (stamp + 1)
                    // inherit the margin without a second constant.
                    addResult(registration, ChunkReadResult.headerFresh(playerUuid, chunkX,
                            chunkZ, dimension, submissionOrder,
                            stamp + HEADER_FRESH_MARGIN_SECONDS));
                    return;
                }
            }
        }
        if (storeServedHit(playerUuid, registration, chunkX, chunkZ, dimension, submissionOrder)) return;

        // The disk-read concurrency gate (disk-read-concurrency-gate-plan.md): the
        // expensive NBT phase starts here, so the permit check sits AFTER the store rung
        // (a hit never consumes a permit) and BEFORE recordSubmitted (a gated read never
        // enters the disk.submitted/completed pair — the store-hit exclusion precedent;
        // law A5's partition would otherwise break). A refused acquire PARKS the read
        // (see the gateParked field comment — the pure bounce starved permit holders);
        // only park OVERFLOW bounces, reusing the saturated flavor: deliverDiskResult
        // routes it to the silent superseded drop with dedup fan-out, no memo seed, no
        // generation escalation, no wire answer — healed by re-declaration ≤1 s.
        if (!this.readGate.tryAcquire()) {
            // Claim-then-back-out makes the park bound EXACT (review B-2: a get()-then-add
            // check let N workers pass at cap-1 concurrently, admitting up to N-1 extras).
            if (this.gateParkedCount.incrementAndGet() > this.gateParkCapacity) {
                this.gateParkedCount.decrementAndGet();
                // DELIBERATELY LOG-FREE (2026-08-13, operator-log-hygiene decision — the
                // overflow WARN fired on a live server and repeated on its throttle
                // interval): overflow is RACE ARMOR (submissions already in flight when
                // the park filled), self-heals by re-declaration ≤ 1 s, and is fully
                // observable as the always-rendered `gated=` diag token / the exporters'
                // `disk.gated` counter. The actionable capacity signal is the LATCHED
                // gate-stop WARN on the router-retention path, not this race.
                this.diag.recordGated();
                addResult(registration, ChunkReadResult.saturated(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
                return;
            }
            this.gateParked.add(new ParkedRead(playerUuid, registration, chunkX, chunkZ, dimension,
                    submissionOrder, operation));
            // Missed-wakeup guard: a release between our failed acquire and the add
            // found an empty park list and drained nothing — re-check ourselves.
            drainGateParked();
            return;
        }
        try {
            gatedReadAndDeliver(playerUuid, registration, chunkX, chunkZ, dimension, submissionOrder, operation);
        } finally {
            // Release on EVERY outcome — including the timeout triage, where future.get
            // throws at DISK_READ_TIMEOUT_SECONDS and the orphaned downstream fetch keeps
            // running OUTSIDE the permit (bounded: the vanilla IOWorker executor is
            // single-threaded; Moonrise self-prioritizes at LOW). Documented, accepted.
            this.readGate.release();
            // Feed the freed permit from the park list (the starvation fix's other
            // half): the releasing worker runs parked expensive reads back-to-back, so
            // permits stay utilized while OTHER workers keep draining the pool queue
            // (store hits, parks, bounces).
            drainGateParked();
        }
    }

    /**
     * Drain parked reads while a permit is free — the loop that keeps permit holders
     * fed. Never blocks: a failed acquire means the current holders will drain on
     * their own release. The parked entry deliberately does NOT re-run the store rung
     * (exactly one store lookup per submission keeps the store hit/miss counters
     * one-to-one with lookups; the forgone deposit-during-park serve is a micro-win
     * not worth the accounting split).
     */
    private void drainGateParked() {
        while (!this.gateParked.isEmpty() && !isShutdown()) {
            if (!this.readGate.tryAcquire()) return;
            var parked = this.gateParked.poll();
            if (parked == null) {
                // Another drainer stole the last entry between our isEmpty and poll.
                // Release and RE-CHECK (review B-MAJOR-1): returning here was the one
                // exit that dropped the permit without re-observing the list — a parker
                // whose own self-drain raced into our sub-µs hold would strand its entry
                // with zero holders left to drain it (a session-length IN_FLIGHT wedge,
                // not a drop-heal). The loop's isEmpty re-check closes the window.
                this.readGate.release();
                continue;
            }
            this.gateParkedCount.decrementAndGet();
            try {
                gatedReadAndDeliver(parked.playerUuid(), parked.registration(), parked.chunkX(), parked.chunkZ(),
                        parked.dimension(), parked.submissionOrder(), parked.operation());
            } finally {
                this.readGate.release();
            }
        }
    }

    /** The expensive phase — every path through here holds a gate permit. */
    private void gatedReadAndDeliver(UUID playerUuid, RequestRegistration registration, int chunkX, int chunkZ, String dimension,
                                     long submissionOrder, ReadOperation operation) {
        long startNs = System.nanoTime();
        // Freshness stamp at READ START (R1-M2): the bytes the read produces reflect
        // region state no earlier than this second, so any save landing during the read
        // or in the read→deposit gap has a header stamp >= it and the sweep drops the
        // row. Stamping later (completion/deposit-call) left those saves invisible.
        long srcStampSeconds = LSSConstants.epochSeconds();
        this.diag.recordSubmitted(); // the NBT path begins here — store hits never count

        byte[] serializedSections;
        try {
            serializedSections = operation.read();
        } catch (Throwable e) {
            // Failure shapes here arrive BOTH wrapped (fetch failures in
            // ExecutionException from future.get) and unwrapped (the B3 split's
            // pool-side parse throws raw). The triage deliberately branches on nothing
            // but the top-level TimeoutException — do NOT add an ExecutionException
            // unwrap, it would silently reclassify the split path (B3 review F9).
            if (e instanceof java.util.concurrent.TimeoutException) {
                // A read exceeding DISK_READ_TIMEOUT_SECONDS is a documented TRANSIENT on
                // slow IO under generation save pressure (miss-memo-design.md A/B finding):
                // it triages down the not-found ladder and self-heals via re-declaration.
                // One throttled line, no stack — a storm of these is diagnosable from
                // disk.errors, and per-chunk stack traces were pure console flooding.
                long releases = this.timeoutWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (releases > 0) {
                    LSSLogger.warn("Disk read timed out (>" + LSSConstants.DISK_READ_TIMEOUT_SECONDS
                            + "s) at " + chunkX + ", " + chunkZ + " — triaged as not-found"
                            + " (counted disk.errors; self-heals by re-declaration on"
                            + " gen-enabled servers)"
                            + (releases > 1 ? " (+" + (releases - 1) + " more since last report)" : ""));
                }
            } else {
                long fails = this.readErrorWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (fails > 0) {
                    LSSLogger.error("Failed to read chunk NBT from disk at " + chunkX + ", "
                            + chunkZ + (fails > 1 ? " (+" + (fails - 1)
                            + " more since last report)" : ""), e);
                }
            }
            this.diag.recordError();
            recordRealCompletion(System.nanoTime() - startNs);
            // Error/timeout TRIAGED as not-found (law A5's disk.errors fold) — says nothing
            // about existence, so it must never seed the miss memo.
            addResult(registration, ChunkReadResult.notFoundFromError(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            // Deliberately NO Error re-throw (the pre-Phase-1 shape re-threw best-effort
            // into a FutureTask nobody inspects — provably unobservable): the last-resort
            // catch in the submit lambda would now see it and deliver a SECOND result,
            // breaking the one-result-per-submit envelope. Containment + delivery above
            // are complete; the pool thread survives either way.
            return;
        }

        if (serializedSections == null) {
            this.diag.recordNotFound();
            recordRealCompletion(System.nanoTime() - startNs);
            addResult(registration, ChunkReadResult.notFoundAuthoritative(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            return;
        }

        long columnTimestamp = LSSConstants.epochSeconds();

        if (serializedSections.length == 0) {
            // Chunk exists on disk (FULL status) but is all air — resolve as found, not "not found"
            this.diag.recordAllAir();
            recordRealCompletion(System.nanoTime() - startNs);
            addResult(registration, new ChunkReadResult(playerUuid, chunkX, chunkZ,
                    null, dimension, 0, columnTimestamp, false, false, false, false,
                    submissionOrder, srcStampSeconds));
            return;
        }

        int estimatedBytes = serializedSections.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        this.diag.recordSuccess();
        recordRealCompletion(System.nanoTime() - startNs);
        addResult(registration, new ChunkReadResult(playerUuid, chunkX, chunkZ,
                serializedSections, dimension, estimatedBytes, columnTimestamp,
                false, false, false, false, submissionOrder, srcStampSeconds));
    }

    /** Standalone readers may own a registration without a platform player state. */
    public void registerPlayer(UUID playerUuid) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new RequestRegistration());
    }

    public void registerPlayer(UUID playerUuid, RequestRegistration registration) {
        this.playerResults.compute(playerUuid, (uuid, old) -> {
            if (old != null && old != registration) old.retire();
            return registration;
        });
    }

    private void addResult(RequestRegistration registration, ChunkReadResult result) {
        registration.addResult(result);
    }

    public ConcurrentLinkedQueue<ChunkReadResult> getPlayerQueue(UUID playerUuid) {
        var registration = this.playerResults.get(playerUuid);
        return registration == null ? null : registration.results();
    }

    public ConcurrentLinkedQueue<ChunkReadResult> getPlayerQueue(UUID playerUuid,
                                                               RequestRegistration registration) {
        return this.playerResults.get(playerUuid) == registration && !registration.isRetired()
                ? registration.results() : null;
    }

    public void removePlayerResults(UUID playerUuid) {
        var removed = this.playerResults.remove(playerUuid);
        if (removed != null) removed.retire();
    }

    public void removePlayerResults(UUID playerUuid, RequestRegistration registration) {
        this.playerResults.remove(playerUuid, registration);
        registration.retire();
    }

    /**
     * Configure the disk-read gate's capacity K (resolved via
     * {@code ServerConfigBase.effectiveMaxConcurrentDiskReads} — the caller owns both
     * runtime facts the store-conditional AUTO needs: the resolved pool size and the
     * POST-DEGRADE store attachment). Called once at service init, after store
     * attachment; stage C's {@code /lsslod set} reuses it for runtime mutation (the
     * capacity is a volatile read per acquire).
     */
    public void configureReadGate(int capacity) {
        this.readGate.updateCapacity(capacity);
    }

    /** The gate's configured capacity K — echo/diag reads. */
    public int readGateCapacity() {
        return this.readGate.capacity();
    }

    /**
     * Runtime K re-resolution (v0.11.0 stage C, R-2: the reader owns BOTH facts the
     * store-conditional resolver needs — its own pool size and its attached store).
     * Called from the owning service's tick pass; cheap no-op on an unchanged K.
     * Lowering below in-use follows the gate's documented drain semantics.
     */
    public void reapplyGateCapacity(dev.vox.lss.common.config.ServerConfigBase config) {
        int k = config.effectiveMaxConcurrentDiskReads(this.threadCount, this.store != null);
        if (k != this.readGate.capacity()) {
            this.readGate.updateCapacity(k);
        }
    }

    public String getDiagnostics() {
        String base = this.diag.formatDiagnostics(getPendingResultCount())
                // Always rendered (a no-op gate shows read_gate=0/<pool>, gated=0): the
                // live-deploy log records this token as the config-era receipt, and an
                // absent-when-inert token would make "gate not binding" and "build
                // without the gate" indistinguishable over RCON. gate_parked is a gauge
                // (diag-only, NEVER exported — the store.queue trap); gate_stops counts
                // router passes stopped by saturation (Amendment 2 retention — the
                // capacity-pressure signal); gated counts park OVERFLOW bounces only
                // (race armor, expected ~0) — mechanism before armor, gated= last.
                + ", read_gate=" + this.readGate.inUse() + "/" + this.readGate.capacity()
                + ", gate_parked=" + this.gateParkedCount.get()
                + ", gate_stops=" + this.diag.getGateStopsCount()
                + ", gated=" + this.diag.getGatedCount();
        // The throttle is engaged only on the Fabric A-incompatible fallback path (a chunk-IO mod
        // replaced vanilla IO). On the normal working-A path it is null and the line is unchanged,
        // so existing diagnostics goldens do not move; when engaged it makes the fallback observable
        // (the only end-to-end signal — no automated test can reach the C2ME path). limit/max shows
        // how far AIMD has backed LSS off under shared-IO load.
        var t = this.throttle;
        if (t == null) return base;
        return base + ", read_throttle=ENGAGED(" + t.currentLimit() + "/" + t.maxLimit() + ")";
    }

    /** Read results delivered but not yet drained by the processing thread, across all players. */
    public int getPendingResultCount() {
        int pending = 0;
        for (var queue : this.playerResults.values()) {
            pending += queue.results().size();
        }
        return pending;
    }

    public DiskReaderDiagnostics getDiag() { return this.diag; }

    public void shutdown() {
        this.isShutdown.set(true);
        this.executor.shutdownNow();
        try {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LSSLogger.warn("Disk reader threads did not terminate within 5 seconds");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        this.playerResults.clear();
    }
}
