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
    private final PlayerBandwidthTracker bandwidth = new PlayerBandwidthTracker();
    private final AtomicLong totalRequestsReceived = new AtomicLong();
    // Single-writer (main thread) — volatile for cross-thread visibility to processing thread
    private volatile int sendQueueSizeSnapshot = 0;

    // ---- Want-set mailbox + backlog (protocol v17) ----

    // Network/region thread → processing thread, latest-wins: a batch overwritten before
    // consumption is superseded wholesale — exactly the want-set replace semantics. This
    // bounds ingress at ONE batch (≤ MAX_BATCH_CHUNK_REQUESTS entries) regardless of client
    // behavior, replacing the old MAX_INCOMING_QUEUE flood bound.
    private final AtomicReference<IncomingBatch> pendingBatch = new AtomicReference<>();
    // Cross-thread superseded/range-filtered events accumulate here and are drained into
    // ProcessingDiagnostics by the processing thread each cycle, preserving that class's
    // single-writer design. Counts pending at player removal die with the state (same as
    // the old incoming queue's contents — accepted, see the soak run-boundary handling).
    private final AtomicLong pendingSuperseded = new AtomicLong();
    private final AtomicLong pendingRangeFiltered = new AtomicLong();

    // Processing-thread-owned want backlog: replaced wholesale by each taken batch,
    // consumed in order (closest-first by construction — the client emits ring order).
    private final ArrayDeque<IncomingRequest> backlog = new ArrayDeque<>();
    // Single-writer (processing thread) — volatile for exporter/diagnostic reads.
    private volatile int backlogSizeSnapshot = 0;

    // The want-set the processing thread most recently applied to the backlog. This is the
    // main-thread probe's FALLBACK source, read when the mailbox is empty — which is almost
    // always: takeIncomingBatch() nulls the mailbox within ~50ms of arrival (the processing loop
    // polls at 20Hz) while batches arrive at only 1Hz, so a probe reading the mailbox ALONE would
    // see null on ~19 of every 20 ticks. This carries a want-set too large for the per-player slot
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

    // ---- Want-set mailbox (any thread) ----

    /** Offer a decoded batch (any thread). Latest-wins; an overwritten batch is superseded. */
    public void offerIncomingBatch(IncomingBatch batch) {
        this.totalRequestsReceived.addAndGet(batch.size());
        var previous = this.pendingBatch.getAndSet(batch);
        if (previous != null) {
            this.pendingSuperseded.addAndGet(previous.size());
        }
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
     * superseded want). Returns true if republished.
     */
    public boolean republishHeldBatch(IncomingBatch held) {
        if (this.pendingBatch.compareAndSet(null, held)) return true;
        this.pendingSuperseded.addAndGet(held.size());
        return false;
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

    // The LIVE frontier: ring (from the player chunk) of the first entry in declaration
    // order that is not ACTUALLY satisfied — stamped by the router every drain pass
    // (~20 Hz). In-flight positions (pending disk/generation, enqueued payloads) stamp it
    // (they are unsatisfied — the anti-starvation pin, mirroring the client scanner's
    // "awaited positions block ring confirmation"); timestamp/probe resolutions do not.
    // -1 until the first stamp. Processing thread only.
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

    /** Router drain stamp: the first not-actually-satisfied entry of this pass defines the
     *  live frontier (outward-damped — see the field comment above). No-op without a
     *  stamped player chunk. */
    public void stampLiveFrontier(int cx, int cz) {
        long player = this.playerChunkPacked;
        if (player == NO_PLAYER_CHUNK) return;
        int observed = PositionUtil.chebyshevDistance(cx, cz,
                PositionUtil.unpackX(player), PositionUtil.unpackZ(player));
        int current = this.liveFrontierRing;
        if (this.frontierDampNanosPerRing == 0 || current < 0 || observed <= current) {
            // Off, first stamp, or inward: apply instantly and restart the outward budget.
            this.liveFrontierRing = observed;
            this.frontierAdvanceMarkNanos = this.frontierClock.getAsLong();
            return;
        }
        long now = this.frontierClock.getAsLong();
        long steps = (now - this.frontierAdvanceMarkNanos) / this.frontierDampNanosPerRing;
        if (steps > 0) {
            this.liveFrontierRing = (int) Math.min(observed, current + steps);
            this.frontierAdvanceMarkNanos = now;
        }
    }

    /**
     * Generation order-spread gate (processing thread only): true when admitting a
     * generation ticket at {@code (cx, cz)} would place it more than {@code maxSpread}
     * Chebyshev rings (from the player's chunk) beyond the FRONTIER — the nearest
     * unsatisfied declared position. Reference, in order: the router-stamped LIVE frontier
     * (20 Hz — the client-declared frontier alone goes stale for a full second between
     * 1 Hz declarations, which collapsed superflat backfill throughput 4x and starved the
     * IOWorker into read timeouts, soak 2026-07-17); else the applied want-set's first
     * entry (closest-first construction). Neither reference can be dragged outward by
     * in-flight work — an in-flight position stamps the frontier AT its own ring, so the
     * band cannot walk away from a starving head (the min-outstanding law's straggler
     * leak, closed for good). False (gate skipped) without a stamped player chunk or any
     * frontier basis — a rig or a converged player, neither of which floods.
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
}
