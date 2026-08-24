package dev.vox.lss.common.processing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private volatile boolean wantsCompressedColumns;
    // The dimension this state was REGISTERED under — invariant for the state's lifetime
    // (a dimension change replaces the state via removePlayer+registerPlayer). The router
    // uses it to reject a stale snapshot's dimension: a multi-tick processing cycle can
    // otherwise route the NEW session's requests under the OLD snapshot's dimension,
    // leaking pending slots against disk reads whose results are dimension-skipped.
    // Null in bare test rigs (guard is skipped) — both platform services always set it.
    private volatile String registeredDimension;

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
    // Duplicate-serve grace (docs/planning/duplicate-serve-grace.md): packed position →
    // send-queue departure stamp (nanos), written ONLY on the send-SUCCESS path — the
    // send-failure bulk drop must never stamp (those columns never reached the wire; their
    // done-bits are proactively cleared via OffThreadProcessor.clearDiskReadDone and the
    // honesty rung exists for exactly that loss class). Read by the router (processing
    // thread) to absorb the accepted crossing race: a ts<=0 re-declaration built before
    // its own payload departed. Dies with the state on player removal AND dimension change
    // (both replace the state via removePlayer+registerPlayer on both platforms). Most
    // stamps are never re-asked, so flushSendQueue sweeps expired entries once per grace
    // interval — without that the map would grow with every column ever sent.
    private final ConcurrentHashMap<Long, Long> departedColumns = new ConcurrentHashMap<>();
    private long departureGraceNanos = LSSConstants.SEND_DEPARTURE_GRACE_MILLIS * 1_000_000L;
    private java.util.function.LongSupplier departureClock = System::nanoTime;
    private long departedSweepMarkNanos; // main thread only (flushSendQueue)
    private final PlayerBandwidthTracker bandwidth;
    private final AtomicLong totalRequestsReceived = new AtomicLong();
    // Single-writer (main thread) — volatile for cross-thread visibility to processing thread
    private volatile int sendQueueSizeSnapshot = 0;

    /**
     * Transport-pressure probe for this player (elytra-wall investigation §8.3). Defaults
     * to {@link ChannelPressureProbe#NO_SIGNAL} so every test rig and any platform that has
     * not wired one behaves exactly as before.
     */
    private volatile ChannelPressureProbe channelPressure = ChannelPressureProbe.NO_SIGNAL;
    /** Last sampled outbound-buffer depth, and the session high-water. -1 = no signal. */
    private volatile long outboundPendingBytes = -1;
    private volatile long outboundPendingHighWater = -1;
    // ---- Send pacing (enableSendPacing, send-pacing-plan.md v3) ----
    /** Refill-floored proportional drain: an over-floor backlog spreads over this many
     *  ticks (exponentially — each tick ships Q/HORIZON of what remains, clamped). */
    static final int PACE_HORIZON_TICKS = 10;
    /** Ceiling on the proportional term, in refill shares (the impl-review clamp): a
     *  DEEP backlog (real-terrain waves reach ~2.5x allocation queued) would otherwise
     *  let Q/HORIZON meet the bank and unbound the very first tick this mechanism
     *  exists to bound — while the term itself stays load-bearing BELOW 20 TPS (the
     *  floor is tick-denominated, the limiter wall-clock-denominated, so on a slow
     *  server the queue term is what restores cap-rate delivery; 2 shares covers full
     *  rate down to 10 TPS, and a server below that is degraded everywhere). Bounds:
     *  bank-sized waves pace at ONE share (a ~5-tick slope — bank = 5 shares = the
     *  client's fast-fire floor, cadence-neutral); deep backlogs at most TWO. */
    static final int PACE_MAX_BURST_SHARES = 2;
    /** Ticks the pace budget stopped a PARTIAL flush — the `paced=` diag receipt (a
     *  mechanism counter beside deferred=/yielded=, never a loss signal). */
    private volatile long pacedTicks = 0;

    private static final dev.vox.lss.common.LogThrottle SEND_FAIL_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);

    // ---- Transport yield (lodYieldsToVanillaTransport, yield plan v2.1) ----
    /** 100 ticks = 5 s: the §1.4 starvation floor — bounds worst-case LOD at ~1 column/5 s
     *  and distinguishes "yielding hard" from "dead". Yield starvation only; the ceiling
     *  path has no floor (F2-7). */
    public static final int YIELD_FLOOR_TICKS = 100;
    /** ~once a minute at 20 TPS: the §2.1 relevance-prune cadence (O(n) removeIf over a
     *  ≤1024-entry queue at this cadence is noise). */
    static final int PRUNE_INTERVAL_TICKS = 1200;
    private volatile long yieldedTicks = 0;
    private int yieldNoSendTicks = 0;
    private int flushTickCounter = 0;
    /** One INFO per PLAYER SESSION the first time that player rides the floor — the log
     *  archive's answer to an after-the-fact "LOD was slow for X" report (yield plan §5;
     *  user decision 2026-08-13: was one-per-JVM, which hid every player after the
     *  first). Instance field, so the latch lives exactly as long as this state: a
     *  dimension change mid-session builds a fresh state and may log once more — the
     *  accepted corner of state-scoped latching. */
    private boolean sustainedYieldNoted;

    // ---- The vanilla-ping backstop (adaptive-transfer-rate-plan.md Mechanism B) ----
    // Per player per session by construction (dies with this state); observed on the
    // service pump, applied to the flush allocation (the m12 plumbing).
    private final PingBackstop pingBackstop = new PingBackstop();

    public PingBackstop getPingBackstop() { return this.pingBackstop; }

    // ---- Want-set mailbox + backlog (protocol v17) ----

    // Network/region thread → processing thread, latest-wins: a batch overwritten before
    // consumption is superseded wholesale — exactly the want-set replace semantics. This
    // bounds ingress at ONE batch (≤ MAX_BATCH_CHUNK_REQUESTS entries) regardless of client
    // behavior, replacing the old MAX_INCOMING_QUEUE flood bound.
    private final AtomicReference<IncomingBatch> pendingBatch = new AtomicReference<>();
    // Offer generation: bumped BEFORE every mailbox write so the Folia pump can detect a
    // batch that passed THROUGH the mailbox (offered AND taken by the processing thread)
    // during its one-tick probe hold. The republish CAS alone cannot see that — it finds
    // the mailbox empty again and would resurrect the held (stale) batch over the newer
    // declaration's already-applied backlog, breaking latest-wins. Bumping before the
    // write means an in-progress offer is never mistaken for quiet: the guard errs toward
    // dropping the held batch (a counted supersession, healed by the next 1 Hz
    // declaration), never toward resurrection.
    private final AtomicLong offerGeneration = new AtomicLong();
    // Cross-thread superseded/range-filtered events accumulate here and are drained into
    // ProcessingDiagnostics by the processing thread each cycle, preserving that class's
    // single-writer design. Counts pending at player removal die with the state (same as
    // the old incoming queue's contents — accepted, see the soak run-boundary handling).
    private final AtomicLong pendingSuperseded = new AtomicLong();
    private final AtomicLong pendingRangeFiltered = new AtomicLong();

    // Processing-thread-owned want backlog: replaced wholesale by each taken batch,
    // consumed in DECLARATION order. Ring-major clients emit closest-first; region-major
    // clients (the v0.13 region walk) emit region-locally ring-ascending — globally
    // NON-monotonic, but the property the pacing gates actually rely on holds on both:
    // the first unsatisfied entry is the nearest outstanding work of its active region.
    private final ArrayDeque<IncomingRequest> backlog = new ArrayDeque<>();
    // Single-writer (processing thread) — volatile for exporter/diagnostic reads.
    private volatile int backlogSizeSnapshot = 0;

    // The want-set the processing thread most recently applied to the backlog. This is the
    // main-thread probe's FALLBACK source, read when the mailbox is empty — which is almost
    // always: takeIncomingBatch() nulls the mailbox within ~50ms of arrival (the processing loop
    // polls at 20Hz) while batches arrive at 1-4Hz (the client's adaptive cadence), so a probe
    // reading the mailbox ALONE would see null on most ticks. This carries a want-set too large for the per-player slot
    // cap across the cycles that work it off. (The mailbox arm is not redundant with it: it is the
    // only thing that probes a batch on its ARRIVAL tick, before this field is even written — see
    // the probeLoadedChunks javadoc on either platform.) Published on apply, cleared when the
    // backlog drains so a converged player is not probed forever. Single-writer (processing
    // thread), volatile for the main thread's read; IncomingBatch is immutable.
    //
    // INVARIANT: published iff the backlog is non-empty — i.e. exactly while work is still owed.
    // Maintained in three places: replaceBacklogWith (apply), pollBacklog (null on drain-to-empty)
    // and restoreBacklog (republish, because the steady-state pass drains before it restores).
    private volatile IncomingBatch publishedWantSet;

    // The want-set most recently applied to the backlog, retained so restoreBacklog can republish
    // on a cycle where no new batch arrived (batches land at 1Hz; the pass runs at 20Hz).
    private IncomingBatch appliedWantSet;

    // Admission slots: held counts are derived from the pending map (single-writer:
    // processing thread; volatile for /lsslod command reads). The sync cap stays
    // immutable; genSlotCap is VOLATILE since v0.11.0 stage C (/lsslod set): the tick
    // pass writes it from the main/pump thread while tryAdmit reads it on the
    // PROCESSING thread — a plain field has no happens-before and a runtime change
    // could stay invisible forever (SET review MAJOR; matches the held-count volatiles).
    private final int syncSlotCap;
    private volatile int genSlotCap;
    private volatile int heldSyncSlots = 0;
    private volatile int heldGenSlots = 0;

    /** Wall-clock state creation == registration instant (the move-desync tracer's
     *  {@code since_s}; diagnostics-only, never load-bearing). */
    private final long createdAtMillis;

    protected AbstractPlayerRequestState(UUID playerUuid, int syncConcurrency, int genConcurrency) {
        this(playerUuid, syncConcurrency, genConcurrency, System::nanoTime);
    }

    /** Clock-injected flavor (tests): drives the bandwidth tracker's refills off a fake
     *  clock so gametests' accelerated tick rates can't starve wall-time refills. */
    protected AbstractPlayerRequestState(UUID playerUuid, int syncConcurrency, int genConcurrency,
                                         java.util.function.LongSupplier nanoClock) {
        this.playerUuid = playerUuid;
        this.syncSlotCap = syncConcurrency;
        this.genSlotCap = genConcurrency;
        this.bandwidth = new PlayerBandwidthTracker(nanoClock);
        this.createdAtMillis = System.currentTimeMillis();
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

    /**
     * True when this session's column payloads may ship zstd frames (codec 1). Derived
     * ONCE at registration by the service — the AND of four terms (plan §2): the client
     * declared {@link LSSConstants#CAPABILITY_ZSTD_COLUMNS}, {@code useCompressedColumns},
     * the server-side codec probe succeeded, and the session is NOT the v16 dialect (a
     * v16 handshake maliciously setting the bit must never get a codec byte — its wire
     * layout has nowhere to carry one). Volatile: written at registration on the
     * registering thread, read by the processing-thread payload builds.
     */
    public boolean wantsCompressedColumns() {
        return this.wantsCompressedColumns;
    }

    public void setWantsCompressedColumns(boolean wants) {
        this.wantsCompressedColumns = wants;
    }

    public void markHandshakeComplete() {
        this.hasHandshake = true;
    }

    public boolean hasCompletedHandshake() {
        return this.hasHandshake;
    }

    // ---- Want-set mailbox (any thread) ----

    /** Offer a decoded batch (any thread). Latest-wins; an overwritten batch is superseded. */
    public void offerIncomingBatch(IncomingBatch batch) {
        this.offerGeneration.incrementAndGet(); // BEFORE the write — see the field comment
        this.totalRequestsReceived.addAndGet(batch.size());
        var previous = this.pendingBatch.getAndSet(batch);
        if (previous != null) {
            this.pendingSuperseded.addAndGet(previous.size());
        }
    }

    /** The current offer generation. The Folia pump records this BEFORE taking a batch to
     *  hold (read-then-take: an offer slipping between the two can only make the eventual
     *  republish refuse spuriously — a counted, re-declaration-healed drop — never let a
     *  stale batch resurrect). */
    public long offerGeneration() {
        return this.offerGeneration.get();
    }

    /** Non-destructive read of the MAILBOX. Read by the main-thread probe (its first source:
     *  this is a batch that has not been applied yet, so it is probed on its arrival tick),
     *  the Folia hold-release, and tests. See also {@link #peekWantSet}, the fallback source
     *  for the ~19 ticks per second on which no batch arrives. */
    public IncomingBatch peekIncomingBatch() {
        return this.pendingBatch.get();
    }

    /** Main-thread loaded-chunk probe FALLBACK source (after {@link #peekIncomingBatch}): the
     *  applied want-set, or null when nothing pends. */
    public IncomingBatch peekWantSet() {
        return this.publishedWantSet;
    }

    /** Processing thread: publish on apply, publish(null) when the backlog drains to empty. */
    public void publishWantSet(IncomingBatch batch) {
        this.publishedWantSet = batch;
    }

    /** Consume the pending batch (processing thread; Folia pump during hold-release). */
    public IncomingBatch takeIncomingBatch() {
        return this.pendingBatch.getAndSet(null);
    }

    /**
     * Folia hold-release republish: put a held batch back ONLY if no newer batch arrived
     * during the hold — a newer batch supersedes the held one (never resurrect a
     * superseded want, modulo the nanoseconds-wide retract-failure window documented at
     * the second guard below). Returns true if republished.
     *
     * <p>Two guards, both needed. The mailbox CAS catches a newer batch still SITTING in
     * the mailbox; the offer-generation guard catches one that passed THROUGH it (offered
     * and taken by the processing thread during the hold) — the CAS alone finds the
     * mailbox empty again and would republish the stale batch over the newer
     * declaration's already-applied backlog. {@code heldAtGeneration} is the value of
     * {@link #offerGeneration()} the pump recorded before taking the batch to hold.
     */
    public boolean republishHeldBatch(IncomingBatch held, long heldAtGeneration) {
        if (this.offerGeneration.get() != heldAtGeneration
                || !this.pendingBatch.compareAndSet(null, held)) {
            this.pendingSuperseded.addAndGet(held.size());
            return false;
        }
        // Close the check-then-CAS window: a full offer+take pass-through between the
        // generation check and the CAS leaves the mailbox empty, so the CAS succeeded on
        // stale data. Re-check and retract. A failed retract means the republished batch
        // already left the mailbox again — overwritten by a newer offer (whose getAndSet
        // counted it superseded; benign), or TAKEN BY ROUTING before this retract could
        // land. The taken case is the one narrow resurrection window this ladder cannot
        // close (2026-08-05 review H1 corrected this comment — it used to claim the
        // pass-through's backlog replace supersedes the stale entries, but that replace
        // happened BEFORE the take: the stale batch's replace lands on top of it):
        // accounting stays balanced, its entries get ordinary dispositions, and the
        // client's next declaration (≤1 s fallback) re-supersedes the stale want-set —
        // one interval of stale routing, nanoseconds-wide to enter, judged not worth a
        // third guard's complexity.
        if (this.offerGeneration.get() != heldAtGeneration
                && this.pendingBatch.compareAndSet(held, null)) {
            this.pendingSuperseded.addAndGet(held.size());
            return false;
        }
        return true;
    }

    /** Record ingress entries dropped by the Chebyshev range guard (any thread). */
    public void recordRangeFiltered(int n) {
        if (n > 0) this.pendingRangeFiltered.addAndGet(n);
    }

    public long drainPendingSuperseded() { return this.pendingSuperseded.getAndSet(0); }
    public long drainPendingRangeFiltered() { return this.pendingRangeFiltered.getAndSet(0); }

    // ---- Backlog (processing thread only) ----

    /** Replace the backlog with a taken batch. Returns the number of dropped (superseded)
     *  entries. An empty batch is the explicit clear. Also publishes the want-set for the
     *  main-thread probe (an empty batch publishes null — nothing left to probe). */
    public int replaceBacklogWith(IncomingBatch batch) {
        int dropped = this.backlog.size();
        this.backlog.clear();
        Collections.addAll(this.backlog, batch.requests());
        this.backlogSizeSnapshot = this.backlog.size();
        this.appliedWantSet = batch.size() == 0 ? null : batch;
        publishWantSet(this.appliedWantSet);
        return dropped;
    }

    /** The player's ACTUAL chunk, stamped by the platform's main/pump thread each lifecycle
     *  tick — the ring origin for the order-spread gate and the inversion evidence counter.
     *  One packed volatile long so the processing thread never sees a torn x/z pair. */
    private static final long NO_PLAYER_CHUNK = Long.MIN_VALUE;
    private volatile long playerChunkPacked = NO_PLAYER_CHUNK;

    public void updatePlayerChunk(int cx, int cz) {
        this.playerChunkPacked = PositionUtil.packPosition(cx, cz);
    }

    /** The packed player chunk, or {@code Long.MIN_VALUE} before the first lifecycle
     *  stamp (the region-summary pump's anchor read — a not-yet-stamped state retries). */
    public long playerChunkPackedOrSentinel() {
        return this.playerChunkPacked;
    }

    // The LIVE frontier: ring (from the player chunk) of the first unsatisfied
    // ACQUISITION entry (ts<=0) of the drain pass — stamped by the router every pass
    // (~20 Hz); a pass with only unsatisfied REVALIDATION entries (ts>0) stamps its first
    // one at end of pass instead (the acquisition-frontier rule, 2026-08-18 —
    // docs/planning/gen-frontier-acquisition-anchor-plan.md). Timestamp/probe resolutions
    // never stamp. THE ANTI-STARVATION INVARIANT MOVED for the ts>0 population: a ts>0
    // in-flight entry no longer stamps the frontier, but by construction it sits in
    // pendingByPosition, and generationOvertakesNearerInFlight reads exactly that map —
    // a STRICTLY TIGHTER hold (a nearer pending SYNC blocks every farther gen candidate
    // outright; a nearer gen ticket collapses the band to cohort span 1). Do not "fix"
    // that pending-map rule without restoring a frontier carrier for ts>0 work.
    // ts<=0 in-flight entries still stamp at their own ring (mirroring the client
    // scanner's "awaited positions block ring confirmation"). -1 until the first stamp.
    // Processing thread only.
    private int liveFrontierRing = -1;

    /** Package-private admission-trace probes (flag-gated caller; processing thread only,
     *  like all pending-map access): the live frontier stamp, the candidate's ring from
     *  the player chunk, and the nearest pending SYNC / outstanding GENERATION rings
     *  ({sync, gen}, -1 when absent). Diagnostic only — never consulted by the gates. */
    int liveFrontierRingForTrace() {
        return this.liveFrontierRing;
    }

    int ringFromPlayerForTrace(int cx, int cz) {
        long player = this.playerChunkPacked;
        if (player == NO_PLAYER_CHUNK) return -1;
        return PositionUtil.chebyshevDistance(cx, cz,
                PositionUtil.unpackX(player), PositionUtil.unpackZ(player));
    }

    int[] nearestPendingRingsForTrace() {
        long player = this.playerChunkPacked;
        if (player == NO_PLAYER_CHUNK) return new int[]{-1, -1};
        int px = PositionUtil.unpackX(player);
        int pz = PositionUtil.unpackZ(player);
        int sync = -1, gen = -1;
        for (var e : this.pendingByPosition.values()) {
            int ring = PositionUtil.chebyshevDistance(e.cx(), e.cz(), px, pz);
            if (e.heldSlot() == SlotType.SYNC_ON_LOAD) {
                if (sync < 0 || ring < sync) sync = ring;
            } else if (gen < 0 || ring < gen) {
                gen = ring;
            }
        }
        return new int[]{sync, gen};
    }

    // Frontier outward damping (see LSSConstants.FRONTIER_OUTWARD_DAMP_MILLIS_PER_RING):
    // liveFrontierRing holds the DAMPED reference the spread gate consults. Inward
    // observations apply instantly; outward ones advance at most one ring per interval,
    // so a movement-minted oscillation (near field momentarily all-satisfied -> observed
    // frontier jumps to the far edge -> movement mints new near work a moment later)
    // cannot drag the admission window to the far edge between mints. Processing thread
    // only. The clock is a seam so damping tests are deterministic; rigs pass 0 to
    // disable (instant outward — the pre-damping gate semantics their pins calibrate).
    private long frontierDampNanosPerRing =
            LSSConstants.FRONTIER_OUTWARD_DAMP_MILLIS_PER_RING * 1_000_000L;
    private java.util.function.LongSupplier frontierClock = System::nanoTime;
    private long frontierAdvanceMarkNanos;

    /** Package-private test probe: the wired damping interval, so the production default
     *  (constant × nanos conversion) is pinned and cannot silently die. */
    long frontierDampNanosPerRingForTest() {
        return this.frontierDampNanosPerRing;
    }

    /** Test seam (protected — rigs subclass from other packages): outward-damping
     *  interval (0 = instant/off, the pre-damping semantics existing gate pins calibrate
     *  against) + clock, so damping tests are deterministic. */
    protected void setFrontierDampingForTest(long nanosPerRing, java.util.function.LongSupplier clock) {
        this.frontierDampNanosPerRing = nanosPerRing;
        this.frontierClock = clock;
    }

    // Stamp-source tag for the admission trace ("acq" = ts<=0 acquisition entry, "reval"
    // = the end-of-pass ts>0 fallback, "-" = never stamped). Tags the last EFFECTIVE
    // stamp — the last call that actually moved (or first-set) the ring; a damping-held
    // outward stamp that advances zero rings leaves the tag on whoever set the standing
    // value, so a trace line's f=/fsrc= pair stays coherent. Diagnostic only — the gates
    // never read it. Processing thread only, like the frontier itself.
    private boolean lastFrontierStampAcquisition;
    private boolean frontierEverStamped;

    String frontierStampSourceForTrace() {
        if (!this.frontierEverStamped) return "-";
        return this.lastFrontierStampAcquisition ? "acq" : "reval";
    }

    /** Two-arg form: stamps as acquisition (the pre-split semantics — every stamp was
     *  equally authoritative). No production callers — the router uses the 3-arg form
     *  everywhere; this exists for test rigs, and `acquisition` is diagnostic-only so
     *  the default is semantically inert there. */
    public void stampLiveFrontier(int cx, int cz) {
        stampLiveFrontier(cx, cz, true);
    }

    /** Router drain stamp: the first not-actually-satisfied ACQUISITION entry (ts<=0) of
     *  this pass defines the live frontier; when a pass holds only unsatisfied
     *  REVALIDATION entries (ts>0 — dirty re-asks, resync), the first of those stamps at
     *  end of pass instead (the acquisition-frontier rule,
     *  docs/planning/gen-frontier-acquisition-anchor-plan.md: an inner revalidation —
     *  data both sides already have, generation can never serve it — must not collapse
     *  the generation admission window). Outward-damped — see the field comment above.
     *  No-op without a stamped player chunk. */
    public void stampLiveFrontier(int cx, int cz, boolean acquisition) {
        long player = this.playerChunkPacked;
        if (player == NO_PLAYER_CHUNK) return;
        int observed = PositionUtil.chebyshevDistance(cx, cz,
                PositionUtil.unpackX(player), PositionUtil.unpackZ(player));
        int current = this.liveFrontierRing;
        if (this.frontierDampNanosPerRing == 0 || current < 0 || observed <= current) {
            // Off, first stamp, or inward: apply instantly and restart the outward budget.
            this.liveFrontierRing = observed;
            this.frontierAdvanceMarkNanos = this.frontierClock.getAsLong();
            this.lastFrontierStampAcquisition = acquisition;
            this.frontierEverStamped = true;
            return;
        }
        long now = this.frontierClock.getAsLong();
        long steps = (now - this.frontierAdvanceMarkNanos) / this.frontierDampNanosPerRing;
        if (steps > 0) {
            this.liveFrontierRing = (int) Math.min(observed, current + steps);
            this.frontierAdvanceMarkNanos = now;
            this.lastFrontierStampAcquisition = acquisition;
            this.frontierEverStamped = true;
        }
        // A zero-step damped stamp changes nothing — the source tag stays with the call
        // that set the standing value (see the tag's comment).
    }

    /**
     * Generation order-spread gate (processing thread only): true when admitting a
     * generation ticket at {@code (cx, cz)} would place it more than {@code maxSpread}
     * Chebyshev rings (from the player's chunk) beyond the FRONTIER — the nearest
     * unsatisfied declared position. Reference, in order: the router-stamped LIVE frontier
     * (20 Hz — the client-declared frontier alone goes stale for a full second between
     * 1 Hz declarations, which collapsed superflat backfill throughput 4x and starved the
     * IOWorker into read timeouts, soak 2026-07-17); else the applied want-set's first
     * entry (the declaration head — the nearest work under ring-major emission, the
     * active region's nearest under region-major; deliberately NOT acquisition-filtered: it only
     * applies before a session's first stamp, and the frontier never returns to -1).
     * Neither reference can be dragged outward by in-flight work — a ts&lt;=0 in-flight
     * position stamps the frontier AT its own ring, so the band cannot walk away from a
     * starving acquisition head (the min-outstanding law's straggler leak, closed for
     * good). Since the acquisition-frontier rule (2026-08-18) a ts&gt;0 in-flight entry no
     * longer stamps; its anti-starvation carrier is the PENDING MAP — such an entry is in
     * {@code pendingByPosition} by construction, and {@code generationOvertakesNearerInFlight}
     * holds every farther gen candidate against it (strictly tighter than the old frontier
     * hold: a nearer pending SYNC blocks outright, a nearer gen ticket collapses the band
     * to cohort span 1). Worst case for an inner ts&gt;0 ask that genuinely needs
     * generation while an outer cohort holds every slot: ~one generation-slot turnover of
     * added latency (the declaration-order drain gives it first refusal on the next freed
     * slot, and once outstanding it gates the outer cohort). False (gate skipped) without
     * a stamped player chunk or any frontier basis — a rig or a converged player, neither
     * of which floods.
     */
    public boolean generationOrderSpreadExceeded(int cx, int cz, int maxSpread) {
        long player = this.playerChunkPacked;
        if (player == NO_PLAYER_CHUNK) return false;
        int px = PositionUtil.unpackX(player);
        int pz = PositionUtil.unpackZ(player);
        int frontier = this.liveFrontierRing;
        if (frontier < 0) {
            var wantSet = this.appliedWantSet;
            if (wantSet == null || wantSet.size() == 0) return false;
            var first = wantSet.requests()[0];
            frontier = PositionUtil.chebyshevDistance(first.cx(), first.cz(), px, pz);
        }
        int candidate = PositionUtil.chebyshevDistance(cx, cz, px, pz);
        return candidate > frontier + maxSpread;
    }

    /**
     * Generation pacing for EVERY admission path — the router's memo rung and the
     * miss-delivery escalation both run this via {@code escalateMissToGeneration}
     * (docs/planning/miss-memo-design.md): "generation never overtakes nearer in-flight
     * work". True when admitting a generation ticket at {@code (cx, cz)} would overtake
     * either:
     * <ul>
     *   <li>a NEARER pending SYNC read — the pre-memo pipeline had this property
     *       implicitly for stationary players (escalations only happened at read
     *       completions, so a nearer in-flight read's escalation usually came first); a
     *       memo escalation or a stale far delivery under movement that skips ahead of it
     *       produces the far-generates-while-near-waits-on-disk leapfrog. As
     *       a side effect this re-creates the read-latency feedback loop: when reads slow
     *       under generation save pressure, admission pauses and the IOWorker drains.</li>
     *   <li>the generation cohort: any candidate more than {@code cohortSpan} rings beyond
     *       the NEAREST outstanding generation ticket. Caps the live band's ring span so
     *       completions stay ring-by-ring instead of scrambling across the whole
     *       frontier+spread window (instant memo refill otherwise pins the full cap in
     *       flight across 3 rings continuously).</li>
     * </ul>
     * The outstanding set is used as a RESTRICTION only, never as the admission window's
     * anchor (the straggler-leak lesson: a lone far ticket can only TIGHTEN this rule —
     * nearest-outstanding — never drag the window outward; the window anchor stays the
     * declared frontier in {@link #generationOrderSpreadExceeded}). Per-player, like the
     * spread gate. False without a player chunk (rig / converged player).
     */
    public boolean generationOvertakesNearerInFlight(int cx, int cz, int cohortSpan) {
        long player = this.playerChunkPacked;
        if (player == NO_PLAYER_CHUNK) return false;
        int px = PositionUtil.unpackX(player);
        int pz = PositionUtil.unpackZ(player);
        int candidate = PositionUtil.chebyshevDistance(cx, cz, px, pz);
        int minGenRing = Integer.MAX_VALUE;
        for (var entry : this.pendingByPosition.long2ObjectEntrySet()) {
            var pending = entry.getValue();
            int ring = PositionUtil.chebyshevDistance(pending.cx(), pending.cz(), px, pz);
            if (pending.heldSlot() == SlotType.SYNC_ON_LOAD) {
                if (ring < candidate) return true; // nearer read in flight — hold
            } else if (ring < minGenRing) {
                minGenRing = ring;
            }
        }
        return minGenRing != Integer.MAX_VALUE && candidate > minGenRing + cohortSpan;
    }

    /** True when any outstanding generation ticket sits NEARER the player's chunk than
     *  {@code (cx, cz)} — a completion arriving out of proximity order. Diagnostics only
     *  (the platform scheduler's completion-order evidence); false without a player chunk. */
    public boolean hasNearerOutstandingGeneration(int cx, int cz) {
        long player = this.playerChunkPacked;
        if (player == NO_PLAYER_CHUNK) return false;
        int px = PositionUtil.unpackX(player);
        int pz = PositionUtil.unpackZ(player);
        int completed = PositionUtil.chebyshevDistance(cx, cz, px, pz);
        long selfPacked = PositionUtil.packPosition(cx, cz);
        for (var entry : this.pendingByPosition.long2ObjectEntrySet()) {
            if (entry.getLongKey() == selfPacked) continue;
            var pending = entry.getValue();
            if (pending.heldSlot() != SlotType.GENERATION) continue;
            if (PositionUtil.chebyshevDistance(pending.cx(), pending.cz(),
                    px, pz) < completed) return true;
        }
        return false;
    }

    public IncomingRequest pollBacklog() {
        var r = this.backlog.pollFirst();
        this.backlogSizeSnapshot = this.backlog.size();
        // Backlog fully consumed: stop the main thread probing a want-set with nothing left
        // to route (a converged player must cost zero probes).
        if (this.backlog.isEmpty()) publishWantSet(null);
        return r;
    }

    /**
     * Restore entries the router pass retained (slot full / pool full / queue full),
     * in their original order, ahead of whatever remains.
     *
     * <p>Republishes the applied want-set: a pass that retains has work still owed, but
     * {@link #pollBacklog} has already published null if the pass drained the deque before
     * restoring — which the steady state ALWAYS does, since SLOT_FULL retains and continues.
     * Without this the probe source would be null on every tick outside saturation, which is
     * exactly backwards (see the {@link #publishedWantSet} note).
     */
    public void restoreBacklog(List<IncomingRequest> retained) {
        for (int i = retained.size() - 1; i >= 0; i--) {
            this.backlog.addFirst(retained.get(i));
        }
        this.backlogSizeSnapshot = this.backlog.size();
        if (!this.backlog.isEmpty()) publishWantSet(this.appliedWantSet);
    }

    /** Volatile snapshot for exporters and the soak quiescence gauge. */
    public int getBacklogSize() { return this.backlogSizeSnapshot; }

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
     * @return packed positions whose resolved-but-undelivered payloads were discarded —
     *         by a send failure OR by the relevance prune — for the caller to route to
     *         {@code OffThreadProcessor.clearDiskReadDone} (empty when everything sent
     *         or remains queued)
     */
    public long[] flushSendQueue(long allocationBytes, SharedBandwidthLimiter globalLimiter,
                                  TickDiagnostics diag, PayloadSender<T> sender) {
        // Short overloads pin the yield/prune/pacing OFF (the S-9a defaults pin): only
        // the full overload can arm them, and only the platform services call that with
        // live config. (The operator-fixed outbound ceiling that used to ride this chain
        // was DELETED 2026-08-13 — deletion review #2: with the netty gauge capped at
        // the high-water mark while writable and the ceiling floored at 64 KB, it could
        // only ever fire while NOT_WRITABLE — exactly when the default-ON yield gate
        // already skips the flush, with better semantics.)
        return flushSendQueue(allocationBytes, globalLimiter, diag, sender, false, 0);
    }

    /**
     * Full flush overload carrying the transport-yield gate
     * ({@code lodYieldsToVanillaTransport}, default true since v0.11.0 — vanilla-first-lod-yield-plan.md
     * v2.1) and the relevance-prune radius (0 = prune disabled). The gate IS netty's
     * writability flag: while the channel is writable the bandwidth limiter is the only
     * constraint; while it is not, this tick's column flush is skipped and the queue
     * retained — LSS never deepens an already-backed-up channel's queue ahead of
     * vanilla's chunk packets. Writability UNKNOWN (legacy probe, closed channel, no
     * signal) never yields — the fail-safe direction.
     */
    public long[] flushSendQueue(long allocationBytes, SharedBandwidthLimiter globalLimiter,
                                  TickDiagnostics diag, PayloadSender<T> sender,
                                  boolean yieldToTransport, int pruneRadiusChunks) {
        return flushSendQueue(allocationBytes, globalLimiter, diag, sender,
                yieldToTransport, pruneRadiusChunks, false);
    }

    /**
     * Fullest overload adding send pacing (send-pacing-plan.md v3:
     * {@code enableSendPacing}, default true — the refill-floored, BURST-CLAMPED
     * proportional drain). While enabled, a tick's column flush writes at most
     * {@code clamp(queuedRawBytes/PACE_HORIZON_TICKS, share, PACE_MAX_BURST_SHARES*share)}
     * RAW bytes where share = allocation/20 (one
     * payload minimum — the presence gate), so the bandwidth bank's burst ships as a
     * ~5-tick slope instead of a one-tick cliff, and vanilla packets interleave every
     * tick. The floor is the allocation's own refill share, so NOTHING is ever paced
     * below the configured cap rate; the pingf-cut allocation shrinks the floor with
     * it, and a backlog's {@code Q/HORIZON} above the cut share is merely non-binding
     * (the limiter stays the authority — the budget only vetoes). Short overloads pin
     * pacing OFF (the S-9a defaults pin).
     */
    public long[] flushSendQueue(long allocationBytes, SharedBandwidthLimiter globalLimiter,
                                  TickDiagnostics diag, PayloadSender<T> sender,
                                  boolean yieldToTransport, int pruneRadiusChunks,
                                  boolean sendPacing) {
        QueuedPayload<T> ready;
        while ((ready = this.readyPayloads.poll()) != null) {
            this.sendQueue.add(ready);
        }

        // Relevance prune (yield plan §2.1, cadence-gated): yield stretches queue
        // residency from ~1 tick to minutes, and an unpruned post-yield drain ships
        // kilometres-behind terrain oldest-first. Runs HERE on the owning thread (the
        // v2 eviction-hook placement raced this main-thread PriorityQueue — F2-3),
        // BEFORE the gate so the §1.4 floor draws from the post-prune head. Pruned
        // positions ride the return array to the processor's clearDiskReadDone (the
        // send-failure route) — diskReadDone is processing-thread-owned.
        long[] prunedPositions = NO_DROPPED_POSITIONS;
        if (pruneRadiusChunks > 0 && ++this.flushTickCounter >= PRUNE_INTERVAL_TICKS) {
            this.flushTickCounter = 0;
            prunedPositions = pruneSendQueueOutsideRange(pruneRadiusChunks);
        }
        this.sendQueueSizeSnapshot = this.sendQueue.size();

        // ONE authoritative probe read per tick — it is the diag gauge, the deference
        // input, AND the yield gate's writability source, so the three can never disagree
        // about which channel state they saw. Contained: this runs inside the per-player
        // flush loop, so a throwing probe would take every LATER player's flush down with
        // it. Both shipped adapters already catch internally; this is the belt.
        long pending;
        ChannelPressureProbe.Writability writable;
        try {
            var snapshot = this.channelPressure.snapshot();
            pending = snapshot.pendingBytes();
            writable = snapshot.writable();
        } catch (Exception e) {
            pending = -1L; // no signal — never throttle on a broken probe
            writable = ChannelPressureProbe.Writability.UNKNOWN;
        }
        this.outboundPendingBytes = pending;
        if (pending > this.outboundPendingHighWater) this.outboundPendingHighWater = pending;

        long[] dropped = prunedPositions;

        // The yield gate (§1.2): while the channel is NOT writable, skip this tick's
        // column flush and retain the queue — with the §1.4 starvation floor: after
        // YIELD_FLOOR_TICKS consecutive withheld ticks, exactly one payload goes out
        // regardless of writability (bounds worst-case LOD at ~1 column/5 s and
        // distinguishes "yielding hard" from "dead"). UNKNOWN never yields.
        boolean floorSendThisTick = false;
        if (yieldToTransport && writable == ChannelPressureProbe.Writability.NOT_WRITABLE
                && !this.sendQueue.isEmpty()) {
            this.yieldedTicks++;
            // A byte-tick pressure integral, not a distinct-bytes count: each withheld
            // tick adds the bytes that were ready and held. Useful as a magnitude gauge;
            // never a delivered-bytes term. Service-scoped only (TickDiagnostics) —
            // the per-player twin was dead API and dropped (review C-7/A-7).
            long queuedBytes = 0;
            for (var entry : this.sendQueue) {
                queuedBytes += entry.estimatedBytes();
            }
            diag.recordYieldedTick(queuedBytes);
            // The counter is NOT reset here: it resets only when a floor payload actually
            // leaves (review A-1/B-5 — a floor tick whose send the limiter refuses must
            // stay armed, not silently spend its window). §1.4's contract is "send
            // exactly one payload", not "attempt one".
            if (++this.yieldNoSendTicks >= YIELD_FLOOR_TICKS) {
                floorSendThisTick = true;
                // The floor draws from the POST-PRUNE head on EVERY floor tick, not just
                // the 1-in-12 where the minute cadence lines up (review A-4): a bounded
                // extra prune (≤ once per 5 s) keeps §1.4's "never spends its one payload
                // on terrain the prune was about to discard" true always.
                if (pruneRadiusChunks > 0) {
                    long[] floorPruned = pruneSendQueueOutsideRange(pruneRadiusChunks);
                    if (floorPruned.length > 0) {
                        var merged = new LongArrayList(dropped.length + floorPruned.length);
                        merged.addElements(0, dropped);
                        merged.addElements(merged.size(), floorPruned);
                        dropped = merged.toLongArray();
                        this.sendQueueSizeSnapshot = this.sendQueue.size();
                        if (this.sendQueue.isEmpty()) {
                            sweepDepartedColumns();
                            return dropped;
                        }
                    }
                }
            } else {
                sweepDepartedColumns();
                return dropped;
            }
        } else if (this.sendQueue.isEmpty()) {
            // Round-2 reset rescope (design §Floor): an EMPTY queue at flush entry means
            // nothing is withheld — trivially not starving; both floor counters reset.
            // The pre-v2 else-branch reset (any non-yield tick) admitted an unbounded-
            // starvation interleave: it fired BEFORE any send was attempted, so a
            // zero-allocation tick erased the counter with nothing flowed. A tick with
            // queued work now resets ONLY where a payload actually leaves (in the loop).
            this.yieldNoSendTicks = 0;
        }

        // Send pacing (send-pacing-plan.md v3): the budget is a pure function of the
        // queue and the allocation — no probe input, no arming state. Computed lazily
        // only when armed and non-empty (zero drift risk vs a running counter; the
        // O(queue) walk is bounded by sendQueueLimitPerPlayer and runs at most once
        // per tick per player). RAW-denominated to match the limiter and the
        // allocation the floor derives from (wire <= raw, so the socket-facing
        // amplitude is bounded a fortiori). The clamp: bank-sized waves (Q <= the
        // bank) pace at the refill-share floor — the ~5-tick slope; deep backlogs at
        // most PACE_MAX_BURST_SHARES shares (the ceiling that keeps the first tick
        // bounded on real-terrain waves, while still compensating a sub-20-TPS
        // server's tick-denominated floor down to ~10 TPS).
        long paceBudget = 0;
        if (sendPacing && !this.sendQueue.isEmpty() && !floorSendThisTick) {
            long queuedRawBytes = 0;
            for (var entry : this.sendQueue) {
                queuedRawBytes += entry.estimatedBytes();
            }
            long share = allocationBytes / LSSConstants.TICKS_PER_SECOND;
            paceBudget = Math.clamp(queuedRawBytes / PACE_HORIZON_TICKS,
                    share, Math.max(share, PACE_MAX_BURST_SHARES * share));
        }
        long paceWritten = 0;

        while (!this.sendQueue.isEmpty()) {
            if (!this.bandwidth.canSend(allocationBytes)) break;
            // The pace budget vetoes sends 2+ once this tick's RAW bytes reach it —
            // never the first payload (the presence gate: a legal oversized column
            // ships whole — and at a degenerate budget of 0, the gate is what keeps
            // one payload per tick flowing), and never the starvation-floor tick
            // (structurally exempt — it breaks after one send; the budget is not even
            // computed there). A budget-stopped tick is by construction PARTIAL (the
            // loop condition holds), which is exactly what paced= counts; a
            // limiter-stopped tick books nothing here.
            if (sendPacing && !floorSendThisTick
                    && paceWritten > 0 && paceWritten >= paceBudget) {
                this.pacedTicks++;
                diag.recordPacedTick();
                break;
            }

            var queued = this.sendQueue.peek();
            try {
                sender.send(queued.payload());
                this.sendQueue.poll();
                // Stamp BEFORE the decrement: the router checks the enqueued rung first,
                // so a stamp while still enqueued is inert — but decrement-then-stamp
                // would leave a nanosecond window (enqueued gone, stamp not yet visible)
                // where a crossing re-ask slips past both rungs to a redundant re-serve.
                stampDeparted(queued.packedPos());
                // Sibling, not nested: stampDeparted no-ops when the grace is disabled,
                // and probe suppression must not vanish with it (review P1).
                stampProbeSuppress(queued.packedPos());
                decrementEnqueued(queued.packedPos());
                this.bandwidth.recordSend(queued.estimatedBytes());
                globalLimiter.recordSend(queued.estimatedBytes());
                paceWritten += queued.estimatedBytes();
                diag.recordSectionSent(queued.estimatedBytes());
                // Shipped size (frame for codec-1 payloads) — the wire_bytes gauge that
                // makes /lsslod diag match observed bandwidth (design §5: the limiter
                // keeps charging raw; this counter is the observability half).
                diag.recordWireSent(queued.wireBytes());
                // Round-2 reset rescope (yield design §Floor): a payload actually LEFT,
                // so the floor counter restarts — the ONLY reset besides the
                // empty-queue-at-entry one. A refused/zero-allocation tick resets
                // nothing (review A-1/B-5: a floor window must not be silently spent).
                this.yieldNoSendTicks = 0;
                if (floorSendThisTick) {
                    // The starvation floor sends EXACTLY ONE payload (§1.4) — the
                    // holding governor is still holding; this send only proves liveness.
                    if (!this.sustainedYieldNoted) {
                        this.sustainedYieldNoted = true;
                        LSSLogger.info("Limiting LOD delivery to " + getPlayerName()
                                + ": the client or network cannot keep up, so game traffic"
                                + " is prioritized and LODs will catch up once the"
                                + " connection drains (logged once per session)");
                    }
                    break;
                }
            } catch (Exception e) {
                // Throttled (log sweep): a closing/broken connection can throw here every
                // tick until the disconnect lands — its sibling yield WARN is latched.
                long n = SEND_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.error("Failed to send queued payload to " + getPlayerName()
                            + ", dropping remaining queue (" + this.sendQueue.size()
                            + " entries; " + n + " send failure(s) since the last report)", e);
                }
                // Seeded with any prune-cleared positions from this tick: both classes
                // need the caller's clearDiskReadDone routing, and overwriting would
                // leak the pruned ones' stale done-bits.
                var droppedList = new LongArrayList(dropped.length + this.sendQueue.size());
                droppedList.addElements(0, dropped);
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
        sweepDepartedColumns();
        return dropped;
    }

    /**
     * Remove send-queue entries beyond {@code radiusChunks} (Chebyshev) of the player's
     * current chunk (yield plan §2.1): each pruned entry releases its enqueued mark and
     * returns its position so the CALLER routes it to the processor's
     * {@code clearDiskReadDone} — a future declaration then re-resolves honestly.
     * In-departure-grace entries are kept (their done-bit semantics are pinned); no
     * proximity re-ordering (edit convergence depends on submission order — the v3
     * lever). Owning (main) thread only.
     *
     * <p>Unstated-elsewhere invariant (review N1): this radius (ingress + margin) is
     * strictly LARGER than the client's own movement-prune distance, so the client
     * always forgets a position before the server may prune its payload — a ts&gt;0
     * re-ask that was answered up_to_date can therefore never be stranded stale by a
     * prune. A future change to either radius must preserve that ordering.
     */
    private long[] pruneSendQueueOutsideRange(int radiusChunks) {
        long playerPacked = this.playerChunkPacked;
        if (playerPacked == NO_PLAYER_CHUNK || this.sendQueue.isEmpty()) {
            return NO_DROPPED_POSITIONS;
        }
        int pcx = PositionUtil.unpackX(playerPacked);
        int pcz = PositionUtil.unpackZ(playerPacked);
        LongArrayList pruned = null;
        var it = this.sendQueue.iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long packed = entry.packedPos();
            if (PositionUtil.chebyshevDistance(PositionUtil.unpackX(packed),
                    PositionUtil.unpackZ(packed), pcx, pcz) <= radiusChunks) continue;
            if (isWithinDepartureGrace(packed)) continue;
            it.remove();
            decrementEnqueued(packed);
            if (pruned == null) pruned = new LongArrayList();
            pruned.add(packed);
        }
        return pruned == null ? NO_DROPPED_POSITIONS : pruned.toLongArray();
    }

    /** Test seam: position the prune cadence counter (the interval is 1200 flushes —
     *  driving it for real would floor-send the queue empty long before the prune). */
    void setFlushTickCounterForTest(int value) {
        this.flushTickCounter = value;
    }

    /** Cumulative ticks the transport yield withheld this player's column flush. */
    public long getPacedTicks() { return this.pacedTicks; }

    public long getYieldedTicks() {
        return this.yieldedTicks;
    }


    // ---- Probe suppression (2026-08-05 review P1) ----

    /**
     * Positions whose payload just left the send queue (send success) or whose last
     * resolution was {@code up_to_date}. The main-thread probe skips them for
     * {@link #PROBE_SUPPRESS_TTL_NANOS} — a suppressed position that is re-declared
     * resolves at the router's earlier rungs (duplicate grace for in-grace ts&le;0,
     * timestamp cache for ts&gt;0), so its probe result was guaranteed-unused; without
     * the filter a served, still-loaded column was fully re-serialized EVERY tick until
     * the next declaration dropped it (up to 1 s), and up_to_date-resolved resync
     * positions got no filtering at all. Deliberately SEPARATE from
     * {@link #departedColumns}: the grace map's 500 ms TTL and
     * stamp-on-send-success-only discipline are pinned for the router's duplicate-serve
     * semantics, and up_to_date stamps there would corrupt them. Any thread; swept
     * beside the departure sweep; cleared by the dirty-clear events and the honest
     * re-resolution ({@link #clearDiskReadDone}) so an edited column probes again
     * immediately. Accepted residual (plan review + implementation review): a ts&le;0
     * re-ask landing between the departure grace and this TTL — a genuinely lost
     * delivery — takes a disk read where it used to be probe-served. Two sharper flavors
     * of that shift, both healed by the normal dirty-broadcast/reconnect ladder: a
     * loaded-NEVER-SAVED chunk's disk read is a not-found, which with generation
     * DISABLED answers the session-permanent NOT_GENERATED for a column that is live in
     * memory (a new entry path into the documented MAX_PROBES_PER_TICK_GLOBAL accepted
     * corner — same heal: first save's dirty broadcast, or reconnect; on Paper's default
     * updateEvents, reconnect); and a loaded chunk with unsaved edits serves its
     * pre-edit disk bytes (healed by the edit's own save → dirty broadcast, the system's
     * normal staleness bound). Requires losing a delivery AND re-asking inside a ~1 s
     * window — judged acceptable against re-serializing every served head every tick.
     */
    private final ConcurrentHashMap<Long, Long> probeSuppress = new ConcurrentHashMap<>();
    static final long PROBE_SUPPRESS_TTL_NANOS = 1_500L * 1_000_000L;

    /** Stamp sites: send success in {@code flushSendQueue} (a SIBLING of
     *  {@code stampDeparted}, never nested in it — suppression must survive
     *  grace-disabled rigs) and the {@code drainSendActions} up_to_date choke point. */
    public void stampProbeSuppress(long packed) {
        this.probeSuppress.put(packed, this.departureClock.getAsLong());
    }

    /** Any thread; expired entries removed on read (mirrors {@link #isWithinDepartureGrace}). */
    public boolean isProbeSuppressed(long packed) {
        var stamp = this.probeSuppress.get(packed);
        if (stamp == null) return false;
        if (this.departureClock.getAsLong() - stamp <= PROBE_SUPPRESS_TTL_NANOS) return true;
        this.probeSuppress.remove(packed, stamp);
        return false;
    }

    /**
     * The ONE probe-filter predicate for all three platform probe loops (Fabric main
     * thread, Paper pump, Folia snapshot — three-lens review T10: per-site copies could
     * be reverted individually with every tier green): a position whose payload is in
     * the send pipeline, recently sent, or just answered up_to_date resolves at the
     * router without the probe, so serializing it is guaranteed-unused work. Any thread.
     */
    public boolean skipProbe(long packed) {
        return hasEnqueuedColumn(packed) || isProbeSuppressed(packed);
    }

    int probeSuppressCountForTest() {
        return this.probeSuppress.size();
    }

    /**
     * Direct (non-mailboxed) probe-suppress clear for the dirty broadcasters (three-lens
     * review, concurrency MINOR). The done-bit clear rides the processing-thread mailbox,
     * but the suppress stamp is written at send success on the MAIN/pump thread — so a
     * stale payload flushing after the mailboxed clear applied would re-suppress the
     * edited column for the full TTL, and unlike {@code departedColumns} (whose check is
     * nested under the done-bit) this map is consulted unconditionally by the probe, so a
     * cleared done-bit does not disarm it. The broadcast tick runs strictly AFTER the
     * same tick's send-queue flush on both platforms, so a direct clear here can never be
     * outrun by a stamp for the payload that triggered the edit's re-serve. Any thread.
     */
    public void clearProbeSuppress(long[] positions) {
        for (long pos : positions) {
            this.probeSuppress.remove(pos);
        }
    }

    // ---- Duplicate-serve grace (see the departedColumns field comment) ----

    /**
     * True while a ts&le;0 re-ask for this position falls inside the departure grace: its
     * column payload left the send queue (send success) within the last
     * {@link LSSConstants#SEND_DEPARTURE_GRACE_MILLIS}, so the re-ask almost certainly
     * crossed its own delivery in flight. Any thread; expired entries are removed on read.
     */
    public boolean isWithinDepartureGrace(long packed) {
        var departed = this.departedColumns.get(packed);
        if (departed == null) return false;
        if (this.departureClock.getAsLong() - departed <= this.departureGraceNanos) return true;
        this.departedColumns.remove(packed, departed);
        return false;
    }

    private void stampDeparted(long packed) {
        if (this.departureGraceNanos <= 0) return;
        this.departedColumns.put(packed, this.departureClock.getAsLong());
    }

    /** Expire stale departure stamps, at most once per grace interval (main thread, from
     *  {@code flushSendQueue}). Most stamps are never re-asked, so read-time removal alone
     *  would leak one entry per column ever sent. */
    private void sweepDepartedColumns() {
        if (this.departedColumns.isEmpty() && this.probeSuppress.isEmpty()) return;
        long now = this.departureClock.getAsLong();
        // Grace-disabled hardening (three-lens review): with departureGraceNanos = 0 the
        // gate below would pass every flush and the probeSuppress removeIf would run
        // full-map every tick — fall back to the suppress TTL as the sweep interval
        // (grace entries don't exist in that configuration). The grace's own 500 ms
        // cadence is pinned and unchanged when the grace is on.
        long gateNanos = this.departureGraceNanos > 0
                ? this.departureGraceNanos : PROBE_SUPPRESS_TTL_NANOS;
        if (this.departedSweepMarkNanos != 0
                && now - this.departedSweepMarkNanos <= gateNanos) return;
        this.departedSweepMarkNanos = now;
        this.departedColumns.values().removeIf(stamp -> now - stamp > this.departureGraceNanos);
        // Piggybacked: unconsulted probe-suppress stamps must not outlive their window
        // (most are never re-probed, so read-time removal alone would leak one entry per
        // column ever sent — the departedColumns argument, same shape).
        this.probeSuppress.values().removeIf(stamp -> now - stamp > PROBE_SUPPRESS_TTL_NANOS);
    }

    /** Package-private test probes: the wired grace (pins the production default) and the
     *  map's size (pins the sweep — a leaked map is invisible to behavior tests). */
    long departureGraceNanosForTest() {
        return this.departureGraceNanos;
    }

    int departedColumnCountForTest() {
        return this.departedColumns.size();
    }

    /** Test seam (protected — rigs subclass from other packages): grace window
     *  (0 = off, no stamps written) + clock, so grace tests are deterministic. */
    protected void setDepartureGraceForTest(long graceNanos, java.util.function.LongSupplier clock) {
        this.departureGraceNanos = graceNanos;
        this.departureClock = clock;
    }

    // ---- Processing-thread-facing per-request API ----

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
            // An edited (or honestly re-resolved) column must probe again immediately —
            // the suppress stamp's premise ("the router discards this probe") is gone.
            this.probeSuppress.remove(pos);
        }
    }

    /** Clear one position from diskReadDone (processing thread, honest re-resolution of a ts&le;0 re-request). */
    public void clearDiskReadDone(long packed) {
        this.diskReadDone.remove(packed);
        this.probeSuppress.remove(packed);
    }

    /**
     * Sweep {@code diskReadDone} entries beyond {@code radiusChunks} (Chebyshev) of the
     * player's current chunk (M3, 2026-07-28 review round): without a sweep the served-set
     * grows monotonically for the whole session — tens of MB per long roaming player,
     * released only at disconnect. Sweeping out-of-range bits is semantically free: ingress
     * range-filters every declaration strictly INSIDE this radius, so a swept bit can next
     * be consulted only after the player returns — where a ts&gt;0 re-declaration still
     * short-circuits on the timestamp cache, and a ts&le;0 one re-resolves honestly (the
     * designed self-heal; worst case one extra read, or a redundant re-serve if the
     * timestamp cache also evicted the stamp). Entries with a payload still in the send
     * pipeline or inside the departure grace are KEPT — sweeping those would drop the
     * duplicate-serve silent skip and double-serve after a teleport with a backed-up
     * queue. Processing thread only; no-op until the platform stamps the player chunk.
     */
    public int sweepDiskReadDoneOutsideRange(int radiusChunks) {
        long playerPacked = this.playerChunkPacked;
        if (playerPacked == NO_PLAYER_CHUNK || this.diskReadDone.isEmpty()) return 0;
        int pcx = PositionUtil.unpackX(playerPacked);
        int pcz = PositionUtil.unpackZ(playerPacked);
        int removed = 0;
        var it = this.diskReadDone.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            if (PositionUtil.chebyshevDistance(PositionUtil.unpackX(packed),
                    PositionUtil.unpackZ(packed), pcx, pcz) <= radiusChunks) continue;
            if (hasEnqueuedColumn(packed) || isWithinDepartureGrace(packed)) continue;
            it.remove();
            removed++;
        }
        // Removal alone reclaims NOTHING: fastutil open-hash sets never shrink their
        // backing array, so a roamer's peak-size long[] would stay resident for the
        // session (the reviewer-measured hole in the first cut of this fix). Rebuild when
        // the sweep removed more than what remains — capacity is then ≥2× the need.
        if (removed > this.diskReadDone.size()) {
            this.diskReadDone.trim();
        }
        return removed;
    }

    /** True while a column payload for this position sits in the send pipeline (any thread). */
    public boolean hasEnqueuedColumn(long packed) {
        return this.enqueuedColumns.containsKey(packed);
    }

    private void decrementEnqueued(long packed) {
        this.enqueuedColumns.computeIfPresent(packed, (k, v) -> v <= 1 ? null : v - 1);
    }

    // ---- Accessors for concurrent queues (used by sibling classes) ----

    public void addReadyPayload(QueuedPayload<T> payload) {
        this.enqueuedColumns.merge(payload.packedPos(), 1, Integer::sum);
        this.readyPayloads.add(payload);
    }

    // ---- Getters ----

    public UUID getPlayerUUID() { return this.playerUuid; }
    /** Wall-clock instant this state was created (== registration). Diagnostics-only. */
    public long getCreatedAtMillis() { return this.createdAtMillis; }
    /** Returns a volatile snapshot of the send queue size, safe for cross-thread reads. */
    public int getSendQueueSize() { return this.sendQueueSizeSnapshot; }

    /** Wire this player's transport-pressure probe; never null (use {@code NO_SIGNAL}). */
    public void setChannelPressureProbe(ChannelPressureProbe probe) {
        this.channelPressure = probe == null ? ChannelPressureProbe.NO_SIGNAL : probe;
    }

    /** Last sampled outbound-buffer depth in bytes; -1 = no signal (never "empty"). */
    public long getOutboundPendingBytes() { return this.outboundPendingBytes; }
    /** Session high-water of {@link #getOutboundPendingBytes()}; -1 = never measured. */
    public long getOutboundPendingHighWater() { return this.outboundPendingHighWater; }
    public int getHeldSyncSlots() { return this.heldSyncSlots; }
    public int getHeldGenSlots() { return this.heldGenSlots; }
    public int getSyncSlotCap() { return this.syncSlotCap; }
    public int getGenSlotCap() { return this.genSlotCap; }

    /** Runtime cap change (v0.11.0 stage C): applied to EVERY registered state in the
     *  same tick pass, removing the old "new joins only" split (registration captured
     *  the boot value). Volatile write — see the field comment. */
    public void updateGenSlotCap(int cap) {
        this.genSlotCap = cap;
    }
    public long getTotalSectionsSent() { return this.bandwidth.getTotalSectionsSent(); }
    public long getTotalBytesSent() { return this.bandwidth.getTotalBytesSent(); }
    public long getTotalRequestsReceived() { return this.totalRequestsReceived.get(); }
}
